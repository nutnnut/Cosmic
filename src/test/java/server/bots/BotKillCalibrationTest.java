package server.bots;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 0 kill-rate calibration EMA math (docs/bot/unobserved-lod-design.md §5.0). */
class BotKillCalibrationTest {

    private static final int BOT = 1001;
    private static final int JOB = 100;      // warrior
    private static final int LEVEL = 35;     // level band 3
    private static final int MAP = 100000000;
    private static final int MOB = 210100;

    @BeforeEach
    void reset() {
        BotKillCalibration.resetForTest();
    }

    @Test
    void firstKillOnlyAnchors_noRateYet() {
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, 0L);
        // No prior interval to measure — a bot's rate is 0 until its second kill.
        assertEquals(0.0, BotKillCalibration.freshBotRate(BOT, MAP, MOB, Long.MAX_VALUE), 1e-9);
    }

    @Test
    void secondKillMeasuresRateFromInterval() {
        long t0 = 1_000_000L;
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t0);
        // 6s between kills => 600 kills/hr. First sample seeds the EMA exactly (no prior value).
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t0 + 6_000L);
        double rate = BotKillCalibration.freshBotRate(BOT, MAP, MOB, Long.MAX_VALUE);
        assertEquals(600.0, rate, 1e-6);
    }

    @Test
    void emaBlendsTowardTheNewSample() {
        long t = 1_000_000L;
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t);
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t += 6_000L);   // 600/hr seeds EMA
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t += 3_600L);   // 1000/hr next
        // EMA = 0.8*600 + 0.2*1000 = 680
        assertEquals(680.0, BotKillCalibration.freshBotRate(BOT, MAP, MOB, Long.MAX_VALUE), 1e-6);
    }

    @Test
    void absurdlyFastOrSlowIntervalsAreNotSampled() {
        long t = 1_000_000L;
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t);
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t + 50L);        // <250ms AoE burst: ignored
        assertEquals(0.0, BotKillCalibration.freshBotRate(BOT, MAP, MOB, Long.MAX_VALUE), 1e-9);
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t + 10 * 60_000L); // >5min gap: ignored (re-anchor)
        assertEquals(0.0, BotKillCalibration.freshBotRate(BOT, MAP, MOB, Long.MAX_VALUE), 1e-9);
    }

    @Test
    void bucketFactorIsMeasuredOverPredicted() {
        long t = 1_000_000L;
        // Advisor predicted 1200/hr for this map; measured comes out 600/hr => ratio 0.5.
        BotKillCalibration.notePrediction(BOT, 1200.0, MAP, t);
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t);
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t + 6_000L); // 600/hr measured
        assertEquals(0.5, BotKillCalibration.bucketFactor(JOB, LEVEL / 10), 1e-6);
    }

    @Test
    void bucketFactorDefaultsToOneWithoutSamples() {
        assertEquals(1.0, BotKillCalibration.bucketFactor(JOB, LEVEL / 10), 1e-9);
    }

    @Test
    void freshnessWindowExcludesStaleRates() {
        long t = 1_000_000L;
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t);
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, MOB, t + 6_000L);
        // A rate whose last kill is older than the freshness window is treated as unknown (0).
        long staleWindow = System.currentTimeMillis() - (t + 6_000L) - 1_000L;
        assertTrue(staleWindow > 0);
        assertEquals(0.0, BotKillCalibration.freshBotRate(BOT, MAP, MOB, staleWindow), 1e-9);
    }
}
