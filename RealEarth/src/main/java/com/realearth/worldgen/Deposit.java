package com.realearth.worldgen;

import com.realearth.util.GeoProjection;

/**
 * A real-world mineral or hydrocarbon province: a bounded region where one resource is far more
 * abundant than the global background, at the depths it actually occurs.
 *
 * <p>Deposits are additive. Vanilla ore generation is left completely untouched, so Create
 * Aeronautics and any other mod that expects normal zinc and copper distribution keeps working;
 * a deposit only adds extra veins on top inside its own footprint. That was a deliberate
 * constraint - replacing vanilla ore placement is the single fastest way to break a modpack.
 */
public record Deposit(
        String id,
        /** Shown in the region HUD, e.g. "Kuznetsk Basin". */
        String displayName,
        Resource resource,
        /** Centre of the province. */
        double centreLat,
        double centreLon,
        /** Half-extent in degrees; the footprint is an ellipse, which fits real basins well. */
        double radiusLatDeg,
        double radiusLonDeg,
        /** Depth band in real metres below the surface, not Minecraft Y. */
        double minDepthM,
        double maxDepthM,
        /** Extra veins per chunk at the centre of the province. */
        double richness
) {
    public enum Resource {
        COAL, OIL, NATURAL_GAS, IRON, COPPER, BAUXITE, GOLD,
        DIAMOND, NICKEL, TIN, POTASH, SALT, URANIUM
    }

    /**
     * Fraction of full richness at a position, 1 at the centre and 0 outside the ellipse.
     * Smooth rather than stepped, so the edge of a coal basin is a gradient, not a wall.
     */
    public double densityAt(double lat, double lon) {
        double dLat = (lat - centreLat) / radiusLatDeg;
        double dLon = wrapDeg(lon - centreLon) / radiusLonDeg;
        double r2 = dLat * dLat + dLon * dLon;
        if (r2 >= 1.0) return 0.0;
        double r = Math.sqrt(r2);
        return 0.5 * (1.0 + Math.cos(Math.PI * r));
    }

    /** True when a Minecraft Y falls inside this deposit's real depth band. */
    public boolean coversDepth(double surfaceY, double y) {
        double depthBlocks = surfaceY - y;
        // Below sea level the elevation curve compresses, so a Minecraft block is worth more than
        // a metre down there. Convert through the curve rather than assuming 1 block = 1 m.
        double depthM = ElevationCurve.toMetres(surfaceY, 1.0) - ElevationCurve.toMetres(y, 1.0);
        if (depthM <= 0) depthM = depthBlocks;
        return depthM >= minDepthM && depthM <= maxDepthM;
    }

    public boolean contains(GeoProjection proj, int blockX, int blockZ) {
        return densityAt(proj.latitude(blockZ), proj.longitude(blockX)) > 0.0;
    }

    private static double wrapDeg(double d) {
        double v = (d + 180.0) % 360.0;
        if (v < 0) v += 360.0;
        return v - 180.0;
    }
}
