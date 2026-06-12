package server.bots;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;
import server.maps.Foothold;
import server.maps.MapItem;
import server.maps.MapleMap;
import testutil.Items;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotInventoryManagerTest {
    @Test
    void shouldOnlyAnnounceTradeInviteOnFirstBatchOfSequence() throws Exception {
        BotEntry entry = new BotEntry(mock(Character.class), mock(Character.class), null);
        Character bot = entry.bot;
        Character owner = entry.owner;
        BotManager manager = spy(BotManager.getInstance());
        Method startTradeSequence = method(BotInventoryManager.class,
                "startTradeSequence",
                String.class, Character.class, List.class, int.class, boolean.class, BotEntry.class, Character.class);
        Method openTradeBatch = method(BotInventoryManager.class,
                "openTradeBatch",
                BotEntry.class, Character.class, List.class, int.class);

        when(owner.getId()).thenReturn(42);
        when(owner.getTrade()).thenReturn(null);
        when(bot.getTrade()).thenReturn(null);
        doAnswer(invocation -> null).when(manager).botReply(eq(entry), anyString());

        try (MockedStatic<BotManager> botManagers = mockStatic(BotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            botManagers.when(BotManager::getInstance).thenReturn(manager);

            startTradeSequence.invoke(null, "trash", owner, List.of(mock(Item.class)), 0, false, entry, bot);
            openTradeBatch.invoke(null, entry, bot, List.of(mock(Item.class)), 0);

            verify(manager, times(1)).botReply(eq(entry), anyString());
        }
    }

    @Test
    void shouldExposeExpandedTradeMessagePools() throws Exception {
        List<String> invitationMsgs = listField("TRADE_INVITATION_MSGS");
        List<String> freebieMsgs = listField("TRADE_FREEBIE_QUIPS");

        assertTrue(invitationMsgs.size() >= 20);
        assertTrue(invitationMsgs.contains("ready when u are"));
        assertTrue(invitationMsgs.contains("trade time"));

        assertTrue(freebieMsgs.size() >= 16);
        assertTrue(freebieMsgs.contains("enjoy"));
        assertTrue(freebieMsgs.contains(":)"));
        assertTrue(freebieMsgs.contains("hope that helps"));
    }

    @Test
    void shouldCancelUnmanagedBotTradeWhenManualTimeoutExpires() {
        BotEntry entry = new BotEntry(mock(Character.class), null, null);
        Character bot = entry.bot;
        Trade trade = mock(Trade.class);

        when(bot.getId()).thenReturn(99);
        when(bot.getTrade()).thenReturn(trade);

        BotInventoryManager.tickManualTrade(entry, bot);
        entry.manualTradeTimeoutMs = BotMovementManager.cfg.TICK_MS;

        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            BotInventoryManager.tickManualTrade(entry, bot);

            trades.verify(() -> Trade.cancelTrade(bot, Trade.TradeResult.NO_RESPONSE));
            assertNull(entry.manualTradeRef);
            assertTrue(entry.manualTradeTimeoutMs == 0);
        }
    }

    @Test
    void shouldFilterProtectedEquipsOutOfSellTrashOnly() {
        Equip sellable = mock(Equip.class);
        Equip highIntAllJob = mock(Equip.class);
        Equip highDexWarrior = mock(Equip.class);
        Equip nonWeaponWatk = mock(Equip.class);
        Equip scrolled = mock(Equip.class);
        Equip pirateDex = mock(Equip.class);
        Equip currentWarriorWeapon = mock(Equip.class);
        Equip baseWarriorWeapon = mock(Equip.class);
        Equip currentMageWeapon = mock(Equip.class);
        Equip baseMageWeapon = mock(Equip.class);

        when(sellable.getItemId()).thenReturn(1000001);
        when(highIntAllJob.getItemId()).thenReturn(1082001);
        when(highDexWarrior.getItemId()).thenReturn(1000002);
        when(nonWeaponWatk.getItemId()).thenReturn(1072001);
        when(scrolled.getItemId()).thenReturn(1040001);
        when(pirateDex.getItemId()).thenReturn(1050001);
        when(currentWarriorWeapon.getItemId()).thenReturn(1302000);
        when(currentMageWeapon.getItemId()).thenReturn(1372000);

        when(sellable.getStr()).thenReturn((short) 3);
        when(highIntAllJob.getInt()).thenReturn((short) 6);
        when(highDexWarrior.getDex()).thenReturn((short) 6);
        when(nonWeaponWatk.getWatk()).thenReturn((short) 1);
        when(scrolled.getLevel()).thenReturn((byte) 1);
        when(pirateDex.getDex()).thenReturn((short) 6);
        when(currentWarriorWeapon.getWatk()).thenReturn((short) 24);
        when(baseWarriorWeapon.getWatk()).thenReturn((short) 20);
        when(currentMageWeapon.getMatk()).thenReturn((short) 29);
        when(baseMageWeapon.getMatk()).thenReturn((short) 25);

        assertTrue(!BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 1), sellable, 6, 10));
        assertTrue(BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 0), highIntAllJob, 6, 10));
        assertTrue(BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 1), highDexWarrior, 6, 10));
        assertTrue(BotInventoryManager.shouldKeepForSellTrash(null, nonWeaponWatk));
        assertTrue(BotInventoryManager.shouldKeepForSellTrash(null, scrolled));
        assertTrue(BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 16), pirateDex, 6, 10));

        // Stat at or below the item's WZ base (not scrolled above base) and below the pure
        // threshold => trash, even though it clears the old flat-6 bar.
        assertTrue(!BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 1, "DEX", 6), highDexWarrior, 6, 10));
        // A high absolute stat (>= pure threshold) still protects, even sitting at base.
        Equip pureHighDex = mock(Equip.class);
        when(pureHighDex.getDex()).thenReturn((short) 10);
        assertTrue(BotInventoryManager.hasProtectedSellTrashStat(Map.of("reqJob", 1, "DEX", 10), pureHighDex, 6, 10));
        assertTrue(BotInventoryManager.hasProtectedSellTrashWeaponStat(Map.of("reqJob", 1), currentWarriorWeapon, baseWarriorWeapon));
        assertTrue(BotInventoryManager.hasProtectedSellTrashWeaponStat(Map.of("reqJob", 2), currentMageWeapon, baseMageWeapon));
    }

    @Test
    void shouldSellValuableEquipOverflowWeakestFirstBeyondTheShelfCap() {
        java.util.List<Equip> kept = new java.util.ArrayList<>();
        // Scores 1..10 via STR (1 point each above null-ii base 0).
        for (int i = 1; i <= BotInventoryManager.KEEP_VALUABLE_EQUIP_SLOTS + 2; i++) {
            Equip equip = mock(Equip.class);
            when(equip.getItemId()).thenReturn(1040000 + i);
            when(equip.getStr()).thenReturn((short) i);
            kept.add(equip);
        }

        var overflow = BotInventoryManager.valuableEquipOverflow(null, kept);

        // 10 kept, cap 8: the two weakest rolls (STR 1 and 2) overflow and sell.
        assertEquals(2, overflow.size());
        assertTrue(overflow.contains(kept.get(0)));
        assertTrue(overflow.contains(kept.get(1)));

        // At or below the cap nothing is forced out.
        assertTrue(BotInventoryManager.valuableEquipOverflow(
                null, kept.subList(2, kept.size())).isEmpty());
    }

    @Test
    void shouldNeverSellEquipsAboveTheHardValueGateEvenOnOverflow() {
        // A mule holding a bag of genuine valuables: every kept equip is one att-scroll above
        // base (+5 watk = score 25, the gate). The shelf cap alone would force 6 out — the
        // absolute gate keeps them all; bag pressure is the lesser evil.
        java.util.List<Equip> kept = new java.util.ArrayList<>();
        for (int i = 0; i < BotInventoryManager.KEEP_VALUABLE_EQUIP_SLOTS + 6; i++) {
            Equip equip = mock(Equip.class);
            when(equip.getItemId()).thenReturn(1082000 + i);
            when(equip.getWatk()).thenReturn((short) 5);
            kept.add(equip);
        }
        assertTrue(BotInventoryManager.valuableEquipOverflow(null, kept).isEmpty());

        // Sub-gate rolls beyond the cap still sell like before.
        for (int i = 0; i < 3; i++) {
            Equip weak = mock(Equip.class);
            when(weak.getItemId()).thenReturn(1090000 + i);
            when(weak.getStr()).thenReturn((short) 2);
            kept.add(weak);
        }
        assertEquals(3, BotInventoryManager.valuableEquipOverflow(null, kept).size());
    }

    @Test
    void shouldRankAttackAboveStatPointsInTradeValue() {
        Equip attGlove = mock(Equip.class);
        when(attGlove.getItemId()).thenReturn(1082002);
        when(attGlove.getWatk()).thenReturn((short) 2);
        Equip statHat = mock(Equip.class);
        when(statHat.getItemId()).thenReturn(1002001);
        when(statHat.getDex()).thenReturn((short) 8);

        // +2 watk (10) beats +8 of a main stat (8): attack is what buyers pay for.
        assertTrue(BotInventoryManager.tradeValueScore(null, attGlove)
                > BotInventoryManager.tradeValueScore(null, statHat));
    }

    @Test
    void shouldCollectOnlySellableOffWeaponNonRechargeableAmmoAsTrashUse() {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2060000, 500));  // bow arrows = own ammo -> keep
        use.addItem(Items.itemWithQuantity(2061000, 500));  // xbow bolts = off-weapon -> trash
        use.addItem(Items.itemWithQuantity(2061003, 4_000)); // blue arrows under reserve -> keep
        use.addItem(Items.itemWithQuantity(2061004, 7_000)); // diamond arrows over reserve -> sell excess
        use.addItem(Items.itemWithQuantity(2070000, 200));  // stars: rechargeable -> keep
        use.addItem(Items.itemWithQuantity(2000000, 100));  // potion: not ammo -> keep
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        try (AutoCloseable seams = withSellSeams((id, qty) -> 10, id -> -1, id -> 0);
             MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.BOW);

            List<Item> trash = BotInventoryManager.collectSellTrashUseItems(bot);

            assertEquals(2, trash.size());
            assertTrue(trash.stream().anyMatch(item ->
                    item.getItemId() == 2061000 && BotInventoryManager.sellTrashQuantity(item) == 500));
            assertTrue(trash.stream().anyMatch(item ->
                    item.getItemId() == 2061004 && BotInventoryManager.sellTrashQuantity(item) == 2_000));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void shouldClassifyBagEquipsLikeTheSellPipeline() {
        java.util.List<Item> bag = new java.util.ArrayList<>();
        Equip reservedSelf = equipWithWatk(1102000, 31);
        Equip reservedOther = equipWithWatk(1102001, 28);
        Equip trash = equipWithWatk(1102002, 0);
        bag.add(reservedSelf);
        bag.add(reservedOther);
        bag.add(trash);
        java.util.List<Equip> shelf = new java.util.ArrayList<>();
        for (int i = 0; i < BotInventoryManager.KEEP_VALUABLE_EQUIP_SLOTS; i++) {
            Equip e = equipWithWatk(1103000 + i, 30 - i); // scores 150 down to 35, all on the shelf
            shelf.add(e);
            bag.add(e);
        }
        Equip neverSellBeyondShelf = equipWithWatk(1104000, 5); // score 25 = never-sell gate
        Equip overflow = equipWithWatk(1104001, 1);             // score 5, beyond shelf -> sells
        bag.add(neverSellBeyondShelf);
        bag.add(overflow);

        Set<Item> self = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        self.add(reservedSelf);
        Map<Item, BotInventoryManager.BagEquipClass> out =
                BotInventoryManager.classifyBagEquips(null, bag, self, item -> item == reservedOther);

        // Reservation wins over hoarding even for the highest-value items.
        assertEquals(BotInventoryManager.BagEquipStatus.RESV_SELF, out.get(reservedSelf).status());
        assertEquals(BotInventoryManager.BagEquipStatus.RESV_OTHER, out.get(reservedOther).status());
        assertEquals(BotInventoryManager.BagEquipStatus.TRASH, out.get(trash).status());
        // Shelf ranks follow trade value descending.
        assertEquals("HOARD#1", out.get(shelf.get(0)).label());
        assertEquals("HOARD#2", out.get(shelf.get(1)).label());
        // At/above the never-sell gate stays HOARD even beyond the shelf cap.
        assertEquals(BotInventoryManager.BagEquipStatus.HOARD, out.get(neverSellBeyondShelf).status());
        assertEquals(BotInventoryManager.KEEP_VALUABLE_EQUIP_SLOTS + 1, out.get(neverSellBeyondShelf).rank());
        // Kept-for-value but beyond the shelf and below the gate -> HLIM (matches valuableEquipOverflow).
        assertEquals(BotInventoryManager.BagEquipStatus.HLIM, out.get(overflow).status());
        assertEquals(BotInventoryManager.KEEP_VALUABLE_EQUIP_SLOTS + 2, out.get(overflow).rank());
        assertEquals("HLIM#" + (BotInventoryManager.KEEP_VALUABLE_EQUIP_SLOTS + 2), out.get(overflow).label());
        var overflowItems = BotInventoryManager.valuableEquipOverflow(null,
                java.util.stream.Stream.concat(shelf.stream(),
                        java.util.stream.Stream.of(neverSellBeyondShelf, overflow)).toList());
        assertEquals(List.of(overflow), overflowItems);
    }

    private static Equip equipWithWatk(int itemId, int watk) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getWatk()).thenReturn((short) watk);
        return e;
    }

    @Test
    void shouldSellEquipScrollsWithNoJobRelevantStats() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2040001, 3));  // pure HP scroll -> sell
        use.addItem(Items.itemWithQuantity(2040002, 2));  // pure WDEF scroll -> sell
        use.addItem(Items.itemWithQuantity(2040041, 1));  // LUK scroll -> keep (assassin)
        use.addItem(Items.itemWithQuantity(2043001, 1));  // ATT scroll -> keep
        use.addItem(Items.itemWithQuantity(2040718, 1));  // speed scroll -> keep for any job
        use.addItem(Items.itemWithQuantity(2040003, 1));  // junk scroll but NPC pays 0 -> keep
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        Map<Integer, Map<String, Integer>> effects = Map.of(
                2040001, Map.of("MHP", 10),
                2040002, Map.of("PDD", 10),
                2040041, Map.of("LUK", 2),
                2043001, Map.of("PAD", 2),
                2040718, Map.of("Speed", 1),
                2040003, Map.of("MMP", 10));

        BotInventoryManager.ScrollStatsLookup prevScroll = BotInventoryManager.scrollStats;
        BotInventoryManager.scrollStats = effects::get;
        // dropChance marks everything rare: the rare-drop keep gate must NOT apply to scrolls.
        try (AutoCloseable seams = withSellSeams((id, qty) -> id == 2040003 ? 0 : 10, id -> -1, id -> 100);
             MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.CLAW);

            List<Item> trash = BotInventoryManager.collectSellTrashUseItems(bot);

            assertEquals(2, trash.size());
            assertTrue(trash.stream().anyMatch(item -> item.getItemId() == 2040001));
            assertTrue(trash.stream().anyMatch(item -> item.getItemId() == 2040002));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            BotInventoryManager.scrollStats = prevScroll;
        }
    }

    @Test
    void shouldKeepRareAndCraftingEtcOutOfSellTrash() {
        Character bot = mock(Character.class);
        Inventory etc = new Inventory(bot, InventoryType.ETC, (byte) 24);
        etc.addItem(Items.itemWithQuantity(4000000, 50));   // common junk -> trash
        etc.addItem(Items.itemWithQuantity(4000001, 2));    // rare drop (0.5%) -> keep
        etc.addItem(Items.itemWithQuantity(4250000, 3));    // maker reagent -> keep
        etc.addItem(Items.itemWithQuantity(4006000, 5));    // magic rock (skill-consumed) -> keep
        etc.addItem(Items.itemWithQuantity(4006001, 5));    // summoning rock (skill-consumed) -> keep
        etc.addItem(Items.itemWithQuantity(4000100, 45));   // small crystal leftover stack -> trash
        etc.addItem(Items.itemWithQuantity(4000101, 1252)); // crystal leftover stack >=100 -> keep
        etc.addItem(Items.itemWithQuantity(4000102, 200));  // crystal leftover stack >=100 -> keep
        etc.addItem(Items.itemWithQuantity(4000200, 9));    // NPC pays nothing -> keep
        etc.addItem(Items.itemWithQuantity(4004000, 7));    // stat crystal ore -> keep
        etc.addItem(Items.itemWithQuantity(4005004, 1));    // stat crystal -> keep
        etc.addItem(Items.itemWithQuantity(4007003, 11));   // magic powder -> keep
        etc.addItem(Items.itemWithQuantity(4010006, 8));    // ore -> keep
        etc.addItem(Items.itemWithQuantity(4011008, 2));    // plate/refined material -> keep
        etc.addItem(Items.itemWithQuantity(4020007, 8));    // jewel ore -> keep
        etc.addItem(Items.itemWithQuantity(4021009, 1));    // jewel/refined material -> keep
        etc.addItem(Items.itemWithQuantity(4130000, 1));    // stimulator -> keep
        etc.addItem(Items.itemWithQuantity(4131000, 1));    // crafting manual -> keep
        etc.addItem(Items.itemWithQuantity(4260000, 4));    // monster crystal -> keep
        when(bot.getInventory(InventoryType.ETC)).thenReturn(etc);

        BotInventoryManager.SellPriceLookup price = (id, qty) -> id == 4000200 ? -1 : 10;
        IntUnaryOperator leftover = id -> isIn(id, 4000100, 4000101, 4000102) ? 4260000 : -1;
        IntUnaryOperator dropChance = id -> id == 4000001 ? 5000 : 600000;

        try (AutoCloseable seams = withSellSeams(price, leftover, dropChance)) {
            List<Item> trash = BotInventoryManager.collectSellTrashEtcItems(bot);
            assertEquals(2, trash.size());
            assertTrue(trash.stream().anyMatch(item -> item.getItemId() == 4000000));
            assertTrue(trash.stream().anyMatch(item ->
                    item.getItemId() == 4000100 && BotInventoryManager.sellTrashQuantity(item) == 45));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // Swap the ItemInformationProvider/DB-backed seams (restored on close); quest lookup
    // always answers "not a quest item" so collectFromBag's isSafeToDrop stays inert.
    private static AutoCloseable withSellSeams(BotInventoryManager.SellPriceLookup price,
                                               IntUnaryOperator leftover,
                                               IntUnaryOperator dropChance) {
        BotInventoryManager.SellPriceLookup prevPrice = BotInventoryManager.sellPrice;
        IntUnaryOperator prevLeftover = BotInventoryManager.makerCrystalFromLeftover;
        IntUnaryOperator prevDrop = BotInventoryManager.bestDropChance;
        IntPredicate prevQuest = BotInventoryManager.questItem;
        java.util.function.Predicate<Item> prevUntradeable = BotInventoryManager.untradeable;
        BotInventoryManager.sellPrice = price;
        BotInventoryManager.makerCrystalFromLeftover = leftover;
        BotInventoryManager.bestDropChance = dropChance;
        BotInventoryManager.questItem = id -> false;
        BotInventoryManager.untradeable = item -> false;
        return () -> {
            BotInventoryManager.sellPrice = prevPrice;
            BotInventoryManager.makerCrystalFromLeftover = prevLeftover;
            BotInventoryManager.bestDropChance = prevDrop;
            BotInventoryManager.questItem = prevQuest;
            BotInventoryManager.untradeable = prevUntradeable;
        };
    }

    private static boolean isIn(int itemId, int... itemIds) {
        for (int candidate : itemIds) {
            if (itemId == candidate) {
                return true;
            }
        }
        return false;
    }

    @Test
    void shouldClampReservedTradePages() {
        assertEquals(1, BotInventoryManager.clampTradePage(-4, 0));
        assertEquals(1, BotInventoryManager.clampTradePage(0, 5));
        assertEquals(1, BotInventoryManager.clampTradePage(1, 9));
        assertEquals(2, BotInventoryManager.clampTradePage(2, 10));
        assertEquals(2, BotInventoryManager.clampTradePage(99, 10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSortOwnReservedEquipsByEquipmentScore() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.FIGHTER);

        Equip highScore = mock(Equip.class);
        when(highScore.getWatk()).thenReturn((short) 10);
        when(highScore.getStr()).thenReturn((short) 5);
        when(highScore.getDex()).thenReturn((short) 2);
        when(highScore.getMatk()).thenReturn((short) 0);
        when(highScore.getItemId()).thenReturn(1302000);
        when(highScore.getPosition()).thenReturn((short) 3);

        Equip lowScore = mock(Equip.class);
        when(lowScore.getWatk()).thenReturn((short) 1);
        when(lowScore.getStr()).thenReturn((short) 1);
        when(lowScore.getDex()).thenReturn((short) 0);
        when(lowScore.getMatk()).thenReturn((short) 0);
        when(lowScore.getItemId()).thenReturn(1050000);
        when(lowScore.getPosition()).thenReturn((short) 1);

        Equip midScore = mock(Equip.class);
        when(midScore.getWatk()).thenReturn((short) 3);
        when(midScore.getStr()).thenReturn((short) 2);
        when(midScore.getDex()).thenReturn((short) 1);
        when(midScore.getMatk()).thenReturn((short) 0);
        when(midScore.getItemId()).thenReturn(1070000);
        when(midScore.getPosition()).thenReturn((short) 2);

        Method sortEquipsByTradeScore = method(BotInventoryManager.class, "sortEquipsByTradeScore", List.class, Character.class);

        List<Item> sorted = (List<Item>) sortEquipsByTradeScore.invoke(
                null,
                new java.util.ArrayList<>(List.of(highScore, lowScore, midScore)),
                bot);

        assertEquals(List.of(lowScore, midScore, highScore), sorted);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSortJunkAndOtherReserveByItemId() throws Exception {
        Equip highScoreLowId = mock(Equip.class);
        when(highScoreLowId.getWatk()).thenReturn((short) 10);
        when(highScoreLowId.getStr()).thenReturn((short) 5);
        when(highScoreLowId.getDex()).thenReturn((short) 2);
        when(highScoreLowId.getMatk()).thenReturn((short) 0);
        when(highScoreLowId.getItemId()).thenReturn(1000000);
        when(highScoreLowId.getPosition()).thenReturn((short) 3);

        Equip lowScoreHighId = mock(Equip.class);
        when(lowScoreHighId.getWatk()).thenReturn((short) 1);
        when(lowScoreHighId.getStr()).thenReturn((short) 1);
        when(lowScoreHighId.getDex()).thenReturn((short) 0);
        when(lowScoreHighId.getMatk()).thenReturn((short) 0);
        when(lowScoreHighId.getItemId()).thenReturn(1302000);
        when(lowScoreHighId.getPosition()).thenReturn((short) 1);

        Equip midId = mock(Equip.class);
        when(midId.getWatk()).thenReturn((short) 3);
        when(midId.getStr()).thenReturn((short) 2);
        when(midId.getDex()).thenReturn((short) 1);
        when(midId.getMatk()).thenReturn((short) 0);
        when(midId.getItemId()).thenReturn(1070000);
        when(midId.getPosition()).thenReturn((short) 2);

        Method sortEquipsByItemId = method(BotInventoryManager.class, "sortEquipsByItemId", List.class);

        List<Item> sorted = (List<Item>) sortEquipsByItemId.invoke(
                null,
                new java.util.ArrayList<>(List.of(lowScoreHighId, highScoreLowId, midId)));

        assertEquals(List.of(highScoreLowId, midId, lowScoreHighId), sorted);
    }

    @Test
    void shouldExcludeOneWayPatrolNeighborFromLootRoamScope() {
        MapleMap map = spy(new MapleMap(910009054, 0, 0, 910009054, 1.0f));
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold homeFoothold = new Foothold(new Point(0, 100), new Point(100, 100), 1);
        Foothold oneWayFoothold = new Foothold(new Point(200, 140), new Point(300, 140), 2);
        Foothold returnableFoothold = new Foothold(new Point(400, 100), new Point(500, 100), 3);
        footholds.insert(homeFoothold);
        footholds.insert(oneWayFoothold);
        footholds.insert(returnableFoothold);
        map.setFootholds(footholds);

        BotNavigationGraph.Region homeRegion = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(homeFoothold)));
        BotNavigationGraph.Region oneWayRegion = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(oneWayFoothold)));
        BotNavigationGraph.Region returnableRegion = new BotNavigationGraph.Region(
                3, List.of(new BotNavigationGraph.Segment(returnableFoothold)));
        BotNavigationGraph graph = new BotNavigationGraph(
                map.getId(),
                1,
                BotMovementProfile.base(),
                List.of(homeRegion, oneWayRegion, returnableRegion),
                Map.of(1, homeRegion, 2, oneWayRegion, 3, returnableRegion),
                Map.of(1, 1, 2, 2, 3, 3),
                Map.of(
                        1, List.of(
                                new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.DROP,
                                        new Point(100, 100), new Point(200, 140), 0, 0, 0, 0, 0, 100),
                                new BotNavigationGraph.Edge(1, 3, BotNavigationGraph.EdgeType.WALK,
                                        new Point(100, 100), new Point(400, 100), 0, 0, 0, 0, 0, 120)),
                        3, List.of(new BotNavigationGraph.Edge(3, 1, BotNavigationGraph.EdgeType.WALK,
                                new Point(400, 100), new Point(100, 100), 0, 0, 0, 0, 0, 120))),
                Set.of());

        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(50, 100));
        when(bot.getInventory(any())).thenReturn((Inventory) null);
        BotEntry entry = new BotEntry(bot, null, null);

        MapItem oneWayLoot = mock(MapItem.class);
        when(oneWayLoot.getPosition()).thenReturn(new Point(240, 140));
        MapItem returnableLoot = mock(MapItem.class);
        when(returnableLoot.getPosition()).thenReturn(new Point(440, 100));
        doReturn(List.of(oneWayLoot, returnableLoot)).when(map).getDroppedItems();

        try (MockedStatic<BotNavigationGraphProvider> graphProvider =
                     mockStatic(BotNavigationGraphProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotLootEligibility> lootEligibility =
                     mockStatic(BotLootEligibility.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            graphProvider.when(() -> BotNavigationGraphProvider.peekBestGraph(eq(map), any())).thenReturn(graph);
            lootEligibility.when(() -> BotLootEligibility.canBotTargetLoot(
                    eq(entry), eq(bot), eq(map), eq(oneWayLoot), anyLong())).thenReturn(true);
            lootEligibility.when(() -> BotLootEligibility.canBotTargetLoot(
                    eq(entry), eq(bot), eq(map), eq(returnableLoot), anyLong())).thenReturn(true);

            Point target = BotInventoryManager.findNearestPatrolLootTarget(entry, 1);

            assertEquals(new Point(440, 100), target);
        }
    }

    @Test
    void shouldPatrolTowardMobLootWhenBasePickupEligibilityAllowsIt() {
        MapleMap map = spy(new MapleMap(910000053, 0, 0, 910000053, 1.0f));
        Foothold foothold = new Foothold(new Point(0, 100), new Point(500, 100), 1);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-1000, -1000), new Point(1000, 2000));
        footholds.insert(foothold);
        map.setFootholds(footholds);

        BotNavigationGraph.Region region = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(foothold)));
        BotNavigationGraph graph = new BotNavigationGraph(
                map.getId(),
                1,
                BotMovementProfile.base(),
                List.of(region),
                Map.of(1, region),
                Map.of(1, 1),
                Map.of(),
                Set.of());

        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(88);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(50, 100));
        when(bot.getInventory(any())).thenReturn((Inventory) null);
        BotEntry entry = new BotEntry(bot, null, null);

        MapItem loot = mock(MapItem.class);
        when(loot.getObjectId()).thenReturn(1);
        when(loot.getPosition()).thenReturn(new Point(240, 100));
        when(loot.isPickedUp()).thenReturn(false);
        when(loot.canBePickedBy(any(Character.class))).thenReturn(true);
        when(loot.getDropTime()).thenReturn(System.currentTimeMillis() - 16_000L);
        when(loot.getOwnerId()).thenReturn(99);
        when(loot.isPlayerDrop()).thenReturn(false);
        when(loot.getItemId()).thenReturn(0);
        when(loot.getMeso()).thenReturn(1);
        doReturn(List.of(loot)).when(map).getDroppedItems();
        doReturn(loot).when(map).getMapObject(1);

        BotManager manager = mock(BotManager.class);

        try (MockedStatic<BotNavigationGraphProvider> graphProvider =
                     mockStatic(BotNavigationGraphProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotManager> botManagers =
                     mockStatic(BotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            graphProvider.when(() -> BotNavigationGraphProvider.peekBestGraph(eq(map), any())).thenReturn(graph);
            botManagers.when(BotManager::getInstance).thenReturn(manager);

            assertEquals(new Point(240, 100), BotInventoryManager.findNearestPatrolLootTarget(entry, 1));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listField(String name) throws Exception {
        return (List<String>) field(BotInventoryManager.class, name).get(null);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }
}
