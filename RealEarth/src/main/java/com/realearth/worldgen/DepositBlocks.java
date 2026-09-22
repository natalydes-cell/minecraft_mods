package com.realearth.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.realearth.core.RealEarth;
import com.realearth.worldgen.Deposit.Resource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which block each real resource becomes, and where the operator changes that.
 *
 * <p>Half the resources in {@link DepositRegistry} have no vanilla block - there is no nickel ore,
 * no bauxite, no potash, and certainly no oil. Guessing at mod block ids would be worse than
 * useless: it would silently place nothing, or place the wrong thing, with no way to tell which.
 *
 * <p>So the mapping is a file. {@code config/realearth/deposit-blocks.json} is written on first
 * run with vanilla defaults filled in and the modded slots left empty, each with a comment naming
 * the mod it expects. A resource with no usable block is skipped and reported once at startup,
 * which is how you find out that your oil fields are doing nothing.
 *
 * <p>Several ids may be listed per resource; the first one that exists in the registry wins. That
 * is what lets one config file work across modpacks - list the Create oil block and the
 * Immersive Engineering one, and whichever is installed gets used.
 */
public final class DepositBlocks {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<Resource, List<String>> candidates;

    private DepositBlocks(Map<Resource, List<String>> candidates) {
        this.candidates = candidates;
    }

    public List<String> candidatesFor(Resource resource) {
        return candidates.getOrDefault(resource, List.of());
    }

    public static DepositBlocks load(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve("deposit-blocks.json");

        Map<String, List<String>> raw;
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                raw = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, List<String>>>() {}.getType());
            }
            if (raw == null || raw.isEmpty()) raw = defaults();
        } else {
            raw = defaults();
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(raw, w);
            }
        }

        Map<Resource, List<String>> parsed = new EnumMap<>(Resource.class);
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            try {
                parsed.put(Resource.valueOf(e.getKey()), List.copyOf(e.getValue()));
            } catch (IllegalArgumentException ignored) {
                RealEarth.LOG.warn("deposit-blocks.json names an unknown resource '{}' - ignoring",
                        e.getKey());
            }
        }
        return new DepositBlocks(parsed);
    }

    /**
     * Vanilla where vanilla has an answer, empty where it does not.
     *
     * <p>The deliberate blanks are the point. Oil, nickel, tin, bauxite, potash and uranium are
     * all real provinces in the deposit table and none of them exist in vanilla Minecraft, so
     * leaving the list empty is the honest default - the mod says so at startup rather than
     * pretending to place something.
     */
    private static Map<String, List<String>> defaults() {
        Map<String, List<String>> m = new LinkedHashMap<>();

        // Vanilla has these outright. Deepslate variants are listed second and chosen by depth.
        m.put(Resource.COAL.name(), List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"));
        m.put(Resource.IRON.name(), List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"));
        m.put(Resource.COPPER.name(), List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore"));
        m.put(Resource.GOLD.name(), List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore"));
        m.put(Resource.DIAMOND.name(), List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"));

        // Evaporite provinces. Vanilla has no salt, but the Verkhnekamsk and Saskatchewan beds
        // are real and large, so they map to the nearest sedimentary stand-in rather than nothing.
        m.put(Resource.SALT.name(), List.of("create:salt_ore", "minecraft:calcite"));
        m.put(Resource.POTASH.name(), List.of("create:salt_ore", "minecraft:calcite"));

        // No vanilla equivalent. Fill these in for your modpack; empty means "place nothing".
        m.put(Resource.OIL.name(), List.of(
                // Create: The Factory Must Grow, Immersive Petroleum, Immersive Engineering.
                // Left empty rather than guessed - a wrong id silently places nothing.
        ));
        m.put(Resource.NATURAL_GAS.name(), List.of());
        m.put(Resource.NICKEL.name(), List.of("create:deepslate_zinc_ore", "create:zinc_ore"));
        m.put(Resource.TIN.name(), List.of());
        m.put(Resource.BAUXITE.name(), List.of());
        m.put(Resource.URANIUM.name(), List.of());

        return m;
    }

    /** Resources that ended up with nothing usable, for the one-time startup report. */
    public List<Resource> unmapped(java.util.function.Predicate<String> blockExists) {
        List<Resource> missing = new ArrayList<>();
        for (Resource r : Resource.values()) {
            if (candidatesFor(r).stream().noneMatch(blockExists)) missing.add(r);
        }
        return missing;
    }
}
