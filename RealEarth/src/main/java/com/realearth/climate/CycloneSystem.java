package com.realearth.climate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Planet-wide pressure system simulation: spawns, steers, weakens and retires cyclones.
 *
 * <p>This is the piece that makes weather travel. A low forms over the North Atlantic, the
 * westerlies carry it towards Europe, it rains itself out over the continent and dies near the
 * Urals - the same storm, tracked the whole way, rather than independent per-chunk rolls.
 *
 * <p><b>Cost.</b> Deliberately not a fluid simulation. A few dozen systems stepped once a minute
 * is microseconds of work; the expensive part of weather was never the physics, it was asking
 * every chunk about it. Sampling is O(active systems) and only runs for chunks with players.
 *
 * <p><b>Authority.</b> Server-only. Clients receive the system list and interpolate between
 * updates, so every player in a multiplayer world sees the same storm in the same place.
 */
public final class CycloneSystem {

    /** Simulated hours advanced per step. */
    public static final double HOURS_PER_STEP = 0.5;

    /** Hard cap on concurrent systems, for both realism and predictable cost. */
    private static final int MAX_SYSTEMS = 48;

    private final CopyOnWriteArrayList<Cyclone> systems = new CopyOnWriteArrayList<>();
    private final Random random;
    private long nextId = 1;
    private double simHours;

    private volatile LandmaskProbe landmask;

    public CycloneSystem(long seed) {
        this.random = new Random(seed);
    }

    /** Lets the caller answer "is this lat/lon ocean" without this class knowing about the world. */
    public interface LandmaskProbe {
        boolean isOcean(double latitude, double longitude);
    }

    public void setLandmaskProbe(LandmaskProbe probe) {
        this.landmask = probe;
    }

    public List<Cyclone> active() {
        return Collections.unmodifiableList(systems);
    }

    /**
     * Re-inserts a system loaded from disk, without the spawn rules.
     *
     * <p>Kept separate from spawning on purpose: a saved storm has already earned its place and
     * must come back exactly where it was, even if the conditions that created it - the right
     * season, warm ocean underneath - no longer hold at this moment.
     */
    public void restore(Cyclone cyclone) {
        systems.add(cyclone);
        nextId = Math.max(nextId, cyclone.id + 1);
    }

    /** Restores the simulation clock alongside {@link #restore}. */
    public void restoreClock(double hours) {
        this.simHours = hours;
    }

    public double simulatedHours() {
        return simHours;
    }

    /** Advances the whole planet by one step. Call from the server tick, not per chunk. */
    public void step(int dayOfYear) {
        simHours += HOURS_PER_STEP;

        List<Cyclone> spent = new ArrayList<>();
        for (Cyclone c : systems) {
            advect(c);
            decay(c);
            c.ageHours += HOURS_PER_STEP;
            if (c.isSpent()) spent.add(c);
        }
        systems.removeAll(spent);

        maybeSpawn(dayOfYear);
    }

    /**
     * Moves a system along the prevailing wind for its latitude.
     *
     * <p>The steering flow is the real three-cell circulation: easterlies in the tropics, the
     * westerlies between roughly 30 and 60 degrees, polar easterlies beyond. That is why Atlantic
     * hurricanes track west towards the Caribbean, then recurve northeast once they climb into the
     * westerlies, and why European weather almost always arrives from the west.
     */
    private void advect(Cyclone c) {
        double absLat = Math.abs(c.latitude);
        double zonal;   // degrees longitude per hour, positive eastward
        if (absLat < 30.0) {
            zonal = -0.22;                        // trade easterlies
        } else if (absLat < 60.0) {
            zonal = 0.34;                         // mid-latitude westerlies
        } else {
            zonal = -0.12;                        // polar easterlies
        }

        // Coriolis drift: systems bend poleward in both hemispheres.
        double meridional = (c.latitude >= 0 ? 0.045 : -0.045)
                * (c.type == Cyclone.Type.TROPICAL_CYCLONE ? 1.6 : 1.0);

        // A little wander so tracks are not perfectly parallel lines.
        zonal += (random.nextDouble() - 0.5) * 0.06;
        meridional += (random.nextDouble() - 0.5) * 0.03;

        c.driftLon = zonal;
        c.driftLat = meridional;

        c.longitude = Cyclone.wrapLon(c.longitude + zonal * HOURS_PER_STEP);
        c.latitude += meridional * HOURS_PER_STEP;

        // Reflect at the poles rather than letting latitude run past 90.
        if (c.latitude > 88.0) {
            c.latitude = 176.0 - c.latitude;
        }
        if (c.latitude < -88.0) {
            c.latitude = -176.0 - c.latitude;
        }
    }

    /**
     * Weakens a system over time, faster once it has left the conditions that fed it.
     *
     * <p>A tropical cyclone makes landfall and collapses within a day or two because it loses its
     * warm ocean; a mid-latitude low fades gradually over a week.
     */
    private void decay(Cyclone c) {
        boolean water = landmask == null || landmask.isOcean(c.latitude, c.longitude);
        double rate = switch (c.type) {
            case TROPICAL_CYCLONE -> water ? 0.004 : 0.055;
            case EXTRATROPICAL_LOW -> water ? 0.006 : 0.013;
            case COLD_SURGE -> 0.020;
            case ANTICYCLONE -> 0.008;
        };
        // Tropical systems also die when they wander too far from the warm belt.
        if (c.type == Cyclone.Type.TROPICAL_CYCLONE && Math.abs(c.latitude) > 38.0) {
            rate += 0.030;
        }
        c.intensity -= rate * HOURS_PER_STEP;
        // A weakening system also spreads out, which is why a decaying storm brings drizzle over
        // a wide area rather than a downpour over a small one.
        c.radiusDeg += 0.06 * HOURS_PER_STEP;
    }

    private void maybeSpawn(int dayOfYear) {
        if (systems.size() >= MAX_SYSTEMS) return;
        if (random.nextDouble() > 0.10) return;

        double lon = (random.nextDouble() * 360.0) - 180.0;
        double lat;
        Cyclone.Type type;
        double radius;
        double intensity;
        double lifetime;

        double roll = random.nextDouble();
        if (roll < 0.18) {
            // Tropical cyclones form over warm ocean, 5-20 degrees from the equator, and only in
            // the local late summer - never right on the equator, where there is no Coriolis force
            // to start the rotation.
            boolean north = isTropicalSeason(dayOfYear, true);
            boolean south = isTropicalSeason(dayOfYear, false);
            if (!north && !south) return;
            boolean useNorth = north && (!south || random.nextBoolean());
            lat = (useNorth ? 1 : -1) * (5.0 + random.nextDouble() * 15.0);
            if (landmask != null && !landmask.isOcean(lat, lon)) return;
            type = Cyclone.Type.TROPICAL_CYCLONE;
            radius = 3.0 + random.nextDouble() * 4.0;
            intensity = 0.65 + random.nextDouble() * 0.35;
            lifetime = 120 + random.nextDouble() * 180;
        } else if (roll < 0.62) {
            lat = (random.nextBoolean() ? 1 : -1) * (35.0 + random.nextDouble() * 25.0);
            type = Cyclone.Type.EXTRATROPICAL_LOW;
            radius = 9.0 + random.nextDouble() * 11.0;
            intensity = 0.35 + random.nextDouble() * 0.45;
            lifetime = 100 + random.nextDouble() * 140;
        } else if (roll < 0.88) {
            lat = (random.nextBoolean() ? 1 : -1) * (20.0 + random.nextDouble() * 35.0);
            type = Cyclone.Type.ANTICYCLONE;
            radius = 12.0 + random.nextDouble() * 14.0;
            intensity = 0.30 + random.nextDouble() * 0.40;
            lifetime = 140 + random.nextDouble() * 200;
        } else {
            lat = (random.nextBoolean() ? 1 : -1) * (55.0 + random.nextDouble() * 25.0);
            type = Cyclone.Type.COLD_SURGE;
            radius = 10.0 + random.nextDouble() * 12.0;
            intensity = 0.45 + random.nextDouble() * 0.45;
            lifetime = 48 + random.nextDouble() * 72;
        }

        Cyclone c = new Cyclone(nextId++, type, lat, lon, radius, intensity, lifetime);
        c.tempAnomalyC = switch (type) {
            case ANTICYCLONE -> 4.0 * intensity;
            case COLD_SURGE -> -18.0 * intensity;
            case TROPICAL_CYCLONE -> -2.0 * intensity;
            case EXTRATROPICAL_LOW -> -3.0 * intensity;
        };
        systems.add(c);
    }

    private static boolean isTropicalSeason(int dayOfYear, boolean northern) {
        // Northern peak Aug-Oct, southern peak Jan-Mar.
        return northern ? (dayOfYear >= 180 && dayOfYear <= 320)
                        : (dayOfYear <= 105 || dayOfYear >= 335);
    }

    /** Combined influence of every system at a point. */
    public Sample sampleAt(double latitude, double longitude) {
        double low = 0;
        double high = 0;
        double tempAnom = 0;
        double wind = 0;
        Cyclone dominant = null;
        double dominantInfluence = 0;

        for (Cyclone c : systems) {
            double inf = c.influenceAt(latitude, longitude);
            if (inf <= 0.0) continue;
            if (inf > dominantInfluence) {
                dominantInfluence = inf;
                dominant = c;
            }
            if (c.type == Cyclone.Type.ANTICYCLONE) {
                high += inf;
            } else {
                low += inf;
            }
            tempAnom += c.tempAnomalyC * inf;
            wind += inf * (c.type == Cyclone.Type.TROPICAL_CYCLONE ? 2.2 : 1.0);
        }
        return new Sample(Math.min(1.0, low), Math.min(1.0, high), tempAnom,
                Math.min(1.0, wind), dominant, dominantInfluence);
    }

    public record Sample(double lowInfluence, double highInfluence, double tempAnomalyC,
                         double windFactor, Cyclone dominant, double dominantInfluence) {
        /** Net push towards precipitation: lows add, highs suppress. */
        public double precipitationBias() {
            return lowInfluence - highInfluence * 0.9;
        }
    }
}
