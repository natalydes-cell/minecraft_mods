package com.realearth.climate;

import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import com.realearth.data.EarthData;
import com.realearth.util.GeoProjection;
import com.realearth.worldgen.ElevationCurve;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Freezes and thaws open water with the seasons.
 *
 * <p>The Arctic Ocean is not permanently frozen and the Baltic is not permanently open - both
 * change every year, and on a world with real latitudes that change is visible and worth having.
 * Vanilla freezes water purely on biome temperature, which is a fixed number, so ice there is a
 * property of the map rather than of the time of year.
 *
 * <h2>What decides it</h2>
 * Sea water freezes near -1.8 degC, not 0: dissolved salt depresses the freezing point. So the
 * threshold here is below zero, and it is the reason a brackish river mouth ices over before the
 * open sea beside it does.
 *
 * <h2>Cost</h2>
 * Same shape as the weathering pass - a small fixed number of probes near players, never a scan.
 * Ice on a coast nobody is standing on is not observed, and the seasonal state is recomputed from
 * the date the moment somebody arrives, so nothing is lost by not simulating it.
 */
public final class SeaIce {

    private SeaIce() {}

    /** Probes per player per pass. */
    private static final int SAMPLES_PER_PLAYER = 40;
    /** Horizontal radius probed, in blocks. */
    private static final int RADIUS = 64;
    /** Ticks between passes. Ice is slow; checking four times a minute is ample. */
    private static final int INTERVAL = 300;

    /** Salt water freezes below this, not at zero. */
    private static final double SEAWATER_FREEZING_C = -1.8;
    /** Fresh water on land still freezes at zero. */
    private static final double FRESHWATER_FREEZING_C = 0.0;

    private static int counter;

    public static void tick(ServerLevel level) {
        if (!RealEarthConfig.ENABLE_SEA_ICE.get()) return;
        if (++counter % INTERVAL != 0) return;

        RandomSource random = level.getRandom();
        GeoProjection proj = RealEarth.projection();
        EarthData data = RealEarth.data();
        int daysPerYear = RealEarthConfig.DAYS_PER_YEAR.get();
        int dayOfYear = SolarTime.dayOfYear(level.getDayTime(), daysPerYear);

        for (ServerPlayer player : level.players()) {
            BlockPos origin = player.blockPosition();
            for (int i = 0; i < SAMPLES_PER_PLAYER; i++) {
                int x = origin.getX() + random.nextInt(RADIUS * 2) - RADIUS;
                int z = origin.getZ() + random.nextInt(RADIUS * 2) - RADIUS;

                ChunkAccess chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;

                step(level, proj, data, x, z, dayOfYear, random);
            }
        }
    }

    private static void step(ServerLevel level, GeoProjection proj, EarthData data,
                             int x, int z, int dayOfYear, RandomSource random) {
        // Only the surface matters: ice forms on top, and it melts from the top.
        BlockPos pos = new BlockPos(x, ElevationCurve.SEA_LEVEL, z);
        BlockState state = level.getBlockState(pos);

        boolean isWater = state.is(Blocks.WATER);
        boolean isIce = state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE);
        if (!isWater && !isIce) return;

        // Nothing should freeze under a roof or a hundred blocks down a cave shaft.
        if (!level.canSeeSky(pos.above())) return;

        double lat = proj.latitude(z);
        double lon = proj.longitude(x);
        double metres = data.elevation(lat, lon);
        boolean ocean = metres < 0;

        KoppenClass climate = data.koppen(lat, lon);
        double airTemp = climate.temperatureAt(dayOfYear, lat >= 0);

        // Water holds heat far better than air, so the sea lags the season by weeks and never
        // tracks a cold snap. Damping the swing is what stops the Baltic flickering between ice
        // and open water every few in-game days.
        double waterTemp = climate.baseTempC()
                + (airTemp - climate.baseTempC()) * (ocean ? 0.45 : 0.75);

        double freezing = ocean ? SEAWATER_FREEZING_C : FRESHWATER_FREEZING_C;

        if (isWater && waterTemp < freezing) {
            // The colder it is below the threshold, the faster the surface takes. A degree under
            // is a slow skim; twenty under is solid within a night.
            double margin = freezing - waterTemp;
            if (random.nextDouble() < Math.min(0.9, margin / 12.0)) {
                level.setBlockAndUpdate(pos, iceFor(margin));
            }
        } else if (isIce && waterTemp > freezing + 0.5) {
            double margin = waterTemp - freezing;
            if (random.nextDouble() < Math.min(0.9, margin / 10.0)) {
                // Melting packed ice goes to ordinary ice first, then to water. Multi-year ice
                // does not vanish in one warm afternoon.
                BlockState next = state.is(Blocks.PACKED_ICE)
                        ? Blocks.ICE.defaultBlockState()
                        : Blocks.WATER.defaultBlockState();
                level.setBlockAndUpdate(pos, next);
            }
        }
    }

    /**
     * Thin seasonal ice, or the thick multi-year kind.
     *
     * <p>Packed ice only where it is savagely cold - the central Arctic and the Antarctic shelf.
     * Everywhere else the winter ice is the ordinary sort that melts again in spring, which is
     * what makes the seasonal cycle visible instead of a one-way freeze.
     */
    private static BlockState iceFor(double marginBelowFreezing) {
        return marginBelowFreezing > 18.0
                ? Blocks.PACKED_ICE.defaultBlockState()
                : Blocks.ICE.defaultBlockState();
    }
}
