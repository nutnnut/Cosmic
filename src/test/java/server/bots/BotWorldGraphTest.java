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
                BotWorldGraph.route(graph, 1, 4, 4, new BotWorldGraph.RouteOptions(true, 0, false)));
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
                BotWorldGraph.route(graph, 100000000, 104000000, 4, new BotWorldGraph.RouteOptions(false, 1000, false)));
        assertNull(BotWorldGraph.route(graph, 100000000, 104000000, 4, new BotWorldGraph.RouteOptions(false, 999, false)));
        // Henesys→Ellinia costs only 800.
        assertEquals(List.of(101000000),
                BotWorldGraph.route(graph, 100000000, 101000000, 4, new BotWorldGraph.RouteOptions(false, 800, false)));
        // Broke bots don't see taxi edges at all.
        assertNull(BotWorldGraph.route(graph, 100000000, 101000000, 4, PORTALS_ONLY));
    }

    @Test
    void shouldSailFerryOnlyWhenOptedInWithTicketMoney() {
        // Stations only, no portal edges: crossing the sea must use the ferry edge.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                101000300, new int[0], 200000100, new int[0]));

        assertEquals(List.of(200000100),
                BotWorldGraph.route(graph, 101000300, 200000100, 4, new BotWorldGraph.RouteOptions(false, 5000, true)));
        assertEquals(List.of(101000300),
                BotWorldGraph.route(graph, 200000100, 101000300, 4, new BotWorldGraph.RouteOptions(false, 5000, true)));
        // Not enough for the 5k ticket, or ferries not opted in (follow mode) — no route.
        assertNull(BotWorldGraph.route(graph, 101000300, 200000100, 4, new BotWorldGraph.RouteOptions(false, 4999, true)));
        assertNull(BotWorldGraph.route(graph, 101000300, 200000100, 4, new BotWorldGraph.RouteOptions(false, 5000, false)));
        // The whole Orbis boarding chain carries the edge, so a bot mid-chain keeps moving forward.
        BotWorldGraph.Index pier = BotWorldGraph.indexOf(Map.of(200000111, new int[0]));
        assertEquals(List.of(101000300),
                BotWorldGraph.route(pier, 200000111, 101000300, 4, new BotWorldGraph.RouteOptions(false, 5000, true)));
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

    /** WZ-free: the cross-continent scripted-warp rides are wired into the taxi edge table (so
     *  reachableWithin/route can traverse them and taxiRide executes them like a cab). */
    @Test
    void crossContinentScriptedWarpEdgesAreWired() {
        // Maple Island exit boat (Southperry 60000 -> Lith Harbor 104000000) — the off-island keystone.
        BotWorldGraph.TaxiEdge maple = BotWorldGraph.findTaxiEdge(60000, 104000000);
        assertNotNull(maple, "Maple Island exit boat edge must exist");
        assertEquals(22000, maple.npcId());
        assertEquals(150, maple.fare());
        // Post-Big-Bang Southperry 2000000 (reachable from the Adventurer Training Center) also has Shanks.
        BotWorldGraph.TaxiEdge maple2 = BotWorldGraph.findTaxiEdge(2000000, 104000000);
        assertNotNull(maple2, "post-BB Southperry 2000000 must also have the boat edge");
        assertEquals(22000, maple2.npcId());
        // Dolphin both directions: Herb Town 251000100 <-> Aqua Road 230000000.
        assertNotNull(BotWorldGraph.findTaxiEdge(251000100, 230000000));
        BotWorldGraph.TaxiEdge back = BotWorldGraph.findTaxiEdge(230000000, 251000100);
        assertNotNull(back);
        assertEquals(2060009, back.npcId());
        assertEquals(10000, back.fare());
        // Pason: Lith Harbor 104000000 -> Florina Beach 110000000 (1002002, 1500 meso).
        BotWorldGraph.TaxiEdge florina = BotWorldGraph.findTaxiEdge(104000000, 110000000);
        assertNotNull(florina);
        assertEquals(1002002, florina.npcId());
        assertEquals(1500, florina.fare());
    }

    @Test
    void scriptedEntrancePortalResolvesEachInstructorHiddenStreet() {
        // The bot treats these scripted portals as a normal portal to the instructor map; the lookup
        // feeds both routing (graph edge) and the live portal-finder. Verified vs Map.wz + scripts/portal.
        assertEquals("jobin00", BotWorldGraph.scriptedEntrancePortal(101000000, 101000003)); // Magician (Grendel)
        assertEquals("in02", BotWorldGraph.scriptedEntrancePortal(100000200, 100000201));     // Bowman (Athena)
        assertEquals("in00", BotWorldGraph.scriptedEntrancePortal(240010500, 240010501));     // 4th job (Leafre)
        // Return legs of one-way regions (Lith-anchored symmetry audit).
        assertEquals("east00", BotWorldGraph.scriptedEntrancePortal(140020200, 140020300));   // Snow Island -> Puro dock
        assertEquals("west00", BotWorldGraph.scriptedEntrancePortal(222010300, 222010200));   // KFT Fox Ridge return
        assertEquals("out00", BotWorldGraph.scriptedEntrancePortal(240040700, 240040600));    // Leafre Cave of Life return
        assertNull(BotWorldGraph.scriptedEntrancePortal(101000000, 100000201)); // wrong dest for that map
        assertNull(BotWorldGraph.scriptedEntrancePortal(100000000, 100000201)); // not the entrance map
    }
}
