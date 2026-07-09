package server.bots;

import org.junit.jupiter.api.Test;
import server.Trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure pricing rules of the FM stall pipeline (the errand itself is live-verified). */
class BotFreeMarketManagerTest {

    @Test
    void unitAskMarksUpTheStrongerOfPerceptionAndCostBasis() {
        int informed = BotFreeMarketManager.unitAsk(1_000_000, 20, 400_000);
        assertTrue(informed > 1_000_000, "asks above the perceived price");
        assertTrue(informed < 1_400_000, "informed margin stays modest");

        int ignorant = BotFreeMarketManager.unitAsk(0, 0, 400_000);
        assertTrue(ignorant > 400_000, "no perception -> cost-plus off the shelf keep-value");
        assertTrue(ignorant > BotFreeMarketManager.unitAsk(0, 20, 400_000),
                "ignorance widens the opening margin (room to learn downward)");

        assertEquals(0, BotFreeMarketManager.unitAsk(0, 0, 0), "nothing to go on -> don't list");
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
    void slotWorthTracksTheFarmingCostScaffold() {
        assertEquals(Math.round(BotScrollManager.FARM_MESO_PER_SECOND * 30.0),
                BotFreeMarketManager.slotWorthMesos(),
                "a slot must out-earn ~30s of the farming-cost anchor (retires with it at P3)");
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
}
