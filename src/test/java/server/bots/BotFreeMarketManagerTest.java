package server.bots;

import org.junit.jupiter.api.Test;
import server.Trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure pricing rules of the FM stall pipeline (the errand itself is live-verified). */
class BotFreeMarketManagerTest {

    @Test
    void unitAskLetsBeliefOverrideTheAnchorDownward() {
        // The fix: a confident belief from real clearings must NOT be floored by a much higher
        // reproduction/cost anchor (the old max(belief, anchor) pinned illiquid asks billions high).
        int believed = BotFreeMarketManager.unitAsk(2_000_000, 50, 50_000_000, 10_000);
        assertTrue(believed < 5_000_000, "a confident 2m belief prices near 2m, not the 50m anchor");
        assertTrue(believed > 2_000_000, "still above the belief by the opening margin");

        // No belief -> the (already sanity-capped) anchor stands, cost-plus.
        int ignorant = BotFreeMarketManager.unitAsk(0, 0, 400_000, 10_000);
        assertTrue(ignorant > 400_000, "no perception -> cost-plus off the anchor");
        assertTrue(ignorant > BotFreeMarketManager.unitAsk(0, 20, 400_000, 10_000),
                "ignorance widens the opening margin (room to learn downward)");

        // Salvage (NPC sell-back) is the hard floor — never list below it.
        assertTrue(BotFreeMarketManager.unitAsk(0, 0, 1_000, 500_000) >= 500_000,
                "never opens below the NPC salvage reservation");

        assertEquals(0, BotFreeMarketManager.unitAsk(0, 0, 0, 0), "nothing to go on -> don't list");
    }

    @Test
    void listingPremiumIsAfterFeeGainOverNpcSale() {
        // 100k NPC stack value: a 101k gross premium is whatever survives the fee
        long npc = 100_000;
        int barelyAbove = 101_000;
        long fee = Trade.getFee(barelyAbove);
        assertEquals(1_000 - fee, BotFreeMarketManager.listingPremium(barelyAbove, 1, npc),
                "fee comes straight off the premium");
        assertTrue(BotFreeMarketManager.listingPremium(150_000, 1, npc) > 0,
                "clear premium over NPC lists");
        assertTrue(BotFreeMarketManager.listingPremium(90_000, 1, npc) < 0,
                "below NPC sell-back never lists (NPC is the standing better bid)");
    }

    @Test
    void loginMarketTripChanceIsTheSteadyStateMidTripFraction() {
        // 2 breaks/hr x 15 min mean trip = mid-trip half the time... capped at the 0.35 ceiling
        assertEquals(0.35, BotFreeMarketManager.loginMarketTripChance(2.0), 1e-9);
        // 1 break/hr -> 15/60 = 25% of a random snapshot is mid-trip
        assertEquals(0.25, BotFreeMarketManager.loginMarketTripChance(1.0), 1e-9);
        assertEquals(0.0, BotFreeMarketManager.loginMarketTripChance(0.0), 1e-9);
        assertEquals(0.0, BotFreeMarketManager.loginMarketTripChance(-3.0), 1e-9,
                "degenerate personality never seeds");
    }

    @Test
    void slotWorthTracksTheFarmingCostAnchor() {
        assertEquals(Math.round(BotScrollManager.farmMesoPerSecond() * 30.0),
                BotFreeMarketManager.slotWorthMesos(),
                "a slot must out-earn ~30s of the live farming-income anchor");
    }

    @Test
    void npcShopStaplesNeverJustifyATrip() {
        BotFreeMarketManager.NpcShopPrice realLookup = BotFreeMarketManager.npcShopPrice;
        try {
            BotFreeMarketManager.npcShopPrice = id -> id == 2000002 ? 160 : 0; // potion vs scroll
            var potion = new BotFreeMarketManager.ListingPlan(
                    new client.inventory.Item(2000002, (short) 0, (short) 100), (short) 1, (short) 100, 150);
            var scroll = new BotFreeMarketManager.ListingPlan(
                    new client.inventory.Item(2040804, (short) 0, (short) 3), (short) 1, (short) 3, 600_000);
            assertEquals(1, BotFreeMarketManager.tripWorthyCount(java.util.List.of(potion, scroll)),
                    "only the non-NPC-shop stack counts toward the trip trigger");
        } finally {
            BotFreeMarketManager.npcShopPrice = realLookup;
        }
    }

    @Test
    void bundleQuantitiesPriceWholeStacks() {
        BotFreeMarketManager.ListingPlan plan = new BotFreeMarketManager.ListingPlan(
                new client.inventory.Item(2040804, (short) 0, (short) 5), (short) 1, (short) 5, 600_000);
        assertEquals(3_000_000, plan.bundlePrice(), "bundle price = unit ask x per-bundle qty");
    }

    @Test
    void priceStylingAppliesToTheBuyerFacingBundleTotal() {
        BotFreeMarketManager.NpcSellLookup realSell = BotFreeMarketManager.npcSell;
        BotFreeMarketManager.NpcShopPrice realShop = BotFreeMarketManager.npcShopPrice;
        try {
            BotFreeMarketManager.npcShopPrice = id -> 0;
            BotFreeMarketManager.npcSell = (id, qty) -> 1_000; // far below: styling is free to act
            var plan = new BotFreeMarketManager.ListingPlan(
                    new client.inventory.Item(2040804, (short) 0, (short) 5), (short) 1, (short) 5, 623_457);
            long raw = plan.bundlePrice(); // 3,117,285 — calculator output
            assertEquals(BotMarketMath.humanizeAsk(raw, 7), BotFreeMarketManager.styledBundlePrice(plan, 7),
                    "charm rounds the whole-bundle total the buyer reads, not the per-unit quotient");

            // Presentation never crosses economics: salvage at the styled value -> raw total stands.
            BotFreeMarketManager.npcSell = (id, qty) -> BotMarketMath.humanizeAsk(raw, 7);
            assertEquals(raw, BotFreeMarketManager.styledBundlePrice(plan, 7),
                    "styling that would dip to/below NPC sell-back is discarded");
        } finally {
            BotFreeMarketManager.npcSell = realSell;
            BotFreeMarketManager.npcShopPrice = realShop;
        }
    }

    @Test
    void repriceWithUndercutCrossesBelowCompetitionAndNeverAboveOwnPerception() {
        // Design sec 5: the "load-bearing" anti-freeze rule - a cheaper visible competitor pulls
        // the reprice DOWN to cross below it, instead of drifting toward pure self-perception.
        double withCompetitor = BotFreeMarketManager.repriceWithUndercut(
                1_000_000, 1_200_000, 5, 500_000, 900_000);
        double withoutCompetitor = BotFreeMarketManager.repriceWithUndercut(
                1_000_000, 1_200_000, 5, 500_000, 0);
        assertTrue(withCompetitor < withoutCompetitor,
                "a cheaper visible competitor pulls the reprice down vs. chasing pure perception");
        assertTrue(withCompetitor < 1_000_000, "actually steps the ask down toward the competitor");

        // A pricier ("silly") competitor never drags the target above what plain perception gives.
        double withSillyCompetitor = BotFreeMarketManager.repriceWithUndercut(
                1_000_000, 1_200_000, 5, 500_000, 5_000_000);
        assertEquals(withoutCompetitor, withSillyCompetitor, 1e-6,
                "a competitor pricier than perception doesn't change the target");

        // The seller's reservation still floors the step even under undercut pressure.
        double floored = BotFreeMarketManager.repriceWithUndercut(600_000, 1_200_000, 0, 590_000, 100_000);
        assertTrue(floored >= 590_000, "never crosses the seller's reservation");
    }

    @Test
    void hourlyUnsoldExposureStartsSmallAndScalesWithPressure() {
        double firstHour = BotFreeMarketManager.repriceUnsold(
                1_000_000, 1_500_000, 0, 500_000, 2_000_000, 1);
        double crowdedOrRepeated = BotFreeMarketManager.repriceUnsold(
                1_000_000, 1_500_000, 0, 500_000, 2_000_000, 5);
        double liquidMarket = BotFreeMarketManager.repriceUnsold(
                1_000_000, 1_500_000, 10, 500_000, 2_000_000, 5);

        assertTrue(firstHour < 1_000_000 && firstHour > 950_000,
                "one unsold hour makes a small markdown even when beliefs and competitors are high");
        assertTrue(crowdedOrRepeated < firstHour,
                "larger accumulated supply pressure makes a larger markdown");
        assertTrue(liquidMarket > crowdedOrRepeated,
                "established clearing confidence damps the visible ask correction");
        assertTrue(crowdedOrRepeated >= 500_000,
                "pressure never crosses the seller's reservation");
    }

    @Test
    void onlyAnUnusuallyQuickSaleAddsDemandPressure() {
        assertTrue(BotFreeMarketManager.isFastSaleElapsed(10 * 60_000L));
        assertFalse(BotFreeMarketManager.isFastSaleElapsed(30 * 60_000L));
    }
}
