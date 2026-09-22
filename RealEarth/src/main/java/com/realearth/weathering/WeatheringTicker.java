package com.realearth.weathering;

import com.realearth.climate.KoppenClass;
import com.realearth.climate.SolarTime;
import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import com.realearth.data.EarthData;
import com.realearth.util.GeoProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Optional;
import net.minecraft.util.RandomSource;

/**
 * Ages exposed blocks, faster in wet warm climates and not at all in frozen ones.
 *
 * <h2>Why this is sampled rather than ticked</h2>
 * Minecraft only random-ticks blocks that ask for it, and none of the blocks that should weather -
 * stone, cobblestone, bricks, planks - do. The alternative to sampling would be marking every one
 * of them as random-ticking, which would change their behaviour for every other mod in the pack
 * and multiply the server's random-tick load across the whole world.
 *
 * <p>So instead a fixed, small number of positions near players are probed each pass. The rate is
 * what makes it feel right rather than the mechanism: a block is checked rarely, but the
 * probability it converts is derived from the real weathering time for its climate, so the
 * long-run behaviour matches {@link WeatheringRules} even though any individual block is only
 * looked at occasionally.
 */
public final class WeatheringTicker {

    private WeatheringTicker() {}

    /** Positions probed per player per pass. Deliberately small; this runs forever. */
    private static final int SAMPLES_PER_PLAYER = 24;
    /** Horizontal radius around a player to probe, in blocks. */
    private static final int RADIUS = 48;
    /** Ticks between passes. */
    private static final int INTERVAL = 100;
    /** Vertical extent probed around the player, in blocks. */
    private static final int HEIGHT = 32;

    /** Chance that any one block in range is looked at during a single pass. */
    private static final double PROBE_PROBABILITY =
            SAMPLES_PER_PLAYER / (double) (RADIUS * 2 * RADIUS * 2 * HEIGHT);

    private static int counter;

    public static void tick(ServerLevel level) {
        if (!RealEarthConfig.ENABLE_WEATHERING.get()) return;
        if (++counter % INTERVAL != 0) return;

        RandomSource random = level.getRandom();
        GeoProjection proj = RealEarth.projection();
        EarthData data = RealEarth.data();
        int daysPerYear = RealEarthConfig.DAYS_PER_YEAR.get();
        int dayOfYear = SolarTime.dayOfYear(level.getDayTime(), daysPerYear);

        // How many in-game days one pass represents, for turning a weathering time into a
        // per-probe probability.
        double daysPerPass = INTERVAL / (double) SolarTime.TICKS_PER_DAY;

        for (ServerPlayer player : level.players()) {
            BlockPos origin = player.blockPosition();
            for (int i = 0; i < SAMPLES_PER_PLAYER; i++) {
                int x = origin.getX() + random.nextInt(RADIUS * 2) - RADIUS;
                int z = origin.getZ() + random.nextInt(RADIUS * 2) - RADIUS;
                int y = origin.getY() + random.nextInt(HEIGHT) - HEIGHT / 2;

                BlockPos pos = new BlockPos(x, y, z);
                // Never touch an unloaded chunk: that would force it to load, which is the exact
                // opposite of what the rest of the mod works to avoid.
                ChunkAccess chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;

                tryWeather(level, pos, proj, data, dayOfYear, daysPerPass, random);
            }
        }
    }

    private static void tryWeather(ServerLevel level, BlockPos pos, GeoProjection proj,
                                   EarthData data, int dayOfYear, double daysPerPass,
                                   RandomSource random) {
        BlockState state = level.getBlockState(pos);
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        WeatheringRules.Rule rule = WeatheringRules.ruleFor(id.toString());
        if (rule == null) return;
        if (!conditionMet(level, pos, rule.requirement())) return;

        double lat = proj.latitude(pos.getZ());
        double lon = proj.longitude(pos.getX());
        KoppenClass climate = data.koppen(lat, lon);

        double days = WeatheringRules.daysFor(rule, climate, dayOfYear, lat >= 0);
        if (Double.isInfinite(days) || days <= 0) return;   // frozen: nothing happens

        // A given block is only probed occasionally, so when it is probed it has to carry all the
        // time that passed since the last look. The probe probability per pass is the sample
        // count over the volume sampled; the conversion chance is the target rate divided by it.
        double chance = Math.min(1.0, (daysPerPass / days) / PROBE_PROBABILITY);

        // Saturating at 1 is not a rounding detail, it is a real limit. In the wettest, warmest
        // climates the target time is short enough that even converting on every single probe is
        // slower than WeatheringRules asks for, so those climates age at the sampling ceiling
        // instead of at the stated rate. The ordering between climates still holds - the tropics
        // are still the fastest thing there is - and slow climates hit their stated times exactly.
        if (random.nextDouble() >= chance) return;

        Block target = BuiltInRegistries.BLOCK.getOptional(
                ResourceLocation.parse(rule.to())).orElse(null);
        if (target == null) return;   // the target block belongs to a mod that is not installed

        level.setBlockAndUpdate(pos, target.defaultBlockState());
    }

    private static boolean conditionMet(ServerLevel level, BlockPos pos,
                                        WeatheringRules.Requirement requirement) {
        return switch (requirement) {
            case ANY -> true;
            case EXPOSED_TO_SKY -> level.canSeeSky(pos.above());
            case DARK_AND_DAMP -> !level.canSeeSky(pos.above())
                    && level.getMaxLocalRawBrightness(pos.above()) < 8;
            case TOUCHING_WATER -> touchingWater(level, pos);
        };
    }

    private static boolean touchingWater(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (level.getFluidState(pos.relative(d)).isSource()) return true;
        }
        return false;
    }

    /** Looks up what a block would become here, for the debug command. */
    public static Optional<String> preview(String blockId, KoppenClass climate,
                                           int dayOfYear, boolean northern) {
        WeatheringRules.Rule rule = WeatheringRules.ruleFor(blockId);
        if (rule == null) return Optional.empty();
        double days = WeatheringRules.daysFor(rule, climate, dayOfYear, northern);
        if (Double.isInfinite(days)) {
            return Optional.of(rule.to() + " - never here, it is below freezing");
        }
        return Optional.of(String.format("%s in about %.0f in-game days", rule.to(), days));
    }
}
