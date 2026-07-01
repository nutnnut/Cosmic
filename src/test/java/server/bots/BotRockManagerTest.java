package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WZ-free tests for the rock-buff sparing gate's TTK threshold interpolation
 * ({@link BotCombatManager#rockTtkThreshold}). Defaults: lvl1 -> 4 attacks, max -> 2 attacks.
 */
class BotRockManagerTest {

    @Test
    void level1NeedsLongFight() {
        // At skill level 1 (short duration), only worth a rock when the mob takes ~4+ attacks.
        assertEquals(4, BotCombatManager.rockTtkThreshold(1, 20));
    }

    @Test
    void maxLevelNeedsOnlyNotOneShot() {
        // At max level, worth a rock unless the mob is 1-shottable (>= 2 attacks to kill).
        assertEquals(2, BotCombatManager.rockTtkThreshold(20, 20));
    }

    @Test
    void interpolatesBetweenEndpoints() {
        int mid = BotCombatManager.rockTtkThreshold(10, 20);
        assertTrue(mid >= 2 && mid <= 4, "mid-level threshold should sit between the endpoints, got " + mid);
        // Monotonic non-increasing as level rises (more casts/duration -> lower bar).
        assertTrue(BotCombatManager.rockTtkThreshold(5, 20) >= BotCombatManager.rockTtkThreshold(15, 20));
    }

    @Test
    void singleLevelSkillUsesMaxBar() {
        // A 1-level skill can't interpolate; treat it as fully ranked (low bar).
        assertEquals(2, BotCombatManager.rockTtkThreshold(1, 1));
    }
}
