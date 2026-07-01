package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WZ/DB-free tests for the pure in-session break decision ({@link BotBreakManager#startsBreak}). */
class BotBreakManagerTest {

    @Test
    void zeroFrequencyNeverBreaks() {
        // Non-managed bots default to breakFreqPerHour 0 -> they never take random breaks.
        assertFalse(BotBreakManager.startsBreak(0.0, 0.5, 0.0));
    }

    @Test
    void highFrequencyBreaksOnLowRoll() {
        // 60/hr -> 1.0/min, capped at 0.5: a roll below 0.5 starts a break, above it doesn't.
        assertTrue(BotBreakManager.startsBreak(60.0, 0.0, 0.40));
        assertFalse(BotBreakManager.startsBreak(60.0, 0.0, 0.51));
    }

    @Test
    void probabilityIsCappedSoNoBreakEveryMinute() {
        assertTrue(BotBreakManager.startsBreak(600.0, 0.0, 0.49));
        assertFalse(BotBreakManager.startsBreak(600.0, 0.0, 0.50)); // strict < cap
    }

    @Test
    void diligentBotsBreakLess() {
        // 30/hr -> 0.5/min; farmIdleRatio 1.0 damps it to 0.25/min. A roll of 0.30 breaks a lazy bot
        // (0.30 < 0.50) but not a diligent one (0.30 > 0.25).
        assertTrue(BotBreakManager.startsBreak(30.0, 0.0, 0.30));
        assertFalse(BotBreakManager.startsBreak(30.0, 1.0, 0.30));
    }

    @Test
    void durationIsWithinJitterBand() {
        for (int i = 0; i < 50; i++) {
            long ms = BotBreakManager.breakDurationMs(10);
            assertTrue(ms >= 10 * 60_000L * 0.5 && ms <= 10 * 60_000L * 1.5,
                    "break length should be 0.5x..1.5x the mean, got " + ms);
        }
    }

    // --- catch-up split: which cohort members skip a group break to grind up to the pack ---

    @Test
    void noLevelGap_nobodySplits() {
        int[] levels = {12, 13, 14, 15}; // 3 total spread, but no single >= 3 jump
        for (int lv : levels) {
            assertFalse(BotBreakManager.catchUpSplit(lv, levels, 3), "lv " + lv);
        }
    }

    @Test
    void lowClusterBelowTheGap_allSplit() {
        int[] levels = {10, 11, 20, 21}; // 11 -> 20 is a 9-level wall
        assertTrue(BotBreakManager.catchUpSplit(10, levels, 3));
        assertTrue(BotBreakManager.catchUpSplit(11, levels, 3)); // the whole low pair catches up
        assertFalse(BotBreakManager.catchUpSplit(20, levels, 3));
        assertFalse(BotBreakManager.catchUpSplit(21, levels, 3));
    }

    @Test
    void singleLowOutlier_splits() {
        int[] levels = {10, 15, 16, 17};
        assertTrue(BotBreakManager.catchUpSplit(10, levels, 3));
        assertFalse(BotBreakManager.catchUpSplit(15, levels, 3));
    }

    @Test
    void degenerateInputs_noSplit() {
        assertFalse(BotBreakManager.catchUpSplit(10, new int[] {10}, 3));     // solo
        assertFalse(BotBreakManager.catchUpSplit(10, new int[] {10, 20}, 0)); // trigger 0
    }

    // --- town-break probability ramp: 90% at 1 hop back -> 10% at 10 hops, soft-capped both ends ---

    @Test
    void townBreakChance_rampAndClamps() {
        assertEquals(0.9, BotBreakManager.townBreakChance(1), 1e-9);   // near grind: almost always town
        assertEquals(0.5, BotBreakManager.townBreakChance(5), 0.05);   // mid ramp
        assertEquals(0.1, BotBreakManager.townBreakChance(10), 1e-9);  // soft cap reached
        assertEquals(0.1, BotBreakManager.townBreakChance(25), 1e-9);  // deeper stays at the floor
        assertEquals(0.9, BotBreakManager.townBreakChance(0), 1e-9);   // clamps above 90%
    }
}
