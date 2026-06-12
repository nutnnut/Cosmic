package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotMovementSimulationLabTest {
    @Test
    void shouldReachMoveTargetOnFlatGround() {
        MapleMap map = createFlatMap(910000101, 0, 600, 100);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotEntry entry = lab.spawnBot("SLASH", 1, map, new Point(100, 100));

        lab.setMoveTarget("SLASH", new Point(320, 100), true);
        lab.step(80);

        Point finalPos = lab.position("SLASH");
        assertTrue(Math.abs(finalPos.x - 320) <= 8, "bot should arrive at precise move target");
        assertNull(entry.moveTarget, "move target should clear after arrival");
    }

    @Test
    void shouldFollowOwnerUsingFormationOffsetOnFlatGround() {
        MapleMap map = createFlatMap(910000102, 0, 800, 100);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnActor("OWNER", 10, map, new Point(400, 100));
        BotEntry entry = lab.spawnBot("CRASH", 11, map, new Point(120, 100));

        lab.setFollow("CRASH", "OWNER");
        lab.setFormation("OWNER", BotManager.FormationType.LEFT, 120, 200);
        lab.step(100);

        Point finalPos = lab.position("CRASH");
        int expectedX = 280;
        assertTrue(Math.abs(finalPos.x - expectedX) <= BotMovementManager.cfg.STOP_DIST,
                "bot should settle near owner + formation offset");
        assertTrue(entry.following);
        assertNotNull(lab.describeCurrentState("CRASH"));
    }

    @Test
    void shouldReproduceIntermediateBumpLandingScenarioFromMovementManagerTest() {
        MapleMap map = new MapleMap(910000103, 0, 0, 910000103, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 110), new Point(20, 110), 1));
        footholds.insert(new Foothold(new Point(4, 102), new Point(6, 102), 2));
        map.setFootholds(footholds);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotEntry entry = lab.spawnBot("BUMP", 12, map, new Point(0, 100));
        entry.inAir = true;
        entry.physX = 0;
        entry.physY = 100;
        entry.velY = 0f;
        entry.airVelX = 8;

        lab.stepRaw("BUMP", new Point(20, 110), false);

        Foothold landedFoothold = map.getFootholds().findBelow(lab.position("BUMP"));
        assertNotNull(landedFoothold);
        assertEquals(2, landedFoothold.getId(), "bot should land on the authored intermediate bump foothold");
        assertTrue(!entry.inAir, "bot should land on the intermediate bump");
    }

    @Test
    void shouldReproduceDropPastWallMomentumScenarioFromMovementManagerTest() {
        MapleMap map = new MapleMap(910000104, 0, 0, 910000104, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 0), new Point(20, 0), 1));
        footholds.insert(new Foothold(new Point(0, 0), new Point(0, 80), 2));
        map.setFootholds(footholds);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotEntry entry = lab.spawnBot("DROP", 13, map, new Point(0, 0));
        entry.inAir = true;
        entry.physX = 0;
        entry.physY = 0;
        entry.velY = 0f;
        entry.airVelX = -8;

        lab.stepRaw("DROP", new Point(-50, 80), false);

        assertTrue(lab.position("DROP").x < 0, "bot should keep horizontal motion past the wall endpoint");
        assertEquals(-8, entry.airVelX);
    }

    @Test
    void shouldResolveSlashRopeOscillationDumpByRefreshingPendingExitEdge() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(103000000);
        BotNavigationGraphProvider.rebuildGraph(map);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnActor("OWNER", 20, map, new Point(-69, 156));
        BotEntry entry = lab.spawnBot("SLASH", 21, map, new Point(366, -117));
        lab.setFollow("SLASH", "OWNER");
        lab.setFollowOffset("SLASH", -60);
        lab.setAiAccumulator("SLASH", 50);
        Rope rope = map.getRopes().stream()
                .filter(candidate -> candidate.x() == 366 && candidate.topY() == -358 && candidate.bottomY() == -111)
                .findFirst()
                .orElseThrow();
        lab.attachBotToRope("SLASH", rope, -117);
        lab.setNavState("SLASH", new BotNavigationGraph.Edge(
                160, 116, BotNavigationGraph.EdgeType.CLIMB,
                new Point(366, -118), new Point(427, -101),
                8, 0, 366, -358, -111, 400
        ), 148, true);

        lab.step(10);

        List<String> trace = lab.formatRecentTrace("SLASH", 10);

        // The staged rightward exit is now executable directly: BotMovementManager.shouldSnapToClimbTarget
        // snaps the bot to startPoint.y when within one climbStep — including at rope.bottomY()
        // (pathlog-Leroy/John fix). The bot reaches the anchor exactly, the strict gate's
        // botPos.y == edge.startPoint.y bypass passes, and the launch fires before any
        // oscillation, so the previous "AI replan to a different destination" path is no longer
        // exercised.
        assertTrue(trace.stream().anyMatch(line -> line.contains("nav=exec") && line.contains("edge=CLIMB r160->")),
                "bot should execute a rope exit from r160 instead of oscillating on the rope");
        assertTrue(trace.stream().noneMatch(line -> line.contains("bot=(366,-117)") && line.contains("edge=CLIMB r160->r116") && line.contains("nav=reuse") && line.contains("+1000ms")),
                "stale rope exit should not keep the bot oscillating for a full second");
    }

    @Test
    void shouldResolveBashRopeOscillationDumpByExecutingImmediatelyFromRopeAnchorWindow() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(103000800);
        BotNavigationGraphProvider.rebuildGraph(map);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnActor("OWNER", 30, map, new Point(-437, -859));
        lab.spawnBot("BASH", 31, map, new Point(-437, -1142));
        lab.setMoveTarget("BASH", new Point(-241, -899), true);
        lab.setAiAccumulator("BASH", 50);
        Rope rope = map.getRopes().stream()
                .filter(candidate -> candidate.x() == -437 && candidate.topY() == -1471 && candidate.bottomY() == 84)
                .findFirst()
                .orElseThrow();
        lab.attachBotToRope("BASH", rope, -1142);
        lab.setNavState("BASH", new BotNavigationGraph.Edge(
                25, 2, BotNavigationGraph.EdgeType.CLIMB,
                new Point(-437, -1141), new Point(-477, -1166),
                -8, 0, -437, -1471, 84, 250
        ), 6, true);

        lab.step(8);

        List<String> trace = lab.formatRecentTrace("BASH", 8);

        assertTrue(trace.stream().anyMatch(line -> line.contains("nav=exec") && line.contains("edge=CLIMB r25->r2")),
                "bot should execute the rope exit immediately once it is inside the authored launch window");
        assertTrue(trace.stream().noneMatch(line -> line.contains("bot=(-437,-1137)")),
                "bot should not oscillate around the rope anchor before the exit jump");
    }

    @Test
    void shouldCommitJohnSecondJumpOnTheNextAiTickAfterLanding() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(101020001);
        BotNavigationGraphProvider.rebuildGraph(map);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnActor("OWNER", 50, map, new Point(115, -1521));
        lab.spawnBot("JOHN", 51, map, new Point(114, -1091));
        lab.setFollow("JOHN", "OWNER");
        lab.setFormation("OWNER", BotManager.FormationType.STAGGER, 60, 200);
        lab.setFollowOffset("JOHN", 180);
        lab.setAiAccumulator("JOHN", 50);
        lab.setNavState("JOHN", new BotNavigationGraph.Edge(
                28, 27, BotNavigationGraph.EdgeType.JUMP,
                new Point(148, -1098), new Point(148, -1150),
                0, 0, 0, 0, 0, 400
        ), 19, true);

        lab.step(20);

        List<String> trace = lab.formatRecentTrace("JOHN", 20);

        assertTrue(trace.stream().anyMatch(line -> line.contains("nav=exec")
                        && line.contains("edge=JUMP r28->r27")),
                "seeded jump edge should execute once the bot reaches its launch point");
        assertTrue(trace.stream().anyMatch(line -> line.contains("nav=new")
                        && line.contains("phys=GND")
                        && line.contains("edge=JUMP r27->r")),
                "after landing, the next AI tick should commit the next authored jump from the new region");
        assertTrue(trace.stream().noneMatch(line -> line.contains("phys=AIR") && line.contains("edge=none")),
                "bot should not drop navigation and enter an uncommitted fall between chained jumps");
    }

    @Test
    void shouldUsePetWalkingRoadLocalWalkOffDrop() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000202);
        BotNavigationGraphProvider.rebuildGraph(map);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnActor("OWNER", 40, map, new Point(-1799, -926));
        lab.spawnBot("JOHN", 41, map, new Point(-1366, -664));
        lab.setFollow("JOHN", "OWNER");
        lab.setFormation("OWNER", BotManager.FormationType.STAGGER, 60, 200);
        lab.setFollowOffset("JOHN", 180);
        lab.setAiAccumulator("JOHN", 50);
        lab.primeMapState("JOHN");

        lab.step(17);

        List<String> trace = lab.formatRecentTrace("JOHN", 17);
        assertTrue(trace.stream().anyMatch(line -> line.contains("phys=GND") && line.contains("edge=DROP")),
                "bot should commit a local drop edge while approaching the ledge");
        assertTrue(trace.stream().anyMatch(line -> line.contains("phys=AIR")),
                "bot should eventually leave the ledge and fall through normal physics");
        assertFalse(trace.stream().anyMatch(line -> line.contains("nav=exec") && line.contains("edge=DROP")),
                "walk-off drops should not require an explicit DROP execution step");
        assertFalse(trace.stream().anyMatch(line -> line.contains("graph-warmup")),
                "simulation uses a prebuilt graph and should not pause on graph warmup");
    }

    @Test
    void shouldWalkOffLedgeDropsViaGroundPhysicsWithoutExplicitDropExec() {
        MapleMap map = createWalkOffDropMap(910000401);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        LedgeDropScenario scenario = findLedgeDropScenario(graph, map);

        assertNotNull(scenario, "Expected at least one walk-off drop with a still-grounded start point");

        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        lab.spawnBot("DROPPER", 60, map, scenario.startPoint());
        lab.setMoveTarget("DROPPER", scenario.edge().endPoint, true);
        lab.setNavState("DROPPER", scenario.edge(), scenario.edge().toRegionId, false);
        lab.setAiAccumulator("DROPPER", 50);

        lab.step(40);

        List<String> trace = lab.formatRecentTrace("DROPPER", 40);
        String edgeToken = String.format("edge=DROP r%d->r%d", scenario.edge().fromRegionId, scenario.edge().toRegionId);

        assertTrue(trace.stream().anyMatch(line -> line.contains("phys=GND") && line.contains(edgeToken)),
                "bot should follow the authored drop edge while walking toward the ledge");
        assertTrue(trace.stream().anyMatch(line -> line.contains("phys=AIR")),
                "bot should leave the ledge and fall through physics");
        assertFalse(trace.stream().anyMatch(line -> line.contains("nav=exec") && line.contains(edgeToken)),
                "walk-off drops should not require an explicit DROP execution step");
        assertEquals(scenario.edge().toRegionId, graph.findRegionId(map, lab.position("DROPPER")),
                "bot should land in the destination region after walking off the ledge");
    }

    @Test
    void shouldRecoverFromStaleElNathDropEdgeWindowJustOutsideBot() {
        // pathlog-Leroy-2026-06-12T141517: El Nath ice (fs=0.2). The bot parked 2px OUTSIDE
        // a stale committed DROP window and reused it (blocked) for 21s, while every fresh
        // A* plan's window already contained the bot. With window-inset steering plus the
        // blocked-position give-up, the bot must traverse to the lower region in seconds.
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(211000000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int fromRegionId = graph.findRegionId(map, new Point(1287, 34));
        int toRegionId = graph.findRegionId(map, new Point(1285, 94));
        BotNavigationGraph.Edge liveEdge = graph.getOutgoing(fromRegionId).stream()
                .filter(edge -> edge.type == BotNavigationGraph.EdgeType.DROP)
                .filter(edge -> edge.toRegionId == toRegionId && edge.launchStepX == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a straight DROP edge between the log regions"));

        // Bot stands inside the live plan's window (log: x=1287) but 2px outside the stale one.
        int botX = Math.clamp(1287, liveEdge.launchMinX, liveEdge.launchMaxX);
        BotNavigationGraph.Edge staleEdge = new BotNavigationGraph.Edge(
                fromRegionId, toRegionId, BotNavigationGraph.EdgeType.DROP,
                new Point(botX - 22, liveEdge.startPoint.y), new Point(botX - 22, liveEdge.endPoint.y),
                botX - 42, botX - 2, 0, 0, 0, 0, 0, liveEdge.cost);

        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotNavigationGraph.Region fromRegion = graph.getRegion(fromRegionId);
        lab.spawnBot("LEROY", 80, map, fromRegion.pointAt(botX));
        lab.setMoveTarget("LEROY", new Point(1242, 94), true);
        lab.setNavState("LEROY", staleEdge, toRegionId, true);
        lab.setAiAccumulator("LEROY", 50);

        lab.step(60); // 3s budget vs the 21s field freeze

        assertEquals(toRegionId, graph.findRegionId(map, lab.position("LEROY")),
                "bot must traverse to the lower region instead of parking outside the stale window\n"
                        + String.join("\n", lab.formatRecentTrace("LEROY", 12)));
    }

    private static MapleMap createFlatMap(int mapId, int x1, int x2, int y) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(
                new Point(x1 - 200, y - 200),
                new Point(x2 + 200, y + 200));
        footholds.insert(new Foothold(new Point(x1, y), new Point(x2, y), 1));
        map.setFootholds(footholds);
        return map;
    }

    private static MapleMap createWalkOffDropMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(
                new Point(-200, -200),
                new Point(400, 400));
        footholds.insert(new Foothold(new Point(0, 0), new Point(100, 0), 1));
        footholds.insert(new Foothold(new Point(100, 120), new Point(220, 120), 2));
        map.setFootholds(footholds);
        return map;
    }

    private static LedgeDropScenario findLedgeDropScenario(BotNavigationGraph graph, MapleMap map) {
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) {
                    continue;
                }

                Point startPoint = findWalkableStartBeforeLedge(graph, map, edge);
                if (startPoint != null) {
                    return new LedgeDropScenario(edge, startPoint);
                }
            }
        }
        return null;
    }

    private static Point findWalkableStartBeforeLedge(BotNavigationGraph graph,
                                                      MapleMap map,
                                                      BotNavigationGraph.Edge edge) {
        BotNavigationGraph.Region region = graph.getRegion(edge.fromRegionId);
        if (region == null) {
            return null;
        }

        int direction = Integer.signum(edge.launchStepX);
        if (direction == 0) {
            return null;
        }

        for (int delta = 6; delta <= 18; delta += 2) {
            int candidateX = edge.startPoint.x - (direction * delta);
            if (candidateX < region.minX || candidateX > region.maxX) {
                continue;
            }

            Point candidate = region.pointAt(candidateX);
            if (candidate == null || graph.findRegionId(map, candidate) != edge.fromRegionId) {
                continue;
            }
            if (BotPhysicsEngine.canWalkGroundStep(map, candidate, edge.launchStepX)) {
                return candidate;
            }
        }
        return null;
    }

    private record LedgeDropScenario(BotNavigationGraph.Edge edge, Point startPoint) {
    }
}
