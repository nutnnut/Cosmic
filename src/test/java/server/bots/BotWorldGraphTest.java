package server.bots;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldRideTaxiOnlyAboveTheSpendTier() {
        // Towns only, no portal edges: any cross-town route must use the hardcoded cab table.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                100000000, new int[0], 101000000, new int[0], 104000000, new int[0]));
        int tier = BotManager.cfg.TAXI_MIN_MESO;

        // Spend policy: paid cabs between WALKABLE towns cost a route hop only above the taxi tier; below
        // it the bot walks, so with no portal edges there's no route at all.
        assertNull(BotWorldGraph.route(graph, 100000000, 104000000, 4, new BotWorldGraph.RouteOptions(false, tier - 1, false)));
        // At/above the tier the cab is a single ride (Victoria fares 800-1000 sit well under the tier, so
        // meso always covers them once the tier is met). The per-fare gate is exercised by the cost model
        // in BotTravelCostTest, which prices reachability without the executor's spend thrift.
        assertEquals(List.of(104000000),
                BotWorldGraph.route(graph, 100000000, 104000000, 4, new BotWorldGraph.RouteOptions(false, tier, false)));
        assertEquals(List.of(101000000),
                BotWorldGraph.route(graph, 100000000, 101000000, 4, new BotWorldGraph.RouteOptions(false, tier, false)));
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
    void shouldConnectEventManagerTransitLinksWhenOptedIn() {
        // Stations only, no portal edges: the EventManager rides (subway/train/crane/elevator) must carry
        // the cross-region edge exactly like the boats. Verified ids in BotFerryManager.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                103000000, new int[0], 103000100, new int[0], 600010001, new int[0],
                540010000, new int[0], 103000310, new int[0],
                200000141, new int[0], 250000100, new int[0],
                222020100, new int[0], 222020200, new int[0]));
        BotWorldGraph.RouteOptions ferryRich = new BotWorldGraph.RouteOptions(false, 30000, true);

        // Kerning City subway to NLC (5k) and the free train to Kerning Square — same hall boards both.
        assertEquals(List.of(600010001), BotWorldGraph.route(graph, 103000100, 600010001, 4, ferryRich));
        assertEquals(List.of(103000310), BotWorldGraph.route(graph, 103000100, 103000310, 4, ferryRich));
        assertEquals(List.of(540010000), BotWorldGraph.route(graph, 103000000, 540010000, 4, ferryRich));
        assertEquals(List.of(103000000), BotWorldGraph.route(graph, 540010000, 103000000, 4, ferryRich));
        // Orbis cabin -> Mu Lung crane (1500).
        assertEquals(List.of(250000100), BotWorldGraph.route(graph, 200000141, 250000100, 4, ferryRich));
        // Helios elevator 2F <-> 99F (free, intra-tower).
        assertEquals(List.of(222020200), BotWorldGraph.route(graph, 222020100, 222020200, 4, ferryRich));
        assertEquals(List.of(222020100), BotWorldGraph.route(graph, 222020200, 222020100, 4, ferryRich));

        // Gated like the boats: a broke bot can't afford the subway, and follow mode (no ferry) sees no edge.
        assertNull(BotWorldGraph.route(graph, 103000100, 600010001, 4, new BotWorldGraph.RouteOptions(false, 4999, true)));
        assertNull(BotWorldGraph.route(graph, 200000141, 250000100, 4, new BotWorldGraph.RouteOptions(false, 30000, false)));
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
        // Spinel world tour: Victoria towns + Amoria -> Mushroom Shrine (3000); Boat Quay -> Malaysia.
        BotWorldGraph.TaxiEdge shrine = BotWorldGraph.findTaxiEdge(100000000, 800000000);
        assertNotNull(shrine);
        assertEquals(9000020, shrine.npcId());
        assertEquals(3000, shrine.fare());
        assertEquals(550000000, BotWorldGraph.findTaxiEdge(541000000, 550000000).toMapId());
        // The shrine return has no static destination: findTaxiEdge synthesizes a free Spinel ride to
        // the requested origin (the saved WORLDTOUR), so taxiRide can drive it back to where it boarded.
        BotWorldGraph.TaxiEdge shrineBack = BotWorldGraph.findTaxiEdge(800000000, 100000000);
        assertNotNull(shrineBack);
        assertEquals(9000020, shrineBack.npcId());
        assertEquals(0, shrineBack.fare());
        assertEquals(100000000, shrineBack.toMapId());
        // Thomas Swift 9201022: free cab both ways Henesys <-> Amoria; Amoria also boards Spinel.
        assertEquals(680000000, BotWorldGraph.findTaxiEdge(100000000, 680000000).toMapId());
        BotWorldGraph.TaxiEdge amoriaCab = BotWorldGraph.findTaxiEdge(680000000, 100000000);
        assertNotNull(amoriaCab);
        assertEquals(9201022, amoriaCab.npcId());
        assertEquals(0, amoriaCab.fare());
        assertEquals(800000000, BotWorldGraph.findTaxiEdge(680000000, 800000000).toMapId());
        // Audrey: Singapore CBD -> Malaysia Metropolis -> Kampung, plus the return leg.
        BotWorldGraph.TaxiEdge singaporeToMalaysia = BotWorldGraph.findTaxiEdge(540000000, 550000000);
        assertNotNull(singaporeToMalaysia);
        assertEquals(9201135, singaporeToMalaysia.npcId());
        assertEquals(42000, singaporeToMalaysia.fare());
        assertEquals(551000000, BotWorldGraph.findTaxiEdge(550000000, 551000000).toMapId());
        assertEquals(550000000, BotWorldGraph.findTaxiEdge(551000000, 550000000).toMapId());
        // Leafre dock's real Dragon NPC route: 2082003 -> flight maps -> Temple of Time arrival.
        BotWorldGraph.TaxiEdge dragonOut = BotWorldGraph.findTaxiEdge(240000110, 270000100);
        assertNotNull(dragonOut);
        assertEquals(2082003, dragonOut.npcId());
        assertEquals(0, dragonOut.fare());
        BotWorldGraph.TaxiEdge dragonBack = BotWorldGraph.findTaxiEdge(270000100, 240000110);
        assertNotNull(dragonBack);
        assertEquals(2082003, dragonBack.npcId());
        assertEquals(0, dragonBack.fare());
    }

    /** The Mushroom Shrine return is the saved WORLDTOUR origin, injected per-bot — never a static free
     *  edge to Lith Harbor, so the shrine can't be abused as a flat-fee shortcut off a far continent. */
    @Test
    void mushroomShrineReturnIsStatefulNotALithShortcut() {
        // Isolated nodes (no portals): the only links between them are the taxi table.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                240000000, new int[0], 800000000, new int[0], 104000000, new int[0], 100000000, new int[0]));

        // A bot NOT standing at the shrine has no saved origin, so the shrine is a dead end: Leafre can
        // reach it (3000) but CANNOT continue through it to Lith Harbor — the old free shortcut is gone.
        BotWorldGraph.RouteOptions noTour = new BotWorldGraph.RouteOptions(false, 3000, false);
        assertEquals(List.of(800000000), BotWorldGraph.route(graph, 240000000, 800000000, 4, noTour));
        assertNull(BotWorldGraph.route(graph, 240000000, 104000000, 4, noTour));

        // A bot AT the shrine returns to its saved WORLDTOUR origin (set when it boarded) and only there.
        BotWorldGraph.RouteOptions savedHenesys =
                new BotWorldGraph.RouteOptions(false, 0, false, false, Integer.MAX_VALUE, 100000000);
        assertEquals(List.of(100000000), BotWorldGraph.route(graph, 800000000, 100000000, 4, savedHenesys));
        assertNull(BotWorldGraph.route(graph, 800000000, 104000000, 4, savedHenesys));
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
        assertEquals("in01", BotWorldGraph.scriptedEntrancePortal(222020400, 300000100));     // Ellin Forest
        assertNull(BotWorldGraph.scriptedEntrancePortal(101000000, 100000201)); // wrong dest for that map
        assertNull(BotWorldGraph.scriptedEntrancePortal(100000000, 100000201)); // not the entrance map
    }

    /** The Temple-of-Time timeQuest portals are per-bot conditional edges: routable only when the bot's
     *  {@link BotWorldGraph.RouteOptions#unlockedGates} holds that gate's key — never baked into the
     *  shared graph (so the web view / any bot without the quest sees a sealed corridor). */
    @Test
    void templeCorridorGateIsRoutableOnlyWhenUnlocked() {
        // Minimal corridor: the lane entrance walks to Memory Lane 1 by a normal portal, but Lane 1's ONLY
        // forward link is the quest-gated timeQuest portal (270010100 -> 270010110, gate 3501); past it,
        // normal portals continue to Memory Lane 2. So 270010200 is reachable iff gate 3501 is unlocked.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                270010000, new int[]{270010100},
                270010100, new int[0],
                270010110, new int[]{270010200},
                270010200, new int[0]));

        // Empty gate set: the corridor is sealed at Lane 1 — Lane 2 is unreachable.
        assertFalse(BotWorldGraph.reachableWithin(graph, 270010000, 10, PORTALS_ONLY).contains(270010200));

        // Gate 3501 unlocked: the timeQuest edge appears and Memory Lane 2 becomes reachable.
        BotWorldGraph.RouteOptions unlocked = new BotWorldGraph.RouteOptions(
                false, 0, false, false, Integer.MAX_VALUE, -1, -1, Set.of(3501));
        assertTrue(BotWorldGraph.reachableWithin(graph, 270010000, 10, unlocked).contains(270010200));
        // And the executor walks the real timeQuest portal (named "in00") for that hop.
        assertEquals("in00", BotWorldGraph.scriptedEntrancePortal(270010100, 270010110));
    }

    /** Whole-corridor composition: the synthetic index carries every NORMAL forward portal (entrance ->
     *  Lane 1, and each gated portal's landing spot -> the next lane map), while the 16 gated timeQuest
     *  edges come exclusively from {@link BotWorldGraph#QUEST_GATED_ENTRANCES} via {@code unlockedGates}
     *  — proving the per-gate mechanism from {@link #templeCorridorGateIsRoutableOnlyWhenUnlocked} composes
     *  end to end into a full sealed-then-open corridor from the Temple entrance to the Ruins. */
    @Test
    void wholeTempleCorridorIsRoutableOnlyWithAllGatesUnlocked() {
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.ofEntries(
                Map.entry(270000100, new int[]{270010100}), // Temple entrance -> Lane 1 ML1
                // Lane 1 (Dodo)
                Map.entry(270010100, new int[0]),
                Map.entry(270010110, new int[]{270010200}),
                Map.entry(270010200, new int[0]),
                Map.entry(270010210, new int[]{270010300}),
                Map.entry(270010300, new int[0]),
                Map.entry(270010310, new int[]{270010400}),
                Map.entry(270010400, new int[0]),
                Map.entry(270010410, new int[]{270010500}),
                Map.entry(270010500, new int[0]),
                // Lane 2 (Lilynouch)
                Map.entry(270020000, new int[]{270020100}),
                Map.entry(270020100, new int[0]),
                Map.entry(270020110, new int[]{270020200}),
                Map.entry(270020200, new int[0]),
                Map.entry(270020210, new int[]{270020300}),
                Map.entry(270020300, new int[0]),
                Map.entry(270020310, new int[]{270020400}),
                Map.entry(270020400, new int[0]),
                Map.entry(270020410, new int[]{270020500}),
                Map.entry(270020500, new int[0]),
                // Lane 3 (Lyka)
                Map.entry(270030000, new int[]{270030100}),
                Map.entry(270030100, new int[0]),
                Map.entry(270030110, new int[]{270030200}),
                Map.entry(270030200, new int[0]),
                Map.entry(270030210, new int[]{270030300}),
                Map.entry(270030300, new int[0]),
                Map.entry(270030310, new int[]{270030400}),
                Map.entry(270030400, new int[0]),
                Map.entry(270030410, new int[]{270030500}),
                Map.entry(270030500, new int[0]),
                // Ruins hub gate
                Map.entry(270040000, new int[0]),
                Map.entry(270040100, new int[0])));

        // Sealed: with no gates unlocked, Lane 1's only forward link is the gated portal, so the corridor
        // never gets past its very first map.
        Set<Integer> sealed = BotWorldGraph.reachableWithin(graph, 270000100, 40, PORTALS_ONLY);
        assertFalse(sealed.contains(270010200));

        // Fully unlocked: every mainline gate quest done (plus the Ruins item gate) opens the whole
        // corridor end to end, reaching both the last lane-5 miniboss map and the Ruins beyond it.
        BotWorldGraph.RouteOptions allGates = new BotWorldGraph.RouteOptions(
                false, 0, false, false, Integer.MAX_VALUE, -1, -1, Set.of(
                        3501, 3502, 3503, 3504, 3507, 3508, 3509, 3510, 3511,
                        3514, 3515, 3516, 3517, 3518, 3519, BotWorldGraph.TEMPLE_ITEM_GATE));
        Set<Integer> open = BotWorldGraph.reachableWithin(graph, 270000100, 40, allGates);
        assertTrue(open.contains(270030500), "Road to Oblivion 5 (Lyka) should be reachable, got " + open);
        assertTrue(open.contains(270040100), "Ruins should be reachable, got " + open);
    }

    @Test
    void fmExitEdgeExistsOnlyForTheBotStandingInside() {
        int entrance = constants.id.MapId.FM_ENTRANCE; // 910000000
        int room = 910000001;
        int town = 220000000;
        // Rooms connect to the entrance via ordinary portals (WZ scan); the town->entrance market
        // portal is scripted and deliberately ABSENT, so the FM is enterable only by the errand.
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                room, new int[]{entrance},
                entrance, new int[]{room},
                town, new int[0]));

        // No fmReturn (any bot elsewhere): the FM is a dead end - nothing routes out or through.
        assertNull(BotWorldGraph.route(graph, room, town, 8, PORTALS_ONLY));

        // A bot standing inside carries fmReturn (its saved FREE_MARKET town): room -> entrance ->
        // town routes normally, so decides/travel need no FM special-casing anywhere else.
        BotWorldGraph.RouteOptions inside = new BotWorldGraph.RouteOptions(
                false, 0, false, false, Integer.MAX_VALUE, -1, town);
        assertEquals(List.of(entrance, town), BotWorldGraph.route(graph, room, town, 8, inside));

        // The exit hop is executed by walking the scripted out00 (its script warps to the saved
        // town); entrance -> room hops are real portals and must NOT map to the exit.
        assertEquals("out00", BotWorldGraph.scriptedEntrancePortal(entrance, town));
        assertNull(BotWorldGraph.scriptedEntrancePortal(entrance, room));
    }
}
