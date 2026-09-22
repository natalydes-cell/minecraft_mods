package com.realearth.compat.coldsweat;

import com.momosoftworks.coldsweat.api.event.core.init.DefaultTempModifiersEvent;
import com.momosoftworks.coldsweat.api.event.core.registry.TempModifierRegisterEvent;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.realearth.core.RealEarth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wires {@link RealEarthTempModifier} into Cold Sweat.
 *
 * <p><b>Never referenced unless Cold Sweat is installed.</b> Every class in this package touches
 * Cold Sweat types directly, so loading any of them without the mod present would throw
 * {@link NoClassDefFoundError} during class verification. {@link #register} is therefore called
 * only from behind a {@code ModList.isLoaded} check, and the JVM never resolves these names
 * otherwise. That is the whole reason this lives in its own package rather than beside the
 * climate code.
 */
public final class ColdSweatCompat {

    public static final String MOD_ID = "cold_sweat";

    private ColdSweatCompat() {}

    /**
     * Both listeners go on the GAME bus, not the mod bus.
     *
     * <p>This is not a detail to guess at. Cold Sweat's events extend {@code Event} without
     * implementing {@code IModBusEvent}, which means the mod bus rejects them outright - and it
     * rejects them by throwing during mod construction, so the whole game fails to start rather
     * than quietly skipping the integration. An earlier version registered
     * {@code TempModifierRegisterEvent} on the mod bus and hard-crashed every pack that had Cold
     * Sweat installed.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ColdSweatCompat::onRegisterModifiers);
        NeoForge.EVENT_BUS.addListener(ColdSweatCompat::onGatherModifiers);
        RealEarth.LOG.info("Cold Sweat detected - world temperature will follow the real climate "
                + "instead of the biome");
    }

    private static void onRegisterModifiers(TempModifierRegisterEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath(RealEarth.MODID, "real_climate"),
                RealEarthTempModifier::new);
    }

    /**
     * Attaches the modifier to every player, for the world-temperature trait only.
     *
     * <p>Only WORLD: the other traits are the player's own body, their insulation and their
     * resistances, which are Cold Sweat's business and nothing to do with where on Earth they
     * are standing. Touching those would break every insulation item in the pack.
     *
     * <p>Uses {@code DefaultTempModifiersEvent}, not the per-trait
     * {@code GatherDefaultTempModifiersEvent} - that one is deprecated and marked for removal in
     * Cold Sweat 2.4, and building against it would break on the next release.
     */
    private static void onGatherModifiers(DefaultTempModifiersEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        event.addModifier(Temperature.Trait.WORLD, new RealEarthTempModifier());
    }
}
