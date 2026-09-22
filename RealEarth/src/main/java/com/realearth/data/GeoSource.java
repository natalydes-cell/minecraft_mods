package com.realearth.data;

/**
 * One open-data raster the mod can read, as declared in {@code config/realearth/sources.json}.
 *
 * <p>Everything here is user-editable on purpose: the defaults point at well-known open datasets,
 * but a server owner can swap in a different provider, a higher-resolution regional tileset, or a
 * purely local folder with no download at all.
 */
public record GeoSource(
        /** Stable id referenced by the generator, e.g. {@code elevation}, {@code koppen}. */
        String id,
        /** What this raster encodes; decides how raw samples are interpreted. */
        Kind kind,
        /** Download URL template, or null for a local-only source. {z}/{x}/{y} are substituted. */
        String urlTemplate,
        /** Folder under {@code config/realearth/data/} holding the tiles. */
        String localDir,
        /** Tile edge in samples. */
        int tileSize,
        /** Samples per degree of longitude at the source's native resolution. */
        double samplesPerDegree,
        /** Applied as {@code raw * scale + offset} to turn a stored sample into a real unit. */
        double scale,
        double offset,
        /** Raw value meaning "no data"; such samples fall back to the neighbour average. */
        int noDataValue,
        /** Attribution string shown in the credits screen. Required by most of these licences. */
        String attribution
) {
    public enum Kind {
        /** Metres relative to sea level, negative below. Land elevation and bathymetry in one. */
        ELEVATION,
        /** Koppen-Geiger class index, 1..30. Drives biome, weather and Cold Sweat parameters. */
        KOPPEN,
        /** Mean monthly air temperature in 0.1 degC, 12 bands. */
        TEMPERATURE,
        /** Mean monthly precipitation in mm, 12 bands. */
        PRECIPITATION,
        /** Land cover class index; drives flora, soil fertility and fauna spawn tables. */
        LANDCOVER,
        /** Surface geology class index; drives the deposit and cave generators. */
        GEOLOGY
    }

    public boolean isDownloadable() {
        return urlTemplate != null && !urlTemplate.isBlank();
    }

    public double decode(int raw) {
        return raw * scale + offset;
    }
}
