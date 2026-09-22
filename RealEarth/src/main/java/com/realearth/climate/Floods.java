package com.realearth.climate;

import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import com.realearth.data.EarthData;
import com.realearth.util.GeoProjection;
import com.realearth.worldgen.ElevationCurve;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Seasonal high water: spring melt and the aftermath of heavy rain.
 *
 * <p>Real rivers are not a fixed width. The Ob and the Lena burst their banks every spring when
 * the snow upstream melts while the mouth is still frozen; a monsoon river triples in a week.
 * That rise and fall is one of the most visible things a real climate does to a landscape, and
 * Minecraft's water, which never moves unless a player touches it, does none of it.
 *
 * <h2>How high the water goes</h2>
 * A flood level is computed per position from the season and the climate, then water is placed up
 * to that level on ground that is low enough and flat enough to flood. Steep ground sheds water
 * and never floods, which is why the valley floor goes under and the hillside above it does not.
 *
 * <h2>Why it recedes cleanly</h2>
 * Only water the flood itself placed is ever removed, tracked by height rather than by a list:
 * anything above the normal waterline and below the current flood line is flood water. That keeps
 * the mechanism stateless - no bookkeeping to persist, nothing to leak - and means a player's
 * pond, canal or base is never drained by a receding flood.
 */
public final class Floods {

    private Floods() {}

    private static final int SAMPLES_PER_PLAYER = 48;
    private static final int RADIUS = 56;
    private static final int INTERVAL = 200;

    /** Highest a flood may ever rise above the local waterline, in blocks. */
    private static final int MAX_FLOOD_RISE = 6;

    /** Slope beyond which water runs off instead of pooling, in degrees. */
    private static final double MAX_FLOODABLE_SLOPE = 6.0;

    private static int counter;

    public static void tick(ServerLevel level) {
        if (!RealEarthConfig.ENABLE_FLOODS.get()) return;
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
        double lat = proj.latitude(z);
        double lon = proj.longitude(x);
        double metres = data.elevation(lat, lon);

        if (metres < 1) return;                       // already sea
        if (metres > 2500) return;                    // above the flood plain entirely
        if (data.slopeDegrees(lat, lon) > MAX_FLOODABLE_SLOPE) return;

        KoppenClass climate = data.koppen(lat, lon);
        int rise = floodRise(climate, lat, dayOfYear);

        int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        int waterline = localWaterline(level, x, z, ground);
        if (waterline == Integer.MIN_VALUE) return;   // no water anywhere near: nothing to flood

        int target = waterline + rise;

        // Rise: fill from just above the waterline up to the target, on ground low enough.
        for (int y = waterline + 1; y <= target; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (y > ground) break;                    // above the land surface; nothing to flood
            BlockState state = level.getBlockState(pos);
            if (!floodable(state)) continue;
            if (random.nextDouble() < 0.5) {
                level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
            }
        }

        // Recede: take back only water sitting above the current flood line. Water at or below
        // the natural waterline is never touched, so lakes and player builds are safe.
        for (int y = target + 1; y <= waterline + MAX_FLOOD_RISE; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).is(Blocks.WATER)) continue;
            if (random.nextDouble() < 0.4) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /**
     * How far above its normal level the water stands today, in blocks.
     *
     * <p>Two causes, and they peak at different times. Snowmelt is a spring pulse in cold
     * climates and absent everywhere warm. Monsoon rain is a late-summer pulse in the tropics and
     * absent in deserts. A place with both - the Himalayan foothills - gets both.
     */
    private static int floodRise(KoppenClass climate, double lat, int dayOfYear) {
        boolean northern = lat >= 0;
        double year = dayOfYear / 365.0;

        // Spring melt: only where winter actually accumulates snow.
        double meltPeak = northern ? 0.30 : 0.80;     // roughly April / October
        double winterTemp = climate.temperatureAt(northern ? 15 : 200, northern);
        double meltStrength = winterTemp < -2 ? Math.min(1.0, (-2 - winterTemp) / 20.0) : 0.0;
        double melt = meltStrength * seasonalPulse(year, meltPeak, 0.07);

        // Wet-season rain: scaled by how wet the climate is at all, so deserts stay dry.
        double rainPeak = northern ? 0.60 : 0.10;     // roughly August / February
        double rainStrength = Math.min(1.0, climate.annualRainMm() / 2000.0);
        double rain = rainStrength * seasonalPulse(year, rainPeak, 0.10);

        double combined = Math.min(1.0, melt + rain);
        return (int) Math.round(combined * MAX_FLOOD_RISE);
    }

    /** A smooth bump peaking at {@code peak} with the given width, wrapping across the year. */
    private static double seasonalPulse(double year, double peak, double width) {
        double d = Math.abs(year - peak);
        d = Math.min(d, 1.0 - d);                     // the year is a circle
        return Math.exp(-(d * d) / (2 * width * width));
    }

    /**
     * The surface of the nearest standing water, or {@link Integer#MIN_VALUE} if there is none.
     *
     * <p>A flood needs a river or lake to rise out of. Without this check the mechanic would
     * conjure water on a dry hilltop in the middle of a continent whenever the season said so.
     */
    private static int localWaterline(ServerLevel level, int x, int z, int ground) {
        for (int dy = 0; dy <= MAX_FLOOD_RISE + 2; dy++) {
            int y = ground - dy;
            if (y < ElevationCurve.MIN_Y) break;
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).is(Blocks.WATER)) return y;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(pos.relative(d, 3)).is(Blocks.WATER)) return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Air and the soft ground cover a flood can cover; never stone, never a player's build. */
    private static boolean floodable(BlockState state) {
        return state.isAir()
                || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.SNOW);
    }
}
