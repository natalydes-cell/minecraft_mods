package com.realearth.climate;

import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import com.realearth.data.EarthData;
import com.realearth.net.RegionInfoPayload;
import com.realearth.region.Region;
import com.realearth.util.GeoProjection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the planet's weather on the server and hands each player their local conditions.
 *
 * <p>One instance per level, persisted with the world, so a storm that was halfway across the
 * Atlantic when the server stopped is still halfway across the Atlantic when it starts again.
 * Without persistence every restart would teleport the weather, which is exactly the kind of
 * detail that makes a simulated climate feel fake.
 *
 * <h2>Cost</h2>
 * The whole planet is stepped once every {@code cycloneStepTicks} - 30 seconds by default - and
 * that step is a loop over at most 48 systems. Per-player weather is resolved once every
 * {@code weatherSampleIntervalTicks}, not every tick, because weather does not change
 * meaningfully in 50 milliseconds. On an empty server the cost is a few dozen floating-point
 * operations per half-minute.
 */
public final class WeatherManager extends SavedData {

    public static final String KEY = "realearth_weather";

    private final CycloneSystem cyclones;
    private int tickCounter;

    /** Last resolved conditions per player, so the HUD and Cold Sweat read a cached value. */
    private final Map<UUID, LocalWeather.Result> playerWeather = new HashMap<>();

    /** Last region card sent to each player, so an unchanged one is never re-sent. */
    private final Map<UUID, RegionInfoPayload> lastSent = new HashMap<>();

    private WeatherManager(CycloneSystem cyclones) {
        this.cyclones = cyclones;
    }

    public static SavedData.Factory<WeatherManager> factory(long seed) {
        return new SavedData.Factory<>(
                () -> new WeatherManager(new CycloneSystem(seed)),
                (tag, lookup) -> load(tag, seed),
                null);
    }

    /** The level's manager, created on first access. */
    public static WeatherManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(level.getSeed()), KEY);
    }

    public CycloneSystem cyclones() {
        return cyclones;
    }

    // --- Tick ------------------------------------------------------------------------------

    public void tick(ServerLevel level) {
        if (!RealEarthConfig.ENABLE_CYCLONES.get()) return;

        EarthData data = RealEarth.data();
        cyclones.setLandmaskProbe(data::isOcean);

        tickCounter++;

        int dayOfYear = SolarTime.dayOfYear(level.getDayTime(), RealEarthConfig.DAYS_PER_YEAR.get());

        if (tickCounter % RealEarthConfig.CYCLONE_STEP_TICKS.get() == 0) {
            cyclones.step(dayOfYear);
            setDirty();
        }

        if (tickCounter % RealEarthConfig.WEATHER_SAMPLE_INTERVAL_TICKS.get() == 0) {
            resampleForPlayers(level, dayOfYear);
        }
    }

    /**
     * Recomputes conditions only where somebody is standing.
     *
     * <p>This is the single most important optimisation in the weather system. The planet is
     * twenty million blocks across; resolving weather across all of it would be pointless work,
     * because weather only exists when it is observed. Nothing is simulated in a region with no
     * players, and the cyclone tracks alone carry the state forward until someone arrives.
     */
    private void resampleForPlayers(ServerLevel level, int dayOfYear) {
        GeoProjection proj = RealEarth.projection();
        EarthData data = RealEarth.data();

        // Drop state for players who have logged out, or both maps grow for the life of the
        // server. lastSent especially: it holds a payload per player who has ever been online.
        playerWeather.keySet().removeIf(id -> level.getPlayerByUUID(id) == null);
        lastSent.keySet().removeIf(id -> level.getPlayerByUUID(id) == null);

        for (ServerPlayer player : level.players()) {
            double x = player.getX();
            double z = player.getZ();
            double lat = proj.latitude(z);
            double lon = proj.longitude(x);

            double metres = data.elevation(lat, lon);
            double baseline = data.coarseElevation(lat, lon);
            KoppenClass k = data.koppen(lat, lon);
            boolean ocean = metres < 0;

            CycloneSystem.Sample sample = cyclones.sampleAt(lat, lon);
            double hour = RealEarthConfig.ENABLE_TIME_ZONES.get()
                    ? SolarTime.localHour(level.getDayTime(), lon)
                    : SolarTime.localHour(level.getDayTime(), 0.0);

            // Deterministic per-position, per-hour noise: the same place at the same hour gives
            // the same roll on every machine, so client prediction matches the server exactly.
            double noise = positionNoise(x, z, level.getDayTime());

            LocalWeather.Result result = LocalWeather.resolve(
                    k, lat, metres, baseline, player.getY(), dayOfYear, hour, sample, ocean, noise);

            playerWeather.put(player.getUUID(), result);
            sendRegionInfo(player, level, lat, lon, metres, k, result);
        }
    }

    /**
     * Sends the region card, but only when it would say something new.
     *
     * <p>{@code differsMeaningfullyFrom} is what keeps this cheap. A player standing still inside
     * the Sahara produces one packet, not one every two seconds for as long as they stand there,
     * and a server with fifty players sends a handful of packets a minute rather than thousands.
     */
    private void sendRegionInfo(ServerPlayer player, ServerLevel level, double lat, double lon,
                                double metres, KoppenClass climate, LocalWeather.Result weather) {
        Region region = RealEarth.regions().at(lat, lon);
        String regionName = region != null ? region.displayName()
                : (metres < 0 ? "Open Ocean" : "Unnamed Territory");
        String regionKind = region != null ? prettyKind(region.kind()) : "";

        Cyclone storm = weather.incomingStorm();
        String stormName = storm == null ? "" : prettyStorm(storm);

        String biome = level.getBiome(player.blockPosition()).unwrapKey()
                .map(key -> key.location().getPath().replace('_', ' '))
                .orElse("unknown");

        RegionInfoPayload payload = new RegionInfoPayload(
                regionName,
                regionKind,
                prettyClimate(climate),
                biome,
                lat,
                lon,
                metres,
                (float) weather.temperatureC(),
                weather.precipitation().name(),
                (float) weather.windSpeedMs(),
                (int) SolarTime.localHour(level.getDayTime(), lon),
                SolarTime.zoneOffsetHours(lon),
                stormName,
                (float) weather.hoursToArrival());

        RegionInfoPayload previous = lastSent.get(player.getUUID());
        if (!payload.differsMeaningfullyFrom(previous)) return;

        lastSent.put(player.getUUID(), payload);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static String prettyKind(Region.Kind kind) {
        String lower = kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String prettyClimate(KoppenClass k) {
        String name = k.name();
        int underscore = name.indexOf('_');
        String code = underscore > 0 ? name.substring(0, underscore) : name;
        String rest = underscore > 0
                ? name.substring(underscore + 1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                : "";
        return rest.isEmpty() ? code : code + " - " + rest;
    }

    private static String prettyStorm(Cyclone storm) {
        return switch (storm.type) {
            case TROPICAL_CYCLONE -> "Tropical cyclone";
            case EXTRATROPICAL_LOW -> "Storm front";
            case COLD_SURGE -> "Cold surge";
            case ANTICYCLONE -> "High pressure";
        };
    }

    /** The cached conditions for a player, or null before the first sample. */
    public LocalWeather.Result weatherFor(ServerPlayer player) {
        return playerWeather.get(player.getUUID());
    }

    /**
     * Stable pseudo-random value in [0,1) for a position and hour. Changes slowly in space so
     * neighbouring chunks share weather, and slowly in time so showers last minutes rather than
     * flickering on and off between samples.
     */
    private static double positionNoise(double x, double z, long dayTime) {
        long cellX = (long) Math.floor(x / 2048.0);
        long cellZ = (long) Math.floor(z / 2048.0);
        long hour = dayTime / (SolarTime.TICKS_PER_DAY / 24);
        long h = cellX * 0x9E3779B97F4A7C15L ^ cellZ * 0xC2B2AE3D27D4EB4FL ^ hour * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return ((h >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
    }

    // --- Persistence -----------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        for (Cyclone c : cyclones.active()) {
            CompoundTag t = new CompoundTag();
            t.putLong("Id", c.id);
            t.putString("Type", c.type.name());
            t.putDouble("Lat", c.latitude);
            t.putDouble("Lon", c.longitude);
            t.putDouble("Radius", c.radiusDeg);
            t.putDouble("Intensity", c.intensity);
            t.putDouble("TempAnomaly", c.tempAnomalyC);
            t.putDouble("Age", c.ageHours);
            t.putDouble("Lifetime", c.lifetimeHours);
            list.add(t);
        }
        tag.put("Cyclones", list);
        tag.putDouble("SimHours", cyclones.simulatedHours());
        return tag;
    }

    private static WeatherManager load(CompoundTag tag, long seed) {
        CycloneSystem sys = new CycloneSystem(seed);
        ListTag list = tag.getList("Cyclones", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Cyclone.Type type;
            try {
                type = Cyclone.Type.valueOf(t.getString("Type"));
            } catch (IllegalArgumentException e) {
                continue; // a type removed in a later version; drop the system rather than crash
            }
            Cyclone c = new Cyclone(t.getLong("Id"), type,
                    t.getDouble("Lat"), t.getDouble("Lon"),
                    t.getDouble("Radius"), t.getDouble("Intensity"),
                    t.getDouble("Lifetime"));
            c.tempAnomalyC = t.getDouble("TempAnomaly");
            c.ageHours = t.getDouble("Age");
            sys.restore(c);
        }
        sys.restoreClock(tag.getDouble("SimHours"));
        return new WeatherManager(sys);
    }
}
