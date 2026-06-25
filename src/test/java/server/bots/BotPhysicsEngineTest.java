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

    @Test
    void shouldLoadElNathSlipperinessFromWzAndSlide() {
        MapleMap elNath = BotNavigationMapLoader.loadMapGeometry(211000000);
        assertEquals(0.2f, elNath.getFootholdSpeed(), 0.001f);

        // End-to-end on the real map: one tick of acceleration reaches a fraction of the
        // speed it reaches on a normal map.
        Foothold fh = elNath.getFootholds().getAllFootholds().stream()
                .filter(f -> !f.isWall() && f.getY1() == f.getY2() && Math.abs(f.getX2() - f.getX1()) > 80)
                .findFirst().orElseThrow();
        Point start = new Point((f1(fh.getX1(), fh.getX2())), fh.getY1());
        BotPhysicsEngine.GroundTravelState state =
                new BotPhysicsEngine.GroundTravelState(start.x, 0.0, 0.0);
        BotPhysicsEngine.GroundStepResult step =
                BotPhysicsEngine.simulateGroundMotion(elNath, start, fh, 1, state, BotMovementProfile.base());
        double snowSpeed = Math.abs(step.state().hspeed());
        double normalSpeed = hspeedAfterTicks(flatGroundMap(0f), 1, 1, 0.0);
        assertTrue(snowSpeed < normalSpeed * 0.5,
                "el nath accel " + snowSpeed + " vs normal " + normalSpeed);
    }

    private static int f1(int x1, int x2) {
        return Math.min(x1, x2) + 20;
    }

    @Test
    void shouldMatchPacketFittedKineticCurvesOnSnow() {
        // Fitted to logs/monitored-packets-elnath-slippery-walk-left-right-spd100.log:
        // constant accel 280 px/s^2 (1400*fs), cap 125 px/s, constant glide decel 80 px/s^2.
        MapleMap snow = flatGroundMap(0.2f);
        double tickS = BotPhysicsEngine.cfg.TICK_MS / 1000.0;
        double stepS = 0.008; // CLIENT_GROUND_STEP_MS

        int accelTicks = Math.max(1, (int) Math.round(0.2 / tickS));
        double vPxs = hspeedAfterTicks(snow, 1, accelTicks, 0.0) / stepS;
        assertEquals(280.0 * accelTicks * tickS, vPxs, 15.0, "linear 280 px/s^2 ramp");

        double topPxs = hspeedAfterTicks(snow, 1, 400, 0.0) / stepS;
        assertEquals(125.0, topPxs, 1.0, "cap = walk speed, unchanged by fs");

        int glideTicks = Math.max(1, (int) Math.round(1.0 / tickS));
        double vAfter1s = hspeedAfterTicks(snow, 0, glideTicks, 1.0) / stepS;
        assertEquals(125.0 - 80.0, vAfter1s, 8.0, "linear 80 px/s^2 glide");
    }

    @Test
    void shouldHalveCarriedMomentumOnLanding() {
        // Packet-verified landing rule (elnath-tricky-jumps-spd100v2 + 100speedjumpmovement
        // logs): touchdown halves the horizontal velocity (125 -> 62, 26 -> 13).
        MapleMap snow = flatGroundMap(0.2f);
        Character bot = mockBot(new Point(0, 50), snow);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 0;
        entry.physY = 50;
        entry.velY = 5f;
        entry.airVelX = 6; // full walk step, 120 px/s
        entry.moveDir = 0;

        landAirborne(entry, bot);
        double stepsPerTick = BotPhysicsEngine.cfg.TICK_MS / 8.0;
        assertEquals(6 * 0.5 / stepsPerTick, entry.hspeed, 0.05,
                "landing keeps HALF the incoming horizontal velocity");
    }

    @Test
    void shouldRideHalvedMomentumWhenCounterStrafeKeyHeldAtTouchdown() {
        // Landing no longer counter-strafe-brakes (commit 54daaf229 "ride the momentum"): at the
        // landing tick moveDir still holds STALE airborne steering, so the old "opposite key zeroes
        // hspeed" brake fired on noise — it killed landing momentum and left facing backwards. A
        // counter-strafe key held through touchdown now keeps the same halved momentum as a neutral
        // landing; the next ground tick brakes on the REAL planned direction (slipperyStopDir still
        // guards icy ledges). (Trades away the client "stop dead on an icy ledge" trick on purpose.)
        MapleMap snow = flatGroundMap(0.2f);
        Character bot = mockBot(new Point(0, 50), snow);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 0;
        entry.physY = 50;
        entry.velY = 5f;
        entry.airVelX = 6;
        entry.moveDir = -1; // counter-strafe held through touchdown — must NOT zero momentum anymore

        landAirborne(entry, bot);
        double stepsPerTick = BotPhysicsEngine.cfg.TICK_MS / 8.0;
        assertEquals(6 * 0.5 / stepsPerTick, entry.hspeed, 0.05,
                "counter-strafe at touchdown rides the halved landing momentum, no longer stops dead");
    }

    private static void landAirborne(BotEntry entry, Character bot) {
        for (int i = 0; i < 60; i++) {
            if (BotPhysicsEngine.stepAirborne(entry, bot) == BotPhysicsEngine.AirborneStepResult.LANDED) {
                return;
            }
        }
        throw new AssertionError("bot never landed");
    }

    @Test
    void shouldSwingAirVelocityAcrossZeroWithCounterStrafe() {
        // Disasm-true air control (CVecCtrl::CalcFloat @ 0x9b2c3c): a held counter-direction
        // decelerates at 200 x fs px/s^2 straight through zero, then PINS at the input band
        // (walkSpeed/14 = 8.93 px/s at fs=1) in the new direction — mid-air input can never
        // rebuild walk speed (the old symmetric model swung all the way to -walkSpeed).
        MapleMap map = flatGroundMap(0f);
        Character bot = mockBot(new Point(0, -1000), map);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 0;
        entry.physY = -1000;
        entry.velY = 0f;
        entry.airVelX = 6;
        entry.moveDir = -1;

        for (int i = 0; i < 30; i++) {
            assertEquals(BotPhysicsEngine.AirborneStepResult.CONTINUE,
                    BotPhysicsEngine.stepAirborne(entry, bot));
        }
        double totalVelX = entry.airVelX + entry.airSteerVelX;
        assertTrue(totalVelX < 0.0, "counter-strafe should swing past zero, got " + totalVelX);
        // band = walkSpeed ~6.25 px/tick / 14 = 0.4464 px/tick (= 8.93 px/s);
        // tolerance covers HFORCE 16.667 making the engine walk speed 6.250125 px/tick.
        assertEquals(-6.25 / 14.0, totalVelX, 1e-4,
                "counter-strafe pins at the CalcFloat input band, not -walkSpeed");
    }

    @Test
    void shouldNotSlipWithSnowshoes() {
        MapleMap snow = flatGroundMap(0.2f);
        BotMovementProfile snowshoes = new BotMovementProfile(100, 100, true);
        double withShoes = hspeedAfterTicks(snow, snowshoes, 1, 1, 0.0);
        double normal = hspeedAfterTicks(flatGroundMap(0f), 1, 1, 0.0);
        assertEquals(normal, withShoes, 1e-9, "snowshoes = normal walk physics on snow");
        assertEquals(BotPhysicsEngine.launchRunwayPx(flatGroundMap(0f), BotMovementProfile.base()),
                BotPhysicsEngine.launchRunwayPx(snow, snowshoes),
                "snowshoes use the normal-map launch runway");
    }

    @Test
    void shouldCounterStrafeBrakeOnlyWhileSlidingOnSlipperyGround() {
        MapleMap snow = flatGroundMap(0.2f);
        BotMovementProfile base = BotMovementProfile.base();
        // Sliding right at top speed -> brake left; mirrored for left.
        assertEquals(-1, BotPhysicsEngine.counterStrafeBrakeDir(snow, base, 1.0));
        assertEquals(1, BotPhysicsEngine.counterStrafeBrakeDir(snow, base, -1.0));
        // Released once one brake tick can cancel the residual; never on normal ground
        // or with snowshoes (normal physics stops on its own).
        assertEquals(0, BotPhysicsEngine.counterStrafeBrakeDir(snow, base, 0.01));
        assertEquals(0, BotPhysicsEngine.counterStrafeBrakeDir(flatGroundMap(0f), base, 1.0));
        assertEquals(0, BotPhysicsEngine.counterStrafeBrakeDir(
                snow, new BotMovementProfile(100, 100, true), 1.0));
    }

    @Test
    void shouldGlideMidPlatformAndBrakeOnlyNearTheEdge() {
        // Players let go and glide on ice — bots must too. Mid-platform at full speed the
        // glide-out (~98 px) stays on the 30000px ground: no brake. On a 90px ledge the
        // glide would slide off: brake engages.
        MapleMap bigSnow = flatGroundMap(0.2f);
        Foothold big = bigSnow.getFootholds().findBelow(new Point(0, 99));
        assertEquals(0, BotPhysicsEngine.slipperyStopDir(bigSnow, BotMovementProfile.base(),
                new Point(0, 100), big, new BotPhysicsEngine.GroundTravelState(0, 1.0, 0.0)),
                "safe glide-out -> no brake");

        MapleMap ledge = smallPlatformSnowMap();
        Foothold small = ledge.getFootholds().findBelow(new Point(-2000 + 30, -100));
        assertEquals(-1, BotPhysicsEngine.slipperyStopDir(ledge, BotMovementProfile.base(),
                new Point(-2000 + 30, -50), small, new BotPhysicsEngine.GroundTravelState(-2000 + 30, 1.0, 0.0)),
                "glide-out crosses the ledge -> counter-strafe brake");
    }

    @Test
    void shouldStopMuchShorterWhenBrakingThanGliding() {
        // Braking sheds 1400*fs px/s^2 vs the 400*fs glide: from top speed the braked stop
        // distance (~28 px at fs=0.2) is well under half the glide-out (~98 px).
        MapleMap snow = flatGroundMap(0.2f);
        double glide = slideOutDistance(snow, false);
        double braked = slideOutDistance(snow, true);
        assertTrue(braked > 0 && braked < glide * 0.45,
                "braked " + braked + " px vs glide " + glide + " px");
    }

    private static double slideOutDistance(MapleMap map, boolean brake) {
        Foothold fh = map.getFootholds().findBelow(new Point(0, 99));
        BotPhysicsEngine.GroundTravelState state = new BotPhysicsEngine.GroundTravelState(0, 1.0, 0.0);
        Point pos = new Point(0, 100);
        for (int i = 0; i < 400 && Math.abs(state.hspeed()) > 1e-9; i++) {
            int dir = brake
                    ? BotPhysicsEngine.counterStrafeBrakeDir(map, BotMovementProfile.base(), state.hspeed())
                    : 0;
            if (brake && dir == 0 && Math.abs(state.hspeed()) < 0.1) {
                break; // brake released; residual glide-out is sub-pixel
            }
            BotPhysicsEngine.GroundStepResult step = BotPhysicsEngine.simulateGroundMotion(
                    map, pos, fh, dir, state, BotMovementProfile.base());
            state = step.state();
            pos = step.point();
        }
        return Math.abs(pos.x);
    }

    @Test
    void shouldValidateSlipperyLandingsAsBrakeToStop() {
        // 90px platform, landing 30px from its left edge moving right at full speed:
        // a glide-out (~98px) would slide off; the braked stop (~28px) holds.
        MapleMap snow = smallPlatformSnowMap();
        Foothold platform = snow.getFootholds().findBelow(new Point(-2000 + 30, -100));
        BotPhysicsEngine.JumpLanding landing = new BotPhysicsEngine.JumpLanding(
                new Point(-2000 + 30, -50), platform, 5.0, 8.0);
        BotPhysicsEngine.PostLandingJump result = BotPhysicsEngine.simulatePostLandingGroundTicks(
                snow, landing, 1, BotMovementProfile.base(), 3);
        assertTrue(result != null && !result.lostGround(),
                "counter-strafe braking keeps the landing on the platform");
    }

    private static MapleMap smallPlatformSnowMap() {
        // Synthetic-only map id: BotPhysicsEngine caches footholds-by-id per MAP ID, so
        // reusing the real El Nath id (211000000) collides with tests that load the real map.
        MapleMap map = new MapleMap(999211001, 0, 0, 999211001, 1.0f);
        server.maps.FootholdTree tree = new server.maps.FootholdTree(
                new Point(-3000, -2000), new Point(3000, 2000));
        tree.insert(new Foothold(new Point(-2000, -50), new Point(-1910, -50), 1)); // 90px ledge
        tree.insert(new Foothold(new Point(-3000, 400), new Point(3000, 400), 2)); // floor below
        map.setFootholds(tree);
        map.setFootholdSpeed(0.2f);
        return map;
    }

    @Test
    void shouldBangBangApproachOnlyOnSlipperyGround() {
        MapleMap snow = flatGroundMap(0.2f);
        BotMovementProfile base = BotMovementProfile.base();
        // fs=1 / snowshoes: plain sign(dx) passthrough regardless of speed.
        assertEquals(1, BotPhysicsEngine.slipperyApproachDir(flatGroundMap(0f), base, 1.0, 10));
        assertEquals(-1, BotPhysicsEngine.slipperyApproachDir(flatGroundMap(0f), base, 1.0, -10));
        assertEquals(1, BotPhysicsEngine.slipperyApproachDir(
                snow, new BotMovementProfile(100, 100, true), 1.0, 10));
        // Far target: full acceleration even at top slide speed.
        assertEquals(1, BotPhysicsEngine.slipperyApproachDir(snow, base, 1.0, 500));
        // Brake stop-out from top speed (~35 px) no longer fits: counter-strafe.
        assertEquals(-1, BotPhysicsEngine.slipperyApproachDir(snow, base, 1.0, 20));
        // Sliding AWAY from the target: pushing toward it doubles as the brake.
        assertEquals(-1, BotPhysicsEngine.slipperyApproachDir(snow, base, 1.0, -20));
        // At the target with only residual slide: hold.
        assertEquals(0, BotPhysicsEngine.slipperyApproachDir(snow, base, 0.05, 0));
    }

    @Test
    void shouldStopInsideEdgeWindowInsteadOfSlidingOffOnIce() {
        // pathlog-Preston-2026-06-12T083326 (El Nath r17): the bot approached the r17->r14
        // launch window [58,59] at the platform's LEFT edge at full slide speed
        // (72,69,65,60 then 54 -> off the cliff). With the bang-bang approach the bot must
        // come to a stop at the window without ever losing ground.
        MapleMap snow = new MapleMap(999211002, 0, 0, 999211002, 1.0f);
        server.maps.FootholdTree tree = new server.maps.FootholdTree(
                new Point(-3000, -2000), new Point(3000, 2000));
        tree.insert(new Foothold(new Point(-2000, -50), new Point(-1700, -50), 1)); // edge at -2000
        tree.insert(new Foothold(new Point(-3000, 400), new Point(3000, 400), 2)); // floor below
        snow.setFootholds(tree);
        snow.setFootholdSpeed(0.2f);

        int targetX = -1998; // window right at the platform's left edge
        BotMovementProfile profile = BotMovementProfile.base();
        Foothold fh = snow.getFootholds().findBelow(new Point(-1850, -100));
        Point pos = new Point(-1850, -50); // 148 px out, accelerates to full slide on the way
        BotPhysicsEngine.GroundTravelState state =
                new BotPhysicsEngine.GroundTravelState(pos.x, 0.0, 0.0);
        for (int i = 0; i < 600; i++) {
            // Same derivation as the live tick: approach controller supplies the walk intent,
            // moveDir==0 falls back to the slipperyStopDir stop policy (applyGroundMotion).
            int dir = BotPhysicsEngine.slipperyApproachDir(snow, profile, state.hspeed(), targetX - pos.x);
            if (dir == 0) {
                dir = BotPhysicsEngine.slipperyStopDir(snow, profile, pos, fh, state);
            }
            BotPhysicsEngine.GroundStepResult step =
                    BotPhysicsEngine.simulateGroundMotion(snow, pos, fh, dir, state, profile);
            assertFalse(step.lostGround(), "tick " + i + ": slid off the edge at x=" + step.point().x);
            pos = step.point();
            fh = step.foothold();
            state = step.state();
        }
        assertTrue(Math.abs(pos.x - targetX) <= 3,
                "settled at " + pos.x + " px, target " + targetX + " (brake quantization is ~3px)");
        assertTrue(Math.abs(state.hspeed()) < 0.2, "still sliding at hspeed " + state.hspeed());
    }

    @Test
    void shouldFaceTheHeldKeyWhileCounterStrafeBraking() {
        // A counter-strafing player visibly walks AGAINST the slide: facing and stance must
        // follow the held INPUT direction, not the velocity-derived slide direction.
        MapleMap snow = flatGroundMap(0.2f);
        Character bot = mockBot(new Point(0, 100), snow);
        BotEntry entry = new BotEntry(bot, null, null);
        Foothold fh = snow.getFootholds().findBelow(new Point(0, 99));
        entry.physX = 0;
        entry.physY = 100;
        entry.hspeed = 1.0;   // sliding right at top speed
        entry.facingDir = 1;
        entry.moveDir = -1;   // approach-controller brake: opposite key held
        BotPhysicsEngine.applyGroundMotion(entry, bot, fh);
        assertEquals(-1, entry.facingDir, "facing follows the held key, not the slide");
        assertEquals(-1, entry.groundBrakeDir);
        assertEquals(CharacterStance.WALK_LEFT_STANCE, BotPhysicsEngine.resolveStance(entry));

        // Stop-policy brake near a ledge (moveDir==0, slipperyStopDir): same rendering.
        MapleMap ledge = smallPlatformSnowMap();
        Character edgeBot = mockBot(new Point(-2000 + 30, -50), ledge);
        BotEntry edgeEntry = new BotEntry(edgeBot, null, null);
        Foothold small = ledge.getFootholds().findBelow(new Point(-2000 + 30, -100));
        edgeEntry.physX = -2000 + 30;
        edgeEntry.physY = -50;
        edgeEntry.hspeed = 1.0; // sliding right toward the ledge
        edgeEntry.facingDir = 1;
        edgeEntry.moveDir = 0;
        BotPhysicsEngine.applyGroundMotion(edgeEntry, edgeBot, small);
        assertEquals(-1, edgeEntry.facingDir);
        assertEquals(-1, edgeEntry.groundBrakeDir);
        assertEquals(CharacterStance.WALK_LEFT_STANCE, BotPhysicsEngine.resolveStance(edgeEntry));

        // Normal walking facing semantics unchanged: accelerating right faces right.
        entry.moveDir = 1;
        entry.hspeed = 0.5;
        BotPhysicsEngine.applyGroundMotion(entry, bot, fh);
        assertEquals(1, entry.facingDir);
        assertEquals(0, entry.groundBrakeDir);
        assertEquals(CharacterStance.WALK_RIGHT_STANCE, BotPhysicsEngine.resolveStance(entry));
    }

    @Test
    void shouldNotFlipFacingWithoutActualMovement() {
        // No facing change without at least one tick of real displacement (user realism
        // rule): a bot dithering its input at rest - sub-pixel pulses toward a tight launch
        // window - must not broadcast a stationary moonwalk/flip-flop to watchers.
        MapleMap snow = flatGroundMap(0.2f);
        Character bot = mockBot(new Point(0, 100), snow);
        BotEntry entry = new BotEntry(bot, null, null);
        Foothold fh = snow.getFootholds().findBelow(new Point(0, 99));
        entry.physX = 0;
        entry.physY = 100;
        entry.hspeed = -0.02; // residual sub-pixel slide left
        entry.facingDir = -1;
        entry.moveDir = 1;    // counter-input held, but the tick moves less than a pixel
        BotPhysicsEngine.applyGroundMotion(entry, bot, fh);
        assertEquals(0, bot.getPosition().x, "fixture: the tick must not move a whole pixel");
        assertEquals(-1, entry.facingDir, "no facing flip without actual movement");
        assertEquals(0, entry.groundBrakeDir, "no counter-strafe walk stance while stationary");
    }

    @Test
    void shouldKeepFacingLastPressedKeyWhileGlidingWithNoInput() {
        // Key released mid-slide: facing stays on the LAST pressed key, the glide never
        // turns the character into the slide direction.
        MapleMap snow = flatGroundMap(0.2f);
        Character bot = mockBot(new Point(0, 100), snow);
        BotEntry entry = new BotEntry(bot, null, null);
        Foothold fh = snow.getFootholds().findBelow(new Point(0, 99));
        entry.physX = 0;
        entry.physY = 100;
        entry.hspeed = 1.0;   // sliding right at top speed, mid-platform (glide, no brake)
        entry.facingDir = -1; // last pressed key was left
        entry.moveDir = 0;    // no key held
        BotPhysicsEngine.applyGroundMotion(entry, bot, fh);
        assertTrue(bot.getPosition().x > 0, "fixture: the glide tick must actually move");
        assertEquals(-1, entry.facingDir, "facing stays on the last pressed key during glide");
    }

    @Test
    void shouldReserveKineticRunwayOnSnow() {
        int normal = BotPhysicsEngine.launchRunwayPx(flatGroundMap(0f), BotMovementProfile.base());
        int snow = BotPhysicsEngine.launchRunwayPx(flatGroundMap(0.2f), BotMovementProfile.base());
        assertTrue(snow > normal, "snow still reserves extra accel room");
        assertTrue(snow <= normal + 35,
                "kinetic vmax^2/(2*a*fs) ~ 28 px, not the old 1/fs blowup: " + snow);
    }

    @Test
    void shouldDownJumpToAnyRealFloorBelowRegardlessOfDistance() {
        // Down-jump has NO drop-distance cap. The old 300px probe was empirically wrong: it stranded
        // the Orbis station (a ~780px straight drop) and was removed at GRAPH_VERSION 56->57. A landing
        // is found wherever a real floor exists below, any distance — refusal is forbidFallDown / no
        // floor, never distance (see kb_bot_downjump_eligibility, docs/bot/physics-client-audit.md).
        // Do NOT re-cap without in-client + disasm proof.
        assertTrue(BotPhysicsEngine.simulateDownJumpLanding(
                twoFloorMap(150), new Point(0, -150)) != null, "short gap: legal down-jump");
        assertTrue(BotPhysicsEngine.simulateDownJumpLanding(
                twoFloorMap(860), new Point(0, -860)) != null,
                "deep gap with a real floor below is still legal — distance is not a refusal reason");
    }

    private static MapleMap twoFloorMap(int gapPx) {
        MapleMap map = new MapleMap(200000000, 0, 0, 200000000, 1.0f);
        server.maps.FootholdTree tree = new server.maps.FootholdTree(
                new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(new Foothold(new Point(-500, -gapPx), new Point(500, -gapPx), 1));
        tree.insert(new Foothold(new Point(-500, 0), new Point(500, 0), 2));
        map.setFootholds(tree);
        return map;
    }

    private static MapleMap flatGroundMap(float fs) {
        // Synthetic-only map id (see smallPlatformSnowMap): never reuse a real map id here.
        MapleMap map = new MapleMap(999211000, 0, 0, 999211000, 1.0f);
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
        return hspeedAfterTicks(map, BotMovementProfile.base(), desiredDir, ticks, initialHSpeed);
    }

    private static double hspeedAfterTicks(MapleMap map, BotMovementProfile profile, int desiredDir,
                                           int ticks, double initialHSpeed) {
        Foothold fh = map.getFootholds().findBelow(new Point(0, 99));
        BotPhysicsEngine.GroundTravelState state =
                new BotPhysicsEngine.GroundTravelState(0, initialHSpeed, 0.0);
        Point pos = new Point(0, 100);
        for (int i = 0; i < ticks; i++) {
            BotPhysicsEngine.GroundStepResult step =
                    BotPhysicsEngine.simulateGroundMotion(map, pos, fh, desiredDir, state, profile);
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

        entry.airSteerVelX = -1.5;
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
