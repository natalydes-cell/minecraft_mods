package com.realearth.climate;

import com.realearth.worldgen.ElevationCurve;

/**
 * Resolves the weather at one place and moment from the climate baseline, the moving pressure
 * systems, the season, the time of day and the altitude.
 *
 * <p>Pure function of its inputs, with no Minecraft types anywhere. That is what lets the server
 * compute it authoritatively, the client predict it between packets, and both agree.
 */
public final class LocalWeather {

    private LocalWeather() {}

    public enum Precipitation {
        NONE,
        /** Light and brief. */
        DRIZZLE,
        RAIN,
        /** Heavy rain plus lightning. */
        THUNDERSTORM,
        SNOW,
        /** Snow plus high wind: the Antarctic and Siberian winter case. */
        BLIZZARD
    }

    /**
     * @param temperatureC    air temperature at the player, altitude-corrected
     * @param precipitation   what is falling
     * @param intensity       0..1, how hard
     * @param windSpeedMs     metres per second
     * @param cloudCover      0..1
     * @param cloudBaseY      Minecraft Y of the cloud deck; nothing falls above it
     * @param incomingStorm   the system heading here, or null
     * @param hoursToArrival  hours until {@code incomingStorm} is overhead, or -1
     */
    public record Result(
            double temperatureC,
            Precipitation precipitation,
            double intensity,
            double windSpeedMs,
            double cloudCover,
            int cloudBaseY,
            Cyclone incomingStorm,
            double hoursToArrival
    ) {
        public boolean isRaining() {
            return precipitation != Precipitation.NONE;
        }

        public boolean isThundering() {
            return precipitation == Precipitation.THUNDERSTORM;
        }
    }

    /** Environmental lapse rate: air cools ~6.5 degC for every 1000 m of altitude. */
    private static final double LAPSE_RATE_C_PER_M = 0.0065;

    /**
     * @param koppen      climate class at this position
     * @param latitude    degrees, for hemisphere and polar-night handling
     * @param elevationM  real elevation in metres at this exact column (not Minecraft Y)
     * @param baselineM   mean real elevation of the surrounding climate cell, in metres
     * @param playerY     the player's Minecraft Y, for the above-the-clouds check
     * @param dayOfYear   0..364
     * @param hourOfDay   0..23 local solar time, from {@link SolarTime}
     * @param cyclones    the pressure system sample at this position
     * @param ocean       true over open water; storms behave differently at sea
     * @param noise       deterministic 0..1 value keyed to position and time
     */
    public static Result resolve(KoppenClass koppen, double latitude, double elevationM,
                                 double baselineM, double playerY, int dayOfYear,
                                 double hourOfDay, CycloneSystem.Sample cyclones,
                                 boolean ocean, double noise) {

        boolean northern = latitude >= 0;

        // --- Temperature ------------------------------------------------------------------
        double temp = koppen.temperatureAt(dayOfYear, northern);

        // Altitude, applied only to the height ABOVE the surrounding terrain.
        //
        // The Koppen class is the observed climate at this place, and that observation already
        // contains the altitude: Everest is classified EF (ice cap) precisely because it is nine
        // kilometres up. Subtracting a full sea-level lapse rate on top of that counts the
        // mountain twice - an early version produced -70 C on the Everest summit in July against
        // a real figure near -20 C. Only the local relief within the climate cell is left to
        // correct, which is what makes climbing a ridge feel colder than the valley floor
        // without wrecking the absolute numbers.
        double relief = elevationM - baselineM;
        temp -= relief * LAPSE_RATE_C_PER_M;
        // Diurnal swing, strongest in deserts (dry air radiates heat away overnight) and almost
        // absent at sea, where water's heat capacity flattens it.
        double diurnalAmplitude = ocean ? 1.0 : (koppen.isDesert() ? 11.0 : 5.0);
        temp += diurnalAmplitude * Math.cos((hourOfDay - 15.0) / 24.0 * 2.0 * Math.PI);
        // The moving systems: an anticyclone in summer is a heatwave, a cold surge is a freeze.
        temp += cyclones.tempAnomalyC();

        // --- Precipitation chance ---------------------------------------------------------
        double wetChance = koppen.wetFraction() + cyclones.precipitationBias() * 0.55;
        // Orographic lift: air forced up a mountainside cools and drops its moisture, which is
        // why windward slopes are rainforest and the lee side is desert.
        if (elevationM > 800) {
            wetChance += Math.min(0.20, (elevationM - 800) / 12000.0);
        }
        wetChance = clamp01(wetChance);

        boolean precipitating = noise < wetChance;

        // --- Wind -------------------------------------------------------------------------
        double baseWind = ocean ? 7.0 : 3.0;
        // The Roaring Forties and the Drake Passage: nothing blocks the westerlies at 40-60S,
        // so they blow harder there than anywhere else on Earth.
        if (latitude < -38 && latitude > -62) {
            baseWind += 9.0;
        }
        double wind = baseWind + cyclones.windFactor() * (ocean ? 28.0 : 18.0);

        // --- Cloud deck -------------------------------------------------------------------
        // Cloud base rises with temperature and falls with humidity; the deck is what the
        // above-the-clouds rule tests against. Floored well above sea level: a deck computed
        // below the ground it sits over is meaningless, and in very cold air the raw formula
        // goes there.
        int cloudBaseY = (int) Math.round(ElevationCurve.SEA_LEVEL + 128 + temp * 3.0
                - wetChance * 40.0);
        cloudBaseY = Math.max(ElevationCurve.SEA_LEVEL + 40, cloudBaseY);
        double cloudCover = clamp01(wetChance * 0.8 + cyclones.lowInfluence() * 0.6
                - cyclones.highInfluence() * 0.7);

        // Above the deck the sky is clear, always. No rain, no snow, no thunder - the same as
        // looking down on a storm from an aircraft.
        if (playerY > cloudBaseY + 12) {
            precipitating = false;
        }

        // --- What falls -------------------------------------------------------------------
        Precipitation type;
        double intensity = 0;
        if (!precipitating) {
            type = Precipitation.NONE;
        } else {
            intensity = clamp01(0.25 + cyclones.lowInfluence() * 0.9);
            boolean freezing = temp <= 0.5;
            if (freezing) {
                type = wind > 17.0 ? Precipitation.BLIZZARD : Precipitation.SNOW;
            } else {
                double thunderChance = koppen.stormFactor()
                        * (0.3 + cyclones.lowInfluence())
                        * (ocean ? 1.15 : 1.0);
                if (noise * 0.9 < thunderChance * wetChance) {
                    type = Precipitation.THUNDERSTORM;
                    intensity = clamp01(intensity + 0.25);
                } else {
                    type = intensity < 0.35 ? Precipitation.DRIZZLE : Precipitation.RAIN;
                }
            }
        }

        // --- Incoming front ---------------------------------------------------------------
        // Reported so the client can draw the wall of cloud before it arrives, and so the HUD
        // can warn. Only systems actually moving towards this position count.
        Cyclone incoming = cyclones.dominant();
        double hours = -1;
        if (incoming != null && incoming.type != Cyclone.Type.ANTICYCLONE) {
            double speed = Math.hypot(incoming.driftLon, incoming.driftLat);
            if (speed > 1e-4) {
                hours = Math.max(0.0, (incoming.radiusDeg * (1.0 - cyclones.dominantInfluence()))
                        / speed);
            }
        }

        return new Result(temp, type, intensity, wind, cloudCover, cloudBaseY, incoming, hours);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
