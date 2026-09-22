package com.realearth.net;

import com.realearth.client.ClientRegionState;
import com.realearth.core.RealEarth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Packet registration.
 *
 * <p>The region payload is optional on the wire: a vanilla client, or one without RealEarth, can
 * still join a RealEarth server and play. It simply does not get the region card. Making the
 * packet required would lock out exactly the players a server most wants to let in.
 */
@EventBusSubscriber(modid = RealEarth.MODID)
public final class RealEarthNetwork {

    private RealEarthNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();

        registrar.playToClient(
                RegionInfoPayload.TYPE,
                RegionInfoPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    // Guard the dist rather than assuming: this handler is registered from common
                    // code and would otherwise try to touch client classes on a dedicated server.
                    if (FMLEnvironment.dist == Dist.CLIENT) {
                        ClientRegionState.accept(payload);
                    }
                }));
    }
}
