package server.bots;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 2 LOD1 motion-plan pure math (docs/bot/unobserved-lod-design.md §2.1). */
class BotMotionPlanTest {

    private static BotNavigationGraph.Edge walk(int x0, int y0, int x1, int y1) {
        return new BotNavigationGraph.Edge(0, 1, BotNavigationGraph.EdgeType.WALK,
                new Point(x0, y0), new Point(x1, y1), 0, 0, 0, 0, 0, 0);
    }

    @Test
    void routePixelLengthSumsSegmentsAndGaps() {
        // (0,0)->(100,0) [100], gap to next start (110,0) [10], (110,0)->(160,0) [50] = 160.
        double len = BotPhysicsEngine.routePixelLength(List.of(walk(0, 0, 100, 0), walk(110, 0, 160, 0)));
        assertEquals(160.0, len, 1e-9);
    }

    @Test
    void routePixelLengthEmptyOrNullIsZero() {
        assertEquals(0.0, BotPhysicsEngine.routePixelLength(null), 1e-9);
        assertEquals(0.0, BotPhysicsEngine.routePixelLength(List.of()), 1e-9);
    }

    @Test
    void straightLineAppliesSlackFactor() {
        assertEquals(130.0, BotPhysicsEngine.straightLinePixelLength(new Point(0, 0), new Point(100, 0)), 1e-9);
        assertEquals(0.0, BotPhysicsEngine.straightLinePixelLength(null, new Point(1, 1)), 1e-9);
    }

    @Test
    void durationIsDistanceOverSpeed() {
        // 250px at 125px/s = 2.0s = 2000ms (rng=null disables jitter for a deterministic check).
        assertEquals(2000L, BotPhysicsEngine.motionDurationMs(250.0, 125.0, null));
        assertEquals(0L, BotPhysicsEngine.motionDurationMs(0.0, 125.0, null));
        assertEquals(0L, BotPhysicsEngine.motionDurationMs(250.0, 0.0, null));
    }

    @Test
    void durationJitterStaysWithinTenPercent() {
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 1000; i++) {
            long ms = BotPhysicsEngine.motionDurationMs(1000.0, 100.0, rng); // base 10000ms
            assertEquals(true, ms >= 9000 && ms <= 11000, "jitter out of band: " + ms);
        }
    }

    @Test
    void lerpInterpolatesXAndHoldsY() {
        Point from = new Point(0, 77);
        Point to = new Point(200, 999);
        // Midway in time -> x=100, Y held at holdY (not to.y).
        assertEquals(new Point(100, 100), BotPhysicsEngine.motionLerp(from, to, 1000, 3000, 100, 2000));
        // Before departure -> from.x.
        assertEquals(new Point(0, 100), BotPhysicsEngine.motionLerp(from, to, 1000, 3000, 100, 500));
        // After arrival -> to.x.
        assertEquals(new Point(200, 100), BotPhysicsEngine.motionLerp(from, to, 1000, 3000, 100, 9000));
    }

    @Test
    void lerpNullEndpoints() {
        assertNull(BotPhysicsEngine.motionLerp(null, null, 0, 0, 0, 0));
        assertEquals(new Point(5, 100), BotPhysicsEngine.motionLerp(null, new Point(5, 0), 0, 10, 100, 5));
        assertEquals(new Point(9, 100), BotPhysicsEngine.motionLerp(new Point(9, 0), null, 0, 10, 100, 5));
    }

    // --- LOD1 motion-plan Y-band gate: only same-level glides are abstracted; cross-level targets
    // fall through to real physics so the bot navigates the level change (design fidelity). Uses the
    // REAL basic-attack vertical reach (ATTACK_RANGE_Y=50 up / ATTACK_DOWN_MAX=20 down) as SSOT. ---

    @Test
    void yBandGateCoversSameLevelTargets() {
        Point bot = new Point(500, 300);
        assertTrue(BotCombatManager.withinAttackYReach(bot, new Point(900, 300)));   // exactly level
        assertTrue(BotCombatManager.withinAttackYReach(bot, new Point(900, 251)));   // 49 above (< 50)
        assertTrue(BotCombatManager.withinAttackYReach(bot, new Point(900, 250)));   // 50 above (== ATTACK_RANGE_Y)
        assertTrue(BotCombatManager.withinAttackYReach(bot, new Point(900, 320)));   // 20 below (== ATTACK_DOWN_MAX)
    }

    @Test
    void yBandGateFallsThroughBeyondAttackReach() {
        Point bot = new Point(500, 300);
        assertFalse(BotCombatManager.withinAttackYReach(bot, new Point(900, 249)));  // 51 above (> ATTACK_RANGE_Y)
        assertFalse(BotCombatManager.withinAttackYReach(bot, new Point(900, 321)));  // 21 below (> ATTACK_DOWN_MAX)
        assertFalse(BotCombatManager.withinAttackYReach(bot, new Point(900, 150)));  // a platform well above
    }
}
