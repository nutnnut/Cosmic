package server.bots;

import client.Character;
import constants.game.CharacterStance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotNavigationGraphProviderTest {
    private static final Supplier<MapleMap> henesysS = lazyMap(100000000);
    private static final Supplier<BotNavigationGraph> henesysGraphS = lazyGraph(henesysS);
    private static final Supplier<MapleMap> perionS = lazyMap(102000000);
    private static final Supplier<BotNavigationGraph> perionGraphS = lazyGraph(perionS);
    private static final Supplier<MapleMap> kerningS = lazyMap(103000000);
    private static final Supplier<BotNavigationGraph> kerningGraphS = lazyGraph(kerningS);
    private static final Supplier<MapleMap> kerningPharmacyS = lazyMap(103000002);
    private static final Supplier<MapleMap> kpqS1S = lazyMap(103000800);
    private static final Supplier<BotNavigationGraph> kpqS1GraphS = lazyGraph(kpqS1S);
    private static final Supplier<MapleMap> mushroomShrineS = lazyMap(800000000);
    private static final Supplier<MapleMap> swamp1S = lazyMap(107000000);
    private static final Supplier<BotNavigationGraph> swamp1GraphS = lazyGraph(swamp1S);
    private static final Supplier<MapleMap> elNathS = lazyMap(211000000);
    private static final Supplier<BotNavigationGraph> elNathGraphS = lazyGraph(elNathS);

    private static MapleMap henesys() { return henesysS.get(); }
    private static BotNavigationGraph henesysGraph() { return henesysGraphS.get(); }
    private static MapleMap perion() { return perionS.get(); }
    private static BotNavigationGraph perionGraph() { return perionGraphS.get(); }
    private static MapleMap kerning() { return kerningS.get(); }
    private static BotNavigationGraph kerningGraph() { return kerningGraphS.get(); }
    private static MapleMap kerningPharmacy() { return kerningPharmacyS.get(); }
    private static MapleMap kpqS1() { return kpqS1S.get(); }
    private static BotNavigationGraph kpqS1Graph() { return kpqS1GraphS.get(); }
    private static MapleMap mushroomShrine() { return mushroomShrineS.get(); }
    private static MapleMap swamp1() { return swamp1S.get(); }
    private static BotNavigationGraph swamp1Graph() { return swamp1GraphS.get(); }
    private static MapleMap elNath() { return elNathS.get(); }
    private static BotNavigationGraph elNathGraph() { return elNathGraphS.get(); }

    private static Supplier<MapleMap> lazyMap(int mapId) {
        return memoize(() -> BotNavigationMapLoader.loadMapGeometry(mapId));
    }

    private static Supplier<BotNavigationGraph> lazyGraph(Supplier<MapleMap> map) {
        return memoize(() -> BotNavigationGraphProvider.rebuildGraph(map.get()));
    }

    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private volatile T value;
            @Override public T get() {
                T v = value;
                if (v != null) return v;
                synchronized (this) {
                    if (value == null) value = delegate.get();
                    return value;
                }
            }
        };
    }

    @BeforeAll
    static void initWzPath() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
    }

    @Test
    void shouldKeepHenesysLowerTownStreetInOneMergedRegion() {
        int firstRegionId = henesysGraph().findRegionId(henesys(), new Point(990, 334));
        int secondRegionId = henesysGraph().findRegionId(henesys(), new Point(1080, 334));

        assertEquals(firstRegionId, secondRegionId);
        assertTrue(firstRegionId > 0);
    }

    @Test
    void shouldGenerateDirectHenesysJumpEdgeFromBelowToFoothold315() {
        Point start = new Point(1080, 334);
        Point target = new Point(1275, 275);
        int targetRegionId = henesysGraph().findRegionId(henesys(), target);
        BotNavigationGraph.Edge edge = findPath(henesysGraph(), henesys(), start, target).getFirst();

        assertNotNull(edge);
        assertEquals(BotNavigationGraph.EdgeType.JUMP, edge.type);
        assertTrue(edge.containsLaunchX(start.x));
        assertEquals(targetRegionId, edge.toRegionId);
        assertJumpEdgeLandsInRegion(henesysGraph(), henesys(), edge, targetRegionId);
    }

    /**
     * Benchmark from real-client packet captures (user-performed trick jumps at speed100/
     * jump100, no snowshoes — logs/monitored-packets-elnath-tricky-jumps-spd100v2.log):
     * the El Nath icy foothold chain 171 > 262 > 264 > 267 > 277 > 278 > 279 is traversable
     * with consecutive JUMP edges. The three leftward icy hops only become stable with the
     * packet-true landing rule (touchdown halves carried momentum, so the post-landing brake
     * stops on the narrow ledges) and need no runway: a directional jump launch snaps to
     * ±walkSpeed regardless of ground speed.
     */
    @Test
    void shouldChainElNathTrickyJumpFootholdsWithConsecutiveJumpEdges() {
        int[] chain = {171, 262, 264, 267, 277, 278, 279};
        for (int i = 0; i + 1 < chain.length; i++) {
            int fromRegionId = elNathGraph().regionIdByFootholdId.getOrDefault(chain[i], -1);
            int toRegionId = elNathGraph().regionIdByFootholdId.getOrDefault(chain[i + 1], -1);
            assertTrue(fromRegionId >= 0 && toRegionId >= 0,
                    "chain footholds must be in the graph: " + chain[i] + " -> " + chain[i + 1]);
            boolean hasJump = elNathGraph().getOutgoing(fromRegionId).stream()
                    .anyMatch(e -> e.toRegionId == toRegionId
                            && e.type == BotNavigationGraph.EdgeType.JUMP);
            assertTrue(hasJump, "missing JUMP edge for icy hop fh" + chain[i] + " -> fh" + chain[i + 1]);
        }
    }

    /** Same capture session: foothold 258 (x 351..366, y -28) is reachable from BELOW by
     *  jumping (user-verified in the real client at speed100/jump100, no snowshoes). */
    @Test
    void shouldReachElNathFoothold258FromBelowByJumping() {
        int targetRegionId = elNathGraph().regionIdByFootholdId.getOrDefault(258, -1);
        assertTrue(targetRegionId >= 0, "foothold 258 must be in the graph");
        boolean reachableFromBelow = elNathGraph().regionsById.values().stream()
                .flatMap(region -> elNathGraph().getOutgoing(region.id).stream())
                .anyMatch(e -> e.toRegionId == targetRegionId
                        && e.type == BotNavigationGraph.EdgeType.JUMP
                        && e.startPoint.y > -28);
        assertTrue(reachableFromBelow, "foothold 258 must have a JUMP edge launched from below it");
    }

    @Test
    void shouldFindSingleJumpPathFromHenesysStreetToUpperPlatform() {
        Point start = new Point(1080, 334);
        Point target = new Point(1275, 275);
        int targetRegionId = henesysGraph().findRegionId(henesys(), target);
        List<BotNavigationGraph.Edge> path = findPath(henesysGraph(), henesys(), start, target);

        assertEquals(1, path.size());
        assertEquals(BotNavigationGraph.EdgeType.JUMP, path.getFirst().type);
        assertTrue(path.getFirst().containsLaunchX(start.x));
        assertEquals(targetRegionId, path.getFirst().toRegionId);
        assertJumpEdgeLandsInRegion(henesysGraph(), henesys(), path.getFirst(), targetRegionId);
    }

    @Test
    void shouldFindSingleJumpPathFromHenesysStreetToLeftUpperPlatform() {
        int targetRegionId = henesysGraph().findRegionId(henesys(), new Point(938, 274));
        List<BotNavigationGraph.Edge> path = findPath(henesysGraph(), henesys(), new Point(990, 334), new Point(938, 274));

        assertEquals(1, path.size());
        assertEquals(BotNavigationGraph.EdgeType.JUMP, path.getFirst().type);
        assertEquals(targetRegionId, path.getFirst().toRegionId);
        assertJumpEdgeLandsInRegion(henesysGraph(), henesys(), path.getFirst(), targetRegionId);
    }

    @Test
    void shouldNotLaunchHenesysLeftPlatformJumpFromWallBlockedBoundary() {
        Point blockedStart = new Point(939, 334);
        int blockedStartRegionId = henesysGraph().findRegionId(henesys(), blockedStart);
        List<BotNavigationGraph.Edge> path = findPath(henesysGraph(), henesys(), blockedStart, new Point(420, 274));

        assertFalse(path.isEmpty());
        assertEquals(BotNavigationGraph.EdgeType.JUMP, path.getFirst().type);
        assertFalse(path.getFirst().containsLaunchX(blockedStart.x),
                "wall-blocked boundary position should be outside the jump launch window");

        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(
                henesys(), blockedStart, path.getFirst().launchStepX, henesysGraph().movementProfile);
        assertNotNull(landing);
        assertEquals(blockedStartRegionId,
                henesysGraph().regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1),
                "jumping from the blocked boundary should not be treated as a valid upper-platform launch");
    }

    @Test
    void shouldGenerateLocalHenesysJumpEdgesNearRightWall() {
        int lowerRegionId = henesysGraph().findRegionId(henesys(), new Point(3711, 454));
        int middleRegionId = henesysGraph().findRegionId(henesys(), new Point(3532, 394));
        int upperRegionId = henesysGraph().findRegionId(henesys(), new Point(3352, 334));

        assertHasHenesysJumpEdge(lowerRegionId, middleRegionId);
        assertHasHenesysJumpEdge(middleRegionId, upperRegionId);
    }

    @Test
    void shouldGenerateKpqRopeTransferPathToRightUpperPlatform() {
        int leftRopeRegionId = kpqS1Graph().findRopeRegionId(new Point(-437, -892));
        int rightRopeRegionId = kpqS1Graph().findRopeRegionId(new Point(-337, -1000));
        int targetRegionId = kpqS1Graph().findRegionId(kpqS1(), new Point(-86, -897));

        assertTrue(leftRopeRegionId > 0);
        assertTrue(rightRopeRegionId > 0);
        assertTrue(targetRegionId > 0);
        assertTrue(kpqS1Graph().getOutgoing(leftRopeRegionId).stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.CLIMB
                                && edge.toRegionId == rightRopeRegionId
                                && edge.launchStepX > 0),
                "Expected rope-to-rope transfer from the left KPQ rope to the adjacent right rope");

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(kpqS1Graph(), kpqS1(),
                new Point(-437, -892), leftRopeRegionId, targetRegionId, new Point(-86, -897));

        assertFalse(path.isEmpty(), "Left KPQ rope should route to the right upper platform");
        assertEquals(targetRegionId, path.getLast().toRegionId);
    }

    @Test
    void shouldResolveOwnerToUpperPlatformWhenPositionHasSubpixelRounding() {
        // Regression: pathlog-SLASH-2026-04-02T125933 — owner at (2596,1696), bot at (2573,1935),
        // both resolved to region 187. findGroundFoothold returned the lower foothold because the
        // sloped upper foothold's interpolated Y rounds to 1px above the stored position, causing
        // findBelow to skip it. Must resolve to different regions.
        int ownerRegionId = perionGraph().findRegionId(perion(), new Point(2596, 1696));
        int botRegionId = perionGraph().findRegionId(perion(), new Point(2573, 1935));

        assertTrue(ownerRegionId > 0, "Owner position (2596,1696) must resolve to a valid region");
        assertTrue(botRegionId > 0, "Bot position (2573,1935) must resolve to a valid region");
        assertNotEquals(ownerRegionId, botRegionId, "Owner on upper platform and bot on lower platform must be in different regions");
    }

    @Test
    void shouldCaptureGraphBuildReportForRebuild() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(kpqS1());
        BotNavigationGraphProvider.GraphBuildReport report = BotNavigationGraphProvider.getLastBuildReport(kpqS1().getId());

        assertNotNull(report);
        assertEquals(kpqS1().getId(), report.mapId);
        assertEquals(graph.regions.size(), report.regionCount);
        assertTrue(report.totalBuildNs > 0);
        assertTrue(report.totalEdgeCount > 0);
    }

    @Test
    void shouldPrecomputeLaunchWindowForKerningConstructionSlopeJump() {
        List<BotNavigationGraph.Edge> path = findPath(kpqS1Graph(), kpqS1(),
                new Point(449, 113), new Point(1, -341));

        assertFalse(path.isEmpty());
        assertEquals(BotNavigationGraph.EdgeType.JUMP, path.getFirst().type);
        assertTrue(path.getFirst().launchMinX < path.getFirst().launchMaxX);

        int fromRegionId = path.getFirst().fromRegionId;
        int toRegionId = path.getFirst().toRegionId;
        BotNavigationGraph.Edge edgeContaining523 = kpqS1Graph().getOutgoing(fromRegionId).stream()
                .filter(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                        && edge.toRegionId == toRegionId
                        && edge.containsLaunchX(523))
                .findFirst()
                .orElse(null);
        assertNotNull(edgeContaining523,
                "The slope-platform jump should expose a real launch interval around the valid takeoff point");
        assertFalse(edgeContaining523.containsLaunchX(edgeContaining523.launchMinX - 1),
                "launch windows should reject positions just outside the left boundary");
        assertFalse(edgeContaining523.containsLaunchX(edgeContaining523.launchMaxX + 1),
                "launch windows should reject positions just outside the right boundary");
    }

    @Test
    void shouldKeepConnectedPivotFootholdsInOneWalkRegion() {
        MapleMap map = connectedPivotMap(910000201);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);

        int leftRegionId = graph.findRegionId(map, new Point(-508, -421));
        int rightRegionId = graph.findRegionId(map, new Point(-464, -422));

        assertTrue(leftRegionId > 0);
        assertTrue(rightRegionId > 0);
        assertEquals(leftRegionId, rightRegionId);
    }

    @Test
    void shouldTreatConnectedPivotFootholdTraversalAsSameRegion() {
        MapleMap map = connectedPivotMap(910000202);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);

        List<BotNavigationGraph.Edge> path = findPath(graph, map, new Point(-508, -421), new Point(-390, -400));

        assertTrue(path.isEmpty());
    }

    @Test
    void shouldFindSyntheticPathFromLowerPlatformToUpperSlopeViaRope() {
        MapleMap map = ropeToUpperPlatformMap(910000203);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        List<BotNavigationGraph.Edge> path = findPath(graph, map, new Point(50, 100), new Point(50, 0));
        int targetRegionId = graph.findRegionId(map, new Point(50, 0));

        assertFalse(path.isEmpty());
        assertEquals(targetRegionId, path.getLast().toRegionId);
        assertTrue(path.stream().anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.CLIMB));
    }

    @Test
    void shouldGenerateSyntheticJumpEdgeFromLowerPlatformToPlatformAbove() {
        MapleMap map = twoPlatformJumpMap(910000204);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Point launch = new Point(40, 100);
        BotNavigationGraph.Edge edge = findNearbyEdge(graph, map, launch,
                BotNavigationGraph.EdgeType.JUMP, 40, 16);

        assertNotNull(edge);
        assertTrue(edge.containsLaunchX(launch.x));
        assertNotEquals(edge.fromRegionId, edge.toRegionId);
        assertJumpEdgeLandsInRegion(graph, map, edge, edge.toRegionId);
    }

    @Test
    void shouldGenerateSyntheticClimbEdgesForRopes() {
        MapleMap map = ropeToUpperPlatformMap(910000205);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int climbEdgeCount = countEdges(graph, BotNavigationGraph.EdgeType.CLIMB);

        assertTrue(climbEdgeCount > 0, "synthetic rope map should expose climb edges");
    }

    @Test
    void shouldGenerateRopeToRopeClimbEdgeWhenJumpArcCanCatchTargetRope() {
        MapleMap map = createEmptyTestMap(910000002);
        Rope sourceRope = new Rope(0, 100, 200, false);
        Rope targetRope = new Rope(48, 140, 150, false);
        map.addRope(sourceRope);
        map.addRope(targetRope);

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        BotNavigationGraph.Edge ropeTransfer = findFirstRopeToRopeClimbEdge(graph);

        assertNotNull(ropeTransfer);
        assertEquals(1, ropeTransfer.fromRegionId);
        assertEquals(2, ropeTransfer.toRegionId);
        assertEquals(BotPhysicsEngine.walkStep(map), ropeTransfer.launchStepX);
        assertEquals(targetRope.x(), ropeTransfer.endPoint.x);
        assertTrue(ropeTransfer.endPoint.y >= targetRope.topY() && ropeTransfer.endPoint.y <= targetRope.bottomY());
    }

    @Test
    void shouldPreferSyntheticLocalJumpChainOverDetour() {
        MapleMap map = jumpChainMap(910000206);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        List<BotNavigationGraph.Edge> path = findPath(graph, map, new Point(40, 100), new Point(190, 0));
        int targetRegionId = graph.findRegionId(map, new Point(190, 0));

        assertFalse(path.isEmpty());
        assertTrue(path.stream().allMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP));
        assertTrue(path.getFirst().containsLaunchX(40));
        assertEquals(targetRegionId, path.getLast().toRegionId);
    }

    @Test
    void shouldPreferSyntheticLedgeDropsOverDownJumpsWhenDroppingStraightDown() {
        MapleMap map = ledgeDropMap(910000207);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        List<BotNavigationGraph.Edge> path = findPath(graph, map, new Point(95, 0), new Point(120, 120));

        assertEquals(BotNavigationGraph.EdgeType.DROP, path.getFirst().type);
        assertTrue(path.stream().allMatch(edge -> edge.type == BotNavigationGraph.EdgeType.DROP
                || edge.type == BotNavigationGraph.EdgeType.WALK),
                "dropping to a lower platform should stay on drop/walk edges even if the exact drop style changes");
    }

    @Test
    void shouldRequireStraightDropExecutionInsideItsAuthoredLaunchWindow() {
        BotNavigationGraph.Edge dropEdge = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.DROP,
                new Point(100, 100), new Point(100, 160),
                100, 114, 0, 0, 0, 0, 0, 100
        );

        assertTrue(BotNavigationManager.canExecuteDropFromCurrentPosition(
                null, null, new Point(100, 100), dropEdge));
        assertTrue(BotNavigationManager.canExecuteDropFromCurrentPosition(
                null, null, new Point(114, 100), dropEdge));
        assertFalse(BotNavigationManager.canExecuteDropFromCurrentPosition(
                null, null, new Point(115, 100), dropEdge));
    }

    @Test
    void shouldGenerateLaunchWindowForStraightDownJumpEdges() {
        MapleMap map = createEmptyTestMap(910000211);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 0), new Point(200, 0), 1));
        footholds.insert(new Foothold(new Point(40, 120), new Point(160, 120), 2));
        map.setFootholds(footholds);

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        BotNavigationGraph.Edge dropEdge = findFirstStraightDropEdge(graph);

        assertNotNull(dropEdge, "fixture should produce a straight down-jump edge");
        assertTrue(dropEdge.launchMinX < dropEdge.launchMaxX,
                "straight down-jump edges should carry an authored launch window");
        assertTrue(dropEdge.containsLaunchX((dropEdge.launchMinX + dropEdge.launchMaxX) / 2));
        assertFalse(dropEdge.containsLaunchX(dropEdge.launchMinX - 1));
        assertFalse(dropEdge.containsLaunchX(dropEdge.launchMaxX + 1));
    }

    @Test
    void shouldCapStraightDownJumpLaunchWindowAroundSeedAnchor() {
        MapleMap map = createEmptyTestMap(910000212);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 0), new Point(300, 0), 1));
        footholds.insert(new Foothold(new Point(0, 120), new Point(300, 120), 2));
        map.setFootholds(footholds);

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        BotNavigationGraph.Edge dropEdge = findFirstStraightDropEdge(graph);

        assertNotNull(dropEdge, "fixture should produce a straight down-jump edge");
        assertTrue(dropEdge.launchMaxX - dropEdge.launchMinX <= 40,
                "straight down-jump launch windows should be capped to the graphgen prelaunch span (2 * DOWN_JUMP_PRELAUNCH_WINDOW_PX)");
    }

    @Test
    void shouldTreatWalkOffDropsAsDirectionalMovementInsteadOfExecutableAnchors() {
        MapleMap map = ledgeDropMap(910000208);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        LedgeDropCase dropCase = findLedgeDropCaseWithWalkableEarlyStart(graph, map);

        assertNotNull(dropCase, "Expected at least one ledge drop with a still-walkable early start");
        assertTrue(BotPhysicsEngine.canWalkGroundStep(map, dropCase.earlyStart(), dropCase.edge().launchStepX));
        assertFalse(BotNavigationManager.canExecuteDropFromCurrentPosition(
                graph, map, dropCase.edge().startPoint, dropCase.edge()));
        assertFalse(BotNavigationManager.canExecuteDropFromCurrentPosition(
                graph, map, dropCase.earlyStart(), dropCase.edge()));
    }

    @Test
    void shouldAllowJumpExecutionFromAlternativeStartWhenLandingRegionMatches() {
        AlternativeJumpCase jumpCase = findJumpCaseWithAlternativeStart(henesysGraph(), henesys());

        assertNotNull(jumpCase, "Expected at least one jump edge that stays valid from an alternate same-region start point");
        assertNotEquals(jumpCase.edge().startPoint.x, jumpCase.alternativeStart().x);
        assertTrue(BotNavigationManager.canExecuteJumpFromCurrentPosition(
                henesysGraph(), henesys(), jumpCase.alternativeStart(), jumpCase.edge()));
    }

    @Test
    void shouldDiscardCommittedRopeEntryEdgeAfterBotHasAttachedToRope() {
        MapleMap map = topRopeEntryMap(910000209);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        RopeEntryReuseCase reuseCase = findRopeEntryReuseCase(graph, map);

        assertNotNull(reuseCase, "Expected a rope-entry edge whose top attachment point resolves to a non-rope region");

        Character bot = mockBot(reuseCase.botPosition(), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = reuseCase.rope();
        entry.navEdge = reuseCase.edge();
        entry.navTargetRegionId = graph.findRegionId(map, reuseCase.rawTarget());

        BotNavigationManager.NavigationDirective directive =
                BotNavigationManager.resolveTarget(entry, reuseCase.rawTarget(), false);

        assertFalse(directive.consumedTick);
        assertEquals(reuseCase.rawTarget(), directive.targetPos);
        assertNull(entry.navEdge);
    }

    @Test
    void shouldResolveCurrentRegionFromRopeWhileBotIsClimbing() {
        MapleMap map = topRopeEntryMap(910000210);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        RopeEntryReuseCase reuseCase = findRopeEntryReuseCase(graph, map);

        assertNotNull(reuseCase, "Expected a rope-entry edge whose attach point still resolves to ground");
        assertNotEquals(reuseCase.edge().toRegionId, graph.findRegionId(map, reuseCase.botPosition()));

        BotEntry entry = new BotEntry(mockBot(reuseCase.botPosition(), map), null, null);
        entry.climbing = true;
        entry.climbRope = reuseCase.rope();

        assertEquals(reuseCase.edge().toRegionId,
                BotNavigationManager.resolveCurrentRegionId(graph, entry, map, reuseCase.botPosition()));
    }

    @Test
    void shouldResolveFollowTargetFromOwnerRopeRegionWhileOwnerIsHanging() {
        MapleMap map = topRopeEntryMap(910000211);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        RopeEntryReuseCase reuseCase = findRopeEntryReuseCase(graph, map);

        assertNotNull(reuseCase, "Expected a rope-entry edge whose rope point still resolves to ground");
        assertNotEquals(reuseCase.edge().toRegionId, graph.findRegionId(map, reuseCase.botPosition()));

        Character owner = mockBot(reuseCase.botPosition(), map);
        when(owner.getStance()).thenReturn(CharacterStance.ROPE_STANCE);

        BotEntry entry = new BotEntry(mockBot(reuseCase.rawTarget(), map), owner, null);
        entry.following = true;

        assertEquals(reuseCase.edge().toRegionId,
                BotNavigationManager.resolveTargetRegionId(graph, entry, map, owner.getPosition()));
    }

    @Test
    void bumpyKerningSwampIsSameRegion() {
        assertEquals(swamp1Graph().findRegionId(swamp1(), new Point(1378, 123)), swamp1Graph().findRegionId(swamp1(), new Point(-1723, 118)));
    }

    @Test
    void shouldPreferDirectSyntheticJumpWithNegativeLaunchWindow() {
        MapleMap map = negativeJumpMap(910000212);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Point botPosition = new Point(-60, 100);
        Point ownerPosition = new Point(-210, 40);
        int botRegionId = graph.findRegionId(map, botPosition);
        int ownerRegionId = graph.findRegionId(map, ownerPosition);

        BotNavigationGraph.Edge directJump = graph.getOutgoing(botRegionId).stream()
                .filter(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP)
                .filter(edge -> edge.toRegionId == ownerRegionId)
                .filter(edge -> edge.containsLaunchX(botPosition.x))
                .findFirst()
                .orElse(null);

        assertNotNull(directJump, "Expected direct synthetic jump edge at the logged bot X");
        assertTrue(directJump.launchMinX < 0, "jump launch window should keep valid negative X coordinates");
        assertJumpEdgeLandsInRegion(graph, map, directJump, ownerRegionId);

        List<BotNavigationGraph.Edge> path = findPath(graph, map, botPosition, ownerPosition);

        assertEquals(1, path.size(), "expected direct jump path instead of detour");
        assertEquals(BotNavigationGraph.EdgeType.JUMP, path.getFirst().type);
        assertEquals(ownerRegionId, path.getFirst().toRegionId);
    }

    @Test
    void shouldTrimJumpWindowAtInvalidInteriorWallBoundary() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000202);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Point badLaunch = new Point(-1422, -642);
        int fromRegionId = graph.findRegionId(map, badLaunch);
        int targetRegionId = graph.findRegionId(map, new Point(-1375, -664));

        assertEquals(36, fromRegionId);
        assertEquals(32, targetRegionId);

        BotPhysicsEngine.JumpLanding badLanding = BotPhysicsEngine.simulateJumpLanding(
                map, badLaunch, BotPhysicsEngine.walkStep(map), graph.movementProfile);
        assertNotNull(badLanding);
        assertEquals(fromRegionId, graph.regionIdByFootholdId.getOrDefault(badLanding.foothold().getId(), -1),
                "logged bad launch should bounce back to the original region");

        assertTrue(graph.getOutgoing(fromRegionId).stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == targetRegionId
                                && edge.containsLaunchX(-1427)),
                "nearby valid launch point should still route to the target platform");
        assertFalse(graph.getOutgoing(fromRegionId).stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == targetRegionId
                                && edge.containsLaunchX(badLaunch.x)),
                "jump launch windows must not include X positions that no longer land in the target region");
    }

    @Test
    void shouldTrimJumpWindowThatFallsOffLandingPlatformWithMomentum() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000202);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int stepX = BotPhysicsEngine.walkStep(map, graph.movementProfile);
        Point badLaunch = new Point(-1881, -1341);
        Point stableLaunch = new Point(-1898, -1341);
        int fromRegionId = graph.findRegionId(map, badLaunch);
        int targetRegionId = graph.findRegionId(map, new Point(-1841, -1379));

        assertEquals(8, fromRegionId);
        assertEquals(7, targetRegionId);

        BotPhysicsEngine.PostLandingJump unstableLanding =
                BotPhysicsEngine.simulateJumpLandingWithPostLandingTicks(map, badLaunch, stepX, graph.movementProfile, 3);
        BotPhysicsEngine.PostLandingJump stableLanding =
                BotPhysicsEngine.simulateJumpLandingWithPostLandingTicks(map, stableLaunch, stepX, graph.movementProfile, 3);

        assertNotNull(unstableLanding);
        assertTrue(unstableLanding.lostGround(),
                "logged right-edge launch should fall off region 7 after landing momentum is applied");
        assertNotNull(stableLanding);
        assertFalse(stableLanding.lostGround(),
                "nearby deeper launch should stay on the destination platform after landing momentum");

        assertTrue(graph.getOutgoing(fromRegionId).stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == targetRegionId
                                && edge.containsLaunchX(stableLaunch.x)),
                "stable launches should still route to the target platform");
        assertFalse(graph.getOutgoing(fromRegionId).stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == targetRegionId
                                && edge.containsLaunchX(badLaunch.x)),
                "jump launch windows must exclude launches that immediately walk off the landing platform");
    }

    @Test
    void shouldNotGenerateKerningPharmacyJumpIntoBlockedUnderside() {
        BotMovementProfile profile = new BotMovementProfile(105, 100);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(kerningPharmacy(), profile);
        int fromRegionId = graph.regionIdByFootholdId.getOrDefault(17, -1);
        int targetRegionId = graph.regionIdByFootholdId.getOrDefault(1, -1);
        Point badLaunch = new Point(-294, -45);

        assertEquals(14, fromRegionId);
        assertEquals(9, targetRegionId);

        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateJumpLanding(kerningPharmacy(), badLaunch, 0, profile);
        assertNotNull(landing);
        assertEquals(fromRegionId, graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1),
                "blocked underside jump should fall back onto the source platform");

        assertFalse(graph.getOutgoing(fromRegionId).stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == targetRegionId
                                && edge.containsLaunchX(badLaunch.x)),
                "jump window must exclude launches that hit the pharmacy box underside");
    }

    @Test
    void shouldNotGenerateMushroomShrineJumpIntoDoughnutUnderside() {
        BotMovementProfile profile = new BotMovementProfile(110, 100);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(mushroomShrine(), profile);
        int fromRegionId = graph.findRegionId(mushroomShrine(), new Point(3146, 95));
        int targetRegionId = graph.regionIdByFootholdId.getOrDefault(264, -1);
        Point badLaunch = new Point(3145, 95);
        int stepX = BotPhysicsEngine.walkStep(mushroomShrine(), profile);

        assertEquals(55, fromRegionId);
        assertEquals(48, targetRegionId);

        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateJumpLanding(mushroomShrine(), badLaunch, stepX, profile);
        assertNotNull(landing);
        assertEquals(fromRegionId, graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1),
                "doughnut underside jump should bounce back to the outer floor");

        assertFalse(graph.getOutgoing(fromRegionId).stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == targetRegionId
                                && edge.containsLaunchX(badLaunch.x)),
                "jump window must exclude launches that hit the doughnut underside");
    }

    @Test
    void shouldNotPathThroughRopeOscillationLoop() {
        // Regression: pathlog-Leroy-2026-05-07T081138 — bot grounded on a foothold (r=31)
        // whose surface sits 2 px above a rope-top got stuck oscillating onto/off the rope
        // because rope-grab CLIMB edges had cost=0. A* tied the direct PORTAL+DROP path with
        // a useless CLIMB-onto-rope→CLIMB-off-rope+PORTAL+DROP detour and could pick the loop
        // variant. With non-zero cost on the snap-grab/top-step CLIMB edges, the direct path
        // is strictly cheaper.
        BotNavigationGraph graph = kerningGraph();
        MapleMap map = kerning();
        Point groundedAtRopeColumn = new Point(1505, -607);
        Point goalOnLowerPlatform = new Point(1640, -752);
        int fromRegionId = graph.findRegionId(map, groundedAtRopeColumn);
        int targetRegionId = graph.findRegionId(map, goalOnLowerPlatform);
        assertTrue(fromRegionId > 0);
        assertTrue(targetRegionId > 0);

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                graph, map, groundedAtRopeColumn, fromRegionId, targetRegionId, goalOnLowerPlatform);
        assertFalse(path.isEmpty(), "expected a path from r" + fromRegionId + " to r" + targetRegionId);

        BotNavigationGraph.Edge first = path.getFirst();
        BotNavigationGraph.Region toRegion = graph.getRegion(first.toRegionId);
        assertFalse(first.type == BotNavigationGraph.EdgeType.CLIMB && toRegion != null && toRegion.isRopeRegion,
                "Path from a foothold to a non-rope target must not start by grabbing a rope; got " + first.type
                        + " r" + first.fromRegionId + "->r" + first.toRegionId);
    }

    @Test
    void shouldAssignNonZeroCostToSnapClimbEdges() {
        BotNavigationGraph graph = kerningGraph();
        long zeroCostSnapClimbEdges = graph.regions.stream()
                .filter(region -> !region.isRopeRegion)
                .flatMap(region -> graph.getOutgoing(region.id).stream())
                .filter(edge -> edge.type == BotNavigationGraph.EdgeType.CLIMB)
                .filter(edge -> edge.cost == 0)
                .count();
        assertEquals(0, zeroCostSnapClimbEdges,
                "Foothold-to-rope CLIMB edges (and rope-top step-off) must carry non-zero cost so A*"
                        + " never ties the direct path with a useless rope detour");
    }

    @Test
    void shouldDiscoverJumpEdgesAcrossWideIntermediatePlatform() {
        // Regression: pathlog-Clawer-2026-05-07T062632 — at base profile (100%) the bot at
        // (965,0) on r=114 had no direct JUMP edge to r=137 (owner foothold) and no JUMP edge
        // to the intermediate platform r=132 above r=137. A* fell back to a 4-edge climb
        // detour. Both jumps are physically achievable in-client; the missing edges were a
        // graph-generation gap caused by buildFeatureXsByRegionId only projecting endpoints
        // of platforms with width <= 64 down to the region below. r=132 is wider than 64px,
        // so its right edge at x≈886 was never seeded as a feature anchor on r=114, leaving
        // the narrow launch windows for both jumps with zero sampled anchors.
        BotNavigationGraph graph = kerningGraph();
        MapleMap map = kerning();

        Point launchPoint = new Point(965, 0);
        Point directLandingPoint = new Point(841, 6);
        Point intermediateLandingPoint = new Point(880, -30);

        int fromRegionId = graph.findRegionId(map, launchPoint);
        int directRegionId = graph.findRegionId(map, directLandingPoint);
        int intermediateRegionId = graph.findRegionId(map, intermediateLandingPoint);

        assertTrue(fromRegionId > 0, "launch region should resolve");
        assertTrue(directRegionId > 0, "direct landing region should resolve");
        assertTrue(intermediateRegionId > 0, "intermediate platform region should resolve");
        assertNotEquals(fromRegionId, directRegionId);
        assertNotEquals(fromRegionId, intermediateRegionId);
        assertNotEquals(directRegionId, intermediateRegionId);

        List<BotNavigationGraph.Edge> outgoing = graph.getOutgoing(fromRegionId);

        assertTrue(outgoing.stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == intermediateRegionId),
                "JUMP edge from r" + fromRegionId + " landing on intermediate platform r"
                        + intermediateRegionId + " must be discovered");

        assertTrue(outgoing.stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == directRegionId),
                "Direct JUMP edge from r" + fromRegionId + " over the intermediate platform to r"
                        + directRegionId + " must be discovered");
    }

    private static List<BotNavigationGraph.Edge> findPath(BotNavigationGraph graph,
                                                          MapleMap map,
                                                          Point start,
                                                          Point target) {
        int startRegionId = graph.findRegionId(map, start);
        int targetRegionId = graph.findRegionId(map, target);

        assertTrue(startRegionId > 0, "Missing graph region for start point " + start);
        assertTrue(targetRegionId > 0, "Missing graph region for target point " + target);

        return BotNavigationManager.findPath(graph, map, start, startRegionId, targetRegionId, target);
    }

    private static BotNavigationGraph.Edge findNearbyEdge(BotNavigationGraph graph,
                                                          MapleMap map,
                                                          Point point,
                                                          BotNavigationGraph.EdgeType type,
                                                          int maxDx,
                                                          int maxDy) {
        int regionId = graph.findRegionId(map, point);
        assertTrue(regionId > 0, "Missing graph region for point " + point);

        List<BotNavigationGraph.Edge> nearby = new ArrayList<>();
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(regionId)) {
            if (edge.type != type) {
                continue;
            }
            if (Math.abs(edge.startPoint.x - point.x) > maxDx || Math.abs(edge.startPoint.y - point.y) > maxDy) {
                continue;
            }
            nearby.add(edge);
        }

        assertFalse(nearby.isEmpty(), "Missing " + type + " edge near " + point);
        nearby.sort((left, right) -> {
            int leftDistance = Math.abs(left.startPoint.x - point.x) + Math.abs(left.startPoint.y - point.y);
            int rightDistance = Math.abs(right.startPoint.x - point.x) + Math.abs(right.startPoint.y - point.y);
            return Integer.compare(leftDistance, rightDistance);
        });
        return nearby.getFirst();
    }

    private static BotNavigationGraph.Edge findFirstRopeToRopeClimbEdge(BotNavigationGraph graph) {
        for (BotNavigationGraph.Region region : graph.regions) {
            if (!region.isRopeRegion) {
                continue;
            }
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.CLIMB) {
                    continue;
                }
                BotNavigationGraph.Region toRegion = graph.getRegion(edge.toRegionId);
                if (toRegion != null && toRegion.isRopeRegion) {
                    return edge;
                }
            }
        }
        return null;
    }

    private static int countEdges(BotNavigationGraph graph, BotNavigationGraph.EdgeType type) {
        int count = 0;
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type == type) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void assertHasHenesysJumpEdge(int fromRegionId, int toRegionId) {
        assertTrue(henesysGraph().getOutgoing(fromRegionId).stream()
                        .anyMatch(edge -> edge.type == BotNavigationGraph.EdgeType.JUMP
                                && edge.toRegionId == toRegionId),
                "Expected Henesys jump edge r" + fromRegionId + "->r" + toRegionId);
    }

    private static MapleMap createEmptyTestMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        map.setFootholds(new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000)));
        return map;
    }

    private static MapleMap connectedPivotMap(int mapId) {
        MapleMap map = createEmptyTestMap(mapId);
        Foothold left = new Foothold(new Point(-520, -421), new Point(-480, -421), 1);
        Foothold right = new Foothold(new Point(-480, -421), new Point(-380, -400), 2);
        left.setNext(right.getId());
        right.setPrev(left.getId());
        map.getFootholds().insert(left);
        map.getFootholds().insert(right);
        return map;
    }

    private static MapleMap ropeToUpperPlatformMap(int mapId) {
        MapleMap map = createEmptyTestMap(mapId);
        map.getFootholds().insert(new Foothold(new Point(0, 100), new Point(120, 100), 1));
        map.getFootholds().insert(new Foothold(new Point(0, 0), new Point(120, 0), 2));
        map.addRope(new Rope(50, 0, 100, false));
        return map;
    }

    private static MapleMap twoPlatformJumpMap(int mapId) {
        MapleMap map = createEmptyTestMap(mapId);
        map.getFootholds().insert(new Foothold(new Point(0, 100), new Point(120, 100), 1));
        map.getFootholds().insert(new Foothold(new Point(40, 40), new Point(200, 40), 2));
        return map;
    }

    private static MapleMap jumpChainMap(int mapId) {
        MapleMap map = createEmptyTestMap(mapId);
        map.getFootholds().insert(new Foothold(new Point(0, 100), new Point(80, 100), 1));
        map.getFootholds().insert(new Foothold(new Point(80, 50), new Point(160, 50), 2));
        map.getFootholds().insert(new Foothold(new Point(160, 0), new Point(240, 0), 3));
        return map;
    }

    private static MapleMap ledgeDropMap(int mapId) {
        MapleMap map = createEmptyTestMap(mapId);
        map.getFootholds().insert(new Foothold(new Point(0, 0), new Point(100, 0), 1));
        map.getFootholds().insert(new Foothold(new Point(100, 120), new Point(220, 120), 2));
        return map;
    }

    private static MapleMap topRopeEntryMap(int mapId) {
        MapleMap map = createEmptyTestMap(mapId);
        map.getFootholds().insert(new Foothold(new Point(80, 100), new Point(120, 100), 1));
        map.addRope(new Rope(100, 100, 200, false));
        return map;
    }

    private static MapleMap negativeJumpMap(int mapId) {
        MapleMap map = createEmptyTestMap(mapId);
        map.getFootholds().insert(new Foothold(new Point(-120, 100), new Point(-20, 100), 1));
        map.getFootholds().insert(new Foothold(new Point(-300, 40), new Point(20, 40), 2));
        return map;
    }

    private static LedgeDropCase findLedgeDropCaseWithWalkableEarlyStart(BotNavigationGraph graph, MapleMap map) {
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) {
                    continue;
                }

                Point earlyStart = findWalkableEarlyDropStart(graph, map, edge);
                if (earlyStart != null) {
                    return new LedgeDropCase(edge, earlyStart);
                }
            }
        }
        return null;
    }

    private static Point findWalkableEarlyDropStart(BotNavigationGraph graph,
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

        for (int delta = 1; delta <= 14; delta++) {
            int candidateX = edge.startPoint.x - (direction * delta);
            if (candidateX < region.minX || candidateX > region.maxX) {
                continue;
            }

            Point candidate = region.pointAt(candidateX);
            if (candidate == null || candidate.equals(edge.startPoint)) {
                continue;
            }
            if (graph.findRegionId(map, candidate) != edge.fromRegionId) {
                continue;
            }
            if (BotPhysicsEngine.canWalkGroundStep(map, candidate, edge.launchStepX)) {
                return candidate;
            }
        }
        return null;
    }

    private static AlternativeJumpCase findJumpCaseWithAlternativeStart(BotNavigationGraph graph, MapleMap map) {
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.JUMP) {
                    continue;
                }

                Point alternative = findAlternativeJumpStart(graph, map, edge);
                if (alternative != null) {
                    return new AlternativeJumpCase(edge, alternative);
                }
            }
        }
        return null;
    }

    private static Point findAlternativeJumpStart(BotNavigationGraph graph,
                                                  MapleMap map,
                                                  BotNavigationGraph.Edge edge) {
        BotNavigationGraph.Region region = graph.getRegion(edge.fromRegionId);
        if (region == null) {
            return null;
        }

        for (int x = region.minX; x <= region.maxX; x += 4) {
            Point start = region.pointAt(x);
            if (Math.abs(start.x - edge.startPoint.x) < 12) {
                continue;
            }

            BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map, start, edge.launchStepX);
            if (landing == null) {
                continue;
            }

            int landingRegionId = graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1);
            if (landingRegionId == edge.toRegionId) {
                return start;
            }
        }
        return null;
    }

    private static RopeEntryReuseCase findRopeEntryReuseCase(BotNavigationGraph graph, MapleMap map) {
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.CLIMB) {
                    continue;
                }

                BotNavigationGraph.Region from = graph.getRegion(edge.fromRegionId);
                BotNavigationGraph.Region to = graph.getRegion(edge.toRegionId);
                if (from == null || to == null || from.isRopeRegion || !to.isRopeRegion) {
                    continue;
                }

                Point botPosition = new Point(edge.endPoint);
                if (graph.findRegionId(map, botPosition) == edge.toRegionId) {
                    continue;
                }

                Point rawTarget = alternateTargetInRegion(from, edge.startPoint);
                if (rawTarget == null || graph.findRegionId(map, rawTarget) != from.id) {
                    continue;
                }

                Rope rope = BotNavigationGraphProvider.findRopeFromRegion(map, to);
                if (rope != null) {
                    return new RopeEntryReuseCase(edge, rope, botPosition, rawTarget);
                }
            }
        }
        return null;
    }

    private static Point alternateTargetInRegion(BotNavigationGraph.Region region, Point excluded) {
        Point[] candidates = new Point[]{region.centerPoint(), region.leftPoint(), region.rightPoint()};
        for (Point candidate : candidates) {
            if (!candidate.equals(excluded)) {
                return candidate;
            }
        }
        return null;
    }

    private static BotNavigationGraph.Edge findFirstStraightDropEdge(BotNavigationGraph graph) {
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type == BotNavigationGraph.EdgeType.DROP && edge.launchStepX == 0) {
                    return edge;
                }
            }
        }
        return null;
    }

    private static Character mockBot(Point startPosition, MapleMap map) {
        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        AtomicInteger stance = new AtomicInteger(CharacterStance.STAND_RIGHT_STANCE);

        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getHp()).thenReturn(100);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getStance()).thenAnswer(invocation -> stance.get());
        doAnswer(invocation -> {
            stance.set(invocation.getArgument(0));
            return null;
        }).when(bot).setStance(anyInt());
        return bot;
    }

    private static void assertJumpEdgeLandsInRegion(BotNavigationGraph graph,
                                                    MapleMap map,
                                                    BotNavigationGraph.Edge edge,
                                                    int expectedRegionId) {
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(
                map, edge.startPoint, edge.launchStepX, graph.movementProfile);

        assertNotNull(landing, "jump edge should reproduce a landing when simulated from its authored anchor");
        assertEquals(expectedRegionId,
                graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1),
                "jump edge should land in the expected destination region");
    }

    private record LedgeDropCase(BotNavigationGraph.Edge edge, Point earlyStart) {
    }

    private record AlternativeJumpCase(BotNavigationGraph.Edge edge, Point alternativeStart) {
    }

    private record RopeEntryReuseCase(BotNavigationGraph.Edge edge, Rope rope, Point botPosition, Point rawTarget) {
    }
}
