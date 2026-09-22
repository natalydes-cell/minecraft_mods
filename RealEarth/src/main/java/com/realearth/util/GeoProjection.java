package com.realearth.util;

/**
 * Maps Minecraft block coordinates to geographic coordinates and back.
 *
 * <p>Projection is equirectangular (plate carree): longitude maps linearly to X, latitude linearly
 * to Z. It is the only projection that keeps the world east-west wrappable without a seam in the
 * block grid, which is a hard requirement for {@code wrapX}. Area distortion towards the poles is
 * accepted and partially compensated in the biome sampler.
 *
 * <p>Axis convention matches Minecraft's compass: {@code -Z} is north, so latitude +90 (north pole)
 * sits at the most negative Z. {@code +X} is east.
 */
public final class GeoProjection {

    /** WGS84 equatorial circumference in metres. */
    public static final double EQUATOR_M = 40_075_017.0;
    /** Pole-to-pole meridian length in metres. */
    public static final double MERIDIAN_M = 20_003_931.0;

    /** Horizontal metres represented by one block. 1.0 = 1:1 scale, 2.0 = 1:2. */
    private final double metresPerBlock;

    private final int halfWidth;   // blocks from x=0 to the dateline
    private final int halfHeight;  // blocks from z=0 to a pole

    public GeoProjection(double metresPerBlock) {
        if (metresPerBlock <= 0) throw new IllegalArgumentException("metresPerBlock must be > 0");
        this.metresPerBlock = metresPerBlock;
        this.halfWidth = (int) Math.round(EQUATOR_M / metresPerBlock / 2.0);
        this.halfHeight = (int) Math.round(MERIDIAN_M / metresPerBlock / 2.0);

        // Minecraft's hard coordinate limit. 1:1 needs 20,037,508 in each direction and fits;
        // anything finer than 1:1 does not.
        if (halfWidth > 29_999_984 || halfHeight > 29_999_984) {
            throw new IllegalArgumentException(
                "scale " + metresPerBlock + " m/block exceeds Minecraft's +/-29,999,984 limit");
        }
    }

    public int worldHalfWidth()  { return halfWidth; }
    public int worldHalfHeight() { return halfHeight; }
    public double metresPerBlock() { return metresPerBlock; }

    /** Longitude in degrees, -180..180, for a block X. Input is wrapped first. */
    public double longitude(double blockX) {
        return wrapX(blockX) / (double) halfWidth * 180.0;
    }

    /** Latitude in degrees, -90..90, for a block Z. Clamped at the poles. */
    public double latitude(double blockZ) {
        double lat = -blockZ / (double) halfHeight * 90.0;
        return Math.max(-90.0, Math.min(90.0, lat));
    }

    public double blockX(double longitude) {
        return longitude / 180.0 * halfWidth;
    }

    public double blockZ(double latitude) {
        return -latitude / 90.0 * halfHeight;
    }

    /**
     * Wraps a block X into [-halfWidth, halfWidth). This is what makes flying east far enough
     * bring you back to where you started: the dateline at +halfWidth is the same meridian as
     * -halfWidth, so the two edges are genuinely continuous rather than mirrored.
     */
    public double wrapX(double blockX) {
        double span = halfWidth * 2.0;
        double v = (blockX + halfWidth) % span;
        if (v < 0) v += span;
        return v - halfWidth;
    }

    public int wrapX(int blockX) {
        int span = halfWidth * 2;
        int v = (blockX + halfWidth) % span;
        if (v < 0) v += span;
        return v - halfWidth;
    }

    /** True when the position has crossed the dateline and needs a teleport back into range. */
    public boolean needsWrap(double blockX) {
        return blockX < -halfWidth || blockX >= halfWidth;
    }

    /**
     * Shortest signed east-west distance from {@code a} to {@code b} in blocks, taking the wrap
     * into account. Used by the cyclone advection so a storm crossing the Pacific dateline does
     * not appear to jump the whole width of the world.
     */
    public double deltaX(double a, double b) {
        double d = wrapX(b - a);
        return d;
    }

    /**
     * Horizontal ground distance in metres between two block positions, accounting for the
     * longitudinal convergence towards the poles. A degree of longitude is ~111 km at the equator
     * but near zero at the pole, and the flat block grid does not know that.
     */
    public double groundDistanceM(double x1, double z1, double x2, double z2) {
        double latMid = Math.toRadians((latitude(z1) + latitude(z2)) / 2.0);
        double dx = deltaX(x1, x2) * metresPerBlock * Math.cos(latMid);
        double dz = (z2 - z1) * metresPerBlock;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
