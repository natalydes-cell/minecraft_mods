package com.realearth.tools;

import java.awt.Rectangle;
import java.awt.image.Raster;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Downloads the open geodata and converts it into the tiles the mod reads at runtime.
 *
 * <pre>
 *   gradlew importGeodata --args="run/config/realearth --bbox 35,72,-12,45"
 * </pre>
 *
 * <h2>Why a converter exists at all</h2>
 * The mod reads raw little-endian int16 tiles, not GeoTIFF. Decoding a compressed, tiled TIFF
 * costs more per sample than generating the terrain does, and that decode would sit directly on
 * the chunk worker threads. Paying it once here, offline, instead of a hundred million times
 * during play, is the entire reason for this class.
 *
 * <h2>Why the bounding box only applies to elevation</h2>
 * Elevation is 6 GB for the globe and is the one source worth importing piecemeal. Everything
 * else is small enough that partial import would save nothing worth the complexity: the whole
 * planet's climate is 12 MB and its surface geology is 38 kB.
 */
public final class GeoImporter {

    // --- ETOPO 2022 ------------------------------------------------------------------------
    private static final int ETOPO_TILE_DEG = 15;
    private static final int ETOPO_SAMPLES_PER_DEG = 240;
    private static final int ETOPO_TILE_PX = ETOPO_TILE_DEG * ETOPO_SAMPLES_PER_DEG;
    private static final String ETOPO_BASE =
            "https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO2022/data/15s/15s_surface_elev_gtif/";

    // --- Koppen-Geiger V3 ------------------------------------------------------------------
    private static final String KOPPEN_URL = "https://ndownloader.figshare.com/files/61012822";
    /** 1/120 degree, the 1 km map. The archive also holds 0.1 and 0.5 degree versions. */
    private static final int KOPPEN_SAMPLES_PER_DEG = 120;
    private static final String KOPPEN_ENTRY = "1991_2020/koppen_geiger_0p00833333.tif";

    // --- WorldClim 2.1 ---------------------------------------------------------------------
    private static final String WORLDCLIM_TAVG =
            "https://geodata.ucdavis.edu/climate/worldclim/2_1/base/wc2.1_10m_tavg.zip";
    private static final String WORLDCLIM_PREC =
            "https://geodata.ucdavis.edu/climate/worldclim/2_1/base/wc2.1_10m_prec.zip";
    /** 10 arc-MINUTES, not 10 samples per degree: 1/6 of a degree, so 6 samples per degree. */
    private static final int WORLDCLIM_SAMPLES_PER_DEG = 6;

    // --- GLiM ------------------------------------------------------------------------------
    private static final String GLIM_URL = "https://hdl.handle.net/10013/epic.39939.d001";
    private static final int GLIM_SAMPLES_PER_DEG = 2;

    private static final int OUT_TILE = 512;
    private static final int OUT_TILE_SMALL = 256;

    public static void main(String[] args) throws Exception {
        // One jar, two jobs. The companion-mod fetcher reuses this jar's download plumbing and
        // its bundled Gson, so shipping a second executable for it would be pure duplication.
        for (String arg : args) {
            if (arg.equals("--mods")) {
                ModFetcher.main(args);
                return;
            }
        }

        if (args.length < 1) {
            System.err.println("usage: GeoImporter <configDir> [--bbox minLat,maxLat,minLon,maxLon]"
                    + " [--only elevation,koppen,climate,geology]");
            System.err.println("   or: GeoImporter --mods <modsDir> [--only id,id]");
            System.exit(2);
        }
        Path configDir = Path.of(args[0]).toAbsolutePath();
        Bbox box = Bbox.world();
        List<String> only = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--bbox") && i + 1 < args.length) {
                box = Bbox.parse(args[++i]);
            } else if (args[i].equals("--only") && i + 1 < args.length) {
                for (String s : args[++i].split(",")) only.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }

        Path dataRoot = configDir.resolve("data");
        Path cache = configDir.resolve("download-cache");
        Files.createDirectories(dataRoot);
        Files.createDirectories(cache);

        System.out.println("RealEarth geodata importer");
        System.out.println("  config : " + configDir);
        System.out.println("  region : " + box + "  (elevation only)");
        System.out.println();

        if (wanted(only, "geology"))   importGlim(dataRoot.resolve("geology"), cache);
        if (wanted(only, "koppen"))    importKoppen(dataRoot.resolve("koppen"), cache);
        if (wanted(only, "climate"))   importWorldClim(dataRoot, cache);
        if (wanted(only, "elevation")) importElevation(dataRoot.resolve("elevation"), cache, box);

        System.out.println();
        System.out.println("Done. Start the server and the terrain will follow the real Earth.");
        System.out.println("Anything outside the imported region stays flat until you import it.");
    }

    private static boolean wanted(List<String> only, String name) {
        return only.isEmpty() || only.contains(name);
    }

    // =========================================================================================
    // Elevation: 272 tiled GeoTIFFs
    // =========================================================================================

    private static void importElevation(Path out, Path cache, Bbox box) throws IOException {
        System.out.println("[elevation] ETOPO 2022, 15 arc-second");
        Files.createDirectories(out);

        int converted = 0;
        int skipped = 0;
        long bytes = 0;

        // ETOPO names a tile by its north-west corner. Latitudes run 90 down to -75 in 15 degree
        // steps (the tile named N90 covers 75..90), longitudes -180 up to 165.
        for (int latNW = 90; latNW >= -75; latNW -= ETOPO_TILE_DEG) {
            for (int lonW = -180; lonW < 180; lonW += ETOPO_TILE_DEG) {
                if (!box.intersects(latNW - ETOPO_TILE_DEG, latNW, lonW, lonW + ETOPO_TILE_DEG)) {
                    skipped++;
                    continue;
                }
                String name = etopoName(latNW, lonW);
                Path local = cache.resolve(name);
                try {
                    bytes += Downloads.download(ETOPO_BASE + name, local);
                } catch (IOException e) {
                    // Ocean-only tiles genuinely are not published. That is not an error: absent
                    // data means "nothing here", which is what the mod already assumes.
                    System.out.printf("  - %s not published, skipping%n", name);
                    skipped++;
                    continue;
                }
                convertEtopoTile(local, latNW, lonW, out);
                converted++;
                System.out.printf("  + %-46s %3d done, %.2f GB fetched%n",
                        name, converted, bytes / (1024.0 * 1024.0 * 1024.0));
            }
        }
        System.out.printf("[elevation] %d source tiles converted, %d skipped%n", converted, skipped);
    }

    private static String etopoName(int latNW, int lonW) {
        return String.format(Locale.ROOT, "ETOPO_2022_v1_15s_%c%02d%c%03d_surface.tif",
                latNW >= 0 ? 'N' : 'S', Math.abs(latNW),
                lonW >= 0 ? 'E' : 'W', Math.abs(lonW));
    }

    /**
     * Scatters one ETOPO GeoTIFF into the mod's output tiles.
     *
     * <p>The grids do not line up - 3600 source pixels do not divide by 512 - so this is a
     * read-modify-write per output tile rather than a copy. Tiles on the seam between two source
     * tiles get written twice, once by each neighbour, which is why existing contents are loaded
     * first instead of starting from a blank buffer.
     */
    private static void convertEtopoTile(Path tif, int latNW, int lonW, Path out)
            throws IOException {
        Raster raster = Downloads.readRaster(tif, null);
        int w = raster.getWidth();
        int h = raster.getHeight();
        if (w != ETOPO_TILE_PX || h != ETOPO_TILE_PX) {
            System.out.printf("  ! %s is %dx%d, expected %d square - importing anyway%n",
                    tif.getFileName(), w, h, ETOPO_TILE_PX);
        }

        int gx0 = (int) Math.round((lonW + 180.0) * ETOPO_SAMPLES_PER_DEG);
        int gy0 = (int) Math.round((90.0 - latNW) * ETOPO_SAMPLES_PER_DEG);

        TileWriter writer = new TileWriter(out, OUT_TILE,
                360 * ETOPO_SAMPLES_PER_DEG, 180 * ETOPO_SAMPLES_PER_DEG);

        int txMin = Math.floorDiv(gx0, OUT_TILE);
        int txMax = Math.floorDiv(gx0 + w - 1, OUT_TILE);
        int tyMin = Math.floorDiv(gy0, OUT_TILE);
        int tyMax = Math.floorDiv(gy0 + h - 1, OUT_TILE);

        for (int tx = txMin; tx <= txMax; tx++) {
            for (int ty = tyMin; ty <= tyMax; ty++) {
                short[] buffer = writer.read(tx, ty);
                boolean touched = false;
                for (int ly = 0; ly < OUT_TILE; ly++) {
                    int sy = ty * OUT_TILE + ly - gy0;
                    if (sy < 0 || sy >= h) continue;
                    for (int lx = 0; lx < OUT_TILE; lx++) {
                        int sx = tx * OUT_TILE + lx - gx0;
                        if (sx < 0 || sx >= w) continue;
                        // getSampleFloat, not getSample: ETOPO stores elevation as 32-bit float
                        // and the integer accessor truncates towards zero rather than rounding,
                        // which would shave a metre off every negative elevation on the planet
                        // and shift every coastline.
                        buffer[ly * OUT_TILE + lx] = metres(raster.getSampleFloat(sx, sy, 0));
                        touched = true;
                    }
                }
                if (touched) writer.write(tx, ty, buffer);
            }
        }
    }

    /**
     * Rounds a float elevation to the int16 the mod stores. Metres fit comfortably - Earth's
     * range is -10935 to 8849 - and one metre of precision is exactly right, because below
     * 1500 m the elevation curve is 1:1 and one metre is one block.
     */
    private static short metres(float v) {
        if (Float.isNaN(v) || v <= TileWriter.NO_DATA + 1) return TileWriter.NO_DATA;
        long r = Math.round(v);
        if (r > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (r < TileWriter.NO_DATA + 1) return TileWriter.NO_DATA;
        return (short) r;
    }

    // =========================================================================================
    // Koppen-Geiger: one global GeoTIFF, streamed in bands
    // =========================================================================================

    private static void importKoppen(Path out, Path cache) throws IOException {
        System.out.println("[koppen] Beck et al. 2023 V3, 1 km");
        Path zip = cache.resolve("koppen_geiger_tif.zip");
        Downloads.download(KOPPEN_URL, zip);
        Path extracted = cache.resolve("koppen");
        Downloads.unzipMatching(zip, KOPPEN_ENTRY, extracted);

        Path tif = extracted.resolve("koppen_geiger_0p00833333.tif");
        if (!Files.isRegularFile(tif)) {
            System.out.println("  ! expected entry missing from the archive, skipping koppen");
            return;
        }

        int width = 360 * KOPPEN_SAMPLES_PER_DEG;
        int height = 180 * KOPPEN_SAMPLES_PER_DEG;
        int[] actual = Downloads.imageSize(tif);
        if (actual[0] != width || actual[1] != height) {
            System.out.printf("  ! raster is %dx%d, expected %dx%d - georeferencing would be "
                    + "wrong, skipping koppen%n", actual[0], actual[1], width, height);
            return;
        }

        // The class values are 1..30 in the standard Koppen order, which is exactly the numbering
        // KoppenClass uses, so nothing is remapped. 0 is the sea, which the mod reads as ocean.
        streamGlobalTiff(tif, out, OUT_TILE, width, height, v -> {
            int c = Math.round(v);
            return (c < 1 || c > 30) ? TileWriter.NO_DATA : (short) c;
        }, "koppen");
    }

    // =========================================================================================
    // WorldClim: twelve monthly GeoTIFFs averaged into one annual mean
    // =========================================================================================

    private static void importWorldClim(Path dataRoot, Path cache) throws IOException {
        importWorldClimSet(dataRoot.resolve("temperature"), cache, WORLDCLIM_TAVG,
                "wc2.1_10m_tavg", "temperature", 10.0f);
        importWorldClimSet(dataRoot.resolve("precipitation"), cache, WORLDCLIM_PREC,
                "wc2.1_10m_prec", "precipitation", 1.0f);
    }

    /**
     * @param storeScale multiplier applied before storing, matching the source's {@code scale}.
     *                   Temperature is kept as tenths of a degree so half-degree detail survives
     *                   the trip through an integer; precipitation is whole millimetres already.
     */
    private static void importWorldClimSet(Path out, Path cache, String url, String prefix,
                                           String label, float storeScale) throws IOException {
        System.out.println("[" + label + "] WorldClim 2.1, 10 arc-minute");
        Path zip = cache.resolve(prefix + ".zip");
        Downloads.download(url, zip);
        Path extracted = cache.resolve(prefix);
        Downloads.unzipMatching(zip, prefix, extracted);

        List<Path> months = new ArrayList<>();
        try (var stream = Files.list(extracted)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".tif")).sorted()
                    .forEach(months::add);
        }
        if (months.isEmpty()) {
            System.out.println("  ! no monthly rasters found, skipping " + label);
            return;
        }

        int width = 360 * WORLDCLIM_SAMPLES_PER_DEG;
        int height = 180 * WORLDCLIM_SAMPLES_PER_DEG;

        // Small enough to hold whole: 3600x1800 floats is 26 MB per month.
        float[] sum = new float[width * height];
        int[] count = new int[width * height];

        for (Path month : months) {
            int[] size = Downloads.imageSize(month);
            if (size[0] != width || size[1] != height) {
                System.out.printf("  ! %s is %dx%d, expected %dx%d - skipping this month%n",
                        month.getFileName(), size[0], size[1], width, height);
                continue;
            }
            Raster r = Downloads.readRaster(month, null);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float v = r.getSampleFloat(x, y, 0);
                    // WorldClim marks the sea with a large negative fill, not NaN.
                    if (Float.isNaN(v) || v < -1000f) continue;
                    sum[y * width + x] += v;
                    count[y * width + x]++;
                }
            }
        }
        System.out.printf("  averaged %d monthly rasters%n", months.size());

        TileWriter writer = new TileWriter(out, OUT_TILE, width, height);
        for (int ty = 0; ty < writer.tilesY(); ty++) {
            writer.writeTileRow(ty, (gx, gy) -> {
                if (gx >= width || gy >= height) return TileWriter.NO_DATA;
                int i = gy * width + gx;
                if (count[i] == 0) return TileWriter.NO_DATA;
                float mean = sum[i] / count[i];
                long stored = Math.round(mean * storeScale);
                if (stored > Short.MAX_VALUE) return Short.MAX_VALUE;
                if (stored < TileWriter.NO_DATA + 1) return TileWriter.NO_DATA;
                return (short) stored;
            });
        }
        System.out.printf("[%s] %d tiles written%n", label, writer.written());
    }

    // =========================================================================================
    // GLiM: an ESRI ASCII grid, 720x360
    // =========================================================================================

    private static void importGlim(Path out, Path cache) throws IOException {
        System.out.println("[geology] GLiM 0.5 degree");
        Path zip = cache.resolve("glim.zip");
        Downloads.download(GLIM_URL, zip);
        Path extracted = cache.resolve("glim");
        Downloads.unzipMatching(zip, ".asc", extracted);

        Path asc = null;
        try (var stream = Files.list(extracted)) {
            asc = stream.filter(p -> p.getFileName().toString().endsWith(".asc"))
                    .findFirst().orElse(null);
        }
        if (asc == null) {
            System.out.println("  ! no ASCII grid in the archive, skipping geology");
            return;
        }

        int width = 360 * GLIM_SAMPLES_PER_DEG;
        int height = 180 * GLIM_SAMPLES_PER_DEG;
        short[] grid = new short[width * height];
        java.util.Arrays.fill(grid, TileWriter.NO_DATA);

        // ESRI ASCII: six header lines, then rows of whitespace-separated integers running
        // north to south - the same direction as the mod's global grid, so no flip is needed.
        try (BufferedReader in = Files.newBufferedReader(asc, StandardCharsets.UTF_8)) {
            int ncols = 0;
            int nrows = 0;
            for (int i = 0; i < 6; i++) {
                String[] parts = in.readLine().trim().split("\\s+");
                switch (parts[0].toLowerCase(Locale.ROOT)) {
                    case "ncols" -> ncols = Integer.parseInt(parts[1]);
                    case "nrows" -> nrows = Integer.parseInt(parts[1]);
                    default -> { }
                }
            }
            if (ncols != width || nrows != height) {
                System.out.printf("  ! grid is %dx%d, expected %dx%d - skipping geology%n",
                        ncols, nrows, width, height);
                return;
            }
            for (int y = 0; y < height; y++) {
                String line = in.readLine();
                if (line == null) break;
                String[] cells = line.trim().split("\\s+");
                for (int x = 0; x < Math.min(width, cells.length); x++) {
                    int v = Integer.parseInt(cells[x]);
                    grid[y * width + x] = (v < 1 || v > 16)
                            ? TileWriter.NO_DATA : (short) v;
                }
            }
        }

        TileWriter writer = new TileWriter(out, OUT_TILE_SMALL, width, height);
        final int w = width;
        final int h = height;
        for (int ty = 0; ty < writer.tilesY(); ty++) {
            writer.writeTileRow(ty, (gx, gy) ->
                    (gx >= w || gy >= h) ? TileWriter.NO_DATA : grid[gy * w + gx]);
        }
        System.out.printf("[geology] %d tiles written%n", writer.written());
    }

    // =========================================================================================
    // Shared: stream a global GeoTIFF into tiles, one band of rows at a time
    // =========================================================================================

    private interface Recode {
        short apply(float raw);
    }

    private static void streamGlobalTiff(Path tif, Path out, int tileSize, int width, int height,
                                         Recode recode, String label) throws IOException {
        TileWriter writer = new TileWriter(out, tileSize, width, height);

        for (int ty = 0; ty < writer.tilesY(); ty++) {
            int y0 = ty * tileSize;
            int rows = Math.min(tileSize, height - y0);
            // One band: full width, one tile tall. At 1 km that is 43200 x 512 samples, which
            // decodes in a couple of seconds and costs about 90 MB - versus 1.8 GB for the
            // whole raster at once.
            Raster band = Downloads.readRaster(tif, new Rectangle(0, y0, width, rows));

            final int bandRows = rows;
            writer.writeTileRow(ty, (gx, gy) -> {
                int ly = gy - y0;
                if (gx >= width || ly < 0 || ly >= bandRows) return TileWriter.NO_DATA;
                return recode.apply(band.getSampleFloat(gx, ly, 0));
            });
            System.out.printf("  band %d/%d%n", ty + 1, writer.tilesY());
        }
        System.out.printf("[%s] %d tiles written%n", label, writer.written());
    }

    // =========================================================================================

    private record Bbox(double minLat, double maxLat, double minLon, double maxLon) {

        static Bbox world() {
            return new Bbox(-90, 90, -180, 180);
        }

        static Bbox parse(String spec) {
            String[] parts = spec.split(",");
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "--bbox wants minLat,maxLat,minLon,maxLon - got: " + spec);
            }
            return new Bbox(
                    Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim()));
        }

        boolean intersects(double latS, double latN, double lonW, double lonE) {
            return latN > minLat && latS < maxLat && lonE > minLon && lonW < maxLon;
        }

        @Override
        public String toString() {
            if (minLat <= -90 && maxLat >= 90 && minLon <= -180 && maxLon >= 180) {
                return "whole planet (about 6 GB - pass --bbox to import less)";
            }
            return String.format(Locale.ROOT, "lat %.1f..%.1f, lon %.1f..%.1f",
                    minLat, maxLat, minLon, maxLon);
        }
    }

    private GeoImporter() {}
}
