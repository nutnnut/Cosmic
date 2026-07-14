package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corridor-direction inference for a companion following its owner across the Leafre <-> Temple of
 * Time dragon flight. The corridor is strictly linear:
 * Leafre dock (240000110) - 200090500 - 200090510 - Temple (270000100).
 */
class BotDragonFlightManagerTest {

    private static final int LEAFRE_DOCK = 240000110;
    private static final int LEAFRE_FLIGHT = 200090500;
    private static final int TEMPLE_FLIGHT = 200090510;
    private static final int TEMPLE = 270000100;

    @Test
    void onlyTheTwoFlightMapsAreFlightMaps() {
        assertTrue(BotDragonFlightManager.isFlightMap(LEAFRE_FLIGHT));
        assertTrue(BotDragonFlightManager.isFlightMap(TEMPLE_FLIGHT));
        assertFalse(BotDragonFlightManager.isFlightMap(LEAFRE_DOCK));
        assertFalse(BotDragonFlightManager.isFlightMap(TEMPLE));
    }

    @Test
    void ownerAheadInTheCorridorSteersOutboundTowardTemple() {
        // Owner already reached the far (Temple) flight map or Temple itself while the bot is still
        // on the near flight map -> keep going forward.
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, TEMPLE_FLIGHT));
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, TEMPLE));
        assertTrue(BotDragonFlightManager.followOutbound(TEMPLE_FLIGHT, TEMPLE));
    }

    @Test
    void ownerBehindInTheCorridorSteersInboundTowardLeafre() {
        assertFalse(BotDragonFlightManager.followOutbound(TEMPLE_FLIGHT, LEAFRE_FLIGHT));
        assertFalse(BotDragonFlightManager.followOutbound(TEMPLE_FLIGHT, LEAFRE_DOCK));
        assertFalse(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, LEAFRE_DOCK));
    }

    @Test
    void ownerOffCorridorOrSameMapDefaultsOutbound() {
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, 100000000)); // owner elsewhere
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, -1));         // no owner
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, LEAFRE_FLIGHT)); // same map
    }
}
