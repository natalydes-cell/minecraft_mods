package com.realearth.client;

import com.realearth.core.RealEarth;
import com.realearth.core.RealEarthConfig;
import com.realearth.net.RegionInfoPayload;
import com.realearth.worldgen.ElevationCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Thins the air with altitude and thickens it inside a storm.
 *
 * <p>On a world 4000 blocks tall, altitude is a place you actually go, and vanilla's fog is the
 * same at y=2000 as at sea level. Two things change here:
 *
 * <ul>
 *   <li><b>Going up.</b> Air density falls roughly exponentially with height - half of the
 *       atmosphere is below 5.5 km - so fog thins and the sky darkens towards black as you climb.
 *       By the ceiling the sky is nearly starfield. This is the nearest thing to "space" the
 *       engine allows: the dimension stops at y=2032, which at 1:1 vertical is two kilometres,
 *       nowhere near orbit. Calling it space would be a lie; it is a very high mountain sky.</li>
 *   <li><b>Inside weather.</b> A thunderstorm or blizzard pulls the fog in close and greys it,
 *       so a storm arriving is something you see rather than only hear.</li>
 * </ul>
 *
 * <p>Deliberately not a custom cloud renderer. Replacing Minecraft's cloud layer means owning a
 * mesh, a shader and every shader-pack interaction, and a half-working one looks far worse than
 * vanilla clouds. The storm is expressed through fog, sky colour and the HUD warning instead -
 * all of which compose with Iris, Sodium and the rest rather than fighting them.
 */
@EventBusSubscriber(modid = RealEarth.MODID, value = Dist.CLIENT)
public final class AtmosphereRenderer {

    private AtmosphereRenderer() {}

    /** Scale height of the atmosphere in blocks: density falls by 1/e over this distance. */
    private static final double SCALE_HEIGHT_BLOCKS = 2750.0;

    /** Altitude above which the sky starts visibly darkening. */
    private static final double DARKEN_START_Y = 400.0;

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!RealEarthConfig.ENABLE_REGION_HUD.get()) return;   // shares the client toggle

        double altitudeFactor = altitudeFactor(event.getCamera().getPosition());
        RegionInfoPayload info = ClientRegionState.current();

        float r = event.getRed();
        float g = event.getGreen();
        float b = event.getBlue();

        // Towards black with altitude. Blue survives longest, which is what the real sky does -
        // the last colour to go as the air thins is the short wavelength.
        if (altitudeFactor > 0) {
            float keep = (float) (1.0 - altitudeFactor);
            r *= keep;
            g *= keep;
            b *= (float) (1.0 - altitudeFactor * 0.82);
        }

        // Storms grey the air out. Applied after the altitude term so a storm seen from above
        // does not grey the thin sky you are actually looking through.
        if (info != null && altitudeFactor < 0.5) {
            float storm = stormStrength(info) * (float) (1.0 - altitudeFactor * 2.0);
            if (storm > 0) {
                float grey = 0.28f;
                r = lerp(r, grey, storm);
                g = lerp(g, grey, storm);
                b = lerp(b, grey * 1.05f, storm);
            }
        }

        event.setRed(r);
        event.setGreen(g);
        event.setBlue(b);
    }

    @SubscribeEvent
    public static void onFog(ViewportEvent.RenderFog event) {
        if (!RealEarthConfig.ENABLE_REGION_HUD.get()) return;

        double altitudeFactor = altitudeFactor(event.getCamera().getPosition());
        RegionInfoPayload info = ClientRegionState.current();

        float far = event.getFarPlaneDistance();
        float near = event.getNearPlaneDistance();

        // Thin air sees further. Pushing the far plane out is what makes a mountain summit feel
        // like a summit rather than a foggy field at an unusual height.
        if (altitudeFactor > 0) {
            far *= (float) (1.0 + altitudeFactor * 2.5);
            near *= (float) (1.0 + altitudeFactor * 2.0);
            event.setCanceled(true);
        }

        if (info != null && altitudeFactor < 0.5) {
            float storm = stormStrength(info) * (float) (1.0 - altitudeFactor * 2.0);
            if (storm > 0.01f) {
                // Heavy weather closes the view down hard. Floored so it never becomes unplayable.
                far = Math.max(far * (1.0f - storm * 0.65f), 24.0f);
                near = Math.min(near, far * 0.15f);
                event.setCanceled(true);
            }
        }

        if (event.isCanceled()) {
            event.setFarPlaneDistance(far);
            event.setNearPlaneDistance(near);
        }
    }

    /**
     * 0 at and below {@link #DARKEN_START_Y}, rising towards 1 at the world ceiling.
     *
     * <p>Follows the barometric falloff rather than a straight line, because a linear fade makes
     * the first few hundred blocks of climb look wrong - in reality almost nothing changes until
     * you are kilometres up, and then it changes fast.
     */
    private static double altitudeFactor(Vec3 cameraPos) {
        double y = cameraPos.y;
        if (y <= DARKEN_START_Y) return 0.0;

        double above = y - DARKEN_START_Y;
        double density = Math.exp(-above / SCALE_HEIGHT_BLOCKS);
        double maxAbove = ElevationCurve.MAX_Y - DARKEN_START_Y;
        double densityAtCeiling = Math.exp(-maxAbove / SCALE_HEIGHT_BLOCKS);

        // Normalise so the ceiling reaches 1 and sea level reaches 0, whatever the scale height.
        return Math.min(1.0, (1.0 - density) / (1.0 - densityAtCeiling));
    }

    /** 0 to 1, how much weather is in the air right now. */
    private static float stormStrength(RegionInfoPayload info) {
        return switch (info.precipitation()) {
            case "THUNDERSTORM" -> 0.85f;
            case "BLIZZARD" -> 0.95f;
            case "RAIN" -> 0.45f;
            case "SNOW" -> 0.5f;
            case "DRIZZLE" -> 0.2f;
            default -> 0f;
        };
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /** True while the player is high enough that the sky reads as near-space. */
    public static boolean inThinAir() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && altitudeFactor(mc.player.position()) > 0.75;
    }
}
