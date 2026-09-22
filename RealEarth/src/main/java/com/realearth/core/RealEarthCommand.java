package com.realearth.core;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.realearth.climate.CycloneSystem;
import com.realearth.climate.KoppenClass;
import com.realearth.climate.SolarTime;
import com.realearth.climate.WeatherManager;
import com.realearth.climate.WeatherSync;
import com.realearth.data.EarthData;
import com.realearth.region.Region;
import com.realearth.util.GeoProjection;
import com.realearth.worldgen.Deposit;
import com.realearth.worldgen.DepositRegistry;
import com.realearth.worldgen.ElevationCurve;
import com.realearth.worldgen.RealisticCaves;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Locale;

/**
 * {@code /realearth} - what the mod thinks is true at a place.
 *
 * <p>Exists because almost everything this mod does is invisible until you are standing in the
 * right spot on a twenty-million-block planet. Being able to ask "what is at 54N 86E" without
 * flying there is the difference between a system you can check and one you have to trust.
 *
 * <p>{@code probe} takes real coordinates, so it answers for anywhere on Earth whether or not the
 * terrain there has been imported or generated yet.
 */
@EventBusSubscriber(modid = RealEarth.MODID)
public final class RealEarthCommand {

    private RealEarthCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("realearth")
                .requires(source -> source.hasPermission(0));

        root.then(Commands.literal("here")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    GeoProjection proj = RealEarth.projection();
                    double lat = proj.latitude(src.getPosition().z);
                    double lon = proj.longitude(src.getPosition().x);
                    return report(src, lat, lon);
                }));

        root.then(Commands.literal("probe")
                .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90, 90))
                        .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180))
                                .executes(ctx -> report(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "lat"),
                                        DoubleArgumentType.getDouble(ctx, "lon"))))));

        root.then(Commands.literal("goto")
                .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90, 90))
                        .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180))
                                .requires(s -> s.hasPermission(2))
                                .executes(ctx -> {
                                    double lat = DoubleArgumentType.getDouble(ctx, "lat");
                                    double lon = DoubleArgumentType.getDouble(ctx, "lon");
                                    return teleport(ctx.getSource(), lat, lon);
                                }))));

        root.then(Commands.literal("storms").executes(ctx -> storms(ctx.getSource())));

        root.then(Commands.literal("weather").executes(ctx -> weather(ctx.getSource())));

        event.getDispatcher().register(root);
    }

    private static int report(CommandSourceStack src, double lat, double lon) {
        // A diagnostic command that dies silently is worse than no command. Brigadier swallows
        // the trace into a generic "unexpected error", so it is caught and shown here instead.
        try {
            return reportUnsafe(src, lat, lon);
        } catch (Exception e) {
            line(src, "probe failed: %s: %s", e.getClass().getSimpleName(), e.getMessage());
            RealEarth.LOG.error("realearth probe failed at {} {}", lat, lon, e);
            return 0;
        }
    }

    private static int reportUnsafe(CommandSourceStack src, double lat, double lon) {
        EarthData data = RealEarth.data();
        GeoProjection proj = RealEarth.projection();
        // May be null when the command arrives from the console before any world is loaded.
        // Everything except the clock is world-independent, so the rest is still worth printing.
        ServerLevel level = levelOrNull(src);

        double metres = data.elevation(lat, lon);
        KoppenClass climate = data.koppen(lat, lon);
        Region region = RealEarth.regions().at(lat, lon);
        double boost = EarthData.reliefBoostForElevation(metres);

        line(src, "=== %.4f%s %.4f%s ===",
                Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W");
        line(src, "block position  x %.0f  z %.0f", proj.blockX(lon), proj.blockZ(lat));
        line(src, "region          %s", region == null ? "(unnamed)" : region.displayName());
        line(src, "elevation       %.0f m real  ->  y %d in world",
                metres, ElevationCurve.toBlockY(metres, boost));
        line(src, "climate         %s", climate.name());
        line(src, "rock            %s", RealisticCaves.rockAt(data, lat, lon));
        line(src, "fertility       %.2f (1.0 = vanilla farmland)", data.fertility(lat, lon));
        line(src, "slope           %.1f degrees", data.slopeDegrees(lat, lon));

        int daysPerYear = RealEarthConfig.DAYS_PER_YEAR.get();
        if (level != null) {
            long dayTime = level.getDayTime();
            line(src, "local time      %02d:00 (UTC%+d), day length %.1f h",
                    (int) SolarTime.localHour(dayTime, lon),
                    SolarTime.zoneOffsetHours(lon),
                    SolarTime.dayLengthHours(lat,
                            SolarTime.dayOfYear(dayTime, daysPerYear), daysPerYear));
        } else {
            line(src, "local time      (no world loaded yet), zone UTC%+d",
                    SolarTime.zoneOffsetHours(lon));
        }

        List<Deposit> deposits = DepositRegistry.at(DepositRegistry.builtin(), lat, lon);
        if (deposits.isEmpty()) {
            line(src, "deposits        none - ordinary ground");
        } else {
            for (Deposit d : deposits) {
                boolean placeable = !RealEarth.depositBlocks()
                        .candidatesFor(d.resource()).isEmpty();
                line(src, "deposit         %s: %s, %.0f-%.0f m deep, density %.2f%s",
                        d.displayName(), d.resource(), d.minDepthM(), d.maxDepthM(),
                        d.densityAt(lat, lon),
                        placeable ? "" : "  [NO BLOCK CONFIGURED - nothing is placed]");
            }
        }
        return 1;
    }

    private static int storms(CommandSourceStack src) {
        ServerLevel level = levelOrNull(src);
        if (level == null) {
            line(src, "no world loaded yet - storms are simulated per world");
            return 0;
        }
        CycloneSystem sys = WeatherManager.get(level).cyclones();
        var active = sys.active();
        line(src, "=== %d active pressure systems, simulated hour %.0f ===",
                active.size(), sys.simulatedHours());
        active.stream().limit(12).forEach(c -> line(src,
                "  %-18s %6.1f%s %7.1f%s  intensity %.2f  age %.0f/%.0f h",
                c.type, Math.abs(c.latitude), c.latitude >= 0 ? "N" : "S",
                Math.abs(c.longitude), c.longitude >= 0 ? "E" : "W",
                c.intensity, c.ageHours, c.lifetimeHours));
        if (active.size() > 12) line(src, "  ... and %d more", active.size() - 12);
        return 1;
    }

    /**
     * What is actually being sent to each client's rain and thunder gradients.
     *
     * <p>These two numbers are the whole cloud integration: Better Clouds and the vanilla cloud
     * layer both derive coverage from {@code max(rain, thunder)}. Being able to read them is the
     * difference between "the clouds look wrong" and knowing which end of the chain is at fault.
     */
    private static int weather(CommandSourceStack src) {
        ServerLevel level = levelOrNull(src);
        if (level == null) {
            line(src, "no world loaded yet");
            return 0;
        }
        WeatherManager manager = WeatherManager.get(level);
        if (level.players().isEmpty()) {
            line(src, "no players online - weather is only sampled where somebody is standing");
            return 0;
        }
        line(src, "%-18s %-14s %6s %7s %8s  %s", "player", "precipitation", "rain", "thunder",
                "temp", "cloud base");
        for (var player : level.players()) {
            var w = manager.weatherFor(player);
            if (w == null) {
                line(src, "%-18s (not sampled yet)", player.getGameProfile().getName());
                continue;
            }
            line(src, "%-18s %-14s %6.2f %7.2f %7.1fC  y=%d",
                    player.getGameProfile().getName(), w.precipitation(),
                    WeatherSync.rainLevelFor(w), WeatherSync.thunderLevelFor(w),
                    w.temperatureC(), w.cloudBaseY());
        }
        return 1;
    }

    private static int teleport(CommandSourceStack src, double lat, double lon) {
        GeoProjection proj = RealEarth.projection();
        EarthData data = RealEarth.data();
        double x = proj.blockX(lon);
        double z = proj.blockZ(lat);
        double metres = data.elevation(lat, lon);
        // Aim a little above the ground so the arrival is not inside a mountain while the chunk
        // is still generating.
        int y = ElevationCurve.toBlockY(metres, EarthData.reliefBoostForElevation(metres)) + 8;

        var entity = src.getEntity();
        if (entity == null) {
            line(src, "goto needs an entity - use: /tp @s %.0f %d %.0f", x, y, z);
            return 0;
        }
        entity.teleportTo(src.getLevel(), x, y, z, java.util.Set.of(),
                entity.getYRot(), entity.getXRot());
        line(src, "moved to %.4f %.4f  (x %.0f y %d z %.0f)", lat, lon, x, y, z);
        return 1;
    }

    /**
     * The level this command applies to, or null when there is none yet.
     *
     * <p>{@code getLevel()} is documented as non-null but is not: a command piped into the
     * console at startup runs before the overworld exists, and the source's level is null then.
     * Falling back to the server's overworld covers the case where the server is up but the
     * source has no level of its own.
     */
    private static ServerLevel levelOrNull(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        if (level != null) return level;
        return src.getServer() == null ? null : src.getServer().overworld();
    }

    private static void line(CommandSourceStack src, String format, Object... args) {
        String text = String.format(Locale.ROOT, format, args);
        src.sendSuccess(() -> Component.literal(text), false);
    }
}
