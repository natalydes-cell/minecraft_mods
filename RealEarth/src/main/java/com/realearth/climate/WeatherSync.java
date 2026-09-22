package com.realearth.climate;

import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Makes the simulated climate actually rain, per player.
 *
 * <h2>The problem this solves</h2>
 * Everything else in this mod computed weather and then told nobody. The cyclone simulation, the
 * Koppen precipitation model and the storm tracks all fed the HUD and Cold Sweat, while the rain
 * falling in the world stayed vanilla's own coin flip. A thunderstorm could be overhead on the
 * region card under a clear sky.
 *
 * <h2>Why per player and not per level</h2>
 * {@code ServerLevel} has exactly one rain level, which is the right model for a world a few
 * thousand blocks across and completely wrong for a planet twenty million blocks across: two
 * players on different continents must be able to stand in different weather.
 *
 * <p>Minecraft already has the channel for this. {@code ClientboundGameEventPacket} with
 * {@code RAIN_LEVEL_CHANGE} and {@code THUNDER_LEVEL_CHANGE} is sent down a single player's
 * connection and sets that client's gradients alone. Sending each player their own local weather
 * is therefore not a hack around the engine - it is the engine's own per-connection weather.
 *
 * <h2>What follows automatically</h2>
 * Everything that reads {@code Level.getRainLevel} and {@code getThunderLevel}, which is most of
 * the weather-aware world:
 * <ul>
 *   <li><b>Better Clouds</b> derives its volumetric cloud coverage from exactly these two values
 *       ({@code max(rain, thunder)}), so its clouds thicken and darken with the real climate with
 *       no dependency on it, no mixin into it and nothing to break when it updates.</li>
 *   <li>Vanilla clouds, rain and snow particles, the rain sound loop, sky darkening.</li>
 *   <li>Any other weather-aware mod in the pack, for free.</li>
 * </ul>
 *
 * <p>The vanilla weather cycle is switched off, because leaving it on means the level periodically
 * decides it is raining and broadcasts that to everyone, overriding the per-player values.
 */
public final class WeatherSync {

    private WeatherSync() {}

    /** Ticks between resends. The client lerps between values, so this need not be fast. */
    private static final int INTERVAL = 40;

    /** Don't resend unless the value actually moved; the client is already interpolating. */
    private static final float EPSILON = 0.02f;

    private static final Map<UUID, float[]> lastSent = new HashMap<>();

    private static int counter;
    private static boolean cycleDisabled;

    public static void tick(ServerLevel level) {
        if (!RealEarthConfig.DRIVE_VANILLA_WEATHER.get()) return;

        if (!cycleDisabled) {
            cycleDisabled = true;
            disableVanillaCycle(level);
        }

        if (++counter % INTERVAL != 0) return;

        WeatherManager manager = WeatherManager.get(level);
        lastSent.keySet().removeIf(id -> level.getPlayerByUUID(id) == null);

        for (ServerPlayer player : level.players()) {
            LocalWeather.Result weather = manager.weatherFor(player);
            if (weather == null) continue;   // not sampled yet; leave the client alone

            float rain = rainLevel(weather);
            float thunder = thunderLevel(weather);

            float[] previous = lastSent.get(player.getUUID());
            if (previous != null
                    && Math.abs(previous[0] - rain) < EPSILON
                    && Math.abs(previous[1] - thunder) < EPSILON) {
                continue;
            }
            lastSent.put(player.getUUID(), new float[] {rain, thunder});

            player.connection.send(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rain));
            player.connection.send(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunder));
        }
    }

    /** The value that would be sent to a client, for {@code /realearth weather}. */
    public static float rainLevelFor(LocalWeather.Result weather) {
        return rainLevel(weather);
    }

    /** The value that would be sent to a client, for {@code /realearth weather}. */
    public static float thunderLevelFor(LocalWeather.Result weather) {
        return thunderLevel(weather);
    }

    /**
     * How heavily it is falling, 0 to 1.
     *
     * <p>Never quite reaches zero while any cloud is overhead: Better Clouds and the vanilla
     * cloud layer both take their coverage from this, and a hard zero would make the sky snap
     * empty the instant a shower ends. A floor tied to cloud cover keeps overcast weather looking
     * overcast rather than clear-but-grey.
     */
    private static float rainLevel(LocalWeather.Result weather) {
        float base = switch (weather.precipitation()) {
            case NONE -> 0f;
            case DRIZZLE -> 0.35f;
            case RAIN -> 0.75f;
            case THUNDERSTORM -> 1.0f;
            case SNOW -> 0.6f;
            case BLIZZARD -> 1.0f;
        };
        float withIntensity = base * (0.55f + 0.45f * (float) weather.intensity());
        // Cloud cover alone is worth some coverage even with nothing falling.
        float fromCloud = (float) weather.cloudCover() * 0.45f;
        return clamp01(Math.max(withIntensity, fromCloud));
    }

    /**
     * Thunder, 0 to 1. Drives lightning, the darker sky and the heaviest cloud shading.
     *
     * <p>Only a real thunderstorm sets this. A blizzard is violent but it does not produce
     * lightning, and giving it a thunder level would have the sky flashing over Antarctica.
     */
    private static float thunderLevel(LocalWeather.Result weather) {
        if (weather.precipitation() != LocalWeather.Precipitation.THUNDERSTORM) return 0f;
        return clamp01(0.6f + 0.4f * (float) weather.intensity());
    }

    /**
     * Turns off the vanilla weather cycle for this level, once.
     *
     * <p>Left on, the level would keep rolling its own weather and broadcasting the result to
     * every player, which would overwrite the per-player values a second later. Clearing any
     * weather already in progress matters too, or the level starts the session convinced it is
     * raining everywhere.
     */
    private static void disableVanillaCycle(ServerLevel level) {
        GameRules rules = level.getGameRules();
        GameRules.BooleanValue cycle = rules.getRule(GameRules.RULE_WEATHER_CYCLE);
        if (cycle.get()) {
            cycle.set(false, level.getServer());
            RealEarth.LOG.info("RealEarth: vanilla weather cycle disabled - rain and thunder are "
                    + "now driven per player from the climate simulation, so two players on "
                    + "different continents get different weather");
        }
        // Stop any storm the level was already running, then let the per-player packets take over.
        level.setWeatherParameters(0, 0, false, false);
    }

    /** Forget a player's last-sent state, so they are resent on next login. */
    public static void forget(UUID playerId) {
        lastSent.remove(playerId);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
