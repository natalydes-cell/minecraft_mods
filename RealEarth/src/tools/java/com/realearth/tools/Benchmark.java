package com.realearth.tools;

import com.realearth.data.DefaultSources;
import com.realearth.data.GeoSource;
import com.realearth.data.RasterTileset;
import com.realearth.util.GeoProjection;
import com.realearth.worldgen.ElevationCurve;
import com.realearth.worldgen.TerrainDetail;

import java.nio.file.Path;

/**
 * Measures what terrain sampling actually costs, per chunk.
 *
 * <p>Written to answer one question with a number rather than an opinion: is there enough work in
 * the terrain stage to be worth moving onto the GPU? A compute shader only pays for itself when
 * the arithmetic dominates the cost of shipping data across the bus, and a chunk of terrain is a
 * very small batch.
 */
public final class Benchmark {

    private static final int SAMPLES_PER_CHUNK = 17 * 17;

    public static void main(String[] args) {
        Path configDir = Path.of(args.length > 0 ? args[0] : "run/config/realearth").toAbsolutePath();
        GeoSource elevation = DefaultSources.defaults().stream()
                .filter(s -> s.kind() == GeoSource.Kind.ELEVATION).findFirst().orElseThrow();
        RasterTileset tiles = new RasterTileset(elevation, configDir.resolve("data"), 256);
        GeoProjection proj = new GeoProjection(2.0);

        // Inside the imported Nepal region, so the raster is actually hit rather than falling
        // back to a constant.
        int baseX = (int) proj.blockX(86.5);
        int baseZ = (int) proj.blockZ(28.5);

        System.out.println("warming up...");
        run(tiles, proj, baseX, baseZ, 2000);

        for (int pass = 0; pass < 3; pass++) {
            int chunks = 20000;
            long start = System.nanoTime();
            double sink = run(tiles, proj, baseX, baseZ, chunks);
            long ns = System.nanoTime() - start;

            double perChunkUs = ns / 1000.0 / chunks;
            double perSampleNs = ns / (double) (chunks * SAMPLES_PER_CHUNK);
            System.out.printf(
                    "pass %d: %,d chunks in %,d ms  ->  %.1f us/chunk, %.1f ns/sample  (sink %.0f)%n",
                    pass + 1, chunks, ns / 1_000_000, perChunkUs, perSampleNs, sink);
            System.out.printf("         one core sustains %,.0f chunks/s%n", 1_000_000.0 / perChunkUs);
        }
        tiles.close();
    }

    private static double run(RasterTileset tiles, GeoProjection proj, int baseX, int baseZ,
                              int chunks) {
        double sink = 0;
        for (int c = 0; c < chunks; c++) {
            int originX = baseX + (c % 64) * 16;
            int originZ = baseZ + (c / 64 % 64) * 16;
            for (int sz = 0; sz < 17; sz++) {
                for (int sx = 0; sx < 17; sx++) {
                    int x = originX + sx;
                    int z = originZ + sz;
                    double metres = tiles.sample(proj.latitude(z), proj.longitude(x), 40.0);
                    double boost = metres < 1200 ? 1.0
                            : Math.min(3.0, 1.0 + (metres - 1200) / 1900.0);
                    sink += ElevationCurve.toY(metres, boost)
                            + TerrainDetail.roughness(x, z, metres);
                }
            }
        }
        return sink;
    }

    private Benchmark() {}
}
