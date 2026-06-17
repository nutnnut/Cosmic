package server.bots;

import client.Character;
import constants.game.CharacterStance;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class BotMovementManagerTest {
    @Test
    void shouldClampGrindingTargetAwayFromCurrentFootholdEdgeForSameFootholdCombat() {
        MapleMap map = new MapleMap(910000007, 0, 0, 910000007, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold foothold = new Foothold(new Point(0, 100), new Point(200, 100), 1);
        footholds.insert(foothold);
        map.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mockBot(new Point(100, 100), map);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.grinding = true;

        Point adjusted = BotMovementManager.adjustGrindingTargetPosition(entry, foothold, new Point(190, 100));

        assertEquals(new Point(160, 100), adjusted);
    }

    @Test
    void shouldNotClampGrindingTargetWhenTargetIsOnDifferentFoothold() {
        MapleMap map = new MapleMap(910000008, 0, 0, 910000008, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold leftFoothold = new Foothold(new Point(-200, 100), new Point(0, 100), 1);
        Foothold rightFoothold = new Foothold(new Point(1, 100), new Point(200, 100), 2);
        footholds.insert(leftFoothold);
        footholds.insert(rightFoothold);
        map.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mockBot(new Point(-100, 100), map);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.grinding = true;

        Point targetPos = new Point(190, 100);
        Point adjusted = BotMovementManager.adjustGrindingTargetPosition(entry, leftFoothold, targetPos);

        assertEquals(targetPos, adjusted);
    }

    @Test
    void shouldClampGrindingTargetAcrossEntireCurrentRegionInsteadOfSingleFoothold() {
        MapleMap map = new MapleMap(910000023, 0, 0, 910000023, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold leftFoothold = new Foothold(new Point(-200, 100), new Point(0, 100), 1);
        Foothold rightFoothold = new Foothold(new Point(0, 100), new Point(200, 100), 2);
        leftFoothold.setNext(2);
        rightFoothold.setPrev(1);
        footholds.insert(leftFoothold);
        footholds.insert(rightFoothold);
        map.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mockBot(new Point(-150, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.grinding = true;

        Point adjusted = BotMovementManager.adjustGrindingTargetPosition(entry, leftFoothold, new Point(190, 100));

        assertEquals(new Point(160, 100), adjusted);
    }

    @Test
    void shouldNotClampGrindingTargetForSmallRegion() {
        MapleMap map = new MapleMap(910000024, 0, 0, 910000024, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold foothold = new Foothold(new Point(0, 100), new Point(60, 100), 1);
        footholds.insert(foothold);
        map.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mockBot(new Point(20, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.grinding = true;

        Point targetPos = new Point(55, 100);
        Point adjusted = BotMovementManager.adjustGrindingTargetPosition(entry, foothold, targetPos);

        assertEquals(targetPos, adjusted);
    }

    @Test
    void shouldNotAirSteerCommittedRopeExitClimbArc() {
        MapleMap map = new MapleMap(910000025, 0, 0, 910000025, 1.0f);
        map.setFootholds(new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000)));
        Character bot = mockBot(new Point(0, 0), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.airVelX = -8;
        entry.physX = 0;
        entry.physY = 0;
        entry.velY = -10f;
        entry.navEdge = new BotNavigationGraph.Edge(
                25, 14, BotNavigationGraph.EdgeType.CLIMB,
                new Point(-437, -181), new Point(-473, -211),
                -8, 0, -437, -1471, 84, 250
        );

        BotMovementManager.tickAirborne(entry, new Point(300, 0));

        assertEquals(0.0, entry.airSteerVelX);
    }

    @Test
    void shouldNotHoldClimbIdleWhileCommittedClimbEdgeIsActive() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.navEdge = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.CLIMB,
                new Point(0, 0), new Point(0, -100),
                0, 0, 10, -100, 40, 100
        );

        assertFalse(BotMovementManager.shouldHoldClimbIdle(entry, 0, 0));
    }

    @Test
    void shouldAllowIdleClimbHoldWithoutCommittedClimbEdge() {
        BotEntry entry = new BotEntry(null, null, null);

        assertTrue(BotMovementManager.shouldHoldClimbIdle(entry, 0, 0));
    }

    @Test
    void shouldAllowWalkingAlongUpperPlatformWhenExactGroundProbeWouldHitLowerPlatform() {
        MapleMap map = mock(MapleMap.class);
        when(map.getPointBelow(any(Point.class))).thenAnswer(invocation -> {
            Point probe = invocation.getArgument(0);
            if (probe.y >= 151) {
                return new Point(probe.x, 215);
            }
            return new Point(probe.x, 150);
        });
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);

        assertTrue(BotPhysicsEngine.canWalkGroundStep(map, new Point(-73, 151), 8));
    }

    @Test
    void shouldHoldCommittedClimbEdgeWhenAlreadyAtAnchorY() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(668, 1757));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(668, 1727, 1980, false);
        entry.navEdge = new BotNavigationGraph.Edge(
                68, 54, BotNavigationGraph.EdgeType.CLIMB,
                new Point(668, 1757), new Point(796, 2025),
                8, 0, 668, 1727, 1980, 650
        );

        BotMovementManager.tickClimbing(entry, new Point(668, 1757), false);

        assertEquals(new Point(668, 1757), bot.getPosition());
    }

    @Test
    void shouldSnapCommittedClimbEdgeWhenAnchorIsWithinSingleClimbStep() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(-437, -1142));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(-437, -1471, 84, false);
        entry.navEdge = new BotNavigationGraph.Edge(
                25, 2, BotNavigationGraph.EdgeType.CLIMB,
                new Point(-437, -1141), new Point(-477, -1166),
                -8, 0, -437, -1471, 84, 250
        );
        entry.navPreciseTarget = true;

        BotMovementManager.tickClimbing(entry, new Point(-437, -1141), true);

        assertEquals(new Point(-437, -1141), bot.getPosition(),
                "climb movement should snap to a precise anchor that is closer than one climb step");
    }

    @Test
    void shouldNotSnapPreciseClimbTargetOutsideRopeSpan() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(3398, 126, 332, false);
        entry.navPreciseTarget = true;

        // Above the rope (y <= topY) and strictly below it (y > bottomY) must reject snap.
        // Snap AT bottomY is allowed for rope-exit launch anchors authored at the rope bottom
        // (pathlog-Leroy/John); see shouldSnapCommittedClimbEdgeAtRopeBottomYAnchor.
        assertFalse(BotMovementManager.shouldSnapToClimbTarget(entry, new Point(3398, 124), -2));
        assertFalse(BotMovementManager.shouldSnapToClimbTarget(entry, new Point(3398, 333), 1));
    }

    @Test
    void shouldSnapCommittedClimbEdgeAtRopeBottomYAnchor() {
        // pathlog-Leroy-2026-05-10T025338, pathlog-John-2026-05-10T050253:
        // CLIMB exit edge with launchStepX != 0 and startPoint.y == rope.bottomY(). Bot grabbed
        // mid-rope, climbed toward the anchor, every fixed-step climb landed past bottomY,
        // beginFall(0,0) detached, and the loop repeated. The precise-target snap was the right
        // mechanism — it just refused to fire at bottomY because of an over-strict bounds check.
        Rope rope = new Rope(2352, 662, 863, false);
        BotEntry entry = new BotEntry(null, null, null);
        entry.climbing = true;
        entry.climbRope = rope;
        entry.navPreciseTarget = true;

        // Bot within one climbStep of the anchor — natural step would overshoot bottomY.
        int dyWithin = BotPhysicsEngine.climbStepPerTick() - 2;
        assertTrue(BotMovementManager.shouldSnapToClimbTarget(
                entry, new Point(2352, rope.bottomY()), dyWithin));
        // dy >= climbStep: the natural step lands at-or-inside the (point) window. Old
        // behavior — no snap, let the integrator advance.
        int dyAtOrPastStep = BotPhysicsEngine.climbStepPerTick();
        assertFalse(BotMovementManager.shouldSnapToClimbTarget(
                entry, new Point(2352, rope.bottomY()), dyAtOrPastStep));
    }

    @Test
    void shouldKeepClimbingUntilPhysicsDismountsTopStepOffExit() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(3398, 126));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);
        when(map.getPointBelow(any(Point.class))).thenAnswer(invocation -> {
            Point probe = invocation.getArgument(0);
            return new Point(probe.x, 124);
        });

        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(3398, 126, 332, false);
        entry.navEdge = new BotNavigationGraph.Edge(
                53, 25, BotNavigationGraph.EdgeType.CLIMB,
                new Point(3398, 156), new Point(3443, 124),
                0, 0, 3398, 126, 332, 400
        );
        entry.navPreciseTarget = true;

        BotMovementManager.tickClimbing(entry, new Point(3398, 124), true);

        assertEquals(new Point(3398, 124), bot.getPosition());
        assertFalse(entry.climbing);
        assertFalse(entry.inAir);
    }

    @Test
    void shouldUseEdgeSpecificPreciseStopDist() {
        // Regression: pathlog-CRASH-2026-04-02 — bot 2px from CLIMB entry (969 vs 967),
        // stopDist=4 caused it to idle short of the entry, blocking canExecuteClimbEntry forever.
        // CLIMB still needs stopDist=1 to reach the exact anchor, but JUMP uses a launch window
        // and must keep walking until it is inside that window, so stopDist=0 is intentional.
        BotNavigationGraph.Edge climbEdge = new BotNavigationGraph.Edge(
                3, 27, BotNavigationGraph.EdgeType.CLIMB,
                new Point(967, 1545), new Point(879, 1545),
                0, 0, 879, 1503, 1545, 787
        );
        BotNavigationGraph.Edge jumpEdge = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(100, 0), new Point(200, -50),
                8, 0, 0, 0, 0, 300
        );
        BotNavigationGraph.Edge downJumpEdge = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.DROP,
                new Point(100, 0), new Point(100, 120),
                96, 104, 0, 0, 0, 0, 0, 250
        );
        BotNavigationGraph.Edge walkEdge = new BotNavigationGraph.Edge(
                358, 355, BotNavigationGraph.EdgeType.WALK,
                new Point(46, -61), new Point(54, -58),
                0, 0, 0, 0, 0, 100
        );

        assertEquals(1, BotMovementManager.preciseNavStopDist(climbEdge),
                "CLIMB entry must use stopDist=1 to reach exact anchor");
        assertEquals(0, BotMovementManager.preciseNavStopDist(jumpEdge),
                "JUMP entry must use stopDist=0 so the bot walks into the launch window");
        assertEquals(0, BotMovementManager.preciseNavStopDist(downJumpEdge),
                "straight down-jump DROP entry must use stopDist=0 so the bot walks into the launch window");
        assertEquals(4, BotMovementManager.preciseNavStopDist(walkEdge),
                "WALK traversal keeps stopDist=4 to absorb terrain micro-bumps");
        assertEquals(4, BotMovementManager.preciseNavStopDist(null),
                "null edge falls back to WALK tolerance");
    }

    @Test
    void shouldClearCommittedWalkEdgeWhenNextGroundStepIsNotWalkable() {
        MapleMap map = new MapleMap(910000011, 0, 0, 910000011, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(10, 100), 1));
        map.setFootholds(footholds);

        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(8, 100));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.navEdge = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.WALK,
                new Point(8, 100), new Point(60, 100),
                0, 0, 0, 0, 0, 100
        );
        entry.navPreciseTarget = true;

        BotMovementManager.tickGrounded(entry, new Point(60, 100));

        assertNull(entry.navEdge);
        assertEquals(new Point(8, 100), bot.getPosition());
    }

    @Test
    void shouldJumpForwardWhenMobBlocksWalkLaneAndLandingStaysInCurrentRegion() {
        MapleMap map = spy(new MapleMap(910009048, 0, 0, 910009048, 1.0f));
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(map);
        doReturn(List.of(mockMob(new Point(130, 100), 100100))).when(map).getAllMonsters();

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;

        BotMovementManager.tickGrounded(entry, new Point(250, 100));

        assertTrue(entry.inAir, "grounded follow movement should jump over a mob blocking the walk lane");
        assertEquals(BotPhysicsEngine.walkStep(map, entry.movementProfile), entry.airVelX);

        BotMovementManager.tickAirborne(entry, new Point(250, 100));

        assertEquals(0.0, entry.airSteerVelX, 0.0001,
                "mob-avoid jumps should keep the simulated fixed forward arc");
    }

    @Test
    void shouldNotJumpOverBlockingMobWhenSimulatedLandingLeavesCurrentRegion() {
        MapleMap map = spy(new MapleMap(910009049, 0, 0, 910009049, 1.0f));
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(140, 100), 1));
        map.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(map);
        doReturn(List.of(mockMob(new Point(120, 100), 100100))).when(map).getAllMonsters();

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;

        BotMovementManager.tickGrounded(entry, new Point(190, 100));

        assertFalse(entry.inAir, "mob-avoid jump should be skipped when simulation would leave the current platform region");
    }

    @Test
    void shouldKeepClimbingTowardCommittedExitAnchorWhenStillAboveIt() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(-157, -28));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(-157, -115, 118, false);
        entry.navEdge = new BotNavigationGraph.Edge(
                47, 39, BotNavigationGraph.EdgeType.CLIMB,
                new Point(-157, -25), new Point(-61, 121),
                8, 0, -157, -115, 118, 650
        );

        BotMovementManager.tickClimbing(entry, new Point(-157, -25), false);

        assertEquals(new Point(-157, -23), bot.getPosition());
    }

    @Test
    void shouldLandOnIntermediateBumpDuringAirborneTick() {
        MapleMap map = new MapleMap(910000005, 0, 0, 910000005, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 110), new Point(20, 110), 1));
        footholds.insert(new Foothold(new Point(4, 102), new Point(6, 102), 2));
        map.setFootholds(footholds);

        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(0, 100));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 0;
        entry.physY = 100;
        entry.velY = 0f;
        entry.airVelX = 8;

        BotMovementManager.tickAirborne(entry, null);

        assertEquals(new Point(4, 102), bot.getPosition());
        assertFalse(entry.inAir);
    }

    @Test
    void shouldKeepHorizontalMomentumWhenDroppingPastWallEndpoint() {
        MapleMap map = new MapleMap(910000006, 0, 0, 910000006, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 0), new Point(20, 0), 1));
        footholds.insert(new Foothold(new Point(0, 0), new Point(0, 80), 2));
        map.setFootholds(footholds);

        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(0, 0));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 0;
        entry.physY = 0;
        entry.velY = 0f;
        entry.airVelX = -8;

        BotMovementManager.tickAirborne(entry, null);

        assertTrue(bot.getPosition().x < 0);
        assertEquals(-8, entry.airVelX);
    }

    @Test
    void shouldJumpAcrossSmallGapDuringGraphWarmupFallback() {
        MapleMap map = new MapleMap(910000031, 0, 0, 910000031, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(40, 100), 1));
        footholds.insert(new Foothold(new Point(80, 100), new Point(140, 100), 2));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(36, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.graphWarmupFallback = true;

        BotMovementManager.tickGrounded(entry, new Point(110, 100));

        assertTrue(entry.inAir, "graph warmup fallback should jump small same-level gaps instead of freezing");
        assertEquals(BotPhysicsEngine.walkStep(map, entry.movementProfile), entry.airVelX);
    }

    @Test
    void shouldJumpOntoReachablePlatformWhenFallbackWalksIntoWall() {
        MapleMap map = new MapleMap(910000050, 0, 0, 910000050, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold lower = new Foothold(new Point(0, 100), new Point(50, 100), 1);
        Foothold wall = new Foothold(new Point(50, 60), new Point(50, 100), 2);
        Foothold upper = new Foothold(new Point(50, 60), new Point(120, 60), 3);
        wall.setNext(lower.getId());
        wall.setPrev(upper.getId());
        footholds.insert(lower);
        footholds.insert(wall);
        footholds.insert(upper);
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(44, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.graphWarmupFallback = true;

        BotMovementManager.tickGrounded(entry, new Point(90, 60));

        assertTrue(entry.inAir, "fallback should jump when a wall blocks walking but a platform is reachable");
        assertEquals(BotPhysicsEngine.walkStep(map, entry.movementProfile), entry.airVelX);
    }

    @Test
    void shouldAttachNearbyRopeDuringGraphWarmupFallbackWhenTargetIsAbove() {
        MapleMap map = new MapleMap(910000032, 0, 0, 910000032, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 120), new Point(200, 120), 1));
        map.setFootholds(footholds);
        map.addRope(new Rope(100, 40, 120, false));

        Character bot = mockBot(new Point(100, 120), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.graphWarmupFallback = true;

        BotMovementManager.tickGrounded(entry, new Point(100, 40));

        assertTrue(entry.climbing, "graph warmup fallback should use a nearby rope for vertical travel");
        assertEquals(new Point(100, 120), bot.getPosition());
    }

    @Test
    void shouldUseStopFollowHysteresisInsteadOfPacingSameRegionFollowMovement() {
        MapleMap map = new MapleMap(910000033, 0, 0, 910000033, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(0, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.observedOwnerStepX = 4;

        int stoppedStep = BotMovementManager.resolveGroundStepX(
                entry, new Point(0, 100), new Point(20, 100), BotMovementManager.cfg.STOP_DIST, BotMovementManager.cfg.FOLLOW_DIST);
        assertEquals(0, stoppedStep,
                "follow should stop anywhere inside STOP_DIST instead of micro-throttling to an exact point");

        int walkStep = BotPhysicsEngine.walkStep(map, entry.movementProfile);
        int followStep = BotMovementManager.resolveGroundStepX(
                entry, new Point(0, 100), new Point(90, 100), BotMovementManager.cfg.STOP_DIST, BotMovementManager.cfg.FOLLOW_DIST);

        assertEquals(walkStep, followStep,
                "follow should restart at FOLLOW_DIST using normal full-speed movement");
    }

    @Test
    void shouldUseFullWalkStepForFastFollowBotsInsteadOfPacing() {
        MapleMap map = new MapleMap(910000034, 0, 0, 910000034, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(0, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.wasMovingX = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        entry.observedOwnerStepX = 4;

        int walkStep = BotPhysicsEngine.walkStep(map, entry.movementProfile);
        int step = BotMovementManager.resolveGroundStepX(
                entry, new Point(0, 100), new Point(60, 100), BotMovementManager.cfg.STOP_DIST, BotMovementManager.cfg.FOLLOW_DIST);

        assertEquals(walkStep, step,
                "fast follow bots should keep full walk speed instead of being micro-throttled");
    }

    @Test
    void shouldShowStandingStanceWhileGroundVelocityDeceleratesWithoutMoveInput() {
        MapleMap map = new MapleMap(910000035, 0, 0, 910000035, 1.0f);
        Character bot = mockBot(new Point(0, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = false;
        entry.movementVelX = 80;
        entry.moveDir = 0;
        entry.facingDir = 1;

        assertTrue(BotPhysicsEngine.isStandingStance(BotPhysicsEngine.resolveStance(entry)),
                "residual ground velocity should not force a walking stance when no move key is held");
    }

    @Test
    void shouldNotUseSpeedMismatchFidgetWhenOwnerIsIdle() {
        MapleMap map = new MapleMap(910000041, 0, 0, 910000041, 1.0f);
        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        entry.observedOwnerStepX = 0;
        entry.observedOwnerStepY = 0;

        assertFalse(BotFidgetManager.shouldStartSpeedMismatchFidget(entry, new Point(100, 100), new Point(110, 100)),
                "idle owners should use the long idle-fidget roll, not the active follow speed-mismatch fidget");

        entry.observedOwnerStepX = 4;

        assertTrue(BotFidgetManager.shouldStartSpeedMismatchFidget(entry, new Point(100, 100), new Point(110, 100)),
                "slow-but-moving owners remain eligible for speed-mismatch follow fidgets");
    }

    @Test
    void shouldHoldProneWhileFidgetIsActive() {
        MapleMap map = new MapleMap(910000036, 0, 0, 910000036, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        BotFidgetManager.startFidget(entry, BotFidgetMode.PRONE, System.currentTimeMillis(), 3000);

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertTrue(entry.crouching);
    }

    @Test
    void shouldNotChangeDirectionForProneFidgets() {
        MapleMap map = new MapleMap(910000048, 0, 0, 910000048, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.facingDir = -1;
        BotFidgetManager.startFidget(entry, BotFidgetMode.PRONE, System.currentTimeMillis(), 3000);

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertEquals(-1, entry.facingDir, "prone fidget should keep the current facing direction");
        assertEquals(CharacterStance.PRONE_LEFT_STANCE, bot.getStance(),
                "prone fidget should send left-facing prone stance");

        BotFidgetManager.clear(entry);
        entry.facingDir = -1;
        entry.crouching = false;
        BotFidgetManager.startFidget(entry, BotFidgetMode.SPAM_PRONE, System.currentTimeMillis(), 3000);

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertEquals(-1, entry.facingDir, "spam-prone fidget should not synthesize a turn input");
        assertEquals(CharacterStance.PRONE_LEFT_STANCE, bot.getStance(),
                "spam-prone fidget should send left-facing prone stance");
    }

    @Test
    void shouldAllowSocialFidgetsAtBaseMoveSpeed() {
        MapleMap map = new MapleMap(910000038, 0, 0, 910000038, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(100, 100);
        BotFidgetManager.startFidget(entry, BotFidgetMode.PRONE, System.currentTimeMillis(), 3000, BotFidgetTrigger.SOCIAL);

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertTrue(entry.crouching);
    }

    @Test
    void shouldAllowAutoFollowFidgetsAtBaseMoveSpeed() {
        MapleMap map = new MapleMap(910000047, 0, 0, 910000047, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(100, 100);
        BotFidgetManager.startFidget(entry, BotFidgetMode.PRONE, System.currentTimeMillis(), 3000, BotFidgetTrigger.AUTO_FOLLOW);

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertTrue(entry.crouching, "base-speed follow fidgets should not be blocked by a speed-stat guard");
    }

    @Test
    void shouldAlternateDirectionalFidgetJumps() {
        MapleMap map = new MapleMap(910000039, 0, 0, 910000039, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        BotFidgetManager.startFidget(entry, BotFidgetMode.DIAGONAL_JUMP, System.currentTimeMillis(), 3000);

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        int firstJumpVelX = entry.airVelX;
        assertTrue(firstJumpVelX != 0, "diagonal jump fidget should launch with horizontal momentum");

        BotPhysicsEngine.idleOnGround(entry, bot);
        entry.nextFidgetJumpAtMs = 0L;

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertEquals(-Integer.signum(firstJumpVelX), Integer.signum(entry.airVelX),
                "diagonal jump fidget should alternate jump direction on the next grounded launch");
    }

    @Test
    void shouldKeepJumpFidgetRunningWhileAirborne() {
        MapleMap map = new MapleMap(910000040, 0, 0, 910000040, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        BotFidgetManager.startFidget(entry, BotFidgetMode.JUMP, System.currentTimeMillis(), 3000);

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertTrue(entry.inAir);

        bot.setPosition(new Point(100, 0));
        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertEquals(BotFidgetMode.JUMP, entry.fidgetMode,
                "jump fidgets should not clear themselves while airborne above the ground target");
    }

    @Test
    void shouldRepeatJumpFidgetAfterLandingUntilDurationEnds() {
        MapleMap map = new MapleMap(910000042, 0, 0, 910000042, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        BotFidgetManager.startFidget(entry, BotFidgetMode.JUMP, System.currentTimeMillis(), 3000);

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertTrue(entry.inAir);

        BotPhysicsEngine.idleOnGround(entry, bot);
        entry.nextFidgetActionAtMs = Long.MAX_VALUE;
        entry.nextFidgetJumpAtMs = 0L;

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertTrue(entry.inAir, "grounded jump fidgets should launch again even if air steering is cooling down");
    }

    @Test
    void shouldOnlySpamAirSteerWhenJumpFidgetRollEnablesIt() {
        MapleMap map = new MapleMap(910000046, 0, 0, 910000046, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        BotFidgetManager.startFidget(entry, BotFidgetMode.JUMP, System.currentTimeMillis(), 3000);
        BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true);

        entry.fidgetSpamAirSteer = false;
        entry.airSteerVelX = 0.0;
        entry.nextFidgetActionAtMs = 0L;

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        // No key held on a non-spam fidget hop: the only allowed airSteerVelX change is the
        // CalcFloat no-input drag (1 x fs px/s^2 = 0.0025 px/tick per tick) — a random
        // steering press would move it by ~0.5 px/tick.
        assertEquals(0.0, entry.airSteerVelX, 0.005,
                "non-spam jump fidgets should not reroll random air steering every airborne tick");

        entry.fidgetSpamAirSteer = true;
        entry.fidgetActionBaseDelayMs = 100;
        entry.airSteerVelX = 0.0;
        // Zero the launch momentum: under the packet-true air model a side press in the SAME
        // direction as a full-speed launch is pinned at the walk-speed cap (no velocity
        // change), which would make this assertion depend on the random roll's direction.
        entry.airVelX = 0;
        entry.nextFidgetActionAtMs = 0L;
        long before = System.currentTimeMillis();

        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertTrue(entry.airSteerVelX != 0.0,
                "spam-air-steer jump fidgets should press random side input on their own delay");
        long after = System.currentTimeMillis();
        assertTrue(entry.nextFidgetActionAtMs >= before + 100
                        && entry.nextFidgetActionAtMs <= after + 150,
                "air-steer spam should use a tick-aligned 0/50ms jitter");
    }

    @Test
    void shouldSpamSidewaysDuringFidgetWithoutDroppingFollowMode() {
        MapleMap map = new MapleMap(910000043, 0, 0, 910000043, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        BotFidgetManager.startFidget(entry, BotFidgetMode.SPAM_SIDEWAYS, System.currentTimeMillis(), 3000);
        assertTrue(entry.fidgetActionBaseDelayMs >= 100 && entry.fidgetActionBaseDelayMs <= 250);
        assertEquals(0, entry.fidgetActionBaseDelayMs % BotPhysicsEngine.cfg.TICK_MS);

        long before = System.currentTimeMillis();
        assertTrue(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertEquals(BotFidgetMode.SPAM_SIDEWAYS, entry.fidgetMode);
        assertTrue(entry.fidgetMoveDir != 0, "sideway spam should keep an active sideways fidget direction");
        assertTrue(bot.getPosition().x != 100, "sideway spam should cause sideways motion during the tick");
        long after = System.currentTimeMillis();
        assertTrue(entry.nextFidgetActionAtMs >= before + entry.fidgetActionBaseDelayMs
                        && entry.nextFidgetActionAtMs <= after + entry.fidgetActionBaseDelayMs + 50,
                "sideway spam should use tick-aligned 0/50ms jitter around its per-fidget base interval");
        assertTrue(entry.following, "sideway spam should not convert follow mode into a manual move command");
    }

    @Test
    void shouldContinueFollowingAfterAutoFidgetEnds() {
        MapleMap map = new MapleMap(910000044, 0, 0, 910000044, 1.0f);
        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        long now = System.currentTimeMillis();
        BotFidgetManager.startFidget(entry, BotFidgetMode.SPAM_SIDEWAYS, now, 2000);
        bot.setPosition(new Point(130, 100));
        entry.fidgetUntilMs = now - 1;

        assertFalse(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertEquals(BotFidgetMode.NONE, entry.fidgetMode);
        assertNull(entry.moveTarget, "speed-mismatch follow fidgets should resume following immediately");
        assertFalse(entry.moveTargetPrecise);
    }

    @Test
    void shouldReturnToFidgetOriginWithPreciseMoveTargetAfterIdleOrSocialFidgetEnds() {
        MapleMap map = new MapleMap(910000045, 0, 0, 910000045, 1.0f);
        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.movementProfile = new BotMovementProfile(140, 100);
        long now = System.currentTimeMillis();
        BotFidgetManager.startFidget(entry, BotFidgetMode.SPAM_SIDEWAYS, now, 2000, BotFidgetTrigger.SOCIAL);
        bot.setPosition(new Point(130, 100));
        entry.fidgetUntilMs = now - 1;

        assertFalse(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertEquals(new Point(100, 100), entry.moveTarget,
                "social fidget cleanup should reuse the precise move-target path from the here command");
        assertTrue(entry.moveTargetPrecise);

        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        bot.setPosition(new Point(130, 100));
        BotFidgetManager.startFidget(entry, BotFidgetMode.SPAM_SIDEWAYS, now, 2000, BotFidgetTrigger.IDLE);
        bot.setPosition(new Point(160, 100));
        entry.fidgetUntilMs = now - 1;

        assertFalse(BotFidgetManager.tryHandleTick(entry, new Point(110, 100), true));
        assertEquals(new Point(130, 100), entry.moveTarget,
                "idle fidget cleanup should return to its own recorded origin");
        assertTrue(entry.moveTargetPrecise);
    }

    @Test
    void shouldNotUseDownJumpForUnstuckRecovery() {
        MapleMap map = new MapleMap(910000037, 0, 0, 910000037, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        map.setFootholds(footholds);

        Character bot = mockBot(new Point(100, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);

        BotMovementManager.tickUnstuck(entry);

        assertFalse(entry.downJumpPending, "unstuck recovery should only use lateral jumps");
        assertTrue(entry.inAir, "unstuck recovery should launch the bot instead of crouching in place");
    }

    @Test
    void shouldNotApplyAirSteeringDuringCommittedNavJump() {
        MapleMap map = new MapleMap(910000009, 0, 0, 910000009, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(-200, 200), new Point(200, 200), 1));
        map.setFootholds(footholds);

        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(100, 100));
        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(1);
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 100;
        entry.physY = 100;
        entry.velY = 0f;
        entry.airVelX = -8;
        entry.navEdge = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(100, 100), new Point(50, 50),
                -8, 0, 0, 0, 0, 300
        );

        BotMovementManager.tickAirborne(entry, new Point(-300, 100));

        assertEquals(0.0, entry.airSteerVelX, 0.0001);
        assertEquals(new Point(92, 103), bot.getPosition());
    }

    @Test
    void shouldKeepRopeGrabEnabledWhenJumpingFromRopeToRope() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(668, 1757));
        when(bot.getHp()).thenReturn(100);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        entry.climbRope = new Rope(668, 1727, 1980, false);

        BotMovementManager.jumpToRope(entry, bot, 8);

        assertTrue(entry.inAir);
        assertTrue(entry.climbUpIntent);
        assertEquals(0, entry.ropeGrabCooldownMs);
        assertEquals(668, entry.blockedRopeGrab.x());
    }

    @Test
    void shouldSwapMovementProfileWhileBucketGraphWarms() {
        MapleMap map = new MapleMap(910000027, 0, 0, 910000027, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        map.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());

        Character bot = mockBot(new Point(20, 100), map);
        when(bot.getTotalMoveSpeedStat()).thenReturn(109);
        when(bot.getTotalJumpStat()).thenReturn(107);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.movementProfile = BotMovementProfile.base();

        BotMovementProfile targetProfile = BotMovementProfile.fromCharacter(bot);
        assertEquals(new BotMovementProfile(110, 105), targetProfile);
        entry.navEdge = new BotNavigationGraph.Edge(
                1, 2, BotNavigationGraph.EdgeType.JUMP,
                new Point(20, 100), new Point(80, 40),
                8, 0, 0, 0, 0, 300
        );
        entry.navTargetPos = new Point(20, 100);
        entry.navTargetRegionId = 2;
        entry.navPreciseTarget = true;

        assertTrue(BotMovementManager.refreshMovementProfile(entry),
                "profile swap should commit immediately and let nav use closest graph while the exact graph warms");
        assertEquals(targetProfile, entry.movementProfile);
        assertNull(entry.navEdge);
        assertNull(entry.navTargetPos);
        assertEquals(-1, entry.navTargetRegionId);
        assertFalse(entry.navPreciseTarget);
    }

    private static BotNavigationGraph.Edge edge(BotNavigationGraph.EdgeType type) {
        return new BotNavigationGraph.Edge(0, 1, type, new Point(0, 0), new Point(100, 0),
                1, 0, 0, 0, 0, 0);
    }

    @Test
    void dodgeModeAllowedOnlyDuringGroundLocomotionOffLaunchEdges() {
        // Idle (not following/grinding/traveling): never dodge.
        assertFalse(BotMovementManager.dodgeModeAllowed(false, false, false, null, false));

        // Following with free walking (no committed edge): dodge allowed.
        assertTrue(BotMovementManager.dodgeModeAllowed(true, false, false, null, false));

        // Grinding: allowed.
        assertTrue(BotMovementManager.dodgeModeAllowed(false, true, false, null, false));

        // Traveling (map-to-map autopilot, grinding not yet set): now allowed on plain ground too.
        assertTrue(BotMovementManager.dodgeModeAllowed(false, false, true, null, false));
        assertTrue(BotMovementManager.dodgeModeAllowed(false, false, true, edge(BotNavigationGraph.EdgeType.WALK), false));

        // THE GAP: a committed WALK edge is still plain ground walking, so dodge must be allowed across it.
        assertTrue(BotMovementManager.dodgeModeAllowed(false, true, false, edge(BotNavigationGraph.EdgeType.WALK), false));

        // Non-WALK edges have launch windows a dodge would wreck: never dodge mid JUMP/DROP/CLIMB/PORTAL
        // (true even while traveling).
        assertFalse(BotMovementManager.dodgeModeAllowed(false, true, false, edge(BotNavigationGraph.EdgeType.JUMP), false));
        assertFalse(BotMovementManager.dodgeModeAllowed(false, true, false, edge(BotNavigationGraph.EdgeType.DROP), false));
        assertFalse(BotMovementManager.dodgeModeAllowed(false, true, false, edge(BotNavigationGraph.EdgeType.CLIMB), false));
        assertFalse(BotMovementManager.dodgeModeAllowed(false, true, false, edge(BotNavigationGraph.EdgeType.PORTAL), false));
        assertFalse(BotMovementManager.dodgeModeAllowed(false, false, true, edge(BotNavigationGraph.EdgeType.JUMP), false));

        // Precise nav target steering: never dodge even on a WALK edge (incl. while traveling near the portal).
        assertFalse(BotMovementManager.dodgeModeAllowed(false, true, false, edge(BotNavigationGraph.EdgeType.WALK), true));
        assertFalse(BotMovementManager.dodgeModeAllowed(true, false, false, null, true));
        assertFalse(BotMovementManager.dodgeModeAllowed(false, false, true, edge(BotNavigationGraph.EdgeType.WALK), true));
    }

    private static Character mockBot(Point startPosition, MapleMap map) {
        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        AtomicInteger stance = new AtomicInteger();
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

    private static Monster mockMob(Point position, int id) {
        Monster mob = mock(Monster.class);
        when(mob.getPosition()).thenReturn(new Point(position));
        when(mob.getId()).thenReturn(id);
        when(mob.isAlive()).thenReturn(true);
        when(mob.isFacingLeft()).thenReturn(false);
        return mob;
    }
}
