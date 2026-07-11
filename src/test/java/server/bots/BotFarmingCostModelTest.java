package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotFarmingCostModelTest {

    private static BotFarmingCostModel.FarmInput in(double dropRate, double mobHp, double dps) {
        // attack cycle 0.6s, no seek overhead, 1 meso/sec → rarityMeso reads as "effort seconds".
        return new BotFarmingCostModel.FarmInput(dropRate, mobHp, dps, 0.6, 0.0, 1.0);
    }

    @Test
    void rarerItemCostsMore() {
        double common = BotFarmingCostModel.rarityMeso(in(0.10, 1000, 1000)); // 10 kills
        double rare = BotFarmingCostModel.rarityMeso(in(0.001, 1000, 1000));  // 1000 kills
        assertTrue(rare > common * 50, "0.1% drop must cost far more than 10% drop");
    }

    @Test
    void tankierMobCostsMore() {
        double squishy = BotFarmingCostModel.rarityMeso(in(0.01, 500, 1000));
        double tanky = BotFarmingCostModel.rarityMeso(in(0.01, 5000, 1000));
        assertTrue(tanky > squishy, "higher mob HP must raise farming cost");
    }

    @Test
    void killTimeFlooredByAttackCycle() {
        // Tiny HP, enormous DPS: naive HP/DPS = 0.0001s, but you can't kill faster than one swing.
        double t = BotFarmingCostModel.secondsPerKill(in(0.5, 10, 1_000_000));
        assertEquals(0.6, t, 1e-9, "seconds-per-kill must floor at the attack cycle, not 1000 kills/sec");
    }

    @Test
    void killTimeUsesHpOverDpsWhenAboveFloor() {
        // 6000 HP / 1000 dps = 6s, well above the 0.6s floor.
        double t = BotFarmingCostModel.secondsPerKill(in(0.5, 6000, 1000));
        assertEquals(6.0, t, 1e-9);
    }

    @Test
    void zeroDpsIsInfeasible() {
        assertEquals(Double.POSITIVE_INFINITY, BotFarmingCostModel.rarityMeso(in(0.1, 1000, 0)));
    }

    @Test
    void zeroOrUnknownDropRateIsInfeasible() {
        assertEquals(Double.POSITIVE_INFINITY, BotFarmingCostModel.rarityMeso(in(0.0, 1000, 1000)));
    }

    @Test
    void nearZeroDropRateStaysFiniteViaKillCap() {
        // 1e-9 drop rate would be 1e9 kills; the cap holds it to MAX_EXPECTED_KILLS so cost is finite.
        double cost = BotFarmingCostModel.rarityMeso(in(1e-9, 1000, 1000));
        assertTrue(Double.isFinite(cost), "near-zero drop rate must stay finite (capped), not infinite");
        // 1000 HP / 1000 dps = 1.0s per kill → MAX_EXPECTED_KILLS × (1s + 0) × 1 meso/s
        assertEquals(BotFarmingCostModel.MAX_EXPECTED_KILLS * 1.0, cost, 1.0);
    }

    @Test
    void guaranteedDropIsOneKill() {
        // chance > 1,000,000 → rate > 1 → exactly one kill. 1000 HP / 1000 dps = 1s.
        double cost = BotFarmingCostModel.rarityMeso(in(5.0, 1000, 1000));
        assertEquals(1.0, cost, 1e-9);
    }

    @Test
    void byproductCreditNetsAgainstEffort() {
        // 1000 kills × 1s effort at 1 meso/s = 1000 gross; the dropper yields 0.4 meso/kill anyway.
        BotFarmingCostModel.FarmInput input = in(0.001, 1000, 1000);
        assertEquals(1000.0, BotFarmingCostModel.rarityMeso(input), 1e-9);
        assertEquals(600.0, BotFarmingCostModel.rarityMeso(input, 0.4), 1e-9,
                "byproduct income comes off the per-kill effort");
    }

    @Test
    void onPathDropperNetsToZeroNotNegative() {
        // Byproduct worth MORE than the effort (an ordinary grind mob): net clamps at 0 — the item
        // arrives free while leveling; salvage floors the price downstream, never a negative cost.
        double net = BotFarmingCostModel.rarityMeso(in(0.001, 1000, 1000), 5.0);
        assertEquals(0.0, net, 1e-9);
    }
}
