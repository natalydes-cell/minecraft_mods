package com.realearth.worldgen;

/**
 * Metre-scale surface roughness added on top of the real elevation raster.
 *
 * <p>GEBCO at 15 arc-seconds is roughly 460 m per sample at the equator. At 1:2 scale that is one
 * data point every 230 blocks, so without this the world would be a set of enormous smooth ramps:
 * geographically perfect and visually dead. This adds back the detail the source cannot carry.
 *
 * <p>Crucially it is <b>not</b> a terrain generator competing with the data. Amplitude is tied to
 * how rugged the real terrain already is, so it roughens a mountainside and leaves a floodplain
 * flat, and it never moves a coastline - amplitude is forced to zero as elevation approaches sea
 * level, or the noise would carve fake bays and islands into real coasts.
 */
public final class TerrainDetail {

    private TerrainDetail() {}

    /** Metres of elevation below which roughness is suppressed entirely, to protect coastlines. */
    private static final double COAST_GUARD_M = 25.0;

    /**
     * @param metres real elevation at this column, used to scale the amplitude
     * @return a signed offset in blocks, to add to the computed surface Y
     */
    public static double roughness(int blockX, int blockZ, double metres) {
        double amplitude = amplitudeFor(metres);
        if (amplitude <= 0.0) return 0.0;

        // Three octaves. Deliberately few: this is decoration on top of real data, and more
        // octaves would start to contribute shapes the data did not ask for.
        double n = 0.0;
        n += valueNoise(blockX * 0.035, blockZ * 0.035) * 0.60;
        n += valueNoise(blockX * 0.110, blockZ * 0.110) * 0.28;
        n += valueNoise(blockX * 0.370, blockZ * 0.370) * 0.12;

        return n * amplitude;
    }

    private static double amplitudeFor(double metres) {
        double a = Math.abs(metres);
        if (a < COAST_GUARD_M) return 0.0;
        // Ramp in over the first 100 m so there is no sudden band of roughness along every coast.
        double ramp = Math.min(1.0, (a - COAST_GUARD_M) / 100.0);
        if (metres < 0) {
            // Seafloor: gentle, and it does not matter much because it is rarely seen.
            return ramp * Math.min(6.0, a / 400.0);
        }
        // Land: grows with elevation, because high terrain really is rougher. Capped so a
        // Himalayan ridge gains texture without gaining a second, fake, mountain range.
        return ramp * Math.min(22.0, 2.0 + metres / 260.0);
    }

    /**
     * Deterministic smooth value noise in [-1, 1].
     *
     * <p>Position-hashed rather than seeded from the world, so the same coordinates always give
     * the same terrain. On an Earth map that is the correct behaviour - the Matterhorn should not
     * change shape because someone started a new world - and it also means a server and a client
     * computing the surface independently agree without exchanging anything.
     */
    private static double valueNoise(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = smoothstep(x - x0);
        double fz = smoothstep(z - z0);

        double v00 = hash(x0, z0);
        double v10 = hash(x0 + 1, z0);
        double v01 = hash(x0, z0 + 1);
        double v11 = hash(x0 + 1, z0 + 1);

        double top = v00 + (v10 - v00) * fx;
        double bot = v01 + (v11 - v01) * fx;
        return top + (bot - top) * fz;
    }

    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        // Map the top 24 bits to [-1, 1].
        return ((h >>> 40) / 8388608.0) - 1.0;
    }
}
