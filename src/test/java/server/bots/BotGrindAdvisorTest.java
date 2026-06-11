package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The advisor's gear-prospect value is the expected improvement over the worn roll,
 * {@code E[max(0, roll - current)]} over Monte Carlo samples of the real drop-roll
 * distribution (the sampler itself is a seam — {@link BotGrindAdvisor#rollScores} — because
 * ItemInformationProvider cannot load in unit tests; these tests inject fixed sample sets).
 */
class BotGrindAdvisorTest {

    // Fixed "roll distribution" of an item: some below, some above its catalog average of 13.
    private static final double[] SAMPLES = {10.0, 12.0, 14.0, 16.0};

    @Test
    void shouldValueEmptySlotAtTheFullSampleMean() {
        assertEquals(13.0, BotGrindAdvisor.expectedImprovement(SAMPLES, 0.0), 1e-9);
    }

    @Test
    void shouldShrinkAsTheWornRollImproves() {
        double naked = BotGrindAdvisor.expectedImprovement(SAMPLES, 0.0);
        double belowAverage = BotGrindAdvisor.expectedImprovement(SAMPLES, 11.0);
        double average = BotGrindAdvisor.expectedImprovement(SAMPLES, 13.0);
        double goodRoll = BotGrindAdvisor.expectedImprovement(SAMPLES, 15.0);
        double godly = BotGrindAdvisor.expectedImprovement(SAMPLES, 16.0);

        assertTrue(naked > belowAverage, "empty slot must be worth more than a below-average roll");
        assertTrue(belowAverage > average, "below-average roll must be worth more than an average one");
        assertTrue(average > goodRoll, "average roll must be worth more than a good one");
        assertTrue(goodRoll > godly, "good roll must still beat a max roll");
        assertEquals(0.0, godly, 1e-9, "a max roll leaves nothing to gain");
    }

    @Test
    void shouldOnlyCountPositiveImprovements() {
        // current = 12: improvements are 0, 0, 2, 4 -> mean 1.5; the below-roll samples
        // must not drag the expectation negative (the bot keeps its better worn copy).
        assertEquals(1.5, BotGrindAdvisor.expectedImprovement(SAMPLES, 12.0), 1e-9);
    }

    @Test
    void shouldBeZeroOnMissingOrEmptySamples() {
        assertEquals(0.0, BotGrindAdvisor.expectedImprovement(null, 5.0), 1e-9);
        assertEquals(0.0, BotGrindAdvisor.expectedImprovement(new double[0], 5.0), 1e-9);
    }
}
