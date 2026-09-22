package com.realearth.fauna;

import com.realearth.climate.KoppenClass;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Where one animal is allowed to exist.
 *
 * <p>Minecraft spawns mobs by biome, which on an Earth map produces nonsense: polar bears wherever
 * it happens to be snowy, including a Himalayan ridge two thousand kilometres from any sea, and
 * parrots in every jungle including the ones in Africa, which have none. A habitat is the real
 * constraint - a climate range, a latitude range, an altitude range, and whether the animal needs
 * a coast.
 *
 * @param latMin      southern limit of the range, degrees
 * @param latMax      northern limit
 * @param lonMin      western limit, or -180 for cosmopolitan species
 * @param lonMax      eastern limit
 * @param climates    Koppen classes the animal tolerates
 * @param minElevM    lowest elevation it lives at
 * @param maxElevM    highest
 * @param coastalOnly true for animals that must be within reach of the sea
 * @param weight      relative spawn weight inside the range
 */
public record Habitat(
        String entityId,
        double latMin,
        double latMax,
        double lonMin,
        double lonMax,
        Set<KoppenClass> climates,
        double minElevM,
        double maxElevM,
        boolean coastalOnly,
        int weight
) {
    public boolean allows(double lat, double lon, double elevM, KoppenClass climate,
                          boolean nearCoast) {
        if (lat < latMin || lat > latMax) return false;
        if (!containsLon(lon)) return false;
        if (elevM < minElevM || elevM > maxElevM) return false;
        if (coastalOnly && !nearCoast) return false;
        return climates.isEmpty() || climates.contains(climate);
    }

    private boolean containsLon(double lon) {
        if (lonMin <= lonMax) return lon >= lonMin && lon <= lonMax;
        return lon >= lonMin || lon <= lonMax;   // range crosses the dateline
    }

    /** Convenience builder for a cosmopolitan species with no longitude limit. */
    public static Habitat global(String entityId, double latMin, double latMax,
                                 Set<KoppenClass> climates, double minElevM, double maxElevM,
                                 int weight) {
        return new Habitat(entityId, latMin, latMax, -180, 180, climates,
                minElevM, maxElevM, false, weight);
    }

    /** An empty result means "any climate", which {@link #allows} treats as no constraint. */
    public static Set<KoppenClass> climates(KoppenClass... classes) {
        Set<KoppenClass> set = EnumSet.noneOf(KoppenClass.class);
        Collections.addAll(set, classes);
        return set;
    }
}
