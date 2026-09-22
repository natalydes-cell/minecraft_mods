package com.realearth.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.realearth.climate.KoppenClass;
import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the rest of the mod asks "what is really at this latitude and longitude".
 *
 * <p>Owns the {@link RasterTileset} per source and hides whether a given raster is present at all.
 * Every accessor takes a fallback, so a server with only the elevation tiles installed still
 * generates a correct-looking Earth - it just derives climate from latitude instead of reading it.
 * That graceful degradation is deliberate: requiring six complete global datasets before the mod
 * does anything would make it unusable for most people.
 *
 * <p>Instances are shared across chunk worker threads. The tilesets are individually thread-safe
 * and this class adds no mutable state after construction.
 */
public final class EarthData implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<GeoSource.Kind, RasterTileset> tilesets = new EnumMap<>(GeoSource.Kind.class);
    private final Path configDir;

    private EarthData(Path configDir, List<GeoSource> sources, int cacheSize) {
        this.configDir = configDir;
        Path dataRoot = configDir.resolve("data");
        for (GeoSource s : sources) {
            tilesets.put(s.kind(), new RasterTileset(s, dataRoot, cacheSize));
        }
    }

    /**
     * Bumped whenever the built-in source parameters change in a way that makes an older
     * {@code sources.json} wrong rather than merely different.
     *
     * <p>This exists because of a real failure. The file is only written when absent, so an
     * install created before the source list was corrected kept a stale
     * {@code samplesPerDegree} - and the mod went on sampling the climate raster at half its true
     * resolution, silently, putting the Arabian desert in the subarctic. Nothing crashed and
     * nothing warned. A version stamp turns that class of mistake from invisible into loud.
     */
    public static final int SOURCES_FORMAT_VERSION = 2;

    /** Wrapper so the file can carry a version alongside the list. */
    private record SourcesFile(int formatVersion, List<GeoSource> sources) {}

    /**
     * Loads {@code config/realearth/sources.json}, writing the defaults first if it is absent.
     *
     * <p>Writing the file rather than embedding the list is what makes the source list genuinely
     * user-editable: point a source at your own mirror, at a regional high-resolution survey, or
     * at a local folder with no URL at all, and the generator picks it up on next world load.
     */
    public static EarthData load(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve("sources.json");

        List<GeoSource> sources = null;

        if (Files.isRegularFile(file)) {
            SourcesFile parsed = null;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                parsed = GSON.fromJson(r, SourcesFile.class);
            } catch (Exception ignored) {
                // Falls through to the legacy bare-array path below.
            }

            if (parsed != null && parsed.sources() != null && !parsed.sources().isEmpty()
                    && parsed.formatVersion() >= SOURCES_FORMAT_VERSION) {
                sources = parsed.sources();
            } else {
                // Either a bare array from before versioning, or a version older than the
                // built-in defaults. Keep the operator's file - they may have edited it - but
                // move it aside and start from correct values rather than carrying the error
                // forward in silence.
                Path backup = configDir.resolve("sources.json.old");
                for (int i = 2; Files.exists(backup); i++) {
                    backup = configDir.resolve("sources.json.old" + i);
                }
                Files.move(file, backup);
                RealEarth.LOG.warn("sources.json was written by an older RealEarth and its "
                        + "parameters no longer match the data - moved it to {} and wrote fresh "
                        + "defaults. Re-apply any edits you had made.", backup.getFileName());
            }
        }

        if (sources == null) {
            sources = DefaultSources.defaults();
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(new SourcesFile(SOURCES_FORMAT_VERSION, sources), w);
            }
        }

        for (GeoSource s : sources) {
            Files.createDirectories(configDir.resolve("data").resolve(s.localDir()));
        }
        return new EarthData(configDir, sources, RealEarthConfig.TILE_CACHE_SIZE.get());
    }

    public Path configDir() {
        return configDir;
    }

    /** True when at least one tile of this kind is actually present on disk. */
    public boolean has(GeoSource.Kind kind) {
        return tilesets.containsKey(kind);
    }

    // --- Elevation ---------------------------------------------------------------------------

    /**
     * Real elevation in metres, negative below sea level. Land and seafloor on one continuous
     * surface, which is what lets a coastline emerge naturally at the zero crossing instead of
     * being drawn as a separate feature.
     *
     * <p>Without elevation data there is no Earth to generate, so the fallback is a flat plain at
     * 40 m rather than anything clever - an obviously wrong world is a better signal that the
     * tiles are missing than a plausible fake one.
     */
    public double elevation(double lat, double lon) {
        RasterTileset t = tilesets.get(GeoSource.Kind.ELEVATION);
        return t == null ? 40.0 : t.sample(lat, lon, 40.0);
    }

    public boolean isOcean(double lat, double lon) {
        return elevation(lat, lon) < 0.0;
    }

    /**
     * Mean elevation of the surrounding climate cell, in metres.
     *
     * <p>This is the baseline the temperature lapse rate is measured against. The Koppen class
     * already encodes the altitude of the area it describes, so only the relief within that area
     * - the ridge above the valley floor - still needs correcting. Averaging over roughly half a
     * degree matches the scale at which climate classifications are drawn.
     */
    public double coarseElevation(double lat, double lon) {
        double step = 0.25;
        double sum = 0;
        int n = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                sum += elevation(lat + dz * step, lon + dx * step);
                n++;
            }
        }
        return sum / n;
    }

    // --- Climate -----------------------------------------------------------------------------

    /**
     * Koppen class here. Falls back to a latitude-only estimate, which gets the broad bands right
     * - tropics wet, subtropics dry, mid-latitudes temperate, poles frozen - and gets rain shadows
     * and monsoons wrong. Good enough to play, not good enough to ship as the real thing.
     */
    public KoppenClass koppen(double lat, double lon) {
        RasterTileset t = tilesets.get(GeoSource.Kind.KOPPEN);
        if (t != null) {
            int idx = t.sampleClass(lat, lon, -1);
            if (idx >= 0) return KoppenClass.byIndex(idx);
        }
        if (isOcean(lat, lon)) return KoppenClass.OCEAN;
        return estimateFromLatitude(lat, elevation(lat, lon));
    }

    private static KoppenClass estimateFromLatitude(double lat, double elevationM) {
        double a = Math.abs(lat);
        // Altitude acts like latitude: 1000 m of climb is worth roughly 6 degrees poleward.
        a += Math.max(0.0, elevationM) / 1000.0 * 6.0;
        if (a < 10) return KoppenClass.AF_TROPICAL_RAIN;
        if (a < 18) return KoppenClass.AW_TROPICAL_SAV;
        if (a < 30) return KoppenClass.BWH_DESERT_HOT;   // the subtropical desert belt
        if (a < 40) return KoppenClass.CSA_MED_HOT;
        if (a < 50) return KoppenClass.CFB_OCEANIC;
        if (a < 58) return KoppenClass.DFB_CONT_HUMID;
        if (a < 66) return KoppenClass.DFC_SUBARCTIC;
        if (a < 75) return KoppenClass.ET_TUNDRA;
        return KoppenClass.EF_ICE_CAP;
    }

    /** Mean air temperature in degC, from data where available, else from the Koppen class. */
    public double meanTemperature(double lat, double lon, int dayOfYear) {
        RasterTileset t = tilesets.get(GeoSource.Kind.TEMPERATURE);
        if (t != null) {
            double v = t.sample(lat, lon, Double.NaN);
            if (!Double.isNaN(v)) return v;
        }
        return koppen(lat, lon).temperatureAt(dayOfYear, lat >= 0);
    }

    /** Annual precipitation in mm. */
    public double annualPrecipitation(double lat, double lon) {
        RasterTileset t = tilesets.get(GeoSource.Kind.PRECIPITATION);
        if (t != null) {
            double v = t.sample(lat, lon, Double.NaN);
            if (!Double.isNaN(v)) return v * 12.0; // stored as a monthly mean
        }
        return koppen(lat, lon).annualRainMm();
    }

    // --- Surface -----------------------------------------------------------------------------

    public int landcover(double lat, double lon) {
        RasterTileset t = tilesets.get(GeoSource.Kind.LANDCOVER);
        return t == null ? 0 : t.sampleClass(lat, lon, 0);
    }

    public int geology(double lat, double lon) {
        RasterTileset t = tilesets.get(GeoSource.Kind.GEOLOGY);
        return t == null ? 0 : t.sampleClass(lat, lon, 0);
    }

    /**
     * Crop growth multiplier, 1.0 being vanilla farmland.
     *
     * <p>Combines the climate's own fertility with a slope penalty: a Ukrainian chernozem plain
     * grows anything, the same climate on a 30-degree mountainside grows very little, because the
     * soil washes off. Slope is measured from the elevation raster directly.
     */
    public double fertility(double lat, double lon) {
        double base = koppen(lat, lon).fertility();
        double slope = slopeDegrees(lat, lon);
        double penalty = slope <= 5 ? 1.0 : Math.max(0.15, 1.0 - (slope - 5) / 40.0);
        return base * penalty;
    }

    /** Terrain slope in degrees, from a central difference on the elevation raster. */
    public double slopeDegrees(double lat, double lon) {
        double d = 0.01; // degrees; roughly 1 km
        double metresPerDegree = 111_320.0;
        double dzdy = (elevation(lat + d, lon) - elevation(lat - d, lon))
                / (2 * d * metresPerDegree);
        double dzdx = (elevation(lat, lon + d) - elevation(lat, lon - d))
                / (2 * d * metresPerDegree * Math.cos(Math.toRadians(lat)));
        return Math.toDegrees(Math.atan(Math.hypot(dzdx, dzdy)));
    }

    /**
     * How much extra vertical headroom this position deserves, for {@link
     * com.realearth.worldgen.ElevationCurve}.
     *
     * <p>Derived from the terrain itself rather than a hand-written list of mountain ranges, so
     * the Himalaya, the Caucasus, the Alps, the Andes and every range nobody remembered to list
     * all get the same treatment automatically - and the lowlands, which never leave the 1:1 band,
     * pay nothing for it.
     *
     * <p>Deliberately a function of elevation alone. An earlier version also factored in local
     * slope, which broke the world: two neighbouring columns at the same real elevation but
     * different steepness mapped to different Y values, so every change of gradient became a small
     * cliff. Elevation-to-Y must be a single global function or the terrain stops being a surface.
     */
    public double reliefBoost(double lat, double lon) {
        return reliefBoostForElevation(elevation(lat, lon));
    }

    /** The elevation-to-boost curve itself, exposed so callers that already have the elevation
     *  do not pay for a second raster sample. */
    public static double reliefBoostForElevation(double elevationM) {
        if (elevationM < 1200) return 1.0;
        return Math.min(3.0, 1.0 + (elevationM - 1200) / 1900.0);
    }

    public void invalidateCaches() {
        tilesets.values().forEach(RasterTileset::invalidate);
    }

    @Override
    public void close() {
        tilesets.values().forEach(RasterTileset::close);
        tilesets.clear();
    }
}
