package com.realearth.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Downloads the companion mods from their authors' own files, into a mods folder.
 *
 * <pre>
 *   java -jar realearth-importer.jar --mods &lt;modsDir&gt; [--only better-clouds,cold-sweat]
 * </pre>
 *
 * <h2>Why fetch instead of bundle</h2>
 * Every companion mod here is copyleft - MPL-2.0, GPL-3.0, LGPL-3.0. Redistributing them is
 * permitted but carries obligations about licence text and source, the bundled copies go stale
 * the day their authors publish an update, and shipping other people's jars is simply not how
 * the Minecraft ecosystem works. Pointing at the original files keeps attribution, licensing and
 * updates where they belong.
 *
 * <h2>Why the hashes matter</h2>
 * The manifest pins an exact version and its SHA-512. A mod jar is arbitrary code that will run
 * inside the game, so downloading one without checking what arrived would be careless. A file
 * that does not match its hash is deleted rather than kept, because a partially-written or
 * substituted jar is worse than no jar at all.
 */
public final class ModFetcher {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        Path modsDir = null;
        Path manifest = Path.of("companion-mods.json");
        List<String> only = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mods" -> modsDir = Path.of(args[++i]).toAbsolutePath();
                case "--manifest" -> manifest = Path.of(args[++i]).toAbsolutePath();
                case "--only" -> {
                    for (String s : args[++i].split(",")) only.add(s.trim().toLowerCase(Locale.ROOT));
                }
                default -> { }
            }
        }

        if (modsDir == null) {
            System.err.println("usage: --mods <modsDir> [--manifest companion-mods.json] "
                    + "[--only id,id]");
            System.exit(2);
        }
        if (!Files.isRegularFile(manifest)) {
            System.err.println("manifest not found: " + manifest);
            System.exit(2);
        }

        Files.createDirectories(modsDir);
        JsonObject doc = JsonParser.parseString(
                Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray mods = doc.getAsJsonArray("mods");

        System.out.println("RealEarth companion mods");
        System.out.println("  target   : " + modsDir);
        System.out.println("  minecraft: " + doc.get("minecraft").getAsString()
                + " " + doc.get("loader").getAsString());
        System.out.println();

        int fetched = 0;
        int skipped = 0;
        int failed = 0;

        for (var element : mods) {
            JsonObject m = element.getAsJsonObject();
            String id = m.get("id").getAsString();
            if (!only.isEmpty() && !only.contains(id)) {
                skipped++;
                continue;
            }

            String filename = m.get("filename").getAsString();
            Path target = modsDir.resolve(filename);
            String expected = m.get("sha512").getAsString();

            System.out.printf("%-24s %s%n", m.get("name").getAsString(),
                    m.get("version").getAsString());
            System.out.printf("  %s, %s, %.1f MB%n",
                    m.get("license").getAsString(), m.get("side").getAsString(),
                    m.get("sizeBytes").getAsLong() / 1024.0 / 1024.0);
            System.out.printf("  %s%n", m.get("why").getAsString());

            if (Files.isRegularFile(target) && sha512(target).equalsIgnoreCase(expected)) {
                System.out.println("  already present and verified");
                System.out.println();
                skipped++;
                continue;
            }

            try {
                Downloads.download(m.get("url").getAsString(), target);
                String actual = sha512(target);
                if (!actual.equalsIgnoreCase(expected)) {
                    Files.deleteIfExists(target);
                    System.out.println("  FAILED: hash mismatch, file deleted");
                    System.out.println("    expected " + expected.substring(0, 24) + "...");
                    System.out.println("    got      " + actual.substring(0, 24) + "...");
                    failed++;
                } else {
                    System.out.println("  downloaded and verified");
                    fetched++;
                }
            } catch (IOException e) {
                System.out.println("  FAILED: " + e.getMessage());
                System.out.println("  download it by hand from " + m.get("page").getAsString());
                failed++;
            }
            System.out.println();
        }

        System.out.printf("%d downloaded, %d already there, %d failed%n", fetched, skipped, failed);
        if (failed == 0) {
            System.out.println();
            System.out.println("All of these are optional - RealEarth runs without them.");
            System.out.println("Distant Horizons and Better Clouds are client-side: a dedicated");
            System.out.println("server does not need them, and players without them can still join.");
        }
        if (failed > 0) System.exit(1);
    }

    private static String sha512(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(128);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private ModFetcher() {}
}
