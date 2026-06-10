package server.bots;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.IntToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotTravelCostTest {

    private static final BotWorldGraph.RouteOptions PORTALS_ONLY = BotWorldGraph.RouteOptions.PORTALS_ONLY;
    /** travelrate 100: getTransportationTime is the identity. */
    private static final IntToLongFunction RATE_100 = ms -> ms;

    @Test
    void shouldFloodPortalHopsAtFixedCostWithinHopCap() {
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                1, new int[]{2}, 2, new int[]{3}, 3, new int[0]));

        Map<Integer, Double> seconds = BotTravelCost.floodSeconds(graph, 1, 8, PORTALS_ONLY, RATE_100);
        assertEquals(0.0, seconds.get(1), 1e-9);
        assertEquals(BotTravelCost.PORTAL_HOP_SECONDS, seconds.get(2), 1e-9);
        assertEquals(2 * BotTravelCost.PORTAL_HOP_SECONDS, seconds.get(3), 1e-9);

        // Hop cap: one hop reaches map 2 but never map 3.
        Map<Integer, Double> capped = BotTravelCost.floodSeconds(graph, 1, 1, PORTALS_ONLY, RATE_100);
        assertTrue(capped.containsKey(2));
        assertFalse(capped.containsKey(3));
    }

    @Test
    void shouldTakeScrollShortcutOnlyWhenOptedIn() {
        // 1->2->3->4(town) by portals; map 1 carries a scroll shortcut straight to 4.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(
                Map.of(1, new int[]{2}, 2, new int[]{3}, 3, new int[]{4}, 4, new int[0]),
                Map.of(1, 4));

        Map<Integer, Double> walked = BotTravelCost.floodSeconds(graph, 1, 8, PORTALS_ONLY, RATE_100);
        assertEquals(3 * BotTravelCost.PORTAL_HOP_SECONDS, walked.get(4), 1e-9);

        Map<Integer, Double> scrolled = BotTravelCost.floodSeconds(graph, 1, 8,
                new BotWorldGraph.RouteOptions(true, 0, false), RATE_100);
        assertEquals(BotTravelCost.SCROLL_SECONDS, scrolled.get(4), 1e-9);
    }

    @Test
    void shouldRideTaxiOnlyWhenMesoCoversTheFare() {
        // Henesys, no portals: cross-town times come from the hardcoded cab table.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(100000000, new int[0]));

        // 999 meso covers the 800 Ellinia fare but not the 1000 Lith fare.
        Map<Integer, Double> broke = BotTravelCost.floodSeconds(graph, 100000000, 8,
                new BotWorldGraph.RouteOptions(false, 999, false), RATE_100);
        assertEquals(BotTravelCost.TAXI_SECONDS, broke.get(101000000), 1e-9);
        assertFalse(broke.containsKey(104000000));

        Map<Integer, Double> funded = BotTravelCost.floodSeconds(graph, 100000000, 8,
                new BotWorldGraph.RouteOptions(false, 1000, false), RATE_100);
        assertEquals(BotTravelCost.TAXI_SECONDS, funded.get(104000000), 1e-9);
    }

    @Test
    void shouldPriceFerryThroughRuntimeTravelRateAtQueryTime() {
        // Half the 5-min departure window + the 10-min ride, both travelrate-scaled.
        assertEquals(750.0, BotTravelCost.ferrySeconds(RATE_100), 1e-9);
        assertEquals(375.0, BotTravelCost.ferrySeconds(ms -> ms / 2), 1e-9);

        // Ellinia station, no portals: crossing to Orbis must use the ferry edge.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(101000300, new int[0]));
        BotWorldGraph.RouteOptions sail = new BotWorldGraph.RouteOptions(false, 5000, true);

        assertEquals(750.0,
                BotTravelCost.floodSeconds(graph, 101000300, 8, sail, RATE_100).get(200000100), 1e-9);
        // The estimate follows the CURRENT travelrate, never a cached one.
        assertEquals(375.0,
                BotTravelCost.floodSeconds(graph, 101000300, 8, sail, ms -> ms / 2).get(200000100), 1e-9);

        // Gates: not opted in, or short the 5k ticket -> no crossing.
        assertFalse(BotTravelCost.floodSeconds(graph, 101000300, 8,
                new BotWorldGraph.RouteOptions(false, 5000, false), RATE_100).containsKey(200000100));
        assertFalse(BotTravelCost.floodSeconds(graph, 101000300, 8,
                new BotWorldGraph.RouteOptions(false, 4999, true), RATE_100).containsKey(200000100));
    }

    @Test
    void shouldDecayScoreWeightLinearlyToTheFloor() {
        Map<Integer, Double> seconds = Map.of(1, 0.0, 2, 1800.0, 3, 3600.0);
        assertEquals(1.0, BotTravelCost.scoreWeight(seconds, 1), 1e-9);
        assertEquals(0.5, BotTravelCost.scoreWeight(seconds, 2), 1e-9);
        assertEquals(BotTravelCost.MIN_SCORE_WEIGHT, BotTravelCost.scoreWeight(seconds, 3), 1e-9);
        // Unreachable under the current options: floor, not zero (data gaps shouldn't ban maps).
        assertEquals(BotTravelCost.MIN_SCORE_WEIGHT, BotTravelCost.scoreWeight(seconds, 99), 1e-9);
    }
}
