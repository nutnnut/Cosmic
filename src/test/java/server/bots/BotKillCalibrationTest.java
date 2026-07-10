package server.bots;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kill-rate calibration math. The measured rate must be SUSTAINED kills/hr — total kills divided by the
 * time spent earning them — because that is the quantity {@link BotGrindAdvisor#modeledKillsPerHour}
 * models and the quantity the unobserved-map abstract grind replays. Averaging instantaneous
 * 3.6e6/interval samples instead would measure a peak rate: biased high on bursty kills and blind to
 * AoE multi-kills.
 */
class BotKillCalibrationTest {

    private static final int BOT = 1001;
    private static final int JOB = 100;      // warrior
    private static final int LEVEL = 35;     // level band 3
    private static final int MAP = 100000000;
    /** freshBotRate needs MIN_ACTIVE_MS (30s) of accumulated grind time before it reports anything. */
    private static final long T0 = 1_000_000L;

    @BeforeEach
    void reset() {
        BotKillCalibration.resetForTest();
    }

    /** Kill every {@code gapMs} for {@code n} kills, starting with an anchor kill at {@link #T0}.
     *  {@code modelKph} is the advisor-model bucket denominator (0 = no model → rate store only). */
    private static long grindSteadily(long gapMs, int n, double modelKph) {
        long t = T0;
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t, modelKph);
        for (int i = 0; i < n; i++) {
            t += gapMs;
            BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t, modelKph);
        }
        return t;
    }

    private static long grindSteadily(long gapMs, int n) {
        return grindSteadily(gapMs, n, 0.0);
    }

    private static double rate() {
        return BotKillCalibration.freshBotRate(BOT, MAP, Long.MAX_VALUE);
    }

    @Test
    void firstKillOnlyAnchors_noRateYet() {
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, T0, 0.0);
        // No elapsed time to attribute the kill to — a bot's rate is unknown until its second kill.
        assertEquals(0.0, rate(), 1e-9);
    }

    @Test
    void shortGrindReportsNoRateYet() {
        grindSteadily(6_000L, 3);   // only 18s of grinding: below MIN_ACTIVE_MS
        assertEquals(0.0, rate(), 1e-9);
    }

    @Test
    void steadyGrindMeasuresKillsOverElapsedTime() {
        grindSteadily(6_000L, 20);  // a kill every 6s => 600 kills/hr, whatever the decay
        assertEquals(600.0, rate(), 1e-6);
    }

    /**
     * Regression: an AoE cast that kills five mobs at once earned five kills for the price of one burst.
     * The old estimator advanced its anchor on every kill but discarded any interval under 250ms, so four
     * of the five vanished and mages measured SLOWER than single-target classes.
     */
    @Test
    void aoeMultiKillCountsEveryKillInTheBurst() {
        long t = T0;
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t, 0.0);
        for (int burst = 0; burst < 20; burst++) {
            t += 10_000L;                                   // 10s to line up the next cluster
            BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t, 0.0);
            for (int extra = 0; extra < 4; extra++) {       // four more mobs die on the same cast
                t += 100L;
                BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t, 0.0);
            }
        }
        // 5 kills per 10.4s burst cycle => ~1731 kills/hr. The old estimator reported roughly 360.
        // Tolerance is a few percent: the fading average ends each cycle on the four cheap burst kills,
        // which are more recent than the 10s gap, so it settles a little above the flat cycle mean.
        double trueKph = 5 * 3_600_000.0 / 10_400.0;
        assertEquals(trueKph, rate(), 0.05 * trueKph);
    }

    /**
     * Regression: with alternating fast and slow kills the sustained rate is total kills over total time.
     * A mean of per-kill rates would be dragged up by the fast ones (Jensen's inequality) and report a
     * peak the bot never sustains.
     */
    @Test
    void mixedFastAndSlowKillsMeasureTheSustainedRateNotThePeak() {
        long t = T0;
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t, 0.0);
        for (int i = 0; i < 30; i++) {
            t += 500L;                                       // a quick follow-up kill
            BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t, 0.0);
            t += 30_000L;                                    // then a long hunt for the next mob
            BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t, 0.0);
        }
        // 2 kills per 30.5s => ~236 kills/hr. An EMA of 3.6e6/interval reports ~3660 here.
        assertEquals(2 * 3_600_000.0 / 30_500.0, rate(), 5.0);
    }

    @Test
    void longGapReanchorsInsteadOfBillingIdleTime() {
        long t = grindSteadily(6_000L, 20);
        double before = rate();
        // A 10-minute break (travel, a quest errand) is not slow grinding: it must not drag the rate down.
        BotKillCalibration.recordKill(BOT, JOB, LEVEL, MAP, t + 10 * 60_000L, 0.0);
        assertEquals(before, rate(), 1e-9);
    }

    @Test
    void bucketFactorIsMeasuredOverModel() {
        // The advisor models 1200/hr for this bot here; it really sustains 600/hr => ratio 0.5.
        grindSteadily(6_000L, 20, 1200.0);
        assertEquals(0.5, BotKillCalibration.bucketFactor(JOB, LEVEL / 10), 1e-3);
    }

    @Test
    void bucketFactorDefaultsToOneWithoutSamples() {
        assertEquals(1.0, BotKillCalibration.bucketFactor(JOB, LEVEL / 10), 1e-9);
    }

    @Test
    void freshnessWindowExcludesStaleRates() {
        long last = grindSteadily(6_000L, 20);
        assertTrue(rate() > 0);
        long staleWindow = System.currentTimeMillis() - last - 1_000L;
        assertTrue(staleWindow > 0);
        // A rate whose last kill is older than the freshness window is treated as unknown (0).
        assertEquals(0.0, BotKillCalibration.freshBotRate(BOT, MAP, staleWindow), 1e-9);
    }
}
