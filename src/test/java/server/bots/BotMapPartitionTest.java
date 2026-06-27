package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotMapPartitionTest {

    private static BotMapPartition.PortalRef exit(String name, int targetMap, String targetPortal) {
        return new BotMapPartition.PortalRef(name, null, targetMap, targetPortal);
    }

    /**
     * The headline case for partition-aware travel. Map A (id 1) is split into an upper platform and a
     * lower one; the upper can down-jump to the lower but NOT back up. The exits to C and D sit only on the
     * upper platform; the lower platform reaches ONLY the portal to B. Map C (id 3) has a ramp spanning its
     * platforms (one platform → fully connected) and links only to B; B (id 2) is split (no ramp), and its
     * return-to-A lands you back on whichever platform you left from.
     *
     * <p>A bot stranded on lower A cannot reach D the obvious way (the map-level world graph would wrongly
     * promise A->D in one hop). The only route is out to B, across to the ramp map C to flip platforms, back
     * to B's upper, back to A's UPPER, then to D: A>B>C>B>A>D — re-entering both B and A on a different
     * platform. Reachability here is keyed by ARRIVAL portal (the platform you spawn on).
     */
    @Test
    void routesOutAndBackThroughRampMapWhenLowerPlatformCannotReachExit() {
        BotMapPartition a = new BotMapPartition(1,
                List.of(exit("A_to_B_up", 2, "B_from_A_up"), exit("A_to_C_up", 3, "C_from_A"),
                        exit("A_to_D", 4, "D_from_A"), exit("A_to_B_low", 2, "B_from_A_low")),
                Map.of(
                        "A_from_B_up", Set.of("A_to_B_up", "A_to_C_up", "A_to_D", "A_to_B_low"),
                        "A_from_C", Set.of("A_to_B_up", "A_to_C_up", "A_to_D", "A_to_B_low"),
                        "A_from_D", Set.of("A_to_B_up", "A_to_C_up", "A_to_D", "A_to_B_low"),
                        "A_from_B_low", Set.of("A_to_B_low")),
                false);

        BotMapPartition b = new BotMapPartition(2,
                List.of(exit("B_to_A_up", 1, "A_from_B_up"), exit("B_to_A_low", 1, "A_from_B_low"),
                        exit("B_to_C_low", 3, "C_from_B")),
                Map.of(
                        "B_from_A_up", Set.of("B_to_A_up"),
                        "B_from_C", Set.of("B_to_A_up"),
                        "B_from_A_low", Set.of("B_to_A_low", "B_to_C_low")),
                false);

        BotMapPartition c = BotMapPartition.fullyConnected(3, List.of(exit("C_to_B", 2, "B_from_C")));
        BotMapPartition d = BotMapPartition.fullyConnected(4, List.of());

        Map<Integer, BotMapPartition> partitions = Map.of(1, a, 2, b, 3, c, 4, d);

        // Stranded on lower A: live reachable exits = only the lower portal to B.
        List<BotMapPartition.PortalRef> lowerExits = List.of(exit("A_to_B_low", 2, "B_from_A_low"));
        List<BotWorldPartitionRouter.Node> route =
                BotWorldPartitionRouter.route(id -> partitions.get(id), 1, lowerExits, 4, 12);
        List<Integer> hopMaps = route.stream().map(BotWorldPartitionRouter.Node::mapId).collect(Collectors.toList());
        assertEquals(List.of(2, 3, 2, 1, 4), hopMaps,
                "lower-A bot must detour A>B>C>B>A>D; the ramp map C is the only way to flip onto upper A");
        // The re-entry into A is through an UPPER arrival portal, not the lower one it started on.
        BotWorldPartitionRouter.Node reentryA = route.get(3);
        assertEquals(1, reentryA.mapId());
        assertEquals("A_from_B_up", reentryA.arrivalPortal(), "must come back into A on the upper platform");

        // From upper A all exits are reachable, so D is one hop.
        List<BotMapPartition.PortalRef> upperExits = a.usableExits("A_from_B_up");
        List<BotWorldPartitionRouter.Node> fromUpper =
                BotWorldPartitionRouter.route(id -> partitions.get(id), 1, upperExits, 4, 12);
        assertEquals(List.of(4), fromUpper.stream().map(BotWorldPartitionRouter.Node::mapId).collect(Collectors.toList()));

        // Too few hops -> unreachable (proves the full 5-hop detour is required).
        assertNull(BotWorldPartitionRouter.route(id -> partitions.get(id), 1, lowerExits, 4, 4),
                "the lower-A route needs 5 hops; capping at 4 must report unreachable");

        // Danger gate: the only route to D runs through map B; blocking B must yield no route (the bot
        // warps/holds instead of being walked through a trap map) — same gate the map-level route honors.
        assertNull(BotWorldPartitionRouter.route(id -> partitions.get(id), 1, lowerExits, 4, 12, m -> m == 2),
                "blocking map B must prune the only partition route to D");
    }

    @Test
    void serializeRoundTripPreservesReachabilityForSplitAndFullyConnectedMaps() {
        BotMapPartition split = new BotMapPartition(1,
                List.of(exit("up_exit", 2, "in_up"), exit("low_exit", 3, "in_low")),
                Map.of("arrive_up", Set.of("up_exit", "low_exit"),
                        "arrive_low", Set.of("low_exit")),
                false);
        BotMapPartition back = BotMapPartition.deserialize(split.serialize());
        assertFalse(back.fullyConnected);
        assertEquals(2, back.usableExits("arrive_up").size(), "upper arrival still reaches both exits");
        assertEquals(List.of("low_exit"),
                back.usableExits("arrive_low").stream().map(BotMapPartition.PortalRef::name).collect(Collectors.toList()),
                "lower arrival still reaches only its own exit");

        BotMapPartition full = BotMapPartition.fullyConnected(5,
                List.of(exit("a", 6, "x"), exit("b", 7, "y")));
        BotMapPartition fullBack = BotMapPartition.deserialize(full.serialize());
        assertTrue(fullBack.fullyConnected);
        assertEquals(2, fullBack.usableExits("any_arrival").size(),
                "fully-connected map: any arrival reaches every exit");
    }

    /**
     * Derivation composes the nav graph's {@code canReach} (the intra-map partition SSOT): a one-way DROP
     * from the upper platform to the lower one (no way back) must make the upper arrival reach both
     * portals and the lower arrival reach only its own — no parallel reachability logic of our own.
     */
    @Test
    void derivesPerArrivalReachabilityFromCanReach() {
        BotNavigationGraph.Region upper = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(100, 100), 1))));
        BotNavigationGraph.Region lower = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 200), new Point(100, 200), 2))));
        BotNavigationGraph.Edge drop = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.DROP,
                new Point(50, 100), new Point(50, 200), 0, 0, 0, 0, 0, 100);
        BotNavigationGraph graph = new BotNavigationGraph(
                7, 1,
                List.of(upper, lower),
                Map.of(1, upper, 2, lower),
                Map.of(1, 1, 2, 2),
                Map.of(1, List.of(drop)),
                Set.of());

        BotMapPartition.PortalRef pUp = new BotMapPartition.PortalRef("p_up", new Point(50, 100), 99, "x");
        BotMapPartition.PortalRef pLow = new BotMapPartition.PortalRef("p_low", new Point(50, 200), 88, "y");
        Map<String, Integer> portalRegion = Map.of("p_up", 1, "p_low", 2);

        BotMapPartition part = BotMapPartition.fromNavGraph(
                7, graph, List.of(pUp, pLow), Set.of("p_up", "p_low"), p -> portalRegion.get(p.name()));

        assertFalse(part.fullyConnected, "one-way drop means the map is not fully connected");
        assertEquals(2, part.usableExits("p_up").size(),
                "from the upper platform a bot can down-jump to either portal");
        assertEquals(List.of("p_low"),
                part.usableExits("p_low").stream().map(BotMapPartition.PortalRef::name).collect(Collectors.toList()),
                "from the lower platform only the lower portal is reachable (no jump back up)");
    }
}
