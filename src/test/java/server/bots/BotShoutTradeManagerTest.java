package server.bots;

import client.Character;
import client.inventory.Equip;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class BotShoutTradeManagerTest {

    @Test
    void buyerAdvertisementNeverStagesUnrelatedSellStock() {
        long now = System.currentTimeMillis();
        Character bot = mock(Character.class);
        Character seller = mock(Character.class);
        Trade trade = incomingTradeFrom(seller);
        Equip unrelatedSurplus = mock(Equip.class);
        BotEntry entry = new BotEntry(bot, null, null);

        when(bot.getTrade()).thenReturn(trade);
        when(bot.getId()).thenReturn(101);
        when(bot.getMeso()).thenReturn(1_000_000);
        when(seller.getId()).thenReturn(202);
        when(unrelatedSurplus.getItemId()).thenReturn(1302000);

        entry.fmErrandMapId = 910000000;
        entry.fmPhase = BotFreeMarketManager.PHASE_SHOUT;
        entry.buyWant = new BotMarketGrammar.Offer(
                BotMarketGrammar.Kind.BUY, 1002000, 1, 500_000);
        entry.lastBuyShoutAtMs = now;
        entry.advertisedMarketRole = BotShoutTradeManager.MarketRole.BUYER;
        entry.advertisedMarketRoleUntilMs = now + 180_000L;
        entry.shoutTradeStepAtMs = 1L;

        BotScrollManager.EquipQuote quote = new BotScrollManager.EquipQuote(
                unrelatedSurplus.getItemId(), 0, 500_000, x -> 500_000, 0, 1);
        BotMarketBook book = mock(BotMarketBook.class);

        try (MockedStatic<BotInventoryManager> inventory = mockStatic(BotInventoryManager.class);
             MockedStatic<BotScrollManager> scrolls = mockStatic(BotScrollManager.class);
             MockedStatic<BotMarketBook> books = mockStatic(BotMarketBook.class);
             MockedStatic<BotFreeMarketManager> market = mockStatic(BotFreeMarketManager.class);
             MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            inventory.when(() -> BotInventoryManager.collectMarketableEquips(entry, bot))
                    .thenReturn(List.of(unrelatedSurplus));
            scrolls.when(() -> BotScrollManager.equipMarketQuote(entry, bot, unrelatedSurplus))
                    .thenReturn(quote);
            books.when(() -> BotMarketBook.of(entry, bot)).thenReturn(book);
            market.when(() -> BotFreeMarketManager.isShoutStanding(entry)).thenReturn(true);
            market.when(() -> BotFreeMarketManager.equipUnitAsk(eq(book), eq(quote), anyLong()))
                    .thenReturn(500_000);

            BotShoutTradeManager.tick(entry, bot, false);
        }

        assertTrue(entry.shoutTradeActive(), "the seller's invite should be claimed");
        assertFalse(entry.shoutTradeSelling,
                "an invite answering the active B>/WTB must classify the bot as buyer");
        assertNull(entry.shoutTradeSellEquip,
                "a buyer must never stage unrelated surplus stock");
        assertEquals(1002000, entry.shoutTradeOffer.itemId());
    }

    @Test
    void sellerAdvertisementWinsOverAnExistingBuyWant() {
        long now = System.currentTimeMillis();
        Character bot = mock(Character.class);
        Character buyer = mock(Character.class);
        Trade trade = incomingTradeFrom(buyer);
        Equip advertisedSurplus = mock(Equip.class);
        Equip newerTopSurplus = mock(Equip.class);
        BotEntry entry = new BotEntry(bot, null, null);

        when(bot.getTrade()).thenReturn(trade);
        when(bot.getId()).thenReturn(101);
        when(buyer.getId()).thenReturn(202);
        when(advertisedSurplus.getItemId()).thenReturn(1302000);
        when(newerTopSurplus.getItemId()).thenReturn(1402000);

        entry.buyWant = new BotMarketGrammar.Offer(
                BotMarketGrammar.Kind.BUY, 1002000, 1, 500_000);
        entry.lastBuyShoutAtMs = now;
        entry.advertisedMarketRole = BotShoutTradeManager.MarketRole.SELLER;
        entry.advertisedMarketRoleUntilMs = now + 180_000L;
        entry.advertisedSellEquip = advertisedSurplus;
        entry.shoutTradeStepAtMs = 1L;

        BotScrollManager.EquipQuote advertisedQuote = new BotScrollManager.EquipQuote(
                advertisedSurplus.getItemId(), 0, 500_000, x -> 500_000, 0, 1);
        BotScrollManager.EquipQuote newerQuote = new BotScrollManager.EquipQuote(
                newerTopSurplus.getItemId(), 0, 600_000, x -> 600_000, 0, 1);
        BotMarketBook book = mock(BotMarketBook.class);

        try (MockedStatic<BotInventoryManager> inventory = mockStatic(BotInventoryManager.class);
             MockedStatic<BotScrollManager> scrolls = mockStatic(BotScrollManager.class);
             MockedStatic<BotMarketBook> books = mockStatic(BotMarketBook.class);
            MockedStatic<BotFreeMarketManager> market = mockStatic(BotFreeMarketManager.class);
             MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            inventory.when(() -> BotInventoryManager.collectMarketableEquips(entry, bot))
                    .thenReturn(List.of(newerTopSurplus, advertisedSurplus));
            scrolls.when(() -> BotScrollManager.equipMarketQuote(entry, bot, advertisedSurplus))
                    .thenReturn(advertisedQuote);
            scrolls.when(() -> BotScrollManager.equipMarketQuote(entry, bot, newerTopSurplus))
                    .thenReturn(newerQuote);
            books.when(() -> BotMarketBook.of(entry, bot)).thenReturn(book);
            market.when(() -> BotFreeMarketManager.isShoutStanding(entry)).thenReturn(true);
            market.when(() -> BotFreeMarketManager.equipUnitAsk(eq(book), eq(advertisedQuote), anyLong()))
                    .thenReturn(500_000);
            market.when(() -> BotFreeMarketManager.equipUnitAsk(eq(book), eq(newerQuote), anyLong()))
                    .thenReturn(600_000);

            BotShoutTradeManager.tick(entry, bot, false);
        }

        assertTrue(entry.shoutTradeActive(), "the buyer's invite should be claimed");
        assertTrue(entry.shoutTradeSelling);
        assertEquals(advertisedSurplus, entry.shoutTradeSellEquip);
        assertEquals(1302000, entry.shoutTradeOffer.itemId());
    }

    @Test
    void neutralBotDoesNotGuessThePurposeOfAnUnsolicitedInvite() {
        long now = System.currentTimeMillis();
        Character bot = mock(Character.class);
        Character partner = mock(Character.class);
        BotEntry entry = new BotEntry(bot, null, null);
        Trade trade = incomingTradeFrom(partner);

        when(bot.getTrade()).thenReturn(trade);
        entry.buyWant = new BotMarketGrammar.Offer(
                BotMarketGrammar.Kind.BUY, 1002000, 1, 500_000);
        entry.lastBuyShoutAtMs = now;
        entry.shoutTradeStepAtMs = 1L;

        BotShoutTradeManager.tick(entry, bot, false);

        assertFalse(entry.shoutTradeActive(),
                "without an advertised role the manual trade flow must own the invite");
    }

    @Test
    void sellStandDoesNotReplaceAnUnexpiredBuyerAdvertisement() {
        long now = System.currentTimeMillis();
        BotEntry entry = new BotEntry(mock(Character.class), null, null);
        entry.advertisedMarketRole = BotShoutTradeManager.MarketRole.BUYER;
        entry.advertisedMarketRoleUntilMs = now + 180_000L;
        entry.nextShoutEmitMs = now + 60_000L;

        BotShoutTradeManager.emitAtStand(entry, entry.bot, now);

        assertEquals(BotShoutTradeManager.MarketRole.BUYER, entry.advertisedMarketRole,
                "a visible B>/WTB lease must finish before the bot becomes a seller");
    }

    @Test
    void sellStandAcquiresSellerRoleWhenNoAdvertisementIsActive() {
        long now = System.currentTimeMillis();
        BotEntry entry = new BotEntry(mock(Character.class), null, null);
        entry.nextShoutEmitMs = now + 60_000L;

        BotShoutTradeManager.emitAtStand(entry, entry.bot, now);

        assertEquals(BotShoutTradeManager.MarketRole.SELLER, entry.advertisedMarketRole);
        assertTrue(entry.advertisedMarketRoleUntilMs > now);
    }

    @Test
    void expiredAdvertisementDoesNotClaimAnInvite() {
        Character bot = mock(Character.class);
        Character partner = mock(Character.class);
        BotEntry entry = new BotEntry(bot, null, null);
        Trade trade = incomingTradeFrom(partner);

        when(bot.getTrade()).thenReturn(trade);
        entry.buyWant = new BotMarketGrammar.Offer(
                BotMarketGrammar.Kind.BUY, 1002000, 1, 500_000);
        entry.lastBuyShoutAtMs = System.currentTimeMillis();
        entry.advertisedMarketRole = BotShoutTradeManager.MarketRole.BUYER;
        entry.advertisedMarketRoleUntilMs = 1L;
        entry.shoutTradeStepAtMs = 1L;

        BotShoutTradeManager.tick(entry, bot, false);

        assertFalse(entry.shoutTradeActive());
        assertEquals(BotShoutTradeManager.MarketRole.NONE, entry.advertisedMarketRole);
    }

    @Test
    void cancelledTradeKeepsTheStillVisibleAdvertisementRole() {
        long now = System.currentTimeMillis();
        Character bot = mock(Character.class);
        Trade trade = mock(Trade.class);
        BotEntry entry = new BotEntry(bot, null, null);

        when(bot.getTrade()).thenReturn(trade);
        entry.advertisedMarketRole = BotShoutTradeManager.MarketRole.BUYER;
        entry.advertisedMarketRoleUntilMs = now + 180_000L;
        entry.shoutTradePartnerId = 202;
        entry.shoutTradeDeadlineMs = 1L;

        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            BotShoutTradeManager.tick(entry, bot, false);
        }

        assertFalse(entry.shoutTradeActive());
        assertEquals(BotShoutTradeManager.MarketRole.BUYER, entry.advertisedMarketRole);
    }

    @Test
    void buyerRoleDoesNotAnswerAnotherBotsBuyShoutAsSeller() {
        long now = System.currentTimeMillis();
        int mapId = 910000000;
        Character bot = mock(Character.class);
        Character otherBuyer = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Equip surplus = mock(Equip.class);
        BotEntry entry = new BotEntry(bot, null, null);
        BotMarketGrammar.Offer buyOffer = new BotMarketGrammar.Offer(
                BotMarketGrammar.Kind.BUY, 1302000, 1, 500_000);

        when(bot.getId()).thenReturn(101);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getMap()).thenReturn(map);
        when(map.getCharacterById(202)).thenReturn(otherBuyer);
        when(surplus.getItemId()).thenReturn(1302000);
        entry.advertisedMarketRole = BotShoutTradeManager.MarketRole.BUYER;
        entry.advertisedMarketRoleUntilMs = now + 180_000L;

        BotScrollManager.EquipQuote quote = new BotScrollManager.EquipQuote(
                surplus.getItemId(), 0, 500_000, x -> 500_000, 0, 1);
        BotMarketBook book = mock(BotMarketBook.class);
        BotMarketShoutBus bus = BotMarketShoutBus.getInstance();
        bus.clear();
        bus.publish(mapId, 202, buyOffer, now);

        try (MockedStatic<BotInventoryManager> inventory = mockStatic(BotInventoryManager.class);
             MockedStatic<BotScrollManager> scrolls = mockStatic(BotScrollManager.class);
             MockedStatic<BotMarketBook> books = mockStatic(BotMarketBook.class);
             MockedStatic<BotFreeMarketManager> market = mockStatic(BotFreeMarketManager.class)) {
            inventory.when(() -> BotInventoryManager.collectMarketableEquips(entry, bot))
                    .thenReturn(List.of(surplus));
            scrolls.when(() -> BotScrollManager.equipMarketQuote(entry, bot, surplus))
                    .thenReturn(quote);
            books.when(() -> BotMarketBook.of(entry, bot)).thenReturn(book);
            market.when(() -> BotFreeMarketManager.equipReservationUnit(eq(book), eq(quote), anyLong()))
                    .thenReturn(400_000L);

            BotShoutTradeManager.tick(entry, bot, true);
        } finally {
            bus.clear();
        }

        assertEquals(0L, entry.shoutBuyDecideAtMs,
                "a buyer session must not initiate a sale from another bot's B>");
    }

    @Test
    void unrelatedPurchaseDoesNotRetireVisibleBuyAdvertisement() {
        BotEntry entry = completePurchase(1002000, 1302000);

        assertEquals(1002000, entry.buyWant.itemId(),
                "buying a different upgrade must not retire the visible WTB");
        assertEquals(BotShoutTradeManager.MarketRole.BUYER, entry.advertisedMarketRole);
    }

    @Test
    void matchingPurchaseRetiresVisibleBuyAdvertisement() {
        BotEntry entry = completePurchase(1002000, 1002000);

        assertNull(entry.buyWant);
        assertEquals(BotShoutTradeManager.MarketRole.NONE, entry.advertisedMarketRole);
    }

    @Test
    void escrowRefundCopyReconcilesToTheAdvertisedEquip() {
        Equip advertised = mock(Equip.class);
        Equip restoredCopy = mock(Equip.class);
        when(advertised.getItemId()).thenReturn(1302000);
        when(restoredCopy.getItemId()).thenReturn(1302000);
        when(advertised.getHands()).thenReturn((short) 7);
        when(restoredCopy.getHands()).thenReturn((short) 7);
        when(advertised.getItemExp()).thenReturn(123);
        when(restoredCopy.getItemExp()).thenReturn(123);

        BotEntry entry = answerSellerInvite(advertised, List.of(restoredCopy));

        assertTrue(entry.shoutTradeActive());
        assertEquals(restoredCopy, entry.shoutTradeSellEquip);
        assertEquals(restoredCopy, entry.advertisedSellEquip);
    }

    @Test
    void nearMatchDoesNotReplaceTheAdvertisedEquip() {
        Equip advertised = mock(Equip.class);
        Equip differentRoll = mock(Equip.class);
        when(advertised.getItemId()).thenReturn(1302000);
        when(differentRoll.getItemId()).thenReturn(1302000);
        when(advertised.getHands()).thenReturn((short) 7);
        when(differentRoll.getHands()).thenReturn((short) 8);

        BotEntry entry = answerSellerInvite(advertised, List.of(differentRoll));

        assertFalse(entry.shoutTradeActive());
        assertEquals(advertised, entry.advertisedSellEquip);
    }

    private static Trade incomingTradeFrom(Character partner) {
        Trade trade = mock(Trade.class);
        Trade partnerTrade = mock(Trade.class);
        when(trade.getPartner()).thenReturn(partnerTrade);
        when(trade.getNumber()).thenReturn((byte) 1);
        when(partnerTrade.getChr()).thenReturn(partner);
        return trade;
    }

    private static BotEntry completePurchase(int advertisedItemId, int purchasedItemId) {
        long now = System.currentTimeMillis();
        Character bot = mock(Character.class);
        Character partner = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        BotEntry entry = new BotEntry(bot, null, null);

        when(bot.getTrade()).thenReturn(null);
        when(bot.getMap()).thenReturn(map);
        when(map.getCharacterById(202)).thenReturn(partner);
        entry.buyWant = new BotMarketGrammar.Offer(
                BotMarketGrammar.Kind.BUY, advertisedItemId, 1, 500_000);
        entry.advertisedMarketRole = BotShoutTradeManager.MarketRole.BUYER;
        entry.advertisedMarketRoleUntilMs = now + 180_000L;
        entry.shoutTradePartnerId = 202;
        entry.shoutTradeOffer = new BotMarketGrammar.Offer(
                BotMarketGrammar.Kind.SELL, purchasedItemId, 1, 300_000);
        entry.shoutTradeLocked = true;
        entry.shoutTradeDeadlineMs = now + 10_000L;

        BotMarketBook book = mock(BotMarketBook.class);
        BotManager manager = mock(BotManager.class);
        try (MockedStatic<BotMarketBook> books = mockStatic(BotMarketBook.class);
             MockedStatic<BotManager> managers = mockStatic(BotManager.class);
             MockedStatic<BotEquipManager> equips = mockStatic(BotEquipManager.class)) {
            books.when(() -> BotMarketBook.of(entry, bot)).thenReturn(book);
            managers.when(BotManager::getInstance).thenReturn(manager);
            BotShoutTradeManager.tick(entry, bot, false);
        }
        return entry;
    }

    private static BotEntry answerSellerInvite(Equip advertised, List<Equip> stock) {
        long now = System.currentTimeMillis();
        Character bot = mock(Character.class);
        Character buyer = mock(Character.class);
        Trade trade = incomingTradeFrom(buyer);
        BotEntry entry = new BotEntry(bot, null, null);

        when(bot.getTrade()).thenReturn(trade);
        when(bot.getId()).thenReturn(101);
        when(buyer.getId()).thenReturn(202);
        entry.advertisedMarketRole = BotShoutTradeManager.MarketRole.SELLER;
        entry.advertisedMarketRoleUntilMs = now + 180_000L;
        entry.advertisedSellEquip = advertised;
        entry.shoutTradeStepAtMs = 1L;

        Equip candidate = stock.get(0);
        BotScrollManager.EquipQuote quote = new BotScrollManager.EquipQuote(
                candidate.getItemId(), 0, 500_000, x -> 500_000, 0, 1);
        BotMarketBook book = mock(BotMarketBook.class);
        try (MockedStatic<BotInventoryManager> inventory = mockStatic(BotInventoryManager.class);
             MockedStatic<BotScrollManager> scrolls = mockStatic(BotScrollManager.class);
             MockedStatic<BotMarketBook> books = mockStatic(BotMarketBook.class);
             MockedStatic<BotFreeMarketManager> market = mockStatic(BotFreeMarketManager.class);
             MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            inventory.when(() -> BotInventoryManager.collectMarketableEquips(entry, bot)).thenReturn(stock);
            scrolls.when(() -> BotScrollManager.equipMarketQuote(entry, bot, candidate)).thenReturn(quote);
            books.when(() -> BotMarketBook.of(entry, bot)).thenReturn(book);
            market.when(() -> BotFreeMarketManager.isShoutStanding(entry)).thenReturn(true);
            market.when(() -> BotFreeMarketManager.equipUnitAsk(eq(book), eq(quote), anyLong()))
                    .thenReturn(500_000);
            BotShoutTradeManager.tick(entry, bot, false);
        }
        return entry;
    }
}
