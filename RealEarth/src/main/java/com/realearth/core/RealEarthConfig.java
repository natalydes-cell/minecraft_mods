package com.realearth.core;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side configuration. Everything here changes world generation or simulation, so it lives
 * in the COMMON config and is authoritative on the server - a client cannot opt out of the scale
 * or the weather and still see the same world as everyone else.
 */
public final class RealEarthConfig {

    public static final ModConfigSpec SPEC;

    // --- Scale -----------------------------------------------------------------------------
    public static final ModConfigSpec.DoubleValue METRES_PER_BLOCK;
    public static final ModConfigSpec.BooleanValue WRAP_LONGITUDE;

    // --- Data ------------------------------------------------------------------------------
    public static final ModConfigSpec.IntValue TILE_CACHE_SIZE;
    public static final ModConfigSpec.BooleanValue ALLOW_DOWNLOAD;

    // --- Climate ---------------------------------------------------------------------------
    public static final ModConfigSpec.BooleanValue ENABLE_CYCLONES;
    public static final ModConfigSpec.IntValue CYCLONE_STEP_TICKS;
    public static final ModConfigSpec.IntValue DAYS_PER_YEAR;
    public static final ModConfigSpec.BooleanValue ENABLE_TIME_ZONES;
    public static final ModConfigSpec.BooleanValue ENABLE_SEA_ICE;
    public static final ModConfigSpec.BooleanValue ENABLE_FLOODS;
    public static final ModConfigSpec.BooleanValue DRIVE_VANILLA_WEATHER;

    // --- Performance -----------------------------------------------------------------------
    public static final ModConfigSpec.IntValue WEATHER_SAMPLE_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue EMPTY_REGION_UNLOAD_SECONDS;
    public static final ModConfigSpec.IntValue GENERATOR_THREADS;

    // --- Content ---------------------------------------------------------------------------
    public static final ModConfigSpec.BooleanValue ENABLE_DEPOSITS;
    public static final ModConfigSpec.BooleanValue ENABLE_WEATHERING;
    public static final ModConfigSpec.BooleanValue ENABLE_REGION_HUD;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("World scale and shape.").push("scale");
        METRES_PER_BLOCK = b
                .comment("Real metres per block horizontally.",
                        "1.0 = 1:1 (world is 40,075,000 blocks around; near Minecraft's limit).",
                        "2.0 = 1:2 (default; half the coordinates, a quarter of the chunks).",
                        "Changing this on an existing world will not match already-generated chunks.")
                .defineInRange("metresPerBlock", 2.0, 1.0, 64.0);
        WRAP_LONGITUDE = b
                .comment("Teleport players crossing the dateline back to the other edge,",
                        "so flying east far enough returns you to where you started.")
                .define("wrapLongitude", true);
        b.pop();

        b.comment("Geodata sources and caching.").push("data");
        TILE_CACHE_SIZE = b
                .comment("Raster tiles held in memory per source.",
                        "Each 512x512 tile is 1 MB. 256 tiles per source is roughly 1.5 GB across",
                        "all six sources at worst case; lower this first if memory is tight.")
                .defineInRange("tileCacheSize", 192, 16, 4096);
        ALLOW_DOWNLOAD = b
                .comment("Allow the mod to fetch missing tiles from the URLs in sources.json.",
                        "Off by default: downloading gigabytes of third-party geodata is the",
                        "operator's decision, not the mod's.")
                .define("allowDownload", false);
        b.pop();

        b.comment("Climate and weather simulation.").push("climate");
        ENABLE_CYCLONES = b
                .comment("Planet-wide moving pressure systems. Turning this off leaves the static",
                        "Koppen climate only - biomes still differ, but weather stops travelling.")
                .define("enableCyclones", true);
        CYCLONE_STEP_TICKS = b
                .comment("Ticks between simulation steps. Each step is half a simulated hour.",
                        "600 (30 s) keeps storms visibly moving at negligible cost.")
                .defineInRange("cycloneStepTicks", 600, 20, 24000);
        DAYS_PER_YEAR = b
                .comment("Minecraft days in one simulated year. Drives seasons, sea ice and floods.")
                .defineInRange("daysPerYear", 96, 8, 1460);
        ENABLE_TIME_ZONES = b
                .comment("Derive local solar time from longitude, so it can be night in Moscow",
                        "while it is afternoon in Chicago. The world clock itself is untouched.")
                .define("enableTimeZones", true);
        ENABLE_SEA_ICE = b
                .comment("Freeze and thaw polar and coastal water with the seasons, with drifting",
                        "floes and meltwater floods in spring.")
                .define("enableSeaIce", true);
        ENABLE_FLOODS = b
                .comment("Raise rivers and lakes in the spring melt and the wet season, then let",
                        "them recede. Only water the flood itself placed is ever removed, so a",
                        "pond or canal you built is never drained.")
                .define("enableFloods", true);
        DRIVE_VANILLA_WEATHER = b
                .comment("Send each player the rain and thunder level for THEIR position, so the",
                        "simulated climate actually rains instead of only showing on the HUD.",
                        "",
                        "This is what makes volumetric cloud mods follow the real weather: Better",
                        "Clouds takes its coverage from max(rainLevel, thunderLevel), so it picks",
                        "this up with no integration at all. Vanilla clouds, rain particles and",
                        "every other weather-aware mod follow the same way.",
                        "",
                        "Turning this ON disables the vanilla weather gamerule, because a level",
                        "running its own weather cycle broadcasts over the per-player values.")
                .define("driveVanillaWeather", true);
        b.pop();

        b.comment("Performance. Start here if the server is struggling.").push("performance");
        WEATHER_SAMPLE_INTERVAL_TICKS = b
                .comment("How often a player's local weather is recomputed.",
                        "Weather does not change meaningfully in under a second, so sampling",
                        "every tick is pure waste.")
                .defineInRange("weatherSampleIntervalTicks", 40, 1, 400);
        EMPTY_REGION_UNLOAD_SECONDS = b
                .comment("Seconds a region with no players stays loaded before it is dropped.",
                        "On a planet this size most of the world is empty most of the time;",
                        "this is the single biggest saving available.")
                .defineInRange("emptyRegionUnloadSeconds", 45, 5, 3600);
        GENERATOR_THREADS = b
                .comment("Worker threads for terrain sampling. 0 = half the available cores.",
                        "Raising this past your physical core count makes generation slower,",
                        "not faster.")
                .defineInRange("generatorThreads", 0, 0, 64);
        b.pop();

        b.comment("Optional content.").push("content");
        ENABLE_DEPOSITS = b
                .comment("Add real-world mineral and oil provinces on top of vanilla ore",
                        "generation. Vanilla ores are never removed or moved, so mods that",
                        "depend on normal ore distribution keep working.")
                .define("enableDeposits", true);
        ENABLE_WEATHERING = b
                .comment("Age exposed blocks over time, faster in humid climates.")
                .define("enableWeathering", true);
        ENABLE_REGION_HUD = b
                .comment("Show a region card when entering a named continent, sea, range or biome.")
                .define("enableRegionHud", true);
        b.pop();

        SPEC = b.build();
    }

    private RealEarthConfig() {}

    /** Resolved worker thread count, with the 0-means-auto case applied. */
    public static int generatorThreads() {
        int configured = GENERATOR_THREADS.get();
        if (configured > 0) return configured;
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
}
