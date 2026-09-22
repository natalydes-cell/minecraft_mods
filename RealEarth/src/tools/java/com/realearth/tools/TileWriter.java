package com.realearth.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the mod tile format: gzipped little-endian int16, {@code <dir>/<tx>/<ty>.r16.gz}.
 *
 * <p>Shared by every converter, because the output format is the same whatever the input was.
 * A global raster is written band by band rather than all at once - a 1 km Koppen grid is 933
 * million samples, 1.8 GB as shorts, and holding that would push the importer past any sensible
 * heap. One band of tile-height rows is a few tens of megabytes and streams comfortably.
 */
public final class TileWriter {

    /** Written where the source has no data. Matches GeoSource.noDataValue for every source. */
    public static final short NO_DATA = Short.MIN_VALUE;

    private final Path root;
    private final int tileSize;
    private final int tilesX;
    private final int tilesY;

    private int written;

    public TileWriter(Path root, int tileSize, int globalWidth, int globalHeight) {
        this.root = root;
        this.tileSize = tileSize;
        this.tilesX = ceilDiv(globalWidth, tileSize);
        this.tilesY = ceilDiv(globalHeight, tileSize);
    }

    public int tilesX() {
        return tilesX;
    }

    public int tilesY() {
        return tilesY;
    }

    public int written() {
        return written;
    }

    /**
     * Supplies one sample of the global grid, origin at the top-left (lon -180, lat +90).
     * Returning {@link #NO_DATA} marks the sample absent.
     */
    public interface Band {
        short sample(int globalX, int globalY);
    }

    /**
     * Writes the row of tiles covering global rows {@code [tileY*tileSize, +tileSize)}.
     *
     * <p>A tile is skipped entirely when every sample in it is absent. On a planet that is mostly
     * ocean this drops a large fraction of the output, and the mod treats a missing file and an
     * all-absent file identically, so nothing is lost.
     */
    public void writeTileRow(int tileY, Band band) throws IOException {
        for (int tx = 0; tx < tilesX; tx++) {
            short[] buffer = new short[tileSize * tileSize];
            boolean any = false;

            int baseX = tx * tileSize;
            int baseY = tileY * tileSize;

            for (int ly = 0; ly < tileSize; ly++) {
                for (int lx = 0; lx < tileSize; lx++) {
                    short v = band.sample(baseX + lx, baseY + ly);
                    buffer[ly * tileSize + lx] = v;
                    if (v != NO_DATA) any = true;
                }
            }

            if (!any) continue;
            write(tx, tileY, buffer);
        }
    }

    /**
     * Writes one tile, gzipped.
     *
     * <p>The saving is not marginal. A Koppen tile is 262,144 samples drawn from 30 possible
     * values and compresses about 920 to 1; the planet's climate goes from 990 MB to a few. Even
     * elevation, which is far less repetitive, compresses 9 to 1. The mod decompresses once per
     * tile on a cache miss, so nothing in the terrain hot path pays for it.
     */
    public void write(int tx, int ty, short[] buffer) throws IOException {
        Path file = root.resolve(Integer.toString(tx)).resolve(ty + ".r16.gz");
        Files.createDirectories(file.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(buffer.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(buffer);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(bytes.array());
        }
        written++;
    }

    /**
     * Reads an existing tile, or a fully-absent buffer when there is none.
     *
     * <p>Needed because ETOPO's source tiles do not align with the output grid, so a tile on the
     * seam between two of them is written twice - once by each neighbour - and the second write
     * must not erase the first.
     */
    public short[] read(int tx, int ty) throws IOException {
        short[] buffer = new short[tileSize * tileSize];
        Path file = root.resolve(Integer.toString(tx)).resolve(ty + ".r16.gz");
        if (Files.isRegularFile(file)) {
            try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
                byte[] raw = in.readAllBytes();
                if (raw.length == buffer.length * 2) {
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buffer);
                    return buffer;
                }
            }
        }
        Arrays.fill(buffer, NO_DATA);
        return buffer;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
