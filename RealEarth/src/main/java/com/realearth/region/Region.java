package com.realearth.region;

/**
 * A named place on Earth: a continent, an ocean, a sea, a mountain range, a desert or a lake.
 *
 * <p>Regions exist so the world can tell you where you are. Minecraft's own answer is the biome
 * name, which on a real Earth map is nearly useless - "Snowy Taiga" is true of a third of the
 * northern hemisphere. "Eastern Siberia, Verkhoyansk Range" is the answer a player actually wants.
 *
 * <p>Bounds are a latitude/longitude box, which is crude for a coastline but exactly right for the
 * job: the region card is a label, not a border dispute, and a box costs four comparisons to test.
 * {@code priority} resolves the overlaps that are inevitable with boxes - the Himalaya sits inside
 * Asia, and the more specific one should win.
 */
public record Region(
        String id,
        String displayName,
        Kind kind,
        double minLat,
        double maxLat,
        double minLon,
        double maxLon,
        /** Higher wins where boxes overlap. Continents are 0, countries 10, features 20. */
        int priority
) {
    public enum Kind {
        CONTINENT, OCEAN, SEA, LAKE, RANGE, DESERT, FOREST, PLAIN, ISLAND, STRAIT, RIVER
    }

    /**
     * True when the position falls inside this region's box.
     *
     * <p>Longitude is tested with a wrap, because a box crossing the dateline has its minimum
     * greater than its maximum. The Pacific is the obvious case, and without this it would be the
     * only ocean the mod could never name.
     */
    public boolean contains(double lat, double lon) {
        if (lat < minLat || lat > maxLat) return false;
        if (minLon <= maxLon) {
            return lon >= minLon && lon <= maxLon;
        }
        return lon >= minLon || lon <= maxLon;
    }

    /**
     * Rough size in square degrees, used as a tie-break when two regions share a priority.
     * The smaller one is the more specific answer.
     */
    public double area() {
        double lonSpan = minLon <= maxLon ? (maxLon - minLon) : (360.0 - minLon + maxLon);
        return (maxLat - minLat) * lonSpan;
    }
}
