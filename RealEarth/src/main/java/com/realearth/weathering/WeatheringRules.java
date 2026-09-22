package com.realearth.weathering;

import com.realearth.climate.KoppenClass;

import java.util.HashMap;
import java.util.Map;

/**
 * What a block turns into when the weather works on it, and how long that takes.
 *
 * <p>Modelled on Immersive Weathering's idea - exposed blocks age - but driven by the real climate
 * instead of a flat global rate. That is the whole point of having a climate: stone in the Congo
 * goes green in a season, and the same stone at Vostok does not change in a human lifetime,
 * because chemical weathering roughly doubles for every 10 degC and stops entirely without liquid
 * water.
 *
 * <p>Times below are real-world figures compressed to something playable. Real moss takes a decade
 * to establish; here the wettest climates do it in a few Minecraft days, which keeps the same
 * ordering between climates while staying visible within one play session.
 */
public final class WeatheringRules {

    private WeatheringRules() {}

    /**
     * One ageing step: a block, what it becomes, and the base number of in-game days it takes in
     * a reference temperate climate.
     */
    public record Rule(String from, String to, double baseDays, Requirement requirement) {}

    public enum Requirement {
        /** Needs sky access and rain. Most surface weathering. */
        EXPOSED_TO_SKY,
        /** Needs an adjacent water source: riverbanks, shorelines, cave streams. */
        TOUCHING_WATER,
        /** Needs darkness and damp: cave walls, undersides, shaded north faces. */
        DARK_AND_DAMP,
        /** Needs neither; applies anywhere. */
        ANY
    }

    private static final Map<String, Rule> RULES = new HashMap<>();

    private static void rule(String from, String to, double days, Requirement req) {
        RULES.put(from, new Rule(from, to, days, req));
    }

    static {
        // Stone going green, then to full moss. The common case, and the one that makes an
        // abandoned build look abandoned.
        rule("minecraft:stone", "minecraft:mossy_cobblestone", 26, Requirement.DARK_AND_DAMP);
        rule("minecraft:cobblestone", "minecraft:mossy_cobblestone", 14, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:stone_bricks", "minecraft:cracked_stone_bricks", 20, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks", 16, Requirement.DARK_AND_DAMP);
        rule("minecraft:stone_brick_stairs", "minecraft:mossy_stone_brick_stairs", 22, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:stone_brick_slab", "minecraft:mossy_stone_brick_slab", 22, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:cobblestone_stairs", "minecraft:mossy_cobblestone_stairs", 16, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:cobblestone_slab", "minecraft:mossy_cobblestone_slab", 16, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:cobblestone_wall", "minecraft:mossy_cobblestone_wall", 16, Requirement.EXPOSED_TO_SKY);

        // Copper. Vanilla already oxidises it; this replaces the fixed rate with a climate-driven
        // one, which is why a copper roof in Bergen goes green in a year and one in Cairo does
        // not - salt air and rain, or the lack of them.
        rule("minecraft:copper_block", "minecraft:exposed_copper", 8, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:exposed_copper", "minecraft:weathered_copper", 14, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:weathered_copper", "minecraft:oxidized_copper", 22, Requirement.EXPOSED_TO_SKY);

        // Iron rusting into its decayed forms is not vanilla, so iron is left alone rather than
        // invented. Deepslate and terracotta do not weather meaningfully on any human timescale.

        // Dirt and grass. Bare dirt greens over where anything grows at all.
        rule("minecraft:dirt", "minecraft:grass_block", 6, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:coarse_dirt", "minecraft:dirt", 12, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:dirt_path", "minecraft:coarse_dirt", 9, Requirement.EXPOSED_TO_SKY);
        rule("minecraft:farmland", "minecraft:dirt", 4, Requirement.EXPOSED_TO_SKY);

        // Wood. Rot needs damp, which is why timber survives in a desert and not in a swamp.
        rule("minecraft:oak_planks", "minecraft:stripped_oak_log", 30, Requirement.TOUCHING_WATER);

        // Sand cementing into sandstone under repeated wetting. Slow everywhere, and it is the
        // one process that runs fastest in a climate with a sharp wet/dry cycle rather than
        // constant rain.
        rule("minecraft:sand", "minecraft:sandstone", 70, Requirement.TOUCHING_WATER);
    }

    public static Rule ruleFor(String blockId) {
        return RULES.get(blockId);
    }

    public static boolean hasRule(String blockId) {
        return RULES.containsKey(blockId);
    }

    /**
     * Multiplier on the base time for a climate. Below 1 is faster than the reference.
     *
     * <p>Two things drive it, and both are real. Water is required: with no liquid water there is
     * no chemical weathering and no biology, so a polar desert is effectively frozen in time.
     * Temperature then sets the rate, roughly doubling every 10 degC, which is why the tropics
     * eat a building and the Arctic preserves one.
     */
    public static double climateMultiplier(KoppenClass k, int dayOfYear, boolean northern) {
        double temp = k.temperatureAt(dayOfYear, northern);

        // Nothing happens below freezing: the water that would do the work is ice.
        if (temp <= 0.0) return Double.POSITIVE_INFINITY;

        // Moisture. A desert has the heat but not the water.
        double wetness = Math.max(0.05, k.wetFraction());
        double moistureFactor = 0.35 / wetness;

        // Q10 = 2: every 10 degC above the 15 degC reference halves the time.
        double tempFactor = Math.pow(2.0, (15.0 - temp) / 10.0);

        return moistureFactor * tempFactor;
    }

    /**
     * In-game days for one ageing step here, or {@link Double#POSITIVE_INFINITY} where the
     * process cannot run at all.
     */
    public static double daysFor(Rule rule, KoppenClass k, int dayOfYear, boolean northern) {
        double mult = climateMultiplier(k, dayOfYear, northern);
        if (Double.isInfinite(mult)) return Double.POSITIVE_INFINITY;
        return rule.baseDays() * mult;
    }
}
