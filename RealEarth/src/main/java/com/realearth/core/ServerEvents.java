package com.realearth.core;

import com.realearth.climate.Floods;
import com.realearth.climate.SeaIce;
import com.realearth.climate.WeatherManager;
import com.realearth.stream.RegionUnloader;
import com.realearth.util.GeoProjection;
import com.realearth.weathering.WeatheringTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server-side glue: steps the weather and keeps players inside the wrapped world.
 */
@EventBusSubscriber(modid = RealEarth.MODID)
public final class ServerEvents {

    private ServerEvents() {}

    /** Set once per server run, on the first overworld tick, when the level is fully available. */
    private static boolean configured;

    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        // Reset so a single-player session that quits to the menu and loads another world
        // re-applies the settings rather than assuming the previous world's.
        configured = false;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;

        if (!configured) {
            configured = true;
            RegionUnloader.configure(level);
        }

        WeatherManager.get(level).tick(level);
        WeatheringTicker.tick(level);
        SeaIce.tick(level);
        Floods.tick(level);

        if (RealEarthConfig.WRAP_LONGITUDE.get()) {
            wrapPlayers(level);
        }
    }

    /**
     * Teleports a player who has crossed the dateline to the identical point on the other edge.
     *
     * <p>This is what makes the world closed: fly east long enough and you arrive back where you
     * started, because +180 and -180 degrees are the same meridian. The jump is a pure coordinate
     * translation - same Z, same Y, same look direction, same velocity - so from inside it is
     * invisible. There is no equivalent for north and south: the poles are points, not edges, and
     * walking past one puts you on the far side of the same pole rather than anywhere new.
     *
     * <p>The seam is real, though. Chunks either side of the dateline are generated independently
     * and will not match block for block unless the raster happens to line up, so expect a visible
     * discontinuity there until the pregenerator stitches it.
     */
    private static void wrapPlayers(ServerLevel level) {
        GeoProjection proj = RealEarth.projection();
        int half = proj.worldHalfWidth();

        for (ServerPlayer player : level.players()) {
            double x = player.getX();
            if (x >= -half && x < half) continue;

            double wrapped = proj.wrapX(x);
            // teleportTo keeps velocity and rotation, which matters: a player crossing at speed
            // in an aircraft must keep flying, not stall at the seam.
            player.teleportTo(level, wrapped, player.getY(), player.getZ(),
                    java.util.Set.of(), player.getYRot(), player.getXRot());
        }
    }
}
