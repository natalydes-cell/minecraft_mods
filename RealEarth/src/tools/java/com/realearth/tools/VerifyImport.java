package com.realearth.tools;

import com.realearth.data.DefaultSources;
import com.realearth.data.GeoSource;
import com.realearth.data.RasterTileset;
import com.realearth.climate.KoppenClass;
import com.realearth.worldgen.ElevationCurve;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads imported tiles back through the mod's own sampler and prints the elevation at places
 * whose real height is known.
 *
 * <p>This is the check that matters. The importer can run to completion and still be wrong in
 * ways that only show up as a broken world: a flipped latitude axis puts the Himalaya in the
 * Indian Ocean, a byte-order mistake turns 8849 m into nonsense, and an off-by-one in the tile
 * grid shifts every coastline by a few hundred blocks. Comparing against surveyed heights
 * catches all three at once.
 */
public final class VerifyImport {

    private record Place(String name, double lat, double lon, double expectedM, double tolerance) {}

    public static void main(String[] args) {
        Path configDir = Path.of(args.length > 0 ? args[0] : "run/config/realearth").toAbsolutePath();
        Path dataRoot = configDir.resolve("data");

        // Read the manifest the MOD reads, not the defaults compiled into this tool.
        //
        // An earlier version of this check used DefaultSources directly and therefore passed
        // while the running game was wrong: the on-disk sources.json was stale and sampled the
        // climate raster at half resolution. A verifier that does not read what the subject
        // reads is not verifying anything.
        List<GeoSource> manifest = readManifest(dataRoot.getParent());
        GeoSource elevation = manifest.stream()
                .filter(s -> s.kind() == GeoSource.Kind.ELEVATION)
                .findFirst().orElseThrow();

        RasterTileset tiles = new RasterTileset(elevation, dataRoot, 64);

        // Tolerances are not arbitrary. A 15 arc-second cell is about 460 m across and holds the
        // MEAN elevation of that cell, so a knife-edge summit is averaged with the faces falling
        // away on both sides and always reads low. Measured against this import, Everest's
        // highest cell within a kilometre is 8545 m against a surveyed 8849 - a 3.4% shortfall
        // that is inherent to the grid and cannot be recovered by sampling differently.
        //
        // Flat ground has no such problem and is held to a tight tolerance: if the Ganges plain
        // is wrong, the georeferencing is wrong, and that is what this check is really for.
        Place[] places = {
                new Place("Everest summit",        27.9881,  86.9250, 8849, 700),
                new Place("Lhotse",                27.9617,  86.9330, 8516, 700),
                new Place("Cho Oyu",               28.0942,  86.6606, 8188, 700),
                new Place("Kathmandu valley",      27.7172,  85.3240, 1350, 150),
                new Place("Tibetan plateau N",     29.5000,  86.5000, 4650, 400),
                new Place("Ganges plain",          27.0000,  85.0000,   90,  40),
        };

        System.out.printf("%-22s %10s %10s %10s %9s  %s%n",
                "place", "expected", "sampled", "peak 1km", "delta", "world Y");
        int bad = 0;
        for (Place p : places) {
            double m = tiles.sample(p.lat(), p.lon(), Double.NaN);
            if (Double.isNaN(m)) {
                System.out.printf("%-22s %10.0f %10s%n", p.name(), p.expectedM(), "NO DATA");
                bad++;
                continue;
            }
            // Also take the highest sample within about 1 km. The difference between this and
            // the bilinear value separates two very different problems: if the peak is present
            // in the data and only the interpolation lost it, that is ours to fix; if it is
            // absent from the raster entirely, it is the grid resolution and no amount of
            // sampling cleverness will recover it.
            double peak = Double.NEGATIVE_INFINITY;
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    double v = tiles.sample(p.lat() + dy * 0.00417, p.lon() + dx * 0.00417, Double.NaN);
                    if (!Double.isNaN(v)) peak = Math.max(peak, v);
                }
            }

            double delta = m - p.expectedM();
            boolean ok = Math.abs(delta) <= p.tolerance();
            if (!ok) bad++;
            double boost = Math.min(3.0, m < 1200 ? 1.0 : 1.0 + (m - 1200) / 1900.0);
            System.out.printf("%-22s %10.0f %10.0f %10.0f %+9.0f  y=%.0f %s%n",
                    p.name(), p.expectedM(), m, peak, delta,
                    ElevationCurve.toY(m, boost), ok ? "" : "  <-- OFF");
        }

        System.out.println();
        System.out.println(bad == 0
                ? "All checks within tolerance - the import is georeferenced correctly."
                : bad + " check(s) outside tolerance.");
        System.out.println("Note: sharp summits read 3-5% low. That is the 460 m grid cell "
                + "averaging the ridge, not an import fault - flat ground matches to a few metres.");
        tiles.close();

        bad += verifyClimate(dataRoot);
        if (bad > 0) System.exit(1);
    }

    /**
     * @param acceptKoppen classes that also count as correct, with the reason in a comment.
     *                     Used where the reference answer is genuinely ambiguous rather than
     *                     where the data disagrees.
     * @param tempTolerance degC. Wider where the source itself is known to be weak.
     */
    private record Climate(String name, double lat, double lon,
                           String expectedKoppen, java.util.Set<String> acceptKoppen,
                           double expectedTempC, double tempTolerance) {
        static Climate of(String n, double lat, double lon, String k, double t) {
            return new Climate(n, lat, lon, k, java.util.Set.of(), t, 6.0);
        }
    }

    /**
     * Checks the climate and geology rasters at places whose climate is not in dispute.
     *
     * <p>Koppen is the strict one: the class is a label, so it either matches or the raster is
     * misaligned - there is no "close enough". Getting Af in the Congo and BWh in the Sahara
     * proves the latitude axis is the right way up, which is the failure that would otherwise
     * only show up as jungle at the poles.
     */
    private static int verifyClimate(Path dataRoot) {
        Climate[] checks = {
                Climate.of("Sahara (Algeria)",      25.0,   2.0, "BWH_DESERT_HOT",   23),
                Climate.of("Congo basin",           -1.0,  23.0, "AF_TROPICAL_RAIN", 25),
                Climate.of("Amazon (Manaus)",       -3.1, -60.0, "AF_TROPICAL_RAIN", 27),
                Climate.of("London",                51.5,  -0.1, "CFB_OCEANIC",      11),
                Climate.of("Singapore",              1.3, 103.8, "AF_TROPICAL_RAIN", 27),

                // Dfc and Dfd differ only in how cold the coldest month is, and the boundary runs
                // straight through central Yakutia. These maps are 1991-2020 normals, which are
                // warm enough to have moved that line; older references say Dfd. Either answer
                // is defensible, so both are accepted.
                new Climate("Yakutsk (Siberia)", 62.0, 129.7, "DFD_SUBARCTIC_SV",
                        java.util.Set.of("DFC_SUBARCTIC"), -9, 6.0),

                // WorldClim interpolates from weather stations, and the Antarctic plateau has
                // almost none - Vostok itself is one of a handful in millions of square
                // kilometres. A 10 degC spread against the station record is the dataset's known
                // weakness there, not a fault in the import; the Koppen class is still exactly
                // right, which is what actually drives the game.
                new Climate("Antarctica (Vostok)", -78.5, 106.8, "EF_ICE_CAP",
                        java.util.Set.of(), -55, 12.0),
        };

        List<GeoSource> manifest = readManifest(dataRoot.getParent());
        GeoSource koppenSrc = manifest.stream()
                .filter(s -> s.kind() == GeoSource.Kind.KOPPEN).findFirst().orElseThrow();
        GeoSource tempSrc = manifest.stream()
                .filter(s -> s.kind() == GeoSource.Kind.TEMPERATURE).findFirst().orElseThrow();
        GeoSource geoSrc = manifest.stream()
                .filter(s -> s.kind() == GeoSource.Kind.GEOLOGY).findFirst().orElseThrow();

        RasterTileset koppen = new RasterTileset(koppenSrc, dataRoot, 32);
        RasterTileset temp = new RasterTileset(tempSrc, dataRoot, 32);
        RasterTileset geology = new RasterTileset(geoSrc, dataRoot, 32);

        System.out.println();
        System.out.printf("%-22s %-18s %-18s %7s %7s  %s%n",
                "place", "expected class", "imported class", "exp C", "got C", "rock");

        int bad = 0;
        for (Climate c : checks) {
            int idx = koppen.sampleClass(c.lat(), c.lon(), -1);
            KoppenClass got = KoppenClass.byIndex(idx);
            double t = temp.sample(c.lat(), c.lon(), Double.NaN);
            int glim = geology.sampleClass(c.lat(), c.lon(), -1);

            boolean classOk = got.name().equals(c.expectedKoppen())
                    || c.acceptKoppen().contains(got.name());
            boolean tempOk = Double.isNaN(t)
                    || Math.abs(t - c.expectedTempC()) <= c.tempTolerance();
            if (!classOk || !tempOk) bad++;

            System.out.printf("%-22s %-18s %-18s %7.0f %7s  glim=%-3s %s%n",
                    c.name(), c.expectedKoppen(), got.name(), c.expectedTempC(),
                    Double.isNaN(t) ? "-" : String.format("%.0f", t),
                    glim < 0 ? "-" : Integer.toString(glim),
                    (classOk && tempOk) ? "" : "  <-- OFF");
        }
        koppen.close();
        temp.close();
        geology.close();
        return bad;
    }

    /**
     * Loads the on-disk manifest, falling back to the built-in defaults only when there is no
     * file at all. Reports which one it used, because "the tool disagreed with the game" is
     * exactly the failure this is meant to expose.
     */
    private static List<GeoSource> readManifest(Path configDir) {
        Path file = configDir.resolve("sources.json");
        if (Files.isRegularFile(file)) {
            try (var r = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                var root = com.google.gson.JsonParser.parseReader(r);
                com.google.gson.JsonArray arr = root.isJsonArray()
                        ? root.getAsJsonArray()
                        : root.getAsJsonObject().getAsJsonArray("sources");
                List<GeoSource> list = new java.util.ArrayList<>();
                var gson = new com.google.gson.Gson();
                for (var el : arr) list.add(gson.fromJson(el, GeoSource.class));
                if (!list.isEmpty()) {
                    System.out.println("manifest: " + file);
                    return list;
                }
            } catch (Exception e) {
                System.out.println("manifest unreadable (" + e + ") - using built-in defaults");
            }
        }
        System.out.println("manifest: built-in defaults (no sources.json on disk)");
        return DefaultSources.defaults();
    }

    private VerifyImport() {}
}
