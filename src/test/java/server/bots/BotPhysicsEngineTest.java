package server.bots;

import client.Character;
import constants.game.CharacterStance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Foothold;
import server.maps.Rope;

import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotPhysicsEngineTest {
    private static final Supplier<MapleMap> henesysS = lazyMap(100000000);
    private static final Supplier<MapleMap> elliniaWeaponStoreS = lazyMap(101000001);
    private static final Supplier<MapleMap> kerningS = lazyMap(103000000);
    private static final Supplier<MapleMap> kerningPharmacyS = lazyMap(103000002);
    private static final Supplier<MapleMap> kpqS1S = lazyMap(103000800);
    private static final Supplier<MapleMap> mushroomShrineS = lazyMap(800000000);
    private static final Supplier<MapleMap> sleepyForestS = lazyMap(105040400);

    private static MapleMap henesys() { return henesysS.get(); }
    private static MapleMap elliniaWeaponStore() { return elliniaWeaponStoreS.get(); }
    private static MapleMap kerning() { return kerningS.get(); }
    private static MapleMap kerningPharmacy() { return kerningPharmacyS.get(); }
    private static MapleMap kpqS1() { return kpqS1S.get(); }
    private static MapleMap mushroomShrine() { return mushroomShrineS.get(); }
    private static MapleMap sleepyForest() { return sleepyForestS.get(); }

    private static Supplier<MapleMap> lazyMap(int mapId) {
        Supplier<MapleMap> delegate = () -> BotNavigationMapLoader.loadMapGeometry(mapId);
        return new Supplier<>() {
            private volatile MapleMap value;
            @Override public MapleMap get() {
                MapleMap v = value;
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
    void shouldSharePhysicsConfigBetweenMovementManagerAndEngine() {
        assertSame(BotMovementManager.cfg, BotPhysicsEngine.cfg);
    }

    @Test
    void shouldSimulateKnownHenesysVerticalJumpLanding() {
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(henesys(), new Point(1080, 334), 0);

        assertNotNull(landing);
        assertEquals(new Point(1080, 275), landing.point());
    }

    @Test
    void shouldTreatNearbySyntheticRopeAsReachableAndFarOffsetAsUnreachable() {
        MapleMap map = createEmptyTestMap(910000101);
        Rope rope = new Rope(100, 40, 160, false);
        map.addRope(rope);

        Point nearPoint = new Point(rope.x() - BotPhysicsEngine.walkStep(map), rope.bottomY());
        Point farPoint = new Point(rope.x() - BotPhysicsEngine.maxJumpHorizontalTravel(map) - 50, rope.bottomY());

        assertTrue(BotPhysicsEngine.canReachRopeFromGround(map, nearPoint, rope));
        assertFalse(BotPhysicsEngine.canReachRopeFromGround(map, farPoint, rope));
    }

    @Test
    void shouldSimulateRopeToRopeGrabAtActualCatchY() {
        MapleMap map = createEmptyTestMap(910000001);
        Rope sourceRope = new Rope(0, 100, 200, false);
        map.addRope(sourceRope);
        Point jumpStart = new Point(sourceRope.x(), 160);
        int stepX = BotPhysicsEngine.walkStep(map);

        Rope targetRope = null;
        Point ropeGrab = null;
        for (int targetX = stepX; targetX <= BotPhysicsEngine.maxRopeJumpHorizontalTravel(map); targetX += stepX) {
            Rope candidate = new Rope(targetX, 120, 220, false);
            Point candidateGrab = BotPhysicsEngine.simulateRopeJumpGrab(map, jumpStart, stepX, candidate);
            if (candidateGrab != null) {
                targetRope = candidate;
                ropeGrab = candidateGrab;
                break;
            }
        }

        assertNotNull(targetRope, "expected a reachable target rope for the current rope-jump physics");
        assertNotNull(ropeGrab);
        assertEquals(targetRope.x(), ropeGrab.x);
        assertTrue(ropeGrab.y > targetRope.topY(),
                "rope grab should use the actual catch Y instead of snapping to the rope top");
        assertTrue(ropeGrab.y <= targetRope.bottomY());
    }

    @Test
    void shouldClearMovementStateOnReset() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.inAir = true;
        entry.climbing = true;
        entry.crouching = true;
        entry.climbUpIntent = true;
        entry.velY = 7f;
        entry.airVelX = 12;
        entry.physX = 99;
        entry.physY = 88;
        entry.movementVelX = 123;
        entry.movementVelY = -456;
        entry.moveDir = -1;
        entry.downJumpPending = true;
        entry.downJumpGracePeriodMS = 350;

        BotPhysicsEngine.resetMotion(entry, new Point(10, 20));

        assertFalse(entry.inAir);
        assertFalse(entry.climbing);
        assertFalse(entry.crouching);
        assertFalse(entry.climbUpIntent);
        assertFalse(entry.downJumpPending);
        assertEquals(0L, entry.downJumpGracePeriodMS);
        assertEquals(10.0, entry.physX);
        assertEquals(20.0, entry.physY);
        assertEquals(0, entry.movementVelX);
        assertEquals(0, entry.movementVelY);
        assertEquals(0, entry.moveDir);
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, BotPhysicsEngine.resolveStance(entry));
    }

    @Test
    void shouldClearWalkIntentWhenIdlingOnGround() {
        Character bot = mockBot(new Point(10, 20), null);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.moveDir = -1;
        entry.facingDir = -1;
        entry.movementVelX = -125;

        BotPhysicsEngine.idleOnGround(entry, bot);

        assertEquals(0, entry.moveDir);
        assertEquals(0, entry.movementVelX);
        assertEquals(CharacterStance.STAND_LEFT_STANCE, BotPhysicsEngine.resolveStance(entry));
        assertEquals(CharacterStance.STAND_LEFT_STANCE, bot.getStance());
    }

    @Test
    void shouldDeriveMovementSnapshotFromPhysicsState() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.inAir = true;
        entry.facingDir = -1;
        entry.movementVelX = -180;
        entry.movementVelY = -240;

        BotPhysicsEngine.MovementSnapshot snapshot = BotPhysicsEngine.movementSnapshot(entry);

        assertEquals(-180, snapshot.velX());
        assertEquals(-240, snapshot.velY());
        assertEquals(CharacterStance.JUMP_LEFT_STANCE, snapshot.stance());
    }

    @Test
    void shouldResolveIdleGroundStanceFromLastFacingDirection() {
        BotEntry entry = new BotEntry(null, null, null);

        entry.facingDir = 1;
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, BotPhysicsEngine.resolveStance(entry));

        entry.facingDir = -1;
        assertEquals(CharacterStance.STAND_LEFT_STANCE, BotPhysicsEngine.resolveStance(entry));
    }

    @Test
    void shouldResolveProneStanceFromLastFacingDirection() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.crouching = true;

        entry.facingDir = 1;
        assertEquals(CharacterStance.PRONE_RIGHT_STANCE, BotPhysicsEngine.resolveStance(entry));

        entry.facingDir = -1;
        assertEquals(CharacterStance.PRONE_LEFT_STANCE, BotPhysicsEngine.resolveStance(entry));
    }

    @Test
    void shouldResolveDeadStanceFromLastFacingDirection() {
        Character bot = mockBot(new Point(10, 20), null, 0);
        BotEntry entry = new BotEntry(bot, null, null);

        entry.facingDir = 1;
        assertEquals(CharacterStance.DEAD_RIGHT_STANCE, BotPhysicsEngine.resolveStance(entry));

        entry.facingDir = -1;
        assertEquals(CharacterStance.DEAD_LEFT_STANCE, BotPhysicsEngine.resolveStance(entry));
    }

    @Test
    void shouldFaceAirSteeringDirectionEvenWhenMomentumIsOpposite() {
        MapleMap map = mock(MapleMap.class);
        when(map.getFootholds()).thenReturn(null);
        when(map.getRopes()).thenReturn(List.of());

        Character bot = mockBot(new Point(100, 200), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.airVelX = 8;
        entry.movementVelX = BotPhysicsEngine.velocityFromDeltaX(entry.airVelX);
        entry.facingDir = 1;
        entry.physX = 100;
        entry.physY = 200;
        entry.moveDir = -1;  // set intent for air steering left

        BotPhysicsEngine.stepAirborne(entry, bot);

        assertTrue(entry.airSteerVelX < 0.0, "left steer intent should produce negative airSteerVelX");
        assertEquals(-1, entry.facingDir, "facing should follow steer direction, not momentum");
        assertEquals(CharacterStance.JUMP_LEFT_STANCE, BotPhysicsEngine.resolveStance(entry));
    }

    @Test
    void shouldUseLadderAndRopeStancesFromClimbState() {
        BotEntry ladderEntry = new BotEntry(null, null, null);
        ladderEntry.climbing = true;
        ladderEntry.climbRope = new Rope(100, 0, 40, true);

        BotEntry ropeEntry = new BotEntry(null, null, null);
        ropeEntry.climbing = true;
        ropeEntry.climbRope = new Rope(100, 0, 40, false);

        assertEquals(CharacterStance.LADDER_STANCE, BotPhysicsEngine.resolveStance(ladderEntry));
        assertEquals(CharacterStance.ROPE_STANCE, BotPhysicsEngine.resolveStance(ropeEntry));
    }

    @Test
    void shouldTickDownDownJumpGraceInsidePhysicsEngine() {
        BotEntry entry = new BotEntry(null, null, null);
        entry.downJumpGracePeriodMS = 120;

        BotPhysicsEngine.tickMotionTimers(entry);

        assertEquals(70, entry.downJumpGracePeriodMS);
        assertFalse(BotPhysicsEngine.canLand(entry));

        BotPhysicsEngine.tickMotionTimers(entry);

        assertEquals(20, entry.downJumpGracePeriodMS);
        assertFalse(BotPhysicsEngine.canLand(entry));

        BotPhysicsEngine.tickMotionTimers(entry);

        assertEquals(0, entry.downJumpGracePeriodMS);
        assertTrue(BotPhysicsEngine.canLand(entry));
    }

    @Test
    void shouldNotBeginDownJumpFromForbidFallDownFoothold() {
        MapleMap map = createEmptyTestMap(910000059);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold upper = new Foothold(new Point(0, 100), new Point(100, 100), 1);
        Foothold lower = new Foothold(new Point(0, 180), new Point(100, 180), 2);
        upper.setForbidFallDown(true);
        footholds.insert(upper);
        footholds.insert(lower);

        Character bot = mockBot(new Point(50, 100), map);
        BotEntry entry = new BotEntry(bot, null, null);
        BotPhysicsEngine.resetMotion(entry, bot.getPosition());
        BotPhysicsEngine.queueDownJump(entry, bot);

        BotPhysicsEngine.beginDownJump(entry, bot);

        assertFalse(entry.inAir);
        assertFalse(entry.crouching);
        assertFalse(entry.downJumpPending);
        assertEquals(0L, entry.downJumpGracePeriodMS);
        assertEquals(new Point(50, 100), bot.getPosition());
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, bot.getStance());
    }

    @Test
    void shouldRejectSimulatedDownJumpFromForbidFallDownFoothold() {
        MapleMap map = createEmptyTestMap(910000060);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold upper = new Foothold(new Point(0, 100), new Point(100, 100), 1);
        Foothold lower = new Foothold(new Point(0, 180), new Point(100, 180), 2);
        upper.setForbidFallDown(true);
        footholds.insert(upper);
        footholds.insert(lower);

        assertNull(BotPhysicsEngine.simulateDownJumpLanding(map, new Point(50, 100)));
    }

    @Test
    void shouldBeginFallWhenClimbDownMovesPastRopeBottom() {
        Character bot = mockBot(new Point(100, 40), null);
        BotEntry entry = new BotEntry(bot, null, null);
        Rope rope = new Rope(100, 0, 40, false);
        BotPhysicsEngine.attachToRope(entry, bot, rope, rope.bottomY());

        entry.climbVerticalDir = 1;  // intent: climb down
        BotPhysicsEngine.advanceClimb(entry, bot);

        assertTrue(entry.inAir);
        assertFalse(entry.climbing);
        assertEquals(new Point(100, 40), bot.getPosition());
    }

    @Test
    void shouldTreatRopeTopAsDismountBoundaryNotAttachPosition() {
        MapleMap map = mock(MapleMap.class);
        when(map.getPointBelow(any(Point.class))).thenAnswer(invocation -> new Point(100, 0));
        Character bot = mockBot(new Point(100, 0), map);
        BotEntry entry = new BotEntry(bot, null, null);
        Rope rope = new Rope(100, 0, 40, false);
        BotPhysicsEngine.attachToRope(entry, bot, rope, rope.topY());

        assertTrue(entry.climbing);
        assertEquals(new Point(100, 1), bot.getPosition());

        BotPhysicsEngine.holdClimb(entry, bot);

        assertTrue(entry.climbing);
        assertEquals(new Point(100, 1), bot.getPosition());

        entry.climbVerticalDir = -1;
        BotPhysicsEngine.advanceClimb(entry, bot);

        assertFalse(entry.inAir);
        assertFalse(entry.climbing);
        assertEquals(new Point(100, 0), bot.getPosition());
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, bot.getStance());
    }

    @Test
    void shouldSnapGroundMotionBackToFootholdWhenBotStartsSlightlyAboveGround() {
        MapleMap map = mock(MapleMap.class);
        when(map.getPointBelow(any(Point.class))).thenAnswer(invocation -> {
            Point probe = invocation.getArgument(0);
            return new Point(probe.x, 120);
        });
        Character bot = mockBot(new Point(100, 110), map);
        BotEntry entry = new BotEntry(bot, null, null);
        Foothold foothold = mock(Foothold.class);
        when(foothold.slope()).thenReturn(0.0);

        BotPhysicsEngine.resetMotion(entry, bot.getPosition());
        entry.moveDir = 0;  // intent: idle
        BotPhysicsEngine.GroundMotion motion = BotPhysicsEngine.applyGroundMotion(entry, bot, foothold);

        assertFalse(motion.lostGround());
        assertEquals(0, motion.stepX());
        assertEquals(new Point(100, 120), bot.getPosition());
        assertFalse(entry.inAir);
    }

    @Test
    void shouldPreferCloserGroundPointWhenExactProbeFallsThroughToLowerPlatform() {
        MapleMap map = mock(MapleMap.class);
        when(map.getPointBelow(any(Point.class))).thenAnswer(invocation -> {
            Point probe = invocation.getArgument(0);
            if (probe.y >= 151) {
                return new Point(probe.x, 215);
            }
            return new Point(probe.x, 150);
        });

        assertEquals(new Point(-65, 150), BotPhysicsEngine.findGroundPoint(map, new Point(-65, 151)));
    }

    @Test
    void shouldPreferCurrentWalkRegionSurfaceWhenParallelPlatformSitsAbove() {
        MapleMap map = createEmptyTestMap(910000013);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold lowerLeft = new Foothold(new Point(0, 100), new Point(20, 100), 1);
        Foothold lowerRight = new Foothold(new Point(20, 100), new Point(40, 100), 2);
        Foothold upper = new Foothold(new Point(10, 92), new Point(30, 92), 3);
        lowerLeft.setNext(2);
        lowerRight.setPrev(1);
        footholds.insert(lowerLeft);
        footholds.insert(lowerRight);
        footholds.insert(upper);
        BotNavigationGraphProvider.rebuildGraph(map);

        Point ground = BotPhysicsEngine.findWalkRegionGroundPoint(map, lowerLeft, 24, 100);

        assertNotNull(ground);
        assertEquals(new Point(24, 100), ground);
    }

    @Test
    void shouldKeepWalkingAcrossKpqPlatformEvenWithNearbyPlatformAbove() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(kpqS1());
        Point start = new Point(-335, 116);
        Point target = new Point(-170, 103);
        int startRegionId = graph.findRegionId(kpqS1(), start);

        assertEquals(startRegionId, graph.findRegionId(kpqS1(), target));

        Character bot = mockBot(start, kpqS1());
        BotEntry entry = new BotEntry(bot, null, null);
        BotPhysicsEngine.resetMotion(entry, bot.getPosition());
        entry.moveDir = 1;  // intent: walk right

        Foothold currentFoothold = BotPhysicsEngine.findGroundFoothold(kpqS1(), bot.getPosition());
        assertNotNull(currentFoothold);

        for (int i = 0; i < 40 && bot.getPosition().x < target.x; i++) {
            BotPhysicsEngine.GroundMotion motion = BotPhysicsEngine.applyGroundMotion(entry, bot, currentFoothold);
            assertFalse(motion.lostGround(), "Walking across the same KPQ region should not lose ground because of the nearby upper platform");
            currentFoothold = BotPhysicsEngine.findGroundFoothold(kpqS1(), bot.getPosition());
            assertNotNull(currentFoothold);
            assertEquals(startRegionId, graph.findRegionId(kpqS1(), bot.getPosition()));
        }

        assertTrue(bot.getPosition().x > start.x);
    }

    @Test
    void shouldUseIntermediateBumpLandingInFallSimulation() {
        MapleMap map = createEmptyTestMap(910000004);
        server.maps.FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(0, 110), new Point(20, 110), 1));
        footholds.insert(new Foothold(new Point(4, 102), new Point(6, 102), 2));

        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateFallLanding(map, new Point(0, 100), 8);

        assertNotNull(landing);
        assertEquals(new Point(4, 102), landing.point());
        assertEquals(2, landing.foothold().getId());
    }

    @Test
    void shouldLandApexJumpOnSleepyForestUpperPlatform() {
        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateJumpLanding(sleepyForest(), new Point(197, -14), 8);

        assertNotNull(landing);
        assertTrue(landing.point().y < 0,
                "the logged r9->r5 jump should land on the upper platform instead of falling back to the ground below");
    }

    @Test
    void shouldKeepAirborneBotInsideMapSideBoundary() {
        Point start = new Point(376, 182);
        int stepX = BotPhysicsEngine.walkStep(elliniaWeaponStore());

        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateJumpLanding(elliniaWeaponStore(), start, stepX);

        assertNotNull(landing, "rightward shop jump should hit the map boundary and fall back to the platform");
        assertEquals(new Point(400, 182), landing.point());
    }

    @Test
    void shouldPreferExactGroundFootholdWhenOffsetLookupWouldChooseDifferentPlatform() {
        MapleMap map = createEmptyTestMap(910000102);
        Foothold exactGround = new Foothold(new Point(0, 100), new Point(120, 100), 1);
        Foothold nearbyUpper = new Foothold(new Point(0, 92), new Point(120, 92), 2);
        map.getFootholds().insert(exactGround);
        map.getFootholds().insert(nearbyUpper);
        StandingLookupCase lookupCase = findStandingLookupCaseWhereOffsetDiffers(map);

        assertNotNull(lookupCase, "Expected at least one standing point where offset-only lookup picks a different foothold");
        assertNotEquals(lookupCase.exactFoothold().getId(), lookupCase.offsetFoothold().getId());
        Point chosenGround = BotPhysicsEngine.findGroundPoint(map, lookupCase.point());
        Foothold chosenFoothold = BotPhysicsEngine.findGroundFoothold(map, lookupCase.point());

        assertNotNull(chosenGround);
        assertNotNull(chosenFoothold);
        assertEquals(chosenFoothold.getId(),
                map.getFootholds().findBelow(new Point(chosenGround.x, chosenGround.y)).getId());
    }

    private static StandingLookupCase findStandingLookupCaseWhereOffsetDiffers(MapleMap map) {
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            if (foothold.isWall()) {
                continue;
            }

            int minX = Math.min(foothold.getX1(), foothold.getX2());
            int maxX = Math.max(foothold.getX1(), foothold.getX2());
            if (maxX - minX < 2) {
                continue;
            }

            for (int x = minX + 1; x < maxX; x += 4) {
                Point point = new Point(x, footingY(foothold, x));
                Foothold exact = map.getFootholds().findBelow(point);
                Foothold offset = map.getFootholds().findBelow(new Point(point.x, point.y - BotPhysicsEngine.cfg.MAX_SLOPE_UP));
                if (exact != null && offset != null && exact.getId() != offset.getId()) {
                    return new StandingLookupCase(point, exact, offset);
                }
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

    private static int footingY(Foothold foothold, int x) {
        if (foothold.getX1() == foothold.getX2()) {
            return Math.min(foothold.getY1(), foothold.getY2());
        }

        double ratio = (x - foothold.getX1()) / (double) (foothold.getX2() - foothold.getX1());
        return (int) Math.round(foothold.getY1() + (foothold.getY2() - foothold.getY1()) * ratio);
    }

    @Test
    void shouldSlipOnSnowFields() {
        MapleMap normal = flatGroundMap(0f);
        MapleMap snow = flatGroundMap(0.2f); // El Nath info/fs

        // Slow start: one tick in, the snow bot has a fraction of the normal speed.
        double normalEarly = hspeedAfterTicks(normal, 1, 1, 0.0);
        double snowEarly = hspeedAfterTicks(snow, 1, 1, 0.0);
        assertTrue(snowEarly < normalEarly * 0.5,
                "snow accel " + snowEarly + " vs normal " + normalEarly);

        // Same top speed: slipperiness scales force AND friction, terminal is unchanged.
        double normalTop = hspeedAfterTicks(normal, 1, 40, 0.0);
        double snowTop = hspeedAfterTicks(snow, 1, 200, 0.0);
        assertEquals(normalTop, snowTop, 0.2);

        // Long slide: input released at top speed, the snow bot keeps most of it.
        double normalBrake = hspeedAfterTicks(normal, 0, 1, normalTop);
        double snowBrake = hspeedAfterTicks(snow, 0, 1, normalTop);
        assertTrue(snowBrake > normalBrake * 2,
                "snow brake " + snowBrake + " vs normal " + normalBrake);
    }

    private static MapleMap flatGroundMap(float fs) {
        MapleMap map = new MapleMap(211000000, 0, 0, 211000000, 1.0f);
        server.maps.FootholdTree tree = new server.maps.FootholdTree(
                new Point(-20000, -2000), new Point(20000, 2000));
        tree.insert(new Foothold(new Point(-15000, 100), new Point(15000, 100), 1));
        map.setFootholds(tree);
        if (fs > 0f) {
            map.setFootholdSpeed(fs);
        }
        return map;
    }

    private static double hspeedAfterTicks(MapleMap map, int desiredDir, int ticks, double initialHSpeed) {
        Foothold fh = map.getFootholds().findBelow(new Point(0, 99));
        BotPhysicsEngine.GroundTravelState state =
                new BotPhysicsEngine.GroundTravelState(0, initialHSpeed, 0.0);
        Point pos = new Point(0, 100);
        for (int i = 0; i < ticks; i++) {
            BotPhysicsEngine.GroundStepResult step =
                    BotPhysicsEngine.simulateGroundMotion(map, pos, fh, desiredDir, state, BotMovementProfile.base());
            state = step.state();
            pos = step.point();
        }
        return Math.abs(state.hspeed());
    }

    private static MapleMap createEmptyTestMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        map.setFootholds(new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000)));
        return map;
    }

    private static Character mockBot(Point startPosition, MapleMap map) {
        return mockBot(startPosition, map, 100);
    }

    private static Character mockBot(Point startPosition, MapleMap map, int hp) {
        Character bot = mock(Character.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        AtomicInteger stance = new AtomicInteger(CharacterStance.STAND_RIGHT_STANCE);

        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getHp()).thenReturn(hp);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getStance()).thenAnswer(invocation -> stance.get());
        doAnswer(invocation -> {
            stance.set(invocation.getArgument(0));
            return null;
        }).when(bot).setStance(anyInt());
        return bot;
    }

    @Test
    void shouldPermitWalkableEndpointStep() {
        assertTrue(BotPhysicsEngine.isWalkableEndpointStep(0, -1));
        assertTrue(BotPhysicsEngine.isWalkableEndpointStep(BotPhysicsEngine.WALK_GAP_PX, 0));
    }

    @Test
    void shouldBlockWalkBetweenFootholdsForFloatingPlatformAbove() {
        // Current foothold ends at X=10; floating platform spans far above with no nearby endpoint.
        Foothold platform  = new Foothold(new Point(0, 10),    new Point(10, 10),   1);
        Foothold floating  = new Foothold(new Point(-100, -16), new Point(100, -16), 2);

        assertFalse(BotPhysicsEngine.canWalkAcrossFootholds(platform, floating));
    }

    @Test
    void shouldNotLoseGroundWalkingOntoConnectedStep() {
        Foothold platform = new Foothold(new Point(0, 10), new Point(10, 10), 1);
        Foothold step     = new Foothold(new Point(10, 9), new Point(40, 9),  2);

        assertTrue(BotPhysicsEngine.canWalkAcrossFootholds(platform, step));
    }

    @Test
    void shouldNotTreatSeparatedVerticalOffsetAsWalkableEndpointStep() {
        Foothold platform = new Foothold(new Point(0, 10), new Point(10, 10), 1);
        Foothold adjacent = new Foothold(new Point(10, 1), new Point(40, 1),  2);

        assertFalse(BotPhysicsEngine.canWalkAcrossFootholds(platform, adjacent));
    }

    @Test
    void shouldLoseGroundWalkingOffLedgeWithFloatingPlatformAbove() {
        // Bot on a platform walking right off the edge. A wide platform sits MAX_SLOPE_UP above
        // with no endpoint near the ledge — the bot must fall, not snap up.
        MapleMap map = createEmptyTestMap(910000011);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold platform = new Foothold(new Point(0, 10),    new Point(10, 10),   1);
        Foothold floating = new Foothold(new Point(-100, -16), new Point(100, -16), 2);
        footholds.insert(platform);
        footholds.insert(floating);

        // Bot at (4, 10) on platform; physX advanced to 14 (past the ledge at X=10).
        Character bot = mockBot(new Point(4, 10), map);
        BotEntry entry = new BotEntry(bot, null, null);
        BotPhysicsEngine.resetMotion(entry, bot.getPosition());
        entry.physX = 14;
        entry.hspeed = 0;
        entry.moveDir = 1;  // intent: walk right

        BotPhysicsEngine.GroundMotion motion = BotPhysicsEngine.applyGroundMotion(entry, bot, platform);

        assertTrue(motion.lostGround());
        assertTrue(entry.inAir, "walk-off should transition directly into airborne state");
        assertTrue(entry.airVelX > 0, "walk-off should preserve horizontal momentum instead of zeroing X velocity for one tick");
        assertTrue(entry.movementVelX > 0, "movement packet should carry non-zero horizontal velocity on the ledge-drop tick");
        assertTrue(bot.getPosition().x > 10, "walk-off should keep the full horizontal step instead of snapping to the ledge edge");
    }

    @Test
    void shouldBlockGroundStepThroughCollidableWall() {
        MapleMap map = createEmptyTestMap(910000049);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold lower = new Foothold(new Point(0, 100), new Point(50, 100), 1);
        Foothold wall = new Foothold(new Point(50, 60), new Point(50, 100), 2);
        Foothold upper = new Foothold(new Point(50, 60), new Point(120, 60), 3);
        wall.setNext(lower.getId());
        wall.setPrev(upper.getId());
        footholds.insert(lower);
        footholds.insert(wall);
        footholds.insert(upper);

        assertFalse(BotPhysicsEngine.canWalkGroundStep(map, new Point(44, 100), 12),
                "ground movement should not phase through collidable stair/platform walls");
    }

    @Test
    void shouldWalkUpShortCollidableWallEndpointWithinSlopeLimit() {
        MapleMap map = createEmptyTestMap(910000054);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold lower = new Foothold(new Point(0, 100), new Point(50, 100), 1);
        Foothold wall = new Foothold(new Point(50, 80), new Point(50, 100), 2);
        Foothold upper = new Foothold(new Point(50, 80), new Point(120, 80), 3);
        wall.setNext(lower.getId());
        wall.setPrev(upper.getId());
        footholds.insert(lower);
        footholds.insert(wall);
        footholds.insert(upper);

        assertTrue(BotPhysicsEngine.canWalkGroundStep(map, new Point(44, 100), 12),
                "short wall endpoints should behave like a walkable step up within MAX_SLOPE_UP");
    }

    @Test
    void shouldWalkOffLedgeWhenWallTopIsLevelWithGround() {
        MapleMap map = createEmptyTestMap(910000055);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold upper = new Foothold(new Point(0, 80), new Point(50, 80), 1);
        Foothold wall = new Foothold(new Point(50, 80), new Point(50, 140), 2);
        Foothold lower = new Foothold(new Point(50, 140), new Point(120, 140), 3);
        wall.setPrev(upper.getId());
        wall.setNext(lower.getId());
        footholds.insert(upper);
        footholds.insert(wall);
        footholds.insert(lower);

        assertFalse(BotPhysicsEngine.isGroundStepBlockedByWall(map, new Point(44, 80), 12),
                "a wall whose top is level with the current ground is a ledge edge, not a blocking wall");
    }

    @Test
    void shouldBlockGroundStepThroughBottomConnectedOpenTopWall() {
        MapleMap map = createEmptyTestMap(910000057);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold lower = new Foothold(new Point(0, 100), new Point(50, 100), 1);
        Foothold wall = new Foothold(new Point(50, 100), new Point(50, 60), 2);
        wall.setPrev(lower.getId());
        footholds.insert(lower);
        footholds.insert(wall);

        assertFalse(BotPhysicsEngine.canWalkGroundStep(map, new Point(44, 100), 12),
                "bottom-connected walls with an open top should still be collidable");
    }

    @Test
    void shouldTreatMap193000000BottomAnchoredWallsAsCollidable() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(193000000);
        BotNavigationGraphProvider.rebuildGraph(map);

        java.util.Set<Integer> collidableWallIds = BotNavigationGraphProvider.getCachedCollidableWallIds(map.getId());

        assertNotNull(collidableWallIds);
        assertTrue(collidableWallIds.contains(2), "top-right shaft wall should be collidable");
        assertTrue(collidableWallIds.contains(10), "left lower wall should be collidable");
        assertTrue(collidableWallIds.contains(13), "bottom platform right wall should be collidable");
    }

    @Test
    void shouldCacheKerningPharmacyBlockedUndersidesAtGraphBuild() {
        BotNavigationGraphProvider.rebuildGraph(kerningPharmacy());

        java.util.Set<Integer> collidableFromBelowIds = BotNavigationGraphProvider.getCachedCollidableFromBelowIds(kerningPharmacy().getId());

        assertNotNull(collidableFromBelowIds);
        assertTrue(collidableFromBelowIds.contains(1), "left pharmacy box lower edge should block jumps from below");
        assertTrue(collidableFromBelowIds.contains(27), "right pharmacy box lower edge should block jumps from below");
        assertFalse(collidableFromBelowIds.contains(17), "standalone pharmacy platform should stay jump-through from below");
    }

    @Test
    void shouldCacheMushroomShrineDoughnutUndersidesAtGraphBuild() {
        BotNavigationGraphProvider.rebuildGraph(mushroomShrine());

        java.util.Set<Integer> collidableFromBelowIds = BotNavigationGraphProvider.getCachedCollidableFromBelowIds(mushroomShrine().getId());

        assertNotNull(collidableFromBelowIds);
        assertTrue(collidableFromBelowIds.contains(248), "outer doughnut lower edge should block jumps from below");
        assertFalse(collidableFromBelowIds.contains(250), "outer doughnut upper edge should stay a normal floor");
        assertFalse(collidableFromBelowIds.contains(264), "inner doughnut lower edge should stay a normal floor");
    }

    @Test
    void shouldBumpHeadAgainstClosedBoxUnderside() {
        MapleMap map = createEmptyTestMap(910000058);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold lower = new Foothold(new Point(0, 100), new Point(40, 100), 1);
        Foothold right = new Foothold(new Point(40, 100), new Point(40, 60), 2);
        Foothold upper = new Foothold(new Point(40, 60), new Point(0, 60), 3);
        Foothold left = new Foothold(new Point(0, 60), new Point(0, 100), 4);
        lower.setPrev(left.getId());
        lower.setNext(right.getId());
        right.setPrev(lower.getId());
        right.setNext(upper.getId());
        upper.setPrev(right.getId());
        upper.setNext(left.getId());
        left.setPrev(upper.getId());
        left.setNext(lower.getId());
        footholds.insert(lower);
        footholds.insert(right);
        footholds.insert(upper);
        footholds.insert(left);
        BotNavigationGraphProvider.rebuildGraph(map);

        Character bot = mockBot(new Point(20, 120), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 20;
        entry.physY = 120;
        entry.velY = -30f;
        entry.airVelX = 0;

        assertEquals(BotPhysicsEngine.AirborneStepResult.CEILING, BotPhysicsEngine.stepAirborne(entry, bot));
        assertEquals(new Point(20, 101), bot.getPosition());
        assertEquals(0f, entry.velY);
    }

    @Test
    void shouldKeepAirborneBotOnNearSideOfCollidableWall() {
        MapleMap map = createEmptyTestMap(910000052);
        server.maps.FootholdTree footholds = map.getFootholds();
        Foothold lower = new Foothold(new Point(0, 100), new Point(50, 100), 1);
        Foothold wall = new Foothold(new Point(50, 80), new Point(50, 100), 2);
        Foothold upper = new Foothold(new Point(50, 80), new Point(120, 80), 3);
        wall.setNext(lower.getId());
        wall.setPrev(upper.getId());
        footholds.insert(lower);
        footholds.insert(wall);
        footholds.insert(upper);

        Character bot = mockBot(new Point(56, 90), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 56;
        entry.physY = 90;
        entry.velY = 0f;
        entry.airVelX = -8;

        assertEquals(BotPhysicsEngine.AirborneStepResult.WALL, BotPhysicsEngine.stepAirborne(entry, bot));
        assertTrue(bot.getPosition().x > 50, "wall collision should place the bot on the near side, not inside the wall");

        entry.airSteerVelX = -BotPhysicsEngine.cfg.AIR_STEER_MAX;
        BotPhysicsEngine.stepAirborne(entry, bot);

        assertTrue(bot.getPosition().x > 50, "continued air steering into the wall must not cross to the far side");
    }

    @Test
    void shouldUseFootholdXBoundsWhenMapHasSyntheticBounds() {
        MapleMap map = createEmptyTestMap(910000056);
        map.setMapPointBoundings(-(1 << 17), -(1 << 17), 1 << 18, 1 << 18);

        server.maps.FootholdTree footholds = map.getFootholds();
        footholds.insert(new Foothold(new Point(-399, 95), new Point(399, 95), 1));

        assertEquals(-399, footholds.getMinDropX());
        assertEquals(399, footholds.getMaxDropX());

        Character bot = mockBot(new Point(-389, 61), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = -389;
        entry.physY = 61;
        entry.velY = -11.9f;
        entry.airVelX = -11;

        assertEquals(BotPhysicsEngine.AirborneStepResult.WALL, BotPhysicsEngine.stepAirborne(entry, bot));
        assertEquals(-399, bot.getPosition().x);
        assertEquals(0, entry.airVelX);
    }

    private record StandingLookupCase(Point point, Foothold exactFoothold, Foothold offsetFoothold) {
    }
}
