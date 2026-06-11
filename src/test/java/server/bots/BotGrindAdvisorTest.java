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

    // ---- scroll prospects: pure EV, no tiering ----

    @Test
    void shouldValueScrollsByExpectedGain() {
        // Offense-SSOT units (att weight 5.0): 60% +2 att = 6.0 EV beats both the safe
        // 100% +1 att (5.0) and the 10% +5 att jackpot (2.5) — "good" mid scrolls win
        // on expectation alone.
        double midOdds = BotGrindAdvisor.scrollExpectedGain(0.6, 10.0, true);
        double safe = BotGrindAdvisor.scrollExpectedGain(1.0, 5.0, true);
        double jackpot = BotGrindAdvisor.scrollExpectedGain(0.1, 25.0, true);

        assertEquals(6.0, midOdds, 1e-9);
        assertTrue(midOdds > safe, "60% with a big payload must beat the safe small scroll");
        assertTrue(safe > jackpot, "long-shot 10% must not dominate on raw payload");
    }

    @Test
    void shouldNotValueScrollsWithoutAnOpenSlotTarget() {
        // Nothing worn that the scroll applies to (or no upgrade slots left) = vendor trash.
        assertEquals(0.0, BotGrindAdvisor.scrollExpectedGain(0.6, 10.0, false), 1e-9);
    }

    @Test
    void shouldNotValueUselessOrImpossibleScrolls() {
        assertEquals(0.0, BotGrindAdvisor.scrollExpectedGain(0.0, 10.0, true), 1e-9,
                "0% success has no expectation");
        assertEquals(0.0, BotGrindAdvisor.scrollExpectedGain(0.6, 0.0, true), 1e-9,
                "no offense value for this class (e.g. matk scroll on a bowman)");
    }

    // ---- multi-mob maps: spawn-share blend (time on one mob is time not on another) ----

    private static BotGrindAdvisor.MobProfile profile(int mobId, String name, int exp,
                                                      double killSeconds,
                                                      BotGrindPlanner.GearProspect... drops) {
        return new BotGrindAdvisor.MobProfile(mobId, name, 10, exp, killSeconds,
                java.util.List.of(drops));
    }

    @Test
    void shouldBlendExpAndKillTimeBySpawnShare() {
        // 3 spawn points of a 100-exp/2s mob mixed with 1 point of a 20-exp/6s mob:
        // exp = 0.75*100 + 0.25*20 = 80, kill = 0.75*2 + 0.25*6 = 3s — in between, not the best.
        var blend = BotGrindAdvisor.blendCandidate(100, "test map", java.util.Map.of(
                profile(1, "good", 100, 2.0), 3,
                profile(2, "bad", 20, 6.0), 1));

        assertEquals(80, blend.exp());
        assertEquals(3.0, blend.killSeconds(), 1e-9);
        assertEquals(4, blend.spawnPoints());
        assertEquals("good", blend.mobName(), "labeled by the dominant mob");
    }

    @Test
    void shouldDiluteAndMergeDropChancesBySpawnShare() {
        // Item 77 drops from both mobs: 0.75*0.10 + 0.25*0.20 = 0.125 per generic kill.
        // Item 88 only from the minority mob: 0.25*0.40 = 0.10.
        var g1 = new BotGrindPlanner.GearProspect(77, "shield", 0.10, 5.0, 0.1);
        var g2 = new BotGrindPlanner.GearProspect(77, "shield", 0.20, 5.0, 0.1);
        var g3 = new BotGrindPlanner.GearProspect(88, "scroll", 0.40, 6.0, 0.12);
        var blend = BotGrindAdvisor.blendCandidate(100, "test map", java.util.Map.of(
                profile(1, "common", 50, 2.0, g1), 3,
                profile(2, "rare", 50, 2.0, g2, g3), 1));

        assertEquals(2, blend.gearDrops().size());
        for (var g : blend.gearDrops()) {
            if (g.itemId() == 77) {
                assertEquals(0.125, g.chancePerKill(), 1e-9, "shared drop sums diluted chances");
                assertEquals(5.0, g.scoreGain(), 1e-9, "gain is per item, not per mob");
            } else {
                assertEquals(0.10, g.chancePerKill(), 1e-9, "minority-mob drop diluted by share");
            }
        }
    }

    @Test
    void shouldBreakDominantMobTiesByExp() {
        var blend = BotGrindAdvisor.blendCandidate(100, "test map", java.util.Map.of(
                profile(1, "weak", 10, 2.0), 2,
                profile(2, "juicy", 50, 2.0), 2));
        assertEquals("juicy", blend.mobName());
    }
}
