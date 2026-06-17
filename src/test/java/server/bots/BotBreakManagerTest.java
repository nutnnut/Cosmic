package server.bots;

import org.junit.jupiter.api.Test;

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
}
