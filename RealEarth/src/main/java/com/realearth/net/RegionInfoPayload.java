package com.realearth.net;

import com.realearth.core.RealEarth;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything the client needs to draw the region card and the F3 overlay.
 *
 * <p>Sent only when something changes - entering a new region, or the weather actually turning -
 * rather than on a timer. On a planet this size a player can walk for an hour inside one region,
 * and re-sending an identical packet twenty times a second for that hour is exactly the kind of
 * waste that makes a mod unplayable on a busy server.
 *
 * <p>The codec is written by hand rather than composed. {@code StreamCodec.composite} tops out at
 * eight fields and this has more, and a hand-written pair is easier to keep in sync than a
 * chain of nested composites.
 */
public record RegionInfoPayload(
        String regionName,
        String regionKind,
        String climateName,
        String biomeName,
        double latitude,
        double longitude,
        double elevationMetres,
        float temperatureC,
        String precipitation,
        float windSpeedMs,
        int localHour,
        int zoneOffset,
        /** Name of the storm heading here, empty when none is. */
        String incomingStorm,
        float hoursToStorm
) implements CustomPacketPayload {

    public static final Type<RegionInfoPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RealEarth.MODID, "region_info"));

    public static final StreamCodec<FriendlyByteBuf, RegionInfoPayload> STREAM_CODEC =
            StreamCodec.of(RegionInfoPayload::write, RegionInfoPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, RegionInfoPayload p) {
        buf.writeUtf(p.regionName, 128);
        buf.writeUtf(p.regionKind, 32);
        buf.writeUtf(p.climateName, 64);
        buf.writeUtf(p.biomeName, 128);
        buf.writeDouble(p.latitude);
        buf.writeDouble(p.longitude);
        buf.writeDouble(p.elevationMetres);
        buf.writeFloat(p.temperatureC);
        buf.writeUtf(p.precipitation, 32);
        buf.writeFloat(p.windSpeedMs);
        buf.writeVarInt(p.localHour);
        buf.writeVarInt(p.zoneOffset);
        buf.writeUtf(p.incomingStorm, 64);
        buf.writeFloat(p.hoursToStorm);
    }

    private static RegionInfoPayload read(FriendlyByteBuf buf) {
        return new RegionInfoPayload(
                buf.readUtf(128),
                buf.readUtf(32),
                buf.readUtf(64),
                buf.readUtf(128),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readFloat(),
                buf.readUtf(32),
                buf.readFloat(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUtf(64),
                buf.readFloat());
    }

    /** True when this differs from the previous state in a way worth telling the player about. */
    public boolean differsMeaningfullyFrom(RegionInfoPayload other) {
        if (other == null) return true;
        return !regionName.equals(other.regionName)
                || !precipitation.equals(other.precipitation)
                || !incomingStorm.equals(other.incomingStorm)
                || Math.abs(temperatureC - other.temperatureC) > 1.5f
                || localHour != other.localHour;
    }
}
