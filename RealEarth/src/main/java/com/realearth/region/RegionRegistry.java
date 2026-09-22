package com.realearth.region;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.realearth.region.Region.Kind;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The gazetteer: every named place the region card can announce.
 *
 * <p>Written to {@code config/realearth/regions.json} on first run and read back afterwards, so
 * the list is fully editable. Add your own regions, rename the ones here, or delete the lot - the
 * mod only reads what it finds.
 */
public final class RegionRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<Region> regions;

    private RegionRegistry(List<Region> regions) {
        // Sort once, most specific first, so lookup is a linear scan that stops at the first hit.
        List<Region> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(Region::priority).reversed()
                .thenComparingDouble(Region::area));
        this.regions = List.copyOf(sorted);
    }

    public static RegionRegistry load(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve("regions.json");
        List<Region> list;
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                list = GSON.fromJson(r, new TypeToken<List<Region>>() {}.getType());
            }
            if (list == null || list.isEmpty()) list = builtin();
        } else {
            list = builtin();
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(list, w);
            }
        }
        return new RegionRegistry(list);
    }

    /**
     * The most specific named region at this position, or null in the middle of nowhere.
     *
     * <p>Null is a legitimate answer and the HUD treats it as one: most of the planet is not a
     * named feature, and inventing a name for every square kilometre would make the card noise
     * rather than information.
     */
    public Region at(double lat, double lon) {
        for (Region r : regions) {
            if (r.contains(lat, lon)) return r;
        }
        return null;
    }

    /** Every region containing this position, most specific first. Used by the F3 overlay. */
    public List<Region> allAt(double lat, double lon) {
        List<Region> hits = new ArrayList<>(3);
        for (Region r : regions) {
            if (r.contains(lat, lon)) hits.add(r);
        }
        return hits;
    }

    public List<Region> all() {
        return regions;
    }

    // --- The built-in gazetteer ------------------------------------------------------------

    public static List<Region> builtin() {
        List<Region> r = new ArrayList<>();

        // Continents, priority 0: the fallback answer anywhere on land.
        r.add(new Region("eurasia", "Eurasia", Kind.CONTINENT, 8, 78, -12, 180, 0));
        r.add(new Region("africa", "Africa", Kind.CONTINENT, -35, 38, -18, 52, 0));
        r.add(new Region("north_america", "North America", Kind.CONTINENT, 7, 83, -172, -52, 0));
        r.add(new Region("south_america", "South America", Kind.CONTINENT, -56, 13, -82, -34, 0));
        r.add(new Region("australia", "Australia", Kind.CONTINENT, -44, -10, 112, 154, 0));
        r.add(new Region("antarctica", "Antarctica", Kind.CONTINENT, -90, -60, -180, 180, 0));

        // Oceans, priority 0.
        r.add(new Region("pacific", "Pacific Ocean", Kind.OCEAN, -60, 60, 120, -70, 0));
        r.add(new Region("atlantic", "Atlantic Ocean", Kind.OCEAN, -60, 68, -70, 20, 0));
        r.add(new Region("indian", "Indian Ocean", Kind.OCEAN, -60, 25, 20, 120, 0));
        r.add(new Region("arctic", "Arctic Ocean", Kind.OCEAN, 66, 90, -180, 180, 0));
        r.add(new Region("southern", "Southern Ocean", Kind.OCEAN, -78, -60, -180, 180, 0));

        // Sub-regions, priority 10.
        r.add(new Region("siberia", "Siberia", Kind.PLAIN, 50, 73, 60, 140, 10));
        r.add(new Region("europe", "Europe", Kind.CONTINENT, 36, 71, -10, 40, 10));
        r.add(new Region("scandinavia", "Scandinavia", Kind.PLAIN, 55, 71, 4, 31, 10));
        r.add(new Region("central_asia", "Central Asia", Kind.PLAIN, 35, 52, 46, 88, 10));
        r.add(new Region("indian_subcontinent", "Indian Subcontinent", Kind.PLAIN, 6, 32, 68, 90, 10));
        r.add(new Region("southeast_asia", "Southeast Asia", Kind.PLAIN, -10, 24, 92, 141, 10));
        r.add(new Region("sahel", "Sahel", Kind.PLAIN, 11, 18, -17, 40, 10));
        r.add(new Region("horn_of_africa", "Horn of Africa", Kind.PLAIN, -2, 18, 32, 52, 10));
        r.add(new Region("patagonia", "Patagonia", Kind.PLAIN, -56, -38, -76, -62, 10));
        r.add(new Region("great_plains", "Great Plains", Kind.PLAIN, 30, 54, -106, -96, 10));

        // Seas and straits, priority 15.
        r.add(new Region("mediterranean", "Mediterranean Sea", Kind.SEA, 30, 46, -6, 36, 15));
        r.add(new Region("black_sea", "Black Sea", Kind.SEA, 40.9, 47.3, 27, 42, 15));
        r.add(new Region("caspian", "Caspian Sea", Kind.LAKE, 36, 47.3, 46.5, 54.8, 15));
        r.add(new Region("baltic", "Baltic Sea", Kind.SEA, 53.5, 66, 10, 30, 15));
        r.add(new Region("north_sea", "North Sea", Kind.SEA, 51, 61, -4, 9, 15));
        r.add(new Region("red_sea", "Red Sea", Kind.SEA, 12, 30, 32, 43, 15));
        r.add(new Region("caribbean", "Caribbean Sea", Kind.SEA, 9, 22, -88, -60, 15));
        r.add(new Region("bering_sea", "Bering Sea", Kind.SEA, 52, 66, 162, -157, 15));
        r.add(new Region("south_china_sea", "South China Sea", Kind.SEA, 2, 23, 105, 122, 15));
        r.add(new Region("gulf_of_mexico", "Gulf of Mexico", Kind.SEA, 18, 31, -98, -81, 15));
        r.add(new Region("hudson_bay", "Hudson Bay", Kind.SEA, 51, 64, -95, -76, 15));
        // The windiest water on the planet: nothing blocks the westerlies at this latitude.
        r.add(new Region("drake_passage", "Drake Passage", Kind.STRAIT, -62, -55, -70, -58, 20));
        r.add(new Region("gibraltar", "Strait of Gibraltar", Kind.STRAIT, 35.8, 36.2, -6.0, -5.2, 20));
        r.add(new Region("bosphorus", "Bosphorus", Kind.STRAIT, 41.0, 41.3, 28.9, 29.2, 20));
        r.add(new Region("malacca", "Strait of Malacca", Kind.STRAIT, 1, 6, 98, 103, 20));

        // Lakes, priority 20.
        r.add(new Region("baikal", "Lake Baikal", Kind.LAKE, 51.4, 55.9, 103.5, 110.0, 20));
        r.add(new Region("victoria", "Lake Victoria", Kind.LAKE, -3.0, 0.5, 31.5, 34.9, 20));
        r.add(new Region("superior", "Lake Superior", Kind.LAKE, 46.4, 49.0, -92.2, -84.3, 20));
        r.add(new Region("tanganyika", "Lake Tanganyika", Kind.LAKE, -8.8, -3.3, 29.0, 31.3, 20));
        r.add(new Region("aral", "Aral Sea", Kind.LAKE, 43.5, 46.9, 58.0, 62.0, 20));

        // Mountain ranges, priority 20. These are the regions that get the relief headroom.
        r.add(new Region("himalaya", "Himalaya", Kind.RANGE, 26, 36, 72, 96, 20));
        r.add(new Region("karakoram", "Karakoram", Kind.RANGE, 34, 37, 74, 78, 20));
        r.add(new Region("tibetan_plateau", "Tibetan Plateau", Kind.RANGE, 28, 38, 78, 103, 20));
        r.add(new Region("caucasus", "Caucasus", Kind.RANGE, 40.5, 44.5, 38, 50, 20));
        r.add(new Region("alps", "Alps", Kind.RANGE, 44, 48, 5, 16, 20));
        r.add(new Region("andes", "Andes", Kind.RANGE, -56, 11, -81, -62, 20));
        r.add(new Region("rockies", "Rocky Mountains", Kind.RANGE, 32, 60, -125, -104, 20));
        r.add(new Region("urals", "Ural Mountains", Kind.RANGE, 51, 68, 56, 66, 20));
        r.add(new Region("altai", "Altai Mountains", Kind.RANGE, 45, 53, 82, 100, 20));
        r.add(new Region("tian_shan", "Tian Shan", Kind.RANGE, 39, 45, 67, 95, 20));
        r.add(new Region("pamir", "Pamir", Kind.RANGE, 36, 40, 70, 76, 20));
        r.add(new Region("atlas", "Atlas Mountains", Kind.RANGE, 28, 37, -12, 11, 20));
        r.add(new Region("carpathians", "Carpathians", Kind.RANGE, 44, 50, 17, 27, 20));
        r.add(new Region("scandes", "Scandinavian Mountains", Kind.RANGE, 58, 70, 5, 20, 20));
        r.add(new Region("verkhoyansk", "Verkhoyansk Range", Kind.RANGE, 62, 72, 125, 140, 20));
        r.add(new Region("kamchatka", "Kamchatka", Kind.RANGE, 51, 61, 156, 163, 20));
        r.add(new Region("great_dividing", "Great Dividing Range", Kind.RANGE, -38, -16, 145, 153, 20));
        r.add(new Region("drakensberg", "Drakensberg", Kind.RANGE, -31, -27, 27, 30, 20));
        r.add(new Region("zagros", "Zagros Mountains", Kind.RANGE, 27, 39, 44, 56, 20));

        // Deserts, priority 20.
        r.add(new Region("sahara", "Sahara", Kind.DESERT, 15, 31, -17, 34, 20));
        r.add(new Region("arabian_desert", "Arabian Desert", Kind.DESERT, 16, 30, 35, 56, 20));
        r.add(new Region("gobi", "Gobi Desert", Kind.DESERT, 38, 47, 90, 116, 20));
        r.add(new Region("taklamakan", "Taklamakan Desert", Kind.DESERT, 36, 42, 76, 90, 20));
        r.add(new Region("kalahari", "Kalahari Desert", Kind.DESERT, -28, -19, 19, 26, 20));
        r.add(new Region("namib", "Namib Desert", Kind.DESERT, -27, -17, 11.5, 16, 20));
        r.add(new Region("atacama", "Atacama Desert", Kind.DESERT, -30, -18, -71, -68, 20));
        r.add(new Region("mojave", "Mojave Desert", Kind.DESERT, 33, 38, -118, -114, 20));
        r.add(new Region("great_victoria", "Great Victoria Desert", Kind.DESERT, -31, -25, 124, 141, 20));
        r.add(new Region("karakum", "Karakum Desert", Kind.DESERT, 37, 42, 57, 66, 20));
        // The driest, coldest desert on Earth - and it is made of ice, not sand.
        r.add(new Region("antarctic_desert", "Antarctic Polar Desert", Kind.DESERT, -90, -72, -180, 180, 20));

        // Forests, priority 20.
        r.add(new Region("amazon", "Amazon Rainforest", Kind.FOREST, -15, 5, -75, -46, 20));
        r.add(new Region("congo", "Congo Basin", Kind.FOREST, -6, 5, 12, 30, 20));
        r.add(new Region("taiga_siberia", "Siberian Taiga", Kind.FOREST, 55, 68, 60, 140, 20));
        r.add(new Region("boreal_canada", "Canadian Boreal Forest", Kind.FOREST, 50, 65, -135, -60, 20));
        r.add(new Region("borneo", "Borneo Rainforest", Kind.FOREST, -4, 7, 109, 119, 20));

        return r;
    }
}
