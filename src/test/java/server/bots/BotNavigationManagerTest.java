package server.bots;

import client.Character;
import constants.game.CharacterStance;
import org.junit.jupiter.api.BeforeAll;
import server.maps.MapleMap;
import server.maps.Foothold;
import server.maps.Rope;
import org.junit.jupiter.api.Test;
import server.maps.FootholdTree;

import java.awt.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotNavigationManagerTest {
    private static volatile MapleMap kerningCached;

    private static MapleMap kerning() {
        MapleMap v = kerningCached;
        if (v != null) return v;
        synchronized (BotNavigationManagerTest.class) {
            if (kerningCached == null) {
                kerningCached = BotNavigationMapLoader.loadMapGeometry(103000000);
            }
            return kerningCached;
        }
    }

    @BeforeAll
    static void loadMaps() {
        // Avoid loading big maps; create a functionally equivalent synthetic test when possible.
        // Map fixtures are lazy-loaded on first use.
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
    }

    @Test
    void emptyCommittedRouteCoversNearbySameRegionTargetOnly() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.committedRoute = List.of();
        entry.committedRouteTargetRegionId = 7;
        entry.committedRouteTargetPos = new Point(100, 100);

        assertTrue(BotNavigationManager.committedRouteStillCoversTarget(
                entry, 7, 7, new Point(180, 100)));
        assertFalse(BotNavigationManager.committedRouteStillCoversTarget(
                entry, 7, 7, new Point(260, 100)));
        assertFalse(BotNavigationManager.committedRouteStillCoversTarget(
                entry, 4, 7, new Point(100, 100)));
        assertFalse(BotNavigationManager.committedRouteStillCoversTarget(
                entry, 7, 8, new Point(100, 100)));
    }

    @Test
    void shouldPromoteFirstActionableEdgePastLeadingZeroDistanceWalks() {
        BotNavigationGraph.Edge collapsed = BotNavigationManager.collapseLeadingWalkEdges(List.of(
                new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.WALK,
                        new Point(528, -914), new Point(528, -914),
                        0, 0, 0, 0, 0, 50),
                new BotNavigationGraph.Edge(2, 3, BotNavigationGraph.EdgeType.WALK,
                        new Point(528, -914), new Point(528, -914),
                        0, 0, 0, 0, 0, 50),
                new BotNavigationGraph.Edge(3, 4, BotNavigationGraph.EdgeType.JUMP,
                        new Point(540, -914), new Point(612, -980),
                        9, 0, 0, 0, 0, 300)
        ));

        assertNotNull(collapsed);
        assertEquals(BotNavigationGraph.EdgeType.JUMP, collapsed.type);
        assertEquals(1, collapsed.fromRegionId);
        assertEquals(4, collapsed.toRegionId);
        assertEquals(new Point(540, -914), collapsed.startPoint);
        assertEquals(new Point(612, -980), collapsed.endPoint);
        assertEquals(400, collapsed.cost);
    }

    @Test
    void shouldKeepFirstRealWalkInsteadOfCollapsingPastLaterZeroDistanceHandoff() {
        BotNavigationGraph.Edge collapsed = BotNavigationManager.collapseLeadingWalkEdges(List.of(
                new BotNavigationGraph.Edge(24, 22, BotNavigationGraph.EdgeType.WALK,
                        new Point(-947, 153), new Point(-751, 142),
                        0, 0, 0, 0, 0, 120),
                new BotNavigationGraph.Edge(22, 20, BotNavigationGraph.EdgeType.WALK,
                        new Point(-751, 142), new Point(-751, 142),
                        0, 0, 0, 0, 0, 50),
                new BotNavigationGraph.Edge(20, 27, BotNavigationGraph.EdgeType.CLIMB,
                        new Point(-437, 121), new Point(-437, 84),
                        0, 0, -437, 84, 121, 250)
        ));

        assertNotNull(collapsed);
        assertEquals(BotNavigationGraph.EdgeType.WALK, collapsed.type);
        assertEquals(24, collapsed.fromRegionId);
        assertEquals(22, collapsed.toRegionId);
        assertEquals(new Point(-947, 153), collapsed.startPoint);
        assertEquals(new Point(-751, 142), collapsed.endPoint);
        assertEquals(120, collapsed.cost);
    }

    @Test
    void shouldDropLeadingWalkChainWhenItConsumesNoMovement() {
        BotNavigationGraph.Edge collapsed = BotNavigationManager.collapseLeadingWalkEdges(List.of(
                new BotNavigationGraph.Edge(181, 184, BotNavigationGraph.EdgeType.WALK,
                        new Point(565, -2135), new Point(565, -2135),
                        0, 0, 0, 0, 0, 50),
                new BotNavigationGraph.Edge(184, 190, BotNavigationGraph.EdgeType.WALK,
                        new Point(565, -2135), new Point(565, -2135),
                        0, 0, 0, 0, 0, 50)
        ));

        assertNull(collapsed);
    }

    @Test
    void shouldOnlySnapZeroStepClimbExitAtRopeTop() {
        Rope rope = new Rope(675, 143, 215, false);
        BotNavigationGraph.Edge topExit = new BotNavigationGraph.Edge(
                49, 45, BotNavigationGraph.EdgeType.CLIMB,
                new Point(675, 143), new Point(675, 141),
                0, 0, 675, 143, 215, 250
        );
        BotNavigationGraph.Edge bottomExit = new BotNavigationGraph.Edge(
                49, 45, BotNavigationGraph.EdgeType.CLIMB,
                new Point(675, 215), new Point(675, 215),
                0, 0, 675, 143, 215, 250
        );

        assertTrue(BotNavigationManager.isTopStepOffExit(rope, new Point(675, 145), topExit));
        assertTrue(BotNavigationManager.isTopStepOffExit(rope, new Point(675, 171), topExit));
        assertFalse(BotNavigationManager.isTopStepOffExit(rope, new Point(675, 215), bottomExit));
    }

    @Test
    void shouldOnlyExecuteStraightDownJumpInsideLaunchWindow() {
        MapleMap map = new MapleMap(910000031, 0, 0, 910000031, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 0), new Point(200, 0), 1));
        footholds.insert(new Foothold(new Point(40, 120), new Point(160, 120), 2));
        map.setFootholds(footholds);

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        BotNavigationGraph.Edge downJump = findFirstStraightDropEdge(graph);

        assertNotNull(downJump, "fixture should produce a straight down-jump edge");
        assertTrue(downJump.launchMinX < downJump.launchMaxX);

        int insideX = (downJump.launchMinX + downJump.launchMaxX) / 2;
        int outsideX = downJump.launchMaxX + 1;

        assertTrue(BotNavigationManager.canExecuteDropFromCurrentPosition(
                graph, map, new Point(insideX, 0), downJump));
        assertFalse(BotNavigationManager.canExecuteDropFromCurrentPosition(
                graph, map, new Point(outsideX, 0), downJump));
    }

    @Test
    void shouldUsePreciseTargetForCommittedWalkRegionHandoffs() {
        BotNavigationGraph.Edge walkHandoff = new BotNavigationGraph.Edge(
                343, 341, BotNavigationGraph.EdgeType.WALK,
                new Point(28, -1167), new Point(13, -1170),
                0, 0, 0, 0, 0, 100
        );
        BotNavigationGraph.Edge noMoveWalk = new BotNavigationGraph.Edge(
                343, 342, BotNavigationGraph.EdgeType.WALK,
                new Point(28, -1167), new Point(28, -1167),
                0, 0, 0, 0, 0, 50
        );

        assertTrue(BotNavigationManager.shouldUsePreciseWalkTarget(walkHandoff));
        assertFalse(BotNavigationManager.shouldUsePreciseWalkTarget(noMoveWalk));
    }

    @Test
    void shouldDropStaleCollapsedWalkEdgeWhenBotEntersIntermediateRegion() {
        // Regression: pathlog-SLASH-2026-04-02 — collapsed r358→r355 WALK edge (via r359),
        // bot steps into r359 mid-traverse; old code returned null here (fromRegionId mismatch),
        // dropping the edge every tick and causing an oscillation loop.
        BotNavigationGraph.Edge collapsedWalk = new BotNavigationGraph.Edge(
                358, 355, BotNavigationGraph.EdgeType.WALK,
                new Point(46, -61), new Point(54, -58),
                0, 0, 0, 0, 0, 100
        );
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(mock(MapleMap.class));
        BotEntry entry = new BotEntry(bot, null, null);
        entry.navEdge = collapsedWalk;
        entry.navTargetRegionId = 355;
        BotNavigationGraph graph = mock(BotNavigationGraph.class);

        // Bot is in intermediate region 359 — neither source (358) nor destination (355)
        BotNavigationGraph.Edge result = BotNavigationManager.reuseCommittedEdge(graph, entry, 359, 355);

        assertNull(result, "Stale collapsed WALK edge must be dropped once the bot leaves its source region");
    }

    @Test
    void shouldDropCollapsedWalkEdgeOnceDestinationRegionReached() {
        BotNavigationGraph.Edge collapsedWalk = new BotNavigationGraph.Edge(
                358, 355, BotNavigationGraph.EdgeType.WALK,
                new Point(46, -61), new Point(54, -58),
                0, 0, 0, 0, 0, 100
        );
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(mock(MapleMap.class));
        BotEntry entry = new BotEntry(bot, null, null);
        entry.navEdge = collapsedWalk;
        entry.navTargetRegionId = 355;
        BotNavigationGraph graph = mock(BotNavigationGraph.class);

        BotNavigationGraph.Edge result = BotNavigationManager.reuseCommittedEdge(graph, entry, 355, 355);

        assertNull(result, "WALK edge must be dropped once bot reaches destination region");
    }

    @Test
    void shouldUseGraphDerivedJumpLaunchWindowInsteadOfGenericTolerance() {
        Foothold foothold = new Foothold(new Point(500, 107), new Point(530, 107), 1);
        BotNavigationGraph.Region fromRegion = new BotNavigationGraph.Region(20, List.of(new BotNavigationGraph.Segment(foothold)));
        BotNavigationGraph graph = mock(BotNavigationGraph.class);
        when(graph.getRegion(20)).thenReturn(fromRegion);

        BotNavigationGraph.Edge jump = new BotNavigationGraph.Edge(
                20, 15, BotNavigationGraph.EdgeType.JUMP,
                new Point(520, 107), new Point(480, 36),
                516, 523, -8, 0, 0, 0, 0, 850
        );

        assertFalse(BotNavigationManager.isWithinJumpLaunchWindow(graph, new Point(513, 107), jump));
        assertFalse(BotNavigationManager.isWithinJumpLaunchWindow(graph, new Point(514, 107), jump));
        assertTrue(BotNavigationManager.isWithinJumpLaunchWindow(graph, new Point(516, 107), jump));
        assertTrue(BotNavigationManager.isWithinJumpLaunchWindow(graph, new Point(523, 107), jump));
        assertFalse(BotNavigationManager.isWithinJumpLaunchWindow(graph, new Point(525, 107), jump));
        assertFalse(BotNavigationManager.isWithinJumpLaunchWindow(graph, new Point(526, 107), jump));
        assertFalse(BotNavigationManager.isWithinJumpLaunchWindow(graph, new Point(520, 160), jump));
    }

    @Test
    void shouldPickStableJumpLaunchTargetInsideWindow() {
        MapleMap map = new MapleMap(910000010, 0, 0, 910000010, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(500, 107), new Point(530, 107), 1));
        map.setFootholds(footholds);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        BotEntry entry = new BotEntry(bot, null, null);

        BotNavigationGraph.Edge jump = new BotNavigationGraph.Edge(
                1, 15, BotNavigationGraph.EdgeType.JUMP,
                new Point(520, 107), new Point(480, 36),
                516, 523, -8, 0, 0, 0, 0, 850
        );

        Point firstTarget = BotNavigationManager.selectJumpWaypoint(entry, new Point(449, 113), jump);
        Point secondTarget = BotNavigationManager.selectJumpWaypoint(entry, new Point(540, 113), jump);
        Point thirdTarget = BotNavigationManager.selectJumpWaypoint(entry, new Point(520, 113), jump);

        assertTrue(firstTarget.x >= 519 && firstTarget.x <= 520);
        assertEquals(107, firstTarget.y);
        assertEquals(firstTarget, secondTarget);
        assertEquals(firstTarget, thirdTarget);

        assertEquals(new Point(516, 107), BotNavigationManager.selectJumpWaypoint(graph, new Point(449, 113), jump));
        assertEquals(new Point(523, 107), BotNavigationManager.selectJumpWaypoint(graph, new Point(540, 113), jump));
        assertEquals(new Point(520, 107), BotNavigationManager.selectJumpWaypoint(graph, new Point(520, 113), jump));
    }

    @Test
    void shouldChooseTargetRegionEntryBasedOnInRegionPathTarget() {
        MapleMap map = new MapleMap(910000026, 0, 0, 910000026, 1.0f);
        BotNavigationGraph.Region startRegion = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(100, 100), 1))));
        BotNavigationGraph.Region targetRegion = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 200), new Point(200, 200), 2))));
        Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
        regionsById.put(1, startRegion);
        regionsById.put(2, targetRegion);
        BotNavigationGraph.Edge leftEntry = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.CLIMB,
                new Point(50, 100), new Point(0, 200),
                0, 0, 0, 0, 0, 100
        );
        BotNavigationGraph.Edge rightEntry = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.CLIMB,
                new Point(50, 100), new Point(200, 200),
                0, 0, 0, 0, 0, 100
        );
        BotNavigationGraph graph = new BotNavigationGraph(
                map.getId(),
                1,
                List.of(startRegion, targetRegion),
                regionsById,
                Map.of(1, 1, 2, 2),
                Map.of(1, List.of(leftEntry, rightEntry)),
                Set.of()
        );

        List<BotNavigationGraph.Edge> leftPath = BotNavigationManager.findPath(
                graph, map, new Point(50, 100), 1, 2, new Point(40, 200));
        List<BotNavigationGraph.Edge> rightPath = BotNavigationManager.findPath(
                graph, map, new Point(50, 100), 1, 2, new Point(160, 200));

        assertEquals(List.of(leftEntry), leftPath,
                "pathfinding should prefer the entry closest to the left-side in-region target");
        assertEquals(List.of(rightEntry), rightPath,
                "pathfinding should prefer the entry closest to the clamped interior target, not a fixed nearest edge");
    }

    @Test
    void reachabilityIndexIsDirectedAndSkillFiltered() {
        // One-way walk 1->2; region 3 reachable from 2 only via a TELEPORT (skill) edge.
        BotNavigationGraph.Region r1 = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(100, 100), 1))));
        BotNavigationGraph.Region r2 = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 200), new Point(100, 200), 2))));
        BotNavigationGraph.Region r3 = new BotNavigationGraph.Region(
                3, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(500, 300), new Point(600, 300), 3))));
        Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
        regionsById.put(1, r1);
        regionsById.put(2, r2);
        regionsById.put(3, r3);
        BotNavigationGraph.Edge walk12 = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.WALK,
                new Point(50, 100), new Point(50, 200), 0, 0, 0, 0, 0, 100);
        BotNavigationGraph.Edge teleport23 = new BotNavigationGraph.Edge(
                2, 3, BotNavigationGraph.EdgeType.TELEPORT,
                new Point(50, 200), new Point(550, 300), 0, 0, 0, 0, 0, 100);
        BotNavigationGraph graph = new BotNavigationGraph(
                910000026, 1,
                List.of(r1, r2, r3), regionsById,
                Map.of(1, 1, 2, 2, 3, 3),
                Map.of(1, List.of(walk12), 2, List.of(teleport23)),
                Set.of());

        assertEquals(List.of(walk12), graph.getOutgoing(1, 0),
                "walk-only outgoing should keep non-skill edges");
        assertEquals(List.of(), graph.getOutgoing(2, 0),
                "walk-only outgoing should skip baked TELEPORT edges before A* scans them");
        assertEquals(List.of(teleport23), graph.getOutgoing(2, BotNavigationGraph.SKILL_TELEPORT),
                "TELEPORT mask should restore teleport outgoing edges");
        assertNull(graph.costToGoal(3, 0).get(2),
                "walk-only cost-to-goal should not route through TELEPORT edges");
        assertEquals(100, graph.costToGoal(3, BotNavigationGraph.SKILL_TELEPORT).get(2),
                "TELEPORT mask should include teleport edges in the reverse cost index");

        // Walk-only (skillMask 0): 1 reaches 2; the skill-only region 3 is unreachable.
        assertTrue(graph.canReach(1, 2, 0), "walk edge 1->2 is reachable walk-only");
        assertFalse(graph.canReach(1, 3, 0), "skill-only region 3 is NOT walk-reachable from 1");
        assertFalse(graph.canReach(2, 3, 0), "skill-only region 3 is NOT walk-reachable from 2");
        // Directed: the edge is one-way 1->2, so 2 cannot reach 1 (the old undirected index missed this).
        assertFalse(graph.canReach(2, 1, 0), "reachability is directed: no edge 2->1");
        // With TELEPORT usable, 1 and 2 reach 3 through the skill bridge.
        assertTrue(graph.canReach(1, 3, BotNavigationGraph.SKILL_TELEPORT), "TELEPORT bridges 1->2->3");
        assertTrue(graph.canReach(2, 3, BotNavigationGraph.SKILL_TELEPORT), "TELEPORT bridges 2->3");
        // A FLASH_JUMP-only mask must not enable a TELEPORT edge.
        assertFalse(graph.canReach(2, 3, BotNavigationGraph.SKILL_FLASH_JUMP),
                "FLASH_JUMP mask must not enable a TELEPORT edge");

        // Best-effort redirect: target sits near the unreachable skill-only region 3 (550,300). Walk-only
        // from region 1, region 2 (y=200) is reachable and closer to the target than the start (y=100), so
        // it's the "walk as close as possible" pick.
        Point nearR3 = new Point(550, 300);
        assertEquals(2, graph.nearestReachableRegion(1, 0, nearR3),
                "redirect to the reachable region closest to the unreachable target");
        // From region 2 walk-only, nothing reachable is closer than region 2 itself -> -1 (already closest).
        assertEquals(-1, graph.nearestReachableRegion(2, 0, nearR3),
                "no redirect when the start is already the closest reachable region");
    }

    @Test
    void teleportCooldownSteersHorizontallyInsideLaunchWindow() {
        MapleMap map = new MapleMap(910000102, 0, 0, 910000102, 1.0f);
        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.lastEdgeBlockReason = "tele-cd";

        BotNavigationGraph.Edge rightTeleport = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.TELEPORT,
                new Point(100, 100), new Point(250, 100),
                80, 200, 0, 0, 0, 0, 0, 100);

        Point waypoint = BotNavigationManager.selectTeleportCooldownWaypoint(
                entry, new Point(100, 100), rightTeleport);

        assertEquals(new Point(100 + BotPhysicsEngine.walkStep(map, entry.movementProfile), 100), waypoint);

        BotNavigationGraph.Edge upTeleport = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.TELEPORT,
                new Point(100, 100), new Point(100, -50),
                80, 200, 0, 0, 0, 0, 0, 100);

        assertNull(BotNavigationManager.selectTeleportCooldownWaypoint(
                entry, new Point(100, 100), upTeleport));
    }

    @Test
    void ropeExitEdgeCarriesYLaunchWindowAndSteersWithinIt() {
        // Rope-exit CLIMB: fixed x (ropeX=100), launches from any climb height in the Y window [150,260].
        BotNavigationGraph.Edge e = new BotNavigationGraph.Edge(
                5, 6, BotNavigationGraph.EdgeType.CLIMB, new Point(100, 200), new Point(140, 260),
                100, 100,   // degenerate X window (fixed rope x)
                150, 260,   // Y launch window
                3, 0, 100, 140, 280, 50);
        assertTrue(e.containsLaunchY(150));
        assertTrue(e.containsLaunchY(260));
        assertFalse(e.containsLaunchY(149));
        assertTrue(e.containsLaunchY(145, 10), "tolerance widens the window");
        // nearest in-window launch point keeps the rope x
        assertEquals(new Point(100, 150), e.pointAtNearestLaunchY(120));
        assertEquals(new Point(100, 260), e.pointAtNearestLaunchY(300));
        // steer clamps to the window with a 4px inset (LAUNCH_WINDOW_STEER_INSET_PX)
        assertEquals(154, BotNavigationManager.steerYWithinLaunchWindow(e, 100));
        assertEquals(256, BotNavigationManager.steerYWithinLaunchWindow(e, 999));
        assertEquals(200, BotNavigationManager.steerYWithinLaunchWindow(e, 200));
        // a non-rope edge has a degenerate Y window (= startPoint.y), so containsLaunchY is an exact check
        BotNavigationGraph.Edge ground = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.WALK, new Point(0, 0), new Point(10, 0), 0, -1, 0, 0, 0, 0);
        assertTrue(ground.containsLaunchY(0));
        assertFalse(ground.containsLaunchY(1));
    }

    @Test
    void costToGoalIsMinReverseDijkstraAndOmitsRegionsThatCannotReachGoal() {
        // 1 --walk(100)--> 2 --walk(100)--> 3(goal); 1 --jump(150)--> 3 direct; 3 --drop(50)--> 4 (dead end vs goal).
        BotNavigationGraph.Region r1 = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(100, 100), 1))));
        BotNavigationGraph.Region r2 = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 200), new Point(100, 200), 2))));
        BotNavigationGraph.Region r3 = new BotNavigationGraph.Region(
                3, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 300), new Point(100, 300), 3))));
        BotNavigationGraph.Region r4 = new BotNavigationGraph.Region(
                4, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 400), new Point(100, 400), 4))));
        Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
        regionsById.put(1, r1);
        regionsById.put(2, r2);
        regionsById.put(3, r3);
        regionsById.put(4, r4);
        BotNavigationGraph.Edge e12 = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.WALK, new Point(50, 100), new Point(50, 200), 0, 0, 0, 0, 0, 100);
        BotNavigationGraph.Edge e23 = new BotNavigationGraph.Edge(
                2, 3, BotNavigationGraph.EdgeType.WALK, new Point(50, 200), new Point(50, 300), 0, 0, 0, 0, 0, 100);
        BotNavigationGraph.Edge e13 = new BotNavigationGraph.Edge(
                1, 3, BotNavigationGraph.EdgeType.JUMP, new Point(50, 100), new Point(50, 300), 0, 0, 0, 0, 0, 150);
        BotNavigationGraph.Edge e34 = new BotNavigationGraph.Edge(
                3, 4, BotNavigationGraph.EdgeType.DROP, new Point(50, 300), new Point(50, 400), 0, 0, 0, 0, 0, 50);
        BotNavigationGraph graph = new BotNavigationGraph(
                910000026, 1,
                List.of(r1, r2, r3, r4), regionsById,
                Map.of(1, 1, 2, 2, 3, 3, 4, 4),
                Map.of(1, List.of(e12, e13), 2, List.of(e23), 3, List.of(e34)),
                Set.of());

        Map<Integer, Integer> dist = graph.costToGoal(3);
        assertEquals(0, dist.get(3), "goal region cost is 0");
        assertEquals(100, dist.get(2), "2 -> 3 is one 100-cost walk");
        assertEquals(150, dist.get(1), "1 prefers the direct 150 jump over the 200 two-hop walk");
        assertNull(dist.get(4), "region 4 cannot reach the goal -> absent from the index");
    }

    @Test
    void retreatProbePathUsesGoalHeuristicWithoutChangingRouteCost() {
        MapleMap map = new MapleMap(910000027, 0, 0, 910000027, 1.0f);
        BotNavigationGraph.Region r1 = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(100, 100), 1))));
        BotNavigationGraph.Region r2 = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 200), new Point(100, 200), 2))));
        BotNavigationGraph.Region r3 = new BotNavigationGraph.Region(
                3, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 300), new Point(100, 300), 3))));
        Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
        regionsById.put(1, r1);
        regionsById.put(2, r2);
        regionsById.put(3, r3);
        BotNavigationGraph.Edge e12 = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.WALK, new Point(50, 100), new Point(50, 200),
                0, 0, 0, 0, 0, 100);
        BotNavigationGraph.Edge e23 = new BotNavigationGraph.Edge(
                2, 3, BotNavigationGraph.EdgeType.WALK, new Point(50, 200), new Point(50, 300),
                0, 0, 0, 0, 0, 100);
        BotNavigationGraph.Edge e13 = new BotNavigationGraph.Edge(
                1, 3, BotNavigationGraph.EdgeType.JUMP, new Point(50, 100), new Point(50, 300),
                0, 0, 0, 0, 0, 250);
        BotNavigationGraph graph = new BotNavigationGraph(
                map.getId(), 1,
                List.of(r1, r2, r3), regionsById,
                Map.of(1, 1, 2, 2, 3, 3),
                Map.of(1, List.of(e13, e12), 2, List.of(e23)),
                Set.of());

        Point start = new Point(50, 100);
        Point target = new Point(50, 300);
        BotNavigationManager.SearchOutcome optimal = BotNavigationManager.runSearch(
                graph, map, start, 1, 3, target, "measure", true, false, 0L);
        List<BotNavigationGraph.Edge> retreatProbe = BotNavigationManager.findPathForRetreatProbe(
                graph, map, start, 1, 3, target);

        assertEquals(200, optimal.cost());
        assertEquals(List.of(e12, e23), retreatProbe);
        assertEquals(optimal.cost(), retreatProbe.stream().mapToInt(e -> e.cost).sum());
    }

    @Test
    void graphBakesNextHopsFromAllRegionsToPortalRegions() {
        MapleMap map = new MapleMap(910000027, 0, 0, 910000027, 1.0f);
        BotNavigationGraph.Region r1 = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(100, 100), 1))));
        BotNavigationGraph.Region r2 = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 200), new Point(100, 200), 2))));
        BotNavigationGraph.Region portalRegion = new BotNavigationGraph.Region(
                3, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 300), new Point(100, 300), 3))));
        Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
        regionsById.put(1, r1);
        regionsById.put(2, r2);
        regionsById.put(3, portalRegion);
        BotNavigationGraph.Edge e12 = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.WALK, new Point(50, 100), new Point(50, 200),
                0, 0, 0, 0, 0, 100);
        BotNavigationGraph.Edge e23 = new BotNavigationGraph.Edge(
                2, 3, BotNavigationGraph.EdgeType.WALK, new Point(50, 200), new Point(50, 300),
                0, 0, 0, 0, 0, 100);
        BotNavigationGraph.Edge portalMarker = new BotNavigationGraph.Edge(
                3, 3, BotNavigationGraph.EdgeType.PORTAL, new Point(50, 300), new Point(50, 300),
                0, 77, 0, 0, 0, 0);
        BotNavigationGraph graph = new BotNavigationGraph(
                map.getId(), 1,
                List.of(r1, r2, portalRegion), regionsById,
                Map.of(1, 1, 2, 2, 3, 3),
                Map.of(1, List.of(e12), 2, List.of(e23), 3, List.of(portalMarker)),
                Set.of());

        assertTrue(graph.hasPortalRouteTarget(3));
        assertEquals(e12, graph.portalNextHop(1, 3, r1.centerPoint()),
                "baked portal index should store the first hop from non-portal regions too");
        assertEquals(e23, graph.portalNextHop(2, 3, r2.centerPoint()),
                "baked portal index should store the next hop into the portal region");
    }

    @Test
    void routeCacheSeparatesStartAndTargetPointBuckets() {
        BotNavigationGraph.Region r1 = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(200, 100), 1))));
        BotNavigationGraph.Region r2 = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 200), new Point(200, 200), 2))));
        Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
        regionsById.put(1, r1);
        regionsById.put(2, r2);
        BotNavigationGraph.Edge leftExit = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.JUMP, new Point(10, 100), new Point(10, 200),
                0, 0, 0, 0, 0, 100);
        BotNavigationGraph.Edge rightExit = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.JUMP, new Point(190, 100), new Point(190, 200),
                0, 0, 0, 0, 0, 100);
        BotNavigationGraph graph = new BotNavigationGraph(
                910000028, 1,
                List.of(r1, r2), regionsById,
                Map.of(1, 1, 2, 2),
                Map.of(1, List.of(leftExit, rightExit)),
                Set.of());

        int leftStartBucket = BotNavigationManager.routePointBucket(graph, 1, new Point(10, 100));
        int rightStartBucket = BotNavigationManager.routePointBucket(graph, 1, new Point(190, 100));
        int targetBucket = BotNavigationManager.routePointBucket(graph, 2, new Point(100, 200));
        graph.putNextHop(1, 2, leftStartBucket, targetBucket, 0, BotNavigationManager.ROUTE_BUCKETS, leftExit);
        graph.putNextHop(1, 2, rightStartBucket, targetBucket, 0, BotNavigationManager.ROUTE_BUCKETS, rightExit);

        assertEquals(leftExit, graph.cachedNextHop(1, 2, leftStartBucket, targetBucket, 0));
        assertEquals(rightExit, graph.cachedNextHop(1, 2, rightStartBucket, targetBucket, 0));
    }

    @Test
    void shouldRefreshStaleCommittedGroundDropWhenBestFirstEdgeChanges() {
        MapleMap map = new MapleMap(910000032, 0, 0, 910000032, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 0), new Point(300, 0), 1));
        footholds.insert(new Foothold(new Point(0, 120), new Point(100, 120), 2));
        footholds.insert(new Foothold(new Point(200, 120), new Point(300, 120), 3));
        map.setFootholds(footholds);

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Point botPos = new Point(50, 0);
        Point leftTarget = new Point(50, 120);
        Point rightTarget = new Point(250, 120);
        int startRegionId = graph.findRegionId(map, botPos);
        int leftTargetRegionId = graph.findRegionId(map, leftTarget);
        int rightTargetRegionId = graph.findRegionId(map, rightTarget);

        List<BotNavigationGraph.Edge> leftPath = BotNavigationManager.findPath(
                graph, map, botPos, startRegionId, leftTargetRegionId, leftTarget);
        List<BotNavigationGraph.Edge> rightPath = BotNavigationManager.findPath(
                graph, map, botPos, startRegionId, rightTargetRegionId, rightTarget);

        assertFalse(leftPath.isEmpty(), "fixture should produce a left-side drop path");
        assertFalse(rightPath.isEmpty(), "fixture should produce a right-side drop path");

        BotNavigationGraph.Edge staleEdge = leftPath.getFirst();
        BotNavigationGraph.Edge freshEdge = rightPath.getFirst();
        assertEquals(BotNavigationGraph.EdgeType.DROP, staleEdge.type);
        assertEquals(BotNavigationGraph.EdgeType.DROP, freshEdge.type);
        assertNotEquals(staleEdge.toRegionId, freshEdge.toRegionId,
                "regression requires different first actionable drop edges from the same source region");

        Character bot = mockBot(botPos, map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.following = true;
        entry.navEdge = staleEdge;
        entry.navTargetRegionId = leftTargetRegionId;

        BotNavigationManager.NavigationDirective directive =
                BotNavigationManager.resolveTarget(entry, rightTarget, true);

        assertFalse(directive.consumedTick);
        assertEquals(freshEdge.toRegionId, entry.navEdge.toRegionId,
                "grounded reuse must discard a stale drop edge once the current best first edge changes");
        assertEquals(freshEdge.startPoint, entry.navEdge.startPoint);
    }

    @Test
    void shouldGiveUpEdgeBlockedByPositionGateAndReplanFromLivePosition() {
        // pathlog-Leroy-2026-06-12T141517: a committed DROP edge whose stale window ended
        // 2px short of the bot was reused (blocked: *-pos) for 21s while every fresh A*
        // plan's window contained the bot. The position-gate give-up must drop the edge
        // after a few hundred ms and let the replan execute from where the bot stands.
        MapleMap map = new MapleMap(910000033, 0, 0, 910000033, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 0), new Point(300, 0), 1));
        footholds.insert(new Foothold(new Point(0, 120), new Point(300, 120), 2));
        map.setFootholds(footholds);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);

        Point botPos = new Point(250, 0);
        Point target = new Point(250, 120);
        int startRegionId = graph.findRegionId(map, botPos);
        int targetRegionId = graph.findRegionId(map, target);
        assertNotEquals(startRegionId, targetRegionId, "fixture needs distinct upper/lower regions");

        // Stale committed edge: same region pair as the live plan (so the per-tick ground
        // refresh retains it) but a launch window the bot is 2px OUTSIDE of.
        BotNavigationGraph.Edge staleEdge = new BotNavigationGraph.Edge(
                startRegionId, targetRegionId, BotNavigationGraph.EdgeType.DROP,
                new Point(200, 0), new Point(200, 120),
                150, 248, 0, 0, 0, 0, 0, 400);

        Character bot = mockBot(botPos, map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.following = true;
        entry.navEdge = staleEdge;
        entry.navTargetRegionId = targetRegionId;

        // The retention window: for the first few AI ticks the stale edge must be kept
        // (anti-thrash), only blocked: the bot never moves, so the give-up counter runs.
        for (int tick = 0; tick < 6; tick++) {
            BotNavigationManager.resolveTarget(entry, target, true);
            assertEquals("reuse", entry.lastNavDecision, "tick " + tick + " should still reuse the committed edge");
            assertEquals(staleEdge.startPoint, entry.navEdge.startPoint,
                    "tick " + tick + " should not yet give up the committed edge");
            assertEquals("drop-pos", entry.lastEdgeBlockReason);
        }

        // Within the jittered give-up budget (6-10 blocked ticks) the edge is dropped, the
        // fresh plan's window contains the bot, and the drop executes immediately.
        int extraTicks = 0;
        while (!"exec".equals(entry.lastNavDecision) && extraTicks < 8) {
            BotNavigationManager.resolveTarget(entry, target, true);
            extraTicks++;
        }
        assertEquals("exec", entry.lastNavDecision,
                "blocked-position reuse must give up and replan within the jittered tick budget");
        assertTrue(entry.downJumpPending, "replanned drop edge should execute from the bot's live position");
    }

    @Test
    void shouldDropStaleCommittedGroundEdgeWhenLiveTargetRegionDiffersFromEdgeDestination() {
        MapleMap map = mock(MapleMap.class);
        BotNavigationGraph.Region source = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(200, 100), 1))));
        BotNavigationGraph.Region staleLower = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(0, 220), new Point(100, 220), 2))));
        BotNavigationGraph.Region ownerUpper = new BotNavigationGraph.Region(
                3, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(90, 40), new Point(210, 40), 3))));
        Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
        regionsById.put(1, source);
        regionsById.put(2, staleLower);
        regionsById.put(3, ownerUpper);

        BotNavigationGraph.Edge staleDrop = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.DROP,
                new Point(20, 100), new Point(40, 220),
                20, 20, 0, 0, 0, 0, 0, 300);
        BotNavigationGraph.Edge directJump = new BotNavigationGraph.Edge(
                1, 3, BotNavigationGraph.EdgeType.JUMP,
                new Point(100, 100), new Point(140, 40),
                90, 110, 6, 0, 0, 0, 0, 250);
        BotNavigationGraph graph = new BotNavigationGraph(
                910000213, 1, BotMovementProfile.base(),
                List.of(source, staleLower, ownerUpper),
                regionsById,
                Map.of(1, 1, 2, 2, 3, 3),
                Map.of(1, List.of(staleDrop, directJump)),
                Set.of());

        Point botPos = new Point(100, 100);
        Point ownerPos = new Point(140, 40);
        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                graph, map, botPos, 1, 3, ownerPos);
        assertEquals(List.of(directJump), path,
                "synthetic fixture should prefer the direct jump to the live owner region");

        Character bot = mockBot(botPos, map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.navEdge = staleDrop;
        entry.navTargetRegionId = staleDrop.toRegionId;

        assertNull(BotNavigationManager.reuseCommittedEdge(graph, entry, 1, 3),
                "non-AI reuse must drop stale grounded edges whose destination no longer matches the live target");
    }

    @Test
    void shouldDropStaleGroundJumpWhileClimbingOnDifferentRopeRegion() {
        MapleMap map = mock(MapleMap.class);
        BotNavigationGraph.Region ropeRegion = new BotNavigationGraph.Region(30, 1896, -165, 54, false);
        BotNavigationGraph.Region groundRegion = new BotNavigationGraph.Region(
                7, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(1750, -47), new Point(1900, -47), 7))));
        BotNavigationGraph.Region oldJumpDest = new BotNavigationGraph.Region(
                4, List.of(new BotNavigationGraph.Segment(new Foothold(new Point(1450, -107), new Point(1550, -107), 4))));
        BotNavigationGraph.Edge staleJump = new BotNavigationGraph.Edge(
                7, 4, BotNavigationGraph.EdgeType.JUMP,
                new Point(1544, -47), new Point(1495, -107),
                1530, 1559, -6, 0, 0, 0, 0, 450);
        BotNavigationGraph graph = new BotNavigationGraph(
                103000101, 1, BotMovementProfile.base(),
                List.of(ropeRegion, groundRegion, oldJumpDest),
                Map.of(30, ropeRegion, 7, groundRegion, 4, oldJumpDest),
                Map.of(30, 30, 7, 7, 4, 4),
                Map.of(7, List.of(staleJump)),
                Set.of());

        Character bot = mockBot(new Point(1896, -51), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(1896, -165, 54, false);
        entry.navEdge = staleJump;
        entry.navTargetRegionId = 4;

        assertNull(BotNavigationManager.reuseCommittedEdge(graph, entry, 30, 7),
                "a ground jump from another region must not steer a bot that is already climbing a rope");
    }

    @Test
    void shouldRetainCommittedGroundEdgeWhenAlternativeLeadsToSameDestinationRegion() {
        BotNavigationGraph.Edge committedDrop = new BotNavigationGraph.Edge(
                80, 83, BotNavigationGraph.EdgeType.DROP,
                new Point(7, -34), new Point(-84, 99),
                7, 7, 0, 0, 0, 655
        );
        BotNavigationGraph.Edge replacementJump = new BotNavigationGraph.Edge(
                80, 83, BotNavigationGraph.EdgeType.JUMP,
                new Point(5, -34), new Point(-99, 95),
                -35, 45, -7, 0, 0, 0, 0, 750
        );

        assertTrue(BotNavigationManager.shouldRetainCommittedGroundEdge(committedDrop, replacementJump),
                "equivalent first exits into the same destination region should not thrash mid-approach");
    }

    @Test
    void shouldNotRetainCommittedGroundEdgeWhenAlternativeChangesDestinationRegion() {
        BotNavigationGraph.Edge committedDrop = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.DROP,
                new Point(10, 0), new Point(10, 100),
                10, 10, 0, 0, 0, 300
        );
        BotNavigationGraph.Edge replacementDrop = new BotNavigationGraph.Edge(
                1, 3, BotNavigationGraph.EdgeType.DROP,
                new Point(40, 0), new Point(40, 100),
                40, 40, 0, 0, 0, 300
        );

        assertFalse(BotNavigationManager.shouldRetainCommittedGroundEdge(committedDrop, replacementDrop),
                "grounded replans must still refresh when the better first edge changes destination region");
    }

    @Test
    void shouldUseRawTargetWhileMovementGraphWarmsInBackground() {
        MapleMap map = new MapleMap(910000030, 0, 0, 910000030, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(20, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = new BotMovementProfile(105, 105);

        BotNavigationManager.NavigationDirective directive =
                BotNavigationManager.resolveTarget(entry, new Point(180, 100), true);

        assertFalse(directive.consumedTick);
        assertEquals(new Point(180, 100), directive.targetPos);
        assertEquals("graph-warmup", entry.lastNavDecision);
        assertTrue(entry.graphWarmupFallback);
        assertNull(entry.navEdge);

        BotNavigationGraphProvider.getGraph(map, entry.movementProfile);
    }

    @Test
    void shouldHoldCurrentPositionOnlyAtNonTopClimbExitLaunchAnchor() {
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(kerning());
        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(-1251, -137, 2, true);

        BotNavigationGraph.Edge climbExit = new BotNavigationGraph.Edge(
                189, 157, BotNavigationGraph.EdgeType.CLIMB,
                new Point(-1251, -107), new Point(-1132, 156),
                8, 0, -1251, -137, 2, 650
        );

        assertEquals(new Point(-1251, -107),
                BotNavigationManager.selectClimbWaypoint(entry, new Point(-1251, -107), climbExit));
        assertEquals(new Point(-1251, -107),
                BotNavigationManager.selectClimbWaypoint(entry, new Point(-1251, -109), climbExit));
    }

    @Test
    void shouldKeepSteeringToClimbLaunchAnchorWhileBelowExitAnchor() {
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(kerning());
        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(-1251, -137, 2, true);

        BotNavigationGraph.Edge climbExit = new BotNavigationGraph.Edge(
                189, 157, BotNavigationGraph.EdgeType.CLIMB,
                new Point(-1251, -107), new Point(-1132, 156),
                8, 0, -1251, -137, 2, 650
        );

        assertEquals(new Point(-1251, -107),
                BotNavigationManager.selectClimbWaypoint(entry, new Point(-1251, -104), climbExit));
    }

    @Test
    void shouldKeepSteeringToNonTopRopeExitAnchorWhileAboveExitAnchor() {
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(kerning());
        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(707, -769, -455, false);

        BotNavigationGraph.Edge climbExit = new BotNavigationGraph.Edge(
                104, 101, BotNavigationGraph.EdgeType.CLIMB,
                new Point(707, -734), new Point(627, -602),
                -6, 0, 707, -769, -455, 950
        );

        assertEquals(new Point(707, -734),
                BotNavigationManager.selectClimbWaypoint(entry, new Point(707, -764), climbExit));
    }

    @Test
    void shouldKeepCommittedRopeExitClimbEdgeWhileAirborne() {
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(mock(MapleMap.class));
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.navEdge = new BotNavigationGraph.Edge(
                25, 14, BotNavigationGraph.EdgeType.CLIMB,
                new Point(-437, -181), new Point(-473, -211),
                -8, 0, -437, -1471, 84, 250
        );
        entry.navTargetRegionId = 14;
        BotNavigationGraph graph = mock(BotNavigationGraph.class);

        BotNavigationGraph.Edge reused = BotNavigationManager.reuseCommittedEdge(graph, entry, 20, 14);

        assertEquals(entry.navEdge, reused);
    }

    @Test
    void shouldUseTopRopeEntryInsteadOfDroppingToBottomInLithHarbor() {
        MapleMap lithHarbor = BotNavigationMapLoader.loadMapGeometry(104000000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(lithHarbor);
        Point start = new Point(1189, 287);
        Point target = new Point(1265, 331);
        int startRegionId = graph.findRegionId(lithHarbor, start);
        int targetRegionId = graph.findRopeRegionId(target);

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                graph, lithHarbor, start, startRegionId, targetRegionId, target);

        assertFalse(path.isEmpty());
        assertEquals(BotNavigationGraph.EdgeType.CLIMB, path.getFirst().type);
        assertEquals(targetRegionId, path.getFirst().toRegionId);
        assertTrue(path.getFirst().endPoint.y <= target.y + BotMovementManager.cfg.JUMP_Y_THRESH);
    }

    @Test
    void shouldNotLaunchVerticalRopeEntryFromOutsideRopeGrabWindow() {
        MapleMap lithHarbor = BotNavigationMapLoader.loadMapGeometry(104000000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(lithHarbor);
        Point start = new Point(1245, 647);
        Point target = new Point(1265, 331);
        int startRegionId = graph.findRegionId(lithHarbor, start);
        int targetRegionId = graph.findRopeRegionId(target);
        // The intent of this test is to verify out-of-launch-window rope entries are rejected.
        // Look up the vertical (stepX=0) rope-entry edge in the graph directly rather than via
        // findPath, which now picks the time-cheapest entry (often a horizontal jump-grab).
        BotNavigationGraph.Edge ropeEntry = graph.getOutgoing(startRegionId).stream()
                .filter(edge -> edge.type == BotNavigationGraph.EdgeType.CLIMB
                        && edge.toRegionId == targetRegionId
                        && edge.launchStepX == 0
                        && edge.containsLaunchX(1257))
                .findFirst()
                .orElse(null);
        assertNotNull(ropeEntry, "expected a vertical (stepX=0) rope-entry CLIMB edge containing x=1257");
        assertTrue(ropeEntry.launchMinX < ropeEntry.launchMaxX);

        BotNavigationGraph.Region fromRegion = graph.getRegion(ropeEntry.fromRegionId);
        int outsideLaunchX = ropeEntry.launchMaxX < fromRegion.maxX
                ? ropeEntry.launchMaxX + 1
                : ropeEntry.launchMinX - 1;
        assertFalse(ropeEntry.containsLaunchX(outsideLaunchX));

        Character bot = mockBot(fromRegion.pointAt(outsideLaunchX), lithHarbor);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.navEdge = ropeEntry;
        entry.navTargetRegionId = targetRegionId;

        BotNavigationManager.NavigationDirective directive =
                BotNavigationManager.resolveTarget(entry, target, true);

        assertFalse(directive.consumedTick);
        assertFalse(entry.inAir);
        assertEquals("climb-pos", entry.lastEdgeBlockReason);
    }

    @Test
    void shouldJumpOffTopRopeBeforePhysicsAutoDismountsToUpperPlatform() {
        MapleMap lithHarbor = BotNavigationMapLoader.loadMapGeometry(104000000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(lithHarbor);
        Point botPos = new Point(1265, 294);
        Point target = new Point(1802, 647);
        int startRegionId = graph.findRopeRegionId(botPos);
        int targetRegionId = graph.findRegionId(lithHarbor, target);
        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                graph, lithHarbor, botPos, startRegionId, targetRegionId, target);
        assertFalse(path.isEmpty());
        BotNavigationGraph.Edge ropeExit = path.getFirst();
        assertEquals(BotNavigationGraph.EdgeType.CLIMB, ropeExit.type);
        assertTrue(ropeExit.launchStepX > 0);
        // Rope-exit now carries a Y launch window (not a single authored pixel). It fires at the rope x and
        // its window reaches up to the bot's near-top climb height, so the bot can jump off before physics
        // auto-dismounts upward.
        assertEquals(1265, ropeExit.startPoint.x);
        assertTrue(ropeExit.containsLaunchY(botPos.y), "Y window includes the bot's top-rope climb height");

        Character bot = mockBot(botPos, lithHarbor);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.climbing = true;
        entry.climbRope = new Rope(1265, 289, 597, false);

        BotNavigationManager.NavigationDirective directive =
                BotNavigationManager.resolveTarget(entry, target, true);

        assertTrue(directive.consumedTick);
        assertTrue(entry.inAir);
        assertFalse(entry.climbing);
        // Launched from within the verified window at the rope x (no longer snapped to one fixed pixel).
        assertEquals(1265, bot.getPosition().x);
        assertTrue(ropeExit.containsLaunchY(bot.getPosition().y),
                "bot launched from within the rope-exit Y window");
    }

    @Test
    void shouldPreferCurrentRopeRegionAtRopeTopWhenBotStanceIsClimbing() {
        MapleMap map = topRopeSyntheticMap(910000101);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, new BotMovementProfile(105, 100));
        Point ropeTop = new Point(100, 100);
        assertNotEquals(graph.findRopeRegionId(ropeTop), graph.findRegionId(map, ropeTop));

        Character bot = mockBot(ropeTop, map);
        bot.setStance(CharacterStance.ROPE_RIGHT_STANCE);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = new BotMovementProfile(105, 100);

        assertEquals(graph.findRopeRegionId(ropeTop),
                BotNavigationManager.resolveCurrentRegionId(graph, entry, map, ropeTop));
    }

    @Test
    void shouldModelTopStepOffAtPhysicsLandingX() {
        MapleMap map = topRopeSyntheticMap(910000102);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, new BotMovementProfile(105, 100));
        Point ropeTop = new Point(100, 100);
        int startRegionId = graph.findRopeRegionId(ropeTop);
        BotNavigationGraph.Edge topExit = graph.getOutgoing(startRegionId).stream()
                .filter(edge -> edge.type == BotNavigationGraph.EdgeType.CLIMB)
                .filter(edge -> edge.launchStepX == 0)
                .filter(edge -> edge.startPoint.equals(ropeTop))
                .findFirst()
                .orElseThrow();

        assertEquals(new Point(100, 100), topExit.endPoint);
    }

    @Test
    void shouldNotDismountFromRopeTopOnNonAiTickWhenFollowTargetIsAbove() {
        // Regression: pathlog-Preston-2026-05-07T034012 — bot at firstClimbableY of a rope
        // whose top sits 1px below an above-foothold. Owner above the rope makes the raw follow
        // target's dy negative, so on every non-AI physics tick tickClimbing computed
        // climbVerticalDir=-1 and advanceClimb landed the bot onto the foothold above (climbing
        // cleared). The following AI tick saw the bot in the foothold region and re-grabbed the
        // rope. Region oscillated r=foothold ↔ r=rope at 50ms cadence for 10+ seconds.
        //
        // Climb direction is an AI-decided intent. Non-AI ticks must integrate the previously
        // chosen climbVerticalDir, not derive a fresh direction from the raw follow target.
        MapleMap map = new MapleMap(910000200, 0, 0, 910000200, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        // Foothold above the rope top (y=0), 2px gap to rope.topY=2 — same geometry as Preston r114/r173.
        footholds.insert(new Foothold(new Point(80, 0), new Point(120, 0), 1));
        map.setFootholds(footholds);
        Rope rope = new Rope(100, 2, 154, false);
        map.addRope(rope);

        Character bot = mockBot(new Point(100, 0), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();

        // Simulate the state right after AI tick attached the bot to the rope at firstClimbableY.
        entry.climbVerticalDir = -1;
        BotPhysicsEngine.attachToRope(entry, bot, rope, BotPhysicsEngine.firstClimbableY(rope));
        assertTrue(entry.climbing);
        assertEquals(BotPhysicsEngine.firstClimbableY(rope), bot.getPosition().y);
        assertEquals(0, entry.climbVerticalDir, "fresh attach must not carry stale climb intent");

        // Follow target far above the bot — without the fix, dy<0 forces climb-up which dismounts.
        Point followTargetAbove = new Point(50, -54);

        // No nav edge committed (rope-entry was just executed; reuseCommittedEdge would drop it
        // because the bot is now in the rope region == edge.toRegionId). This is the no-edge
        // window between AI ticks where the bug manifests.
        entry.navEdge = null;

        // Run one non-AI physics tick.
        BotMovementManager.tickClimbing(entry, followTargetAbove, false);

        assertTrue(entry.climbing,
                "Non-AI tick must not dismount: AI is the only place climb direction is decided.");
        assertEquals(rope, entry.climbRope);
        assertEquals(new Point(100, BotPhysicsEngine.firstClimbableY(rope)), bot.getPosition(),
                "Bot must hold position on the rope without AI-decided intent.");
    }

    @Test
    void shouldUseExplicitRopeEntryInsteadOfDownJumpFromTopPlatform() {
        MapleMap map = topRopeSyntheticMap(910000201);
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();

        BotNavigationManager.NavigationDirective directive =
                BotNavigationManager.resolveTarget(entry, new Point(100, 150), true);

        assertTrue(directive.consumedTick);
        assertTrue(entry.ropeEntryPending);
        assertFalse(entry.climbing);
        assertFalse(entry.downJumpPending);
        assertFalse(entry.crouching);

        BotMovementManager.tickGrounded(entry, new Point(100, 150));

        assertFalse(entry.ropeEntryPending);
        assertTrue(entry.climbing);
        assertEquals(new Point(100, 101), bot.getPosition());
    }

    @Test
    void shouldUseDownJumpInSwimFallbackWhenTargetIsDirectlyBelow() {
        MapleMap map = new MapleMap(910000202, 0, 0, 910000202, 1.0f);
        map.setSwim(true);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(120, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.graphWarmupFallback = true;

        Point target = new Point(130, 220);
        boolean immediateAction = BotFallbackMovementManager.tryImmediateAction(entry, bot.getPosition(), target);

        assertTrue(immediateAction);
        assertTrue(entry.downJumpPending);
    }

    @Test
    void shouldUseDownJumpInNonSwimFallbackWhenTargetIsDirectlyBelow() {
        MapleMap map = new MapleMap(910000204, 0, 0, 910000204, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        footholds.insert(new Foothold(new Point(20, 220), new Point(180, 220), 2));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(120, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.graphWarmupFallback = true;

        Point target = new Point(130, 220);
        boolean immediateAction = BotFallbackMovementManager.tryImmediateAction(entry, bot.getPosition(), target);

        assertTrue(immediateAction);
        assertTrue(entry.downJumpPending);
    }

    @Test
    void shouldSteerTowardNearestSwimWalkOffLedgeWhenFallbackCannotDownJump() {
        MapleMap map = new MapleMap(910000203, 0, 0, 910000203, 1.0f);
        map.setSwim(true);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(120, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.graphWarmupFallback = true;

        Point target = new Point(260, 220);
        Point steeringTarget = BotFallbackMovementManager.resolveSteeringTarget(entry, bot.getPosition(), target);
        boolean immediateAction = BotFallbackMovementManager.tryImmediateAction(entry, bot.getPosition(), target);

        assertFalse(immediateAction);
        assertFalse(entry.downJumpPending);
        assertNotNull(steeringTarget);
        assertTrue(steeringTarget.y > bot.getPosition().y);
        assertTrue(steeringTarget.x > 200 || steeringTarget.x < 0,
                "fallback should steer past a legal ledge so normal walk-off physics handles the drop");
    }

    @Test
    void shouldNotPreemptivelyDownJumpWhileFollowingDownSameSlopeInFallback() {
        MapleMap map = new MapleMap(910000205, 0, 0, 910000205, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(400, 240), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(40, 114), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.graphWarmupFallback = true;

        Point target = new Point(320, 212);
        Point steeringTarget = BotFallbackMovementManager.resolveSteeringTarget(entry, bot.getPosition(), target);
        boolean immediateAction = BotFallbackMovementManager.tryImmediateAction(entry, bot.getPosition(), target);

        assertFalse(immediateAction);
        assertFalse(entry.downJumpPending);
        assertEquals(target, steeringTarget);
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
        when(bot.getStance()).thenAnswer(invocation -> stance.get());
        doAnswer(invocation -> {
            stance.set(invocation.getArgument(0));
            return null;
        }).when(bot).setStance(anyInt());
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        return bot;
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

    private static MapleMap topRopeSyntheticMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(80, 100), new Point(120, 100), 1));
        map.setFootholds(footholds);
        map.addRope(new Rope(100, 100, 200, false));
        return map;
    }

    private static boolean hasSelfLoopPortal(List<BotNavigationGraph.Edge> path) {
        for (BotNavigationGraph.Edge e : path) {
            if (e.type == BotNavigationGraph.EdgeType.PORTAL && e.fromRegionId == e.toRegionId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kerning City regression (nav-graph backed — run explicitly:
     * mvn test -Dtest=BotNavigationManagerTest#excludeSelfLoopPortalsStillReachesViaWalk).
     *
     * A bot on the far-west platform routing to the east00 portal platform: the cheapest route teleports
     * through Kerning's intra-region shortcut portal (a PORTAL self-loop, fromRegion==toRegion). The
     * committed-route follower can't traverse one, so it used to bail and freeze (live: RougHWealtH stuck
     * job-advancing to BANDIT, nav=no-path). The target IS reachable by plain walk/jump/climb, so a search
     * that excludes self-loop portals must still reach it — and its path must contain no self-loop portal.
     */
    @Test
    void excludeSelfLoopPortalsStillReachesViaWalk() {
        MapleMap map = kerning();
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map);

        Point west = new Point(-1180, 6);     // far-west platform (live stuck position)
        Point east00 = new Point(2512, -204); // east00 portal -> map 102050000
        int fromReg = graph.findRegionId(map, west);
        int toReg = BotNavigationManager.resolvePointTargetRegionId(graph, map, east00);
        assertNotEquals(fromReg, toReg, "scenario assumes a cross-region route");

        // Premise: the optimal route uses the in-map shortcut portal (self-loop).
        BotNavigationManager.SearchOutcome optimal = BotNavigationManager.runSearch(
                graph, map, west, fromReg, toReg, east00, "committed",
                true, false, 0L, false, null, BotNavigationManager.MAX_EDGE_CHECKS, null, 0, false);
        assertTrue(optimal.reached(), "optimal route should reach the portal platform");
        assertTrue(hasSelfLoopPortal(optimal.path()),
                "premise: cheapest route teleports through the intra-region shortcut portal");

        // Fix: excluding self-loop portals (with the committed-route re-search budget) still reaches the
        // target by walking, with no self-loop in the path.
        BotNavigationManager.SearchOutcome portalFree = BotNavigationManager.runSearch(
                graph, map, west, fromReg, toReg, east00, "committed",
                true, false, 0L, false, null, BotNavigationManager.PORTAL_FREE_EDGE_CHECKS, null, 0, true);
        assertTrue(portalFree.reached(), "target must be reachable without the shortcut portal");
        assertFalse(hasSelfLoopPortal(portalFree.path()),
                "excluded route must not contain a self-loop portal the follower can't traverse");
    }
}
