package com.realearth.compat.coldsweat;

import com.momosoftworks.coldsweat.api.temperature.modifier.TempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.realearth.climate.LocalWeather;
import com.realearth.climate.WeatherManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.Function;

/**
 * Feeds RealEarth's climate into Cold Sweat's world temperature.
 *
 * <p>Cold Sweat's own world temperature comes from the biome, which on an Earth map is the wrong
 * input: a Minecraft "Snowy Taiga" is one number, while the real band it covers runs from mild
 * Scandinavian coast to -50 degC in Yakutia. RealEarth already knows the actual temperature at
 * the player's position - latitude, altitude, season, time of day and whatever cyclone is
 * overhead - so this hands Cold Sweat that number instead.
 *
 * <p><b>Replaces rather than adds.</b> The function returned by {@link #calculate} ignores its
 * input, which is Cold Sweat's convention for a modifier that supersedes what came before. Adding
 * an offset on top of the biome temperature would double-count the climate, and the result would
 * be a Sahara that reads 70 degC.
 *
 * <p>Cold Sweat works in its own unit, not Celsius, so the conversion goes through
 * {@code Temperature.convert}. Getting that wrong is silent and severe - the player freezes to
 * death in the tropics - so it is never hand-rolled here.
 */
public class RealEarthTempModifier extends TempModifier {

    public RealEarthTempModifier() {
        // Cold Sweat re-evaluates on its own schedule; once a second is plenty for a value that
        // is itself only resampled every two seconds on the server.
        this.tickRate(20);
    }

    @Override
    protected Function<Double, Double> calculate(LivingEntity entity, Temperature.Trait trait) {
        if (!(entity instanceof ServerPlayer player)
                || !(entity.level() instanceof ServerLevel level)) {
            return temp -> temp;
        }

        LocalWeather.Result weather = WeatherManager.get(level).weatherFor(player);
        if (weather == null) {
            // No sample yet - the player just joined. Leaving the existing value alone is the
            // safe answer; guessing would briefly freeze or cook them on login.
            return temp -> temp;
        }

        double celsius = weather.temperatureC();
        double converted = Temperature.convert(celsius, Temperature.Units.C, Temperature.Units.MC, true);
        return temp -> converted;
    }
}
