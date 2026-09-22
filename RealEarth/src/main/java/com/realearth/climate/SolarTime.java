package com.realearth.climate;

/**
 * Local solar time and day length from longitude and latitude.
 *
 * <p>Minecraft has one global day counter. On a planet twenty thousand kilometres wide that is
 * wrong in an obvious way: it should be night in Moscow while it is afternoon in Chicago. This
 * class derives a per-position local time from the single shared world time, so the server keeps
 * exactly one clock - no extra state, no synchronisation, and multiplayer stays consistent because
 * every client computes the same offset from the same number.
 *
 * <p>Rendering the correct sun position per player is a client-side transform on top of this; the
 * world time itself is never touched, which is what keeps redstone, mob spawning and every other
 * mod that reads {@code level.getDayTime()} working normally.
 */
public final class SolarTime {

    private SolarTime() {}

    /** Minecraft ticks in one day. */
    public static final long TICKS_PER_DAY = 24000L;

    /**
     * Local solar hour, 0..24, where 12 is local noon.
     *
     * <p>Every 15 degrees of longitude is one hour, which is simply 360 degrees divided by 24 -
     * the same arithmetic that produced real time zones, without the political boundaries.
     */
    public static double localHour(long worldDayTime, double longitude) {
        // Minecraft tick 0 is sunrise, not midnight, so shift by a quarter day.
        double utcHour = ((worldDayTime % TICKS_PER_DAY) / (double) TICKS_PER_DAY) * 24.0 + 6.0;
        double local = utcHour + longitude / 15.0;
        return ((local % 24.0) + 24.0) % 24.0;
    }

    /** Whole-hour offset from the world clock, for display as "UTC+3". */
    public static int zoneOffsetHours(double longitude) {
        return (int) Math.round(longitude / 15.0);
    }

    public static int dayOfYear(long worldDayTime, int daysPerYear) {
        long day = worldDayTime / TICKS_PER_DAY;
        return Math.floorMod(day, Math.max(1, daysPerYear));
    }

    /**
     * Solar declination in degrees: how far the sun is from the celestial equator on this day.
     * This single number is why days are long in summer and short in winter, and why the effect
     * grows with latitude.
     */
    public static double declination(int dayOfYear, int daysPerYear) {
        double fraction = (dayOfYear + 10.0) / daysPerYear;
        return -23.44 * Math.cos(2.0 * Math.PI * fraction);
    }

    /**
     * Day length in hours at this latitude and day.
     *
     * <p>Returns 24 during polar day and 0 during polar night. Beyond the Arctic and Antarctic
     * circles the sun genuinely does not set or rise, and the caller must handle that rather than
     * assuming a sunrise will arrive.
     */
    public static double dayLengthHours(double latitude, int dayOfYear, int daysPerYear) {
        double latRad = Math.toRadians(latitude);
        double decRad = Math.toRadians(declination(dayOfYear, daysPerYear));
        double cosH = -Math.tan(latRad) * Math.tan(decRad);
        if (cosH >= 1.0) return 0.0;    // polar night
        if (cosH <= -1.0) return 24.0;  // polar day
        return 2.0 * Math.toDegrees(Math.acos(cosH)) / 15.0;
    }

    /** True when the sun is above the horizon at this position and moment. */
    public static boolean isDaylight(long worldDayTime, double latitude, double longitude,
                                     int daysPerYear) {
        int doy = dayOfYear(worldDayTime, daysPerYear);
        double length = dayLengthHours(latitude, doy, daysPerYear);
        if (length <= 0.0) return false;
        if (length >= 24.0) return true;
        double hour = localHour(worldDayTime, longitude);
        double half = length / 2.0;
        return hour > (12.0 - half) && hour < (12.0 + half);
    }
}
