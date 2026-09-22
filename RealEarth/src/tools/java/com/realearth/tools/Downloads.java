package com.realearth.tools;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloading, unzipping and TIFF decoding, shared by every converter. */
public final class Downloads {

    private Downloads() {}

    /**
     * Fetches a URL to a local file, skipping the download when it is already there.
     *
     * <p>Resuming matters: a full import is six gigabytes over a government file server, and
     * losing all of it to one dropped connection two hours in would be unacceptable. The partial
     * file is written under a {@code .part} name and only moved into place once complete, so an
     * interrupted download is never mistaken for a finished one on the next run.
     */
    public static long download(String url, Path target) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return Files.size(target);
        }
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(target.getParent());

        String current = url;
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(current).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(180_000);
            conn.setRequestProperty("User-Agent", "RealEarth-importer");
            int status = conn.getResponseCode();

            // Several of these hosts redirect across schemes or hosts, which the JDK refuses to
            // follow automatically. Following by hand is the only way figshare and PANGAEA work.
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String next = conn.getHeaderField("Location");
                conn.disconnect();
                if (next == null) throw new IOException("redirect with no Location: " + current);
                current = next.startsWith("http") ? next : URI.create(current).resolve(next).toString();
                continue;
            }
            if (status != 200) {
                conn.disconnect();
                throw new IOException("HTTP " + status + " for " + current);
            }
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                conn.disconnect();
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return Files.size(target);
        }
        throw new IOException("too many redirects for " + url);
    }

    /**
     * Extracts entries whose path contains {@code match} into {@code outDir}, flattening the
     * directory structure. Returns the number extracted.
     */
    public static int unzipMatching(Path zip, String match, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().contains(match)) continue;
                String flat = Path.of(entry.getName()).getFileName().toString();
                Path target = outDir.resolve(flat);
                if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        }
        return count;
    }

    /**
     * Reads pixel data from a TIFF, optionally only a sub-rectangle.
     *
     * <p>{@code readRaster} rather than {@code ImageIO.read} on purpose: these files carry
     * single-band signed 16-bit or 32-bit float samples with no sensible colour model, and going
     * through BufferedImage either fails outright or silently rescales the values into 0..255 -
     * which would turn every elevation on the planet into a number between zero and sea level.
     *
     * @param region sub-rectangle to read, or null for the whole image
     */
    public static Raster readRaster(Path file, Rectangle region) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) throw new IOException("no image input stream for " + file);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("no TIFF reader for " + file
                        + " - is imageio-tiff on the classpath?");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                ImageReadParam param = reader.getDefaultReadParam();
                if (region != null) param.setSourceRegion(region);
                return reader.readRaster(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Image dimensions without decoding the pixels. */
    public static int[] imageSize(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("no TIFF reader for " + file);
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }
}
