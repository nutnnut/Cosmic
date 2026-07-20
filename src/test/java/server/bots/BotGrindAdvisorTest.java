package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The advisor's gear-prospect value is the expected improvement over the worn roll,
 * {@code E[max(0, roll - current)]} over Monte Carlo samples of the real drop-roll
 * distribution (the sampler itself is a seam - {@link BotGrindAdvisor#rollScores} - because
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
    void dangerMesoScalerRampsFromBrokeToRich() {
        // 0 meso -> max aversion; at/above the cap -> min; linear midpoint.
        assertEquals(BotManager.cfg.DANGER_MESO_SCALER_MAX, BotGrindAdvisor.dangerMesoScaler(0), 1e-9);
        assertEquals(BotManager.cfg.DANGER_MESO_SCALER_MIN, BotGrindAdvisor.dangerMesoScaler(BotManager.cfg.DANGER_MESO_CAP), 1e-9);
        assertEquals(BotManager.cfg.DANGER_MESO_SCALER_MIN, BotGrindAdvisor.dangerMesoScaler(BotManager.cfg.DANGER_MESO_CAP * 10L), 1e-9);
        double mid = (BotManager.cfg.DANGER_MESO_SCALER_MAX + BotManager.cfg.DANGER_MESO_SCALER_MIN) / 2.0;
        assertEquals(mid, BotGrindAdvisor.dangerMesoScaler(BotManager.cfg.DANGER_MESO_CAP / 2), 1e-9);
    }

    @Test
    void dangerMapWeightDiscountsDangerousMapsForBrokeBot() {
        client.Character broke = org.mockito.Mockito.mock(client.Character.class);
        org.mockito.Mockito.when(broke.getMeso()).thenReturn(0);
        // A mob expected to deal 20% HP/hit on a 0-meso bot: 1/(1 + 0.20*10) = 1/3.
        var dangerous = new BotGrindPlanner.MobCandidate(1, "m", 30, 100, 2.0, 10, "map10", 8, 0, 0.20, java.util.List.of());
        var safe = new BotGrindPlanner.MobCandidate(2, "m", 30, 100, 2.0, 20, "map20", 8, 0, 0.0, java.util.List.of());
        var weight = BotGrindAdvisor.dangerMapWeight(broke, java.util.List.of(dangerous, safe));
        assertEquals(1.0 / 3.0, weight.applyAsDouble(10), 1e-9);
        assertEquals(1.0, weight.applyAsDouble(20), 1e-9);     // mobless map: undiscounted
        assertEquals(1.0, weight.applyAsDouble(999), 1e-9);    // unknown map: identity
    }

    @Test
    void shouldBeZeroOnMissingOrEmptySamples() {
        assertEquals(0.0, BotGrindAdvisor.expectedImprovement(null, 5.0), 1e-9);
        assertEquals(0.0, BotGrindAdvisor.expectedImprovement(new double[0], 5.0), 1e-9);
    }

    @Test
    void levelDiscountScalesImprovementNotRollTotal() {
        // The wait-discount must scale the IMPROVEMENT over the worn item, the way expectedAcquireGain
        // now composes it (discount * E[max(0, roll*hitFactor - worn)]), NOT the roll total. Worn=100:
        // a +10 drop 5 levels out must out-value a +5 wearable now, since (110-100)*0.9^5=5.90 > 5.00.
        // Under the old roll*discount form the future drop clamped to 0 (110*0.59=64.9 < 100).
        double futureBetter = BotGrindAdvisor.levelDiscount(5)
                * BotGrindAdvisor.expectedImprovement(new double[]{110.0}, 1.0, 100.0);
        double nowMarginal = BotGrindAdvisor.levelDiscount(0)
                * BotGrindAdvisor.expectedImprovement(new double[]{105.0}, 1.0, 100.0);
        assertTrue(futureBetter > 0.0, "the better future drop must not clamp to zero");
        assertTrue(futureBetter > nowMarginal,
                "better future drop must out-value a marginal wearable-now one: " + futureBetter + " vs " + nowMarginal);
    }

    @Test
    void shouldRejectOffBandMapBeforeBuildingItsGearProspects() {
        var inBand = profile(1, "in", 10, 2.0);
        var outOfBand = new BotGrindAdvisor.MobProfile(
                2, "out", 80, 0, 10, 2.0, 2.0, 0.0, java.util.List.of());
        var profiles = java.util.Map.of(1, inBand, 2, outOfBand);

        assertFalse(BotGrindAdvisor.passesMapAdmission(
                java.util.Map.of(1, 2, 2, 20), profiles, 30));
        assertTrue(BotGrindAdvisor.passesMapAdmission(
                java.util.Map.of(1, 3, 2, 20), profiles, 30));
        assertTrue(BotGrindAdvisor.passesMapAdmission(
                java.util.Map.of(1, 2, 2, 20), profiles, 0));
    }

    // ---- scroll prospects: pure EV, no tiering ----

    @Test
    void shouldValueScrollsByExpectedGain() {
        // Offense-SSOT units (att weight 5.0): 60% +2 att = 6.0 EV beats both the safe
        // 100% +1 att (5.0) and the 10% +5 att jackpot (2.5) - "good" mid scrolls win
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

    // ---- owned-but-not-worn gear and level-gated drops ----

    @Test
    void shouldDecayValueByLevelsUntilWearable() {
        assertEquals(1.0, BotGrindAdvisor.levelDiscount(0), 1e-9, "wearable now = full value");
        assertEquals(Math.pow(0.9, 5), BotGrindAdvisor.levelDiscount(5), 1e-9);
        assertTrue(BotGrindAdvisor.levelDiscount(5) > BotGrindAdvisor.levelDiscount(10),
                "longer wait must be worth less");
    }

    @Test
    void shouldCompareDropsAgainstTheBestOwnedCopyTimeConsistently() {
        // Worn item scores 10; a bagged 30 wearable in 5 levels sets the bar at
        // max(10, 0.9^5 * 30) ~ 17.7 - owning a better copy raises the bar even benched.
        double bar = Math.max(10.0, BotGrindAdvisor.levelDiscount(5) * 30.0);

        // A wear-now 20 drop keeps interim value: it serves until the 30 comes online.
        double now20 = BotGrindAdvisor.expectedImprovement(
                new double[]{20.0}, BotGrindAdvisor.levelDiscount(0), bar);
        assertTrue(now20 > 0, "wear-now drop must keep interim value vs a benched better item");

        // A 25 drop also 5 levels out is dominated by the owned 30: same wait, worse item.
        double later25 = BotGrindAdvisor.expectedImprovement(
                new double[]{25.0}, BotGrindAdvisor.levelDiscount(5), bar);
        assertEquals(0.0, later25, 1e-9, "owned better copy with the same wait must win");

        // Without the bagged 30, the same future 25 is a real (discounted) upgrade over 10.
        double later25NoBag = BotGrindAdvisor.expectedImprovement(
                new double[]{25.0}, BotGrindAdvisor.levelDiscount(5), 10.0);
        assertTrue(later25NoBag > 0);
    }

    // ---- cross-slot exclusivity bars (2H <-> shield, overall <-> top+pants) ----

    @Test
    void shouldBarPantsAgainstTheOverallEnsembleNotAnEmptySlot() {
        // Worn overall scores 50; best owned top 10, no pants. A pants drop keeps the top,
        // so it must beat 50 - 10 = 40 — not the empty pants slot (0).
        assertEquals(40.0, BotGrindAdvisor.crossSlotBar(50.0, 0.0, 10.0, false), 1e-9);
        // With no overall the bar collapses to the pieces: pants drop vs (top+pants) - top.
        assertEquals(8.0, BotGrindAdvisor.crossSlotBar(0.0, 8.0, 10.0, false), 1e-9);
    }

    @Test
    void shouldChargeACombinedCandidateForBothPiecesItDisplaces() {
        // Overall drop while wearing top 10 + pants 8: must beat the SUM 18, not just the top.
        assertEquals(18.0, BotGrindAdvisor.crossSlotBar(0.0, 10.0, 8.0, true), 1e-9);
        // Same shape for a 2H weapon vs a worn 1H 30 + shield 12 pair.
        assertEquals(42.0, BotGrindAdvisor.crossSlotBar(0.0, 30.0, 12.0, true), 1e-9);
        // An owned 2H 45 stays the bar when it beats the pair.
        assertEquals(45.0, BotGrindAdvisor.crossSlotBar(45.0, 30.0, 12.0, true), 1e-9);
    }

    @Test
    void shouldNeverReturnANegativeBar() {
        // ensemble >= partner always, so "ensemble - partner" stays >= the kept piece's rival.
        assertTrue(BotGrindAdvisor.crossSlotBar(0.0, 0.0, 25.0, false) >= 0.0);
        assertEquals(0.0, BotGrindAdvisor.crossSlotBar(0.0, 0.0, 0.0, false), 1e-9);
    }

    // ---- scroll headroom: open upgrade slots count, priced by equip type ----

    @Test
    void shouldLetOpenSlotsBeatAMaxedOutStrongerItem() {
        // Worn glove: offense 20 but maxed out -> potential 20. Fresh glove roll: offense 12
        // with 5 open slots; gloves scroll att (EV 6.0/slot): 12 + 0.5*5*6.0 = 27 -> wins.
        double worn = 20.0 + BotScrollManager.scrollHeadroom(0, 6.0);
        double drop = 12.0 + BotScrollManager.scrollHeadroom(5, 6.0);
        assertTrue(drop > worn, "weaker-but-scrollable must out-value maxed-out stronger");
    }

    @Test
    void shouldScaleSlotValueByEquipType() {
        // Same 8-offense gap on a piece whose best scroll is a stat scroll (EV 1.2/slot):
        // 12 + 0.5*5*1.2 = 15 < 20 -> slots alone don't justify re-gearing weak-scrolling types.
        double worn = 20.0 + BotScrollManager.scrollHeadroom(0, 1.2);
        double drop = 12.0 + BotScrollManager.scrollHeadroom(5, 1.2);
        assertTrue(worn > drop, "slot bonus must not be flat across equip types");
    }

    @Test
    void shouldDiscountHeadroomAndGuardEdges() {
        assertEquals(15.0, BotScrollManager.scrollHeadroom(5, 6.0), 1e-9, "0.5 x slots x EV");
        assertEquals(0.0, BotScrollManager.scrollHeadroom(0, 6.0), 1e-9);
        assertEquals(0.0, BotScrollManager.scrollHeadroom(-1, 6.0), 1e-9);
        assertEquals(0.0, BotScrollManager.scrollHeadroom(5, 0.0), 1e-9);
    }

    // ---- multi-mob maps: spawn-share blend (time on one mob is time not on another) ----

    private static BotGrindAdvisor.MobProfile profile(int mobId, String name, int exp,
                                                      double killSeconds,
                                                      BotGrindPlanner.GearProspect... drops) {
        return new BotGrindAdvisor.MobProfile(mobId, name, 10, 0, exp, killSeconds, killSeconds, 0.0,
                java.util.List.of(drops));
    }

    @Test
    void shouldBlendExpAndKillTimeBySpawnShare() {
        // 3 spawn points of a 100-exp/2s mob mixed with 1 point of a 20-exp/6s mob:
        // exp = 0.75*100 + 0.25*20 = 80, kill = 0.75*2 + 0.25*6 = 3s - in between, not the best.
        var blend = BotGrindAdvisor.blendCandidate(100, "test map", 0, java.util.Map.of(
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
        var blend = BotGrindAdvisor.blendCandidate(100, "test map", 0, java.util.Map.of(
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
        var blend = BotGrindAdvisor.blendCandidate(100, "test map", 0, java.util.Map.of(
                profile(1, "weak", 10, 2.0), 2,
                profile(2, "juicy", 50, 2.0), 2));
        assertEquals("juicy", blend.mobName());
    }
}
