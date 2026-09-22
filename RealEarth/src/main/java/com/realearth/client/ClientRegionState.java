package com.realearth.client;

import com.realearth.net.RegionInfoPayload;

/**
 * The client's copy of where it is and what the weather is doing.
 *
 * <p>Holds the last payload and, separately, how long the region card should stay on screen. The
 * card is an announcement, not a permanent overlay: it appears when you cross into somewhere new,
 * holds for a few seconds and fades. The compact line stays, because coordinates and temperature
 * are worth having all the time.
 */
public final class ClientRegionState {

    private ClientRegionState() {}

    /** How long the full card stays fully opaque, in client ticks. */
    private static final int CARD_HOLD_TICKS = 70;
    /** How long it then takes to fade out. */
    private static final int CARD_FADE_TICKS = 30;

    private static volatile RegionInfoPayload current;
    private static volatile String lastRegionName = "";
    private static int cardTicks;

    public static void accept(RegionInfoPayload payload) {
        RegionInfoPayload previous = current;
        current = payload;

        // The card only re-announces on an actual region change. Weather changing inside the same
        // region updates the compact line silently - a thunderstorm should not put a title card
        // over the middle of the screen.
        if (!payload.regionName().equals(lastRegionName)) {
            lastRegionName = payload.regionName();
            cardTicks = CARD_HOLD_TICKS + CARD_FADE_TICKS;
        } else if (previous != null && !payload.incomingStorm().equals(previous.incomingStorm())
                && !payload.incomingStorm().isEmpty()) {
            // A storm appearing on the horizon is worth announcing even without moving.
            cardTicks = CARD_HOLD_TICKS + CARD_FADE_TICKS;
        }
    }

    public static RegionInfoPayload current() {
        return current;
    }

    public static void tick() {
        if (cardTicks > 0) cardTicks--;
    }

    public static boolean cardVisible() {
        return cardTicks > 0 && current != null;
    }

    /** Card opacity, 1 while held then falling to 0 over the fade. */
    public static float cardAlpha() {
        if (cardTicks <= 0) return 0f;
        if (cardTicks > CARD_FADE_TICKS) return 1f;
        return cardTicks / (float) CARD_FADE_TICKS;
    }

    public static void clear() {
        current = null;
        lastRegionName = "";
        cardTicks = 0;
    }
}
