package com.realearth.climate;

/**
 * One mobile pressure system: a cyclone (low, stormy) or an anticyclone (high, clear and calm).
 *
 * <p>Position is in degrees so a system is independent of world scale, and so the same simulation
 * state means the same thing whether the server runs 1:1 or 1:2.
 */
public final class Cyclone {

    public enum Type {
        /** Mid-latitude low: rain, wind, moderate thunder. The workhorse of European weather. */
        EXTRATROPICAL_LOW,
        /** Warm-core tropical system: violent, narrow, only forms over warm ocean. */
        TROPICAL_CYCLONE,
        /** High pressure: suppresses precipitation, brings heatwaves in summer, frost in winter. */
        ANTICYCLONE,
        /** Cold outbreak: a sharp temperature drop sweeping along a front. */
        COLD_SURGE
    }

    public final long id;
    public final Type type;

    /** Centre, degrees. */
    public double latitude;
    public double longitude;

    /** Radius of influence, degrees of great circle. */
    public double radiusDeg;

    /** 0..1. Drives rain rate, wind speed, thunder chance and the temperature anomaly. */
    public double intensity;

    /** Movement, degrees per simulated hour. */
    public double driftLat;
    public double driftLon;

    /** Temperature anomaly at the centre, degC. Negative for a cold surge. */
    public double tempAnomalyC;

    /** Simulated hours since formation. */
    public double ageHours;

    /** Hours after which the system is considered spent and removed. */
    public final double lifetimeHours;

    public Cyclone(long id, Type type, double latitude, double longitude,
                   double radiusDeg, double intensity, double lifetimeHours) {
        this.id = id;
        this.type = type;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusDeg = radiusDeg;
        this.intensity = intensity;
        this.lifetimeHours = lifetimeHours;
    }

    /**
     * Influence of this system at a point, 1 at the centre falling to 0 at the edge.
     *
     * <p>Uses a smooth cosine falloff rather than a hard cutoff so a front arriving overhead ramps
     * the weather up over many minutes instead of switching it on in one tick.
     */
    public double influenceAt(double lat, double lon) {
        double dLat = lat - latitude;
        double dLon = wrapLon(lon - longitude);
        // Longitude degrees shrink towards the poles; without this a polar low would be a
        // thousand kilometres wide in one axis and a few hundred in the other.
        dLon *= Math.cos(Math.toRadians((lat + latitude) * 0.5));
        double dist = Math.sqrt(dLat * dLat + dLon * dLon);
        if (dist >= radiusDeg) return 0.0;
        double t = dist / radiusDeg;
        return 0.5 * (1.0 + Math.cos(Math.PI * t)) * intensity;
    }

    public boolean isSpent() {
        return ageHours >= lifetimeHours || intensity <= 0.02;
    }

    public static double wrapLon(double lon) {
        double v = (lon + 180.0) % 360.0;
        if (v < 0) v += 360.0;
        return v - 180.0;
    }
}
