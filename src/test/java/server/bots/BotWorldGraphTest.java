package server.bots;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotWorldGraphTest {

    private static final BotWorldGraph.RouteOptions PORTALS_ONLY = BotWorldGraph.RouteOptions.PORTALS_ONLY;

    @Test
    void shouldRouteShortestPathWithinHopCap() {
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                1, new int[]{2, 5},
                2, new int[]{1, 3},
                3, new int[]{2, 4},
                5, new int[]{4},
                4, new int[0]));

        assertEquals(List.of(), BotWorldGraph.route(graph, 1, 1, 4, PORTALS_ONLY));
        assertEquals(List.of(2), BotWorldGraph.route(graph, 1, 2, 4, PORTALS_ONLY));
        // 1→5→4 beats 1→2→3→4.
        assertEquals(List.of(5, 4), BotWorldGraph.route(graph, 1, 4, 4, PORTALS_ONLY));
        assertNull(BotWorldGraph.route(graph, 1, 4, 1, PORTALS_ONLY)); // beyond the hop cap
        assertNull(BotWorldGraph.route(graph, 4, 1, 8, PORTALS_ONLY)); // edges are directed; 4 is a sink
    }

    @Test
    void shouldTakeScrollShortcutOnlyWhenOptedIn() {
        // 1→2→3→4(town): three portal hops, so 1 carries a scroll shortcut straight to 4.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(
                Map.of(1, new int[]{2}, 2, new int[]{3}, 3, new int[]{4}, 4, new int[0]),
                Map.of(1, 4));

        assertEquals(List.of(2, 3, 4), BotWorldGraph.route(graph, 1, 4, 4, PORTALS_ONLY));
        assertEquals(List.of(4),
                BotWorldGraph.route(graph, 1, 4, 4, new BotWorldGraph.RouteOptions(true, 0)));
        // The shortcut also shortens routes PAST the town.
        assertEquals(List.of(2, 3), BotWorldGraph.route(graph, 1, 3, 4, PORTALS_ONLY));
    }

    @Test
    void shouldGateScrollShortcutsOnWalkLength() {
        // town = 9. Map 1 is 3 hops out (gets the shortcut), map 2 is 2 hops out (walk is
        // short enough), map 7 can't reach town by portals at all (gets the shortcut).
        Map<Integer, int[]> edges = Map.of(
                1, new int[]{2},
                2, new int[]{3},
                3, new int[]{9},
                7, new int[0],
                9, new int[0]);
        Map<Integer, Integer> returnMaps = Map.of(1, 9, 2, 9, 7, 9);

        Map<Integer, Integer> scrollTargets = BotWorldGraph.computeScrollTargets(edges, returnMaps);

        assertEquals(Map.of(1, 9, 7, 9), scrollTargets);
    }

    @Test
    void shouldRideTaxiOnlyWhenMesoCoversTheFare() {
        // Towns only, no portal edges: any cross-town route must use the hardcoded cab table.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                100000000, new int[0], 101000000, new int[0], 104000000, new int[0]));

        // Henesys→Lith costs 1000.
        assertEquals(List.of(104000000),
                BotWorldGraph.route(graph, 100000000, 104000000, 4, new BotWorldGraph.RouteOptions(false, 1000)));
        assertNull(BotWorldGraph.route(graph, 100000000, 104000000, 4, new BotWorldGraph.RouteOptions(false, 999)));
        // Henesys→Ellinia costs only 800.
        assertEquals(List.of(101000000),
                BotWorldGraph.route(graph, 100000000, 101000000, 4, new BotWorldGraph.RouteOptions(false, 800)));
        // Broke bots don't see taxi edges at all.
        assertNull(BotWorldGraph.route(graph, 100000000, 101000000, 4, PORTALS_ONLY));
    }

    @Test
    void shouldBuildWorldGraphFromWz() {
        BotWorldGraph.Index graph = BotWorldGraph.get();

        // The world has thousands of connected fields.
        assertTrue(graph.edges().size() > 4000, "expected most maps indexed, got " + graph.edges().size());

        // Ground truth from Map.wz 104040000 (Henesys Hunting Ground I): east00 → Henesys,
        // west00 → 104030000 (both pt=2, unscripted).
        int[] neighbors = graph.neighbors(104040000);
        assertTrue(Arrays.stream(neighbors).anyMatch(t -> t == 100000000),
                "hunting ground should connect to Henesys, got " + Arrays.toString(neighbors));
        assertTrue(Arrays.stream(neighbors).anyMatch(t -> t == 104030000),
                "hunting ground should connect to 104030000, got " + Arrays.toString(neighbors));

        // Adjacent map routes in one hop; a farther map routes in a few, ending at the target.
        assertEquals(List.of(100000000), BotWorldGraph.route(104040000, 100000000, 4));
        List<Integer> multiHop = BotWorldGraph.route(104030000, 100000000, 4);
        assertNotNull(multiHop, "104030000 should reach Henesys within 4 hops");
        assertEquals(100000000, multiHop.get(multiHop.size() - 1));

        // Scroll shortcuts: plenty of deep maps qualify; short walks must not. The hunting
        // ground is one portal from Henesys, so no scroll edge there.
        assertTrue(graph.scrollTargets().size() > 100,
                "expected many scroll shortcuts, got " + graph.scrollTargets().size());
        assertEquals(-1, graph.scrollTarget(104040000));
    }
}
