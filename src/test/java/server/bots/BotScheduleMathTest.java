package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WZ/DB-free tests for the population scheduler's pure decision math. */
class BotScheduleMathTest {

    private static final int[] CURVE = {
            3, 2, 2, 1, 1, 1, 2, 4, 6, 7, 8, 9,
            10, 10, 9, 9, 10, 12, 14, 15, 14, 11, 7, 4
    };

    @Test
    void targetReadsTheCurveAndClampsNoise() {
        assertEquals(15, BotScheduleMath.targetForHour(CURVE, 19, 0, 0.5)); // no noise
        // noiseRoll 1.0 -> +noise, 0.0 -> -noise, never below 0
        assertEquals(15 + 2, BotScheduleMath.targetForHour(CURVE, 19, 2, 1.0));
        assertEquals(15 - 2, BotScheduleMath.targetForHour(CURVE, 19, 2, 0.0));
        assertEquals(0, BotScheduleMath.targetForHour(CURVE, 4, 5, 0.0)); // 1 - 5 clamps to 0
        assertEquals(0, BotScheduleMath.targetForHour(CURVE, 99, 0, 0.5)); // out of range -> 0
    }

    @Test
    void activeTodayIsDeterministicPerDayAndRespectsRatio() {
        // Same (seed, day) -> same answer (no within-day flicker).
        assertEquals(BotScheduleMath.isActiveToday(42L, 100L, 0.5),
                BotScheduleMath.isActiveToday(42L, 100L, 0.5));
        // ratio 1.0 always on, 0.0 never on.
        assertTrue(BotScheduleMath.isActiveToday(42L, 100L, 1.0));
        assertFalse(BotScheduleMath.isActiveToday(42L, 100L, 0.0));
        // Over many days, a 0.5 bot is active roughly half the time (loose bounds).
        int on = 0;
        for (long d = 0; d < 400; d++) {
            if (BotScheduleMath.isActiveToday(7L, d, 0.5)) on++;
        }
        assertTrue(on > 120 && on < 280, "expected ~half of 400 days, got " + on);
    }

    @Test
    void onlineDesireZeroWhenNotActiveTodayElsePositive() {
        BotPersonality always = new BotPersonality(1L, 1.0, peakAt(20), 60, 0.8, 0.0, 5,
                0.3, 0.3, 0.5, BotPersonality.Archetype.REGULAR, 60);
        BotPersonality never = new BotPersonality(1L, 0.0, peakAt(20), 60, 0.8, 0.0, 5,
                0.3, 0.3, 0.5, BotPersonality.Archetype.REGULAR, 60);
        assertTrue(BotScheduleMath.onlineDesire(always, 20, 1, 100L) > 0.0);
        assertEquals(0.0, BotScheduleMath.onlineDesire(never, 20, 1, 100L), 1e-9);
        // Higher at the preferred hour than at a trough hour.
        assertTrue(BotScheduleMath.onlineDesire(always, 20, 1, 100L)
                > BotScheduleMath.onlineDesire(always, 8, 1, 100L));
    }

    @Test
    void careerEndsAfterLengthExceptHardcore() {
        assertFalse(BotScheduleMath.careerEnded(5, 10, false));   // 5 < 10 days
        assertTrue(BotScheduleMath.careerEnded(10, 10, false));   // reached length
        assertTrue(BotScheduleMath.careerEnded(99, 10, false));
        assertFalse(BotScheduleMath.careerEnded(99999, BotPersonality.HARDCORE_FOREVER, true)); // hardcore never
    }

    @Test
    void sessionElapsedAfterDuration() {
        long start = 1_000_000L;
        assertFalse(BotScheduleMath.sessionElapsed(start, 60_000L, start + 30_000L));
        assertTrue(BotScheduleMath.sessionElapsed(start, 60_000L, start + 60_000L));
        assertFalse(BotScheduleMath.sessionElapsed(0L, 60_000L, start)); // never started
    }

    @Test
    void unitHashIsInRangeAndDeterministic() {
        for (long i = 0; i < 100; i++) {
            double u = BotScheduleMath.unitHash(i, i * 31);
            assertTrue(u >= 0.0 && u < 1.0, "unit hash out of range: " + u);
        }
        assertEquals(BotScheduleMath.unitHash(5, 9), BotScheduleMath.unitHash(5, 9));
    }

    private static int[] peakAt(int hour) {
        int[] w = new int[24];
        java.util.Arrays.fill(w, 1);
        w[hour] = 11;
        return w;
    }
}
