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
    void listingMustBeatNpcSaleAfterTradeFee() {
        // 100k NPC stack value: a 101k gross that loses its fee should NOT list
        long npc = 100_000;
        int barelyAbove = 101_000;
        long fee = Trade.getFee(barelyAbove);
        assertEquals(fee > 1_000, !BotFreeMarketManager.beatsNpcSale(barelyAbove, 1, npc),
                "fee decides the marginal case");
        assertTrue(BotFreeMarketManager.beatsNpcSale(150_000, 1, npc),
                "clear premium over NPC lists");
        assertFalse(BotFreeMarketManager.beatsNpcSale(90_000, 1, npc),
                "below NPC sell-back never lists (NPC is the standing better bid)");
    }

    @Test
    void bundleQuantitiesPriceWholeStacks() {
        BotFreeMarketManager.ListingPlan plan = new BotFreeMarketManager.ListingPlan(
                new client.inventory.Item(2040804, (short) 0, (short) 5), (short) 1, (short) 5, 600_000);
        assertEquals(3_000_000, plan.bundlePrice(), "bundle price = unit ask x per-bundle qty");
    }
}
