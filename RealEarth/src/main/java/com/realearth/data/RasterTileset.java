package com.realearth.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Reads one {@link GeoSource} as a grid of tiles, with a bounded LRU cache in front of disk.
 *
 * <p>The whole point of tiling is that the mod never holds the planet in memory. A 15 arc-second
 * global elevation grid is 86400x43200 samples - 7.4 GB as shorts. At 512x512 tiles only the
 * handful under the loaded chunks is resident, which is a few megabytes.
 *
 * <p>Thread safety matters here: chunk generation runs on the worker pool, so several threads hit
 * the same tileset at once. Reads take the shared lock, and only a genuine cache miss escalates to
 * the write lock, so the common path does not serialise.
 */
public final class RasterTileset implements AutoCloseable {

    /** One decoded tile. {@code samples} is row-major, {@code tileSize * tileSize} entries. */
    private record Tile(int[] samples) {}

    private final GeoSource source;
    private final Path root;
    private final int tileSize;
    private final int maxCachedTiles;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final LinkedHashMap<Long, Tile> cache;

    /** A tile that is known to be absent; avoids hammering the disk for ocean-only regions. */
    private static final Tile MISSING = new Tile(new int[0]);

    public RasterTileset(GeoSource source, Path dataRoot, int maxCachedTiles) {
        this.source = source;
        this.root = dataRoot.resolve(source.localDir());
        this.tileSize = source.tileSize();
        this.maxCachedTiles = Math.max(8, maxCachedTiles);
        this.cache = new LinkedHashMap<>(this.maxCachedTiles * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Tile> eldest) {
                return size() > RasterTileset.this.maxCachedTiles;
            }
        };
    }

    public GeoSource source() {
        return source;
    }

    /**
     * Bilinearly samples the raster at a geographic position, in the source's real units.
     *
     * <p>Bilinear rather than nearest-neighbour is not cosmetic: at 1:2 scale one source sample
     * covers several blocks, and nearest-neighbour turns every coastline and contour into visible
     * 8-block stairsteps.
     *
     * @return the decoded value, or {@code fallback} where the raster has no data
     */
    public double sample(double latitude, double longitude, double fallback) {
        double spd = source.samplesPerDegree();
        // Global sample grid, origin at (lon -180, lat +90), y increasing southwards.
        double gx = (longitude + 180.0) * spd;
        double gy = (90.0 - latitude) * spd;

        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy);
        double fx = gx - x0, fy = gy - y0;

        double v00 = raw(x0,     y0,     fallback);
        double v10 = raw(x0 + 1, y0,     fallback);
        double v01 = raw(x0,     y0 + 1, fallback);
        double v11 = raw(x0 + 1, y0 + 1, fallback);

        double top = v00 + (v10 - v00) * fx;
        double bot = v01 + (v11 - v01) * fx;
        return top + (bot - top) * fy;
    }

    /** Nearest-neighbour sample, for class rasters where interpolating indices is meaningless. */
    public int sampleClass(double latitude, double longitude, int fallback) {
        double spd = source.samplesPerDegree();
        int gx = (int) Math.round((longitude + 180.0) * spd);
        int gy = (int) Math.round((90.0 - latitude) * spd);
        double v = raw(gx, gy, Double.NaN);
        return Double.isNaN(v) ? fallback : (int) Math.round((v - source.offset()) / source.scale());
    }

    private double raw(int gx, int gy, double fallback) {
        int globalWidth = (int) Math.round(360.0 * source.samplesPerDegree());
        int globalHeight = (int) Math.round(180.0 * source.samplesPerDegree());

        // Longitude wraps; latitude clamps at the poles.
        gx = Math.floorMod(gx, globalWidth);
        gy = Math.max(0, Math.min(globalHeight - 1, gy));

        int tx = gx / tileSize, ty = gy / tileSize;
        Tile tile = tile(tx, ty);
        if (tile == MISSING) return fallback;

        int lx = gx - tx * tileSize, ly = gy - ty * tileSize;
        int rawValue = tile.samples()[ly * tileSize + lx];
        if (rawValue == source.noDataValue()) return fallback;
        return source.decode(rawValue);
    }

    private Tile tile(int tx, int ty) {
        long key = ((long) tx << 32) | (ty & 0xFFFFFFFFL);

        lock.readLock().lock();
        try {
            Tile hit = cache.get(key);
            if (hit != null) return hit;
        } finally {
            lock.readLock().unlock();
        }

        lock.writeLock().lock();
        try {
            // Another thread may have loaded it while we waited for the write lock.
            Tile hit = cache.get(key);
            if (hit != null) return hit;
            Tile loaded = load(tx, ty);
            cache.put(key, loaded);
            return loaded;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Loads one tile from the raw little-endian 16-bit format the importer writes.
     *
     * <p>Deliberately not GeoTIFF or PNG at runtime: decoding those on the chunk worker threads
     * costs far more than a straight byte-to-short copy, and this read sits directly in the
     * terrain hot path. {@link TileImporter} converts the downloaded originals once, offline.
     */
    private Tile load(int tx, int ty) {
        Path gz = tilePath(root, tx, ty);
        Path plain = tilePathUncompressed(root, tx, ty);

        boolean compressed = Files.isRegularFile(gz);
        Path file = compressed ? gz : plain;
        if (!compressed && !Files.isRegularFile(plain)) return MISSING;

        try {
            byte[] bytes = compressed ? readGzip(gz) : Files.readAllBytes(file);
            int expected = tileSize * tileSize * 2;
            if (bytes.length < expected) return MISSING;
            int[] out = new int[tileSize * tileSize];
            for (int i = 0; i < out.length; i++) {
                int lo = bytes[i * 2] & 0xFF;
                int hi = bytes[i * 2 + 1];
                out[i] = (hi << 8) | lo; // signed: hi keeps its sign bit
            }
            return new Tile(out);
        } catch (IOException e) {
            return MISSING;
        }
    }

    /**
     * Where one tile lives: {@code <source>/<tx>/<ty>.r16.gz}.
     *
     * <p>Split into a directory per column rather than one flat folder. A global 15 arc-second
     * elevation set is roughly 14,000 tiles, and every common filesystem - NTFS especially -
     * degrades badly on directory listings that size. The importer writes the same layout.
     *
     * <p>Gzipped, because the saving is enormous and the cost is not. A Koppen tile holds 262,144
     * samples drawn from 30 possible values and compresses about 920 to 1 - half a megabyte
     * becomes 570 bytes, and the whole planet's climate drops from 990 MB to a few. Elevation is
     * less repetitive and still compresses 9 to 1. Decompression happens once per tile on a cache
     * miss, never on the sampling path, so the terrain hot loop is untouched.
     */
    public static Path tilePath(Path sourceRoot, int tx, int ty) {
        return sourceRoot.resolve(Integer.toString(tx)).resolve(ty + ".r16.gz");
    }

    /**
     * The uncompressed form, still read when present.
     *
     * <p>Kept as a fallback so anyone hand-building tiles - from a national survey, or a script of
     * their own - can write plain binary and have it work without needing to match the
     * compression.
     */
    public static Path tilePathUncompressed(Path sourceRoot, int tx, int ty) {
        return sourceRoot.resolve(Integer.toString(tx)).resolve(ty + ".r16");
    }

    private static byte[] readGzip(Path file) throws IOException {
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
            return in.readAllBytes();
        }
    }

    public void invalidate() {
        lock.writeLock().lock();
        try {
            cache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        invalidate();
    }
}
