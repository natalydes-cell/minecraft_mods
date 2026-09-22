package com.realearth.client;

import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import com.realearth.net.RegionInfoPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Draws the region card and the persistent conditions line.
 *
 * <p>Two things, deliberately different in weight. The card is a brief announcement when you
 * arrive somewhere - "Himalaya", with the climate and conditions under it - and then gets out of
 * the way. The line in the corner is always there and stays small, because coordinates and
 * temperature are reference information, not news.
 */
@EventBusSubscriber(modid = RealEarth.MODID, value = Dist.CLIENT)
public final class RegionHud {

    private RegionHud() {}

    private static final int MARGIN = 6;

    @EventBusSubscriber(modid = RealEarth.MODID, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void registerLayers(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.HOTBAR,
                    ResourceLocation.fromNamespaceAndPath(RealEarth.MODID, "region_info"),
                    RegionHud::render);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientRegionState.tick();
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!RealEarthConfig.ENABLE_REGION_HUD.get()) return;

        Minecraft mc = Minecraft.getInstance();
        // The F3 screen already shows all of this in more detail, and the card would cover it.
        if (mc.getDebugOverlay().showDebugScreen() || mc.options.hideGui) return;

        RegionInfoPayload info = ClientRegionState.current();
        if (info == null) return;

        Font font = mc.font;
        drawConditionsLine(graphics, font, info);

        if (ClientRegionState.cardVisible()) {
            drawCard(graphics, font, info, ClientRegionState.cardAlpha());
        }
    }

    /** The always-on line: coordinates, altitude, temperature, local time. */
    private static void drawConditionsLine(GuiGraphics graphics, Font font, RegionInfoPayload i) {
        String coords = String.format("%.3f%s %.3f%s",
                Math.abs(i.latitude()), i.latitude() >= 0 ? "N" : "S",
                Math.abs(i.longitude()), i.longitude() >= 0 ? "E" : "W");
        String line = String.format("%s  %+.0f m  %+.1f C  %02d:00 UTC%+d",
                coords, i.elevationMetres(), i.temperatureC(), i.localHour(), i.zoneOffset());

        int width = font.width(line);
        int x = graphics.guiWidth() - width - MARGIN;
        int y = MARGIN;
        graphics.fill(x - 3, y - 2, x + width + 3, y + font.lineHeight + 1, 0x70000000);
        graphics.drawString(font, line, x, y, 0xFFD8D8D8, false);
    }

    /** The arrival card: big name, then climate and conditions. */
    private static void drawCard(GuiGraphics graphics, Font font, RegionInfoPayload i, float alpha) {
        int a = (int) (alpha * 255) << 24;
        if (a == 0) return;

        String title = i.regionName();
        String subtitle = i.regionKind().isEmpty() ? i.climateName()
                : i.regionKind() + "  -  " + i.climateName();
        String conditions = String.format("%s, %+.1f C, wind %.0f m/s",
                prettyPrecipitation(i.precipitation()), i.temperatureC(), i.windSpeedMs());

        int centreX = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() / 5;

        graphics.drawCenteredString(font, title, centreX, y, 0x00FFFFFF | a);
        graphics.drawCenteredString(font, subtitle, centreX, y + 12, 0x00BFBFBF | a);
        graphics.drawCenteredString(font, conditions, centreX, y + 24, 0x009FBFCF | a);

        // A storm warning is the one thing on this card that is actionable, so it gets its own
        // line and a colour that reads as a warning even at low opacity.
        if (!i.incomingStorm().isEmpty() && i.hoursToStorm() >= 0) {
            String warn = String.format("%s approaching - %.0f h", i.incomingStorm(), i.hoursToStorm());
            graphics.drawCenteredString(font, warn, centreX, y + 38, 0x00FFAA33 | a);
        }
    }

    private static String prettyPrecipitation(String raw) {
        return switch (raw) {
            case "NONE" -> "Clear";
            case "DRIZZLE" -> "Drizzle";
            case "RAIN" -> "Rain";
            case "THUNDERSTORM" -> "Thunderstorm";
            case "SNOW" -> "Snow";
            case "BLIZZARD" -> "Blizzard";
            default -> raw;
        };
    }
}
