package server.bots;

import client.BuffStat;
import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.Trade;
import tools.Pair;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldDescribeAutoSellStackablesWithSellQuantity() {
        // Equips delegate to BotOfferManager.formatItemSpecifier (needs live ii, not unit-testable);
        // stackables: null ii (tests) falls names back to id=
        assertEquals("id=2040001 x4",
                BotInventoryManager.describeAutoSellItem(null, null, Items.itemWithQuantity(2040001, 4)));
        assertEquals("id=2040002",
                BotInventoryManager.describeAutoSellItem(null, null, Items.itemWithQuantity(2040002, 1)));
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
        assertTrue(BotInventoryManager.hasProtectedSellTrashWeaponStat(currentWarriorWeapon, baseWarriorWeapon));
        assertTrue(BotInventoryManager.hasProtectedSellTrashWeaponStat(currentMageWeapon, baseMageWeapon));
    }

    @Test
    void shouldProtectAboveBaseMatkRollOnAnyJobWeapon() {
        // Black Umbrella field case: reqJob-0 ONE-HANDED SWORD with base MAD 85 that rolled
        // 92 (+7 over base). The protected-roll gate must read the MAD axis even though the
        // weapon's type/reqJob mask is not mage-only — godly MAD rolls are mage trade stock.
        Equip rolledUmbrella = mock(Equip.class);
        when(rolledUmbrella.getWatk()).thenReturn((short) 85); // watk rolled clean (= base)
        when(rolledUmbrella.getMatk()).thenReturn((short) 92);
        Equip baseUmbrella = mock(Equip.class);
        when(baseUmbrella.getWatk()).thenReturn((short) 85);
        when(baseUmbrella.getMatk()).thenReturn((short) 85);

        assertTrue(BotInventoryManager.hasProtectedSellTrashWeaponStat(rolledUmbrella, baseUmbrella),
                "+7 MAD over WZ base must shelf-protect regardless of who holds it");

        // Clean copy (both axes at base) stays unprotected -> normal sell-trash flow.
        assertTrue(!BotInventoryManager.hasProtectedSellTrashWeaponStat(baseUmbrella, baseUmbrella));
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

    // ---- USE value model: pressure-driven + value shelf ---------------------------------------

    @Test
    void ammoAndPotionsAreNeverPlainTrash_onlyJunkSellsOnANormalTrip() {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        use.addItem(Items.itemWithQuantity(2060000, 500));  // bow arrows = own ammo -> runway
        use.addItem(Items.itemWithQuantity(2061000, 500));  // xbow bolts = off-weapon, LONE stack -> shelf
        use.addItem(Items.itemWithQuantity(2070000, 200));  // weaker star = off-weapon, redundant lower tier -> shelf
        use.addItem(Items.itemWithQuantity(2070005, 200));  // stronger star = off-weapon, redundant best tier -> kept
        use.addItem(Items.itemWithQuantity(2000000, 100));  // recovery potion -> runway
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        try (AutoCloseable seams = withUseSeams(
                    id -> id == 2000000 ? recovery(50, 0) : null,
                    id -> id, (id) -> 100, (id, qty) -> 10 * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.BOW);

            // A normal sell trip sells only JUNK -> nothing here (no single cures / junk scrolls / stale quest).
            assertTrue(BotInventoryManager.collectSellTrashUseItems(bot).isEmpty());
            // But the bag holds a shelf stack (the weaker off-class star) a cramped trip could shed.
            assertTrue(BotInventoryManager.crampedUseSalesAvailable(bot));

            var classes = BotInventoryManager.classifyBagUse(bot);
            assertEquals(BotInventoryManager.UseTier.RUNWAY, tierOf(classes, 2060000)); // own ammo
            assertEquals(BotInventoryManager.UseTier.RUNWAY, tierOf(classes, 2000000)); // recovery
            // Off-class ammo: among REDUNDANT stacks the best tier is kept, the rest sheds; a LONE
            // off-class stack (the bolts) just shelves and is valued normally.
            assertEquals(BotInventoryManager.UseTier.SHELF, tierOf(classes, 2061000));  // lone crossbow stack
            assertEquals(BotInventoryManager.UseTier.RUNWAY, tierOf(classes, 2070005)); // redundant best star tier
            assertEquals(BotInventoryManager.UseTier.SHELF, tierOf(classes, 2070000));  // redundant weaker star tier
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void rechargeableAmmoValuedPerSet_duplicateSetsSellFirst() {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        // Clawer's hoard, in miniature: 3 slots of the same mid star + 1 slot of a better star.
        Item dupA = Items.itemWithQuantity(2070004, 6000);
        Item dupB = Items.itemWithQuantity(2070004, 6000);
        Item dupC = Items.itemWithQuantity(2070004, 6000);
        Item best = Items.itemWithQuantity(2070005, 6000);
        use.addItem(dupA); use.addItem(dupB); use.addItem(dupC); use.addItem(best);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        try (AutoCloseable seams = withUseSeams(id -> null,
                    id -> id == 2070005 ? 50 : 40,           // 2070005 is the best tier
                    id -> 800,                                // per-set value (quantity-independent)
                    (id, qty) -> 5 * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.CLAW);

            var classes = BotInventoryManager.classifyBagUse(bot);
            // One set of the best tier is the combat runway; the rest are shelf.
            assertEquals(BotInventoryManager.UseTier.RUNWAY,
                    classes.get(best).tier());
            // Of the three identical mid-star sets: one carries the per-set value, two are redundant (0).
            long zeroValueDupSets = java.util.stream.Stream.of(dupA, dupB, dupC)
                    .filter(it -> classes.get(it).tier() == BotInventoryManager.UseTier.SHELF)
                    .filter(it -> classes.get(it).keepValue() == 0)
                    .count();
            assertEquals(2, zeroValueDupSets);

            // Under pressure to free 2 slots, the two redundant duplicate sets go first.
            List<Item> sales = BotInventoryManager.collectCrampedUseSales(bot, 2, null);
            assertEquals(2, sales.size());
            assertTrue(sales.stream().allMatch(it -> it.getItemId() == 2070004));
            assertTrue(sales.stream().noneMatch(it -> it == best));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void crampedSalesSellWorstMesoPerSlotFirst_smallCheapStacksLead() {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        use.addItem(Items.itemWithQuantity(2999001, 3));    // 3 x 500 = 1500 total
        use.addItem(Items.itemWithQuantity(2999002, 700));  // 700 x 10 = 7000 total
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        try (AutoCloseable seams = withUseSeams(id -> null, id -> 0, id -> 0,
                    (id, qty) -> (id == 2999001 ? 500 : 10) * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.SWORD1H);
            // Free a single slot: the smaller-value stack (1500) sells before the bigger (7000).
            List<Item> sales = BotInventoryManager.collectCrampedUseSales(bot, 1, null);
            assertEquals(1, sales.size());
            assertEquals(2999001, sales.get(0).getItemId());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void crampedSalesUseScrollMarketValueSoDarkAttackScrollBeatsCheapAmmo() {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        Item darkAtt = Items.itemWithQuantity(2043002, 1);  // dark ATT scroll, low NPC sell-back
        Item offClassAmmo = Items.itemWithQuantity(2061000, 500);
        use.addItem(darkAtt);
        use.addItem(offClassAmmo);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        BotInventoryManager.ScrollStatsLookup prevScroll = BotInventoryManager.scrollStats;
        try (AutoCloseable seams = withUseSeams(id -> null, id -> 0, id -> 0,
                    (id, qty) -> id == 2043002 ? 1 : 10 * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            BotInventoryManager.scrollStats = id -> id == 2043002
                    ? Map.of("success", 30, "cursed", 50, "PAD", 5)
                    : null;
            BotInventoryManager.scrollMarketValue = (b, id) -> id == 2043002 ? 2_000_000.0 : 0.0;
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.BOW);

            List<Item> sales = BotInventoryManager.collectCrampedUseSales(bot, 1, null);

            assertEquals(1, sales.size());
            assertEquals(2061000, sales.get(0).getItemId());
            assertFalse(sales.contains(darkAtt));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            BotInventoryManager.scrollStats = prevScroll;
        }
    }

    @Test
    void recoveryRunwayCoversTarget_surplusShelfedAndNeverSoldBelowTarget() {
        int prevWarn = BotManager.cfg.POT_LOW_WARN;
        BotManager.cfg.POT_LOW_WARN = 2; // potResupplyTarget = 2 * 5 = 10 (HP and MP)
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        List<Item> pots = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Item p = Items.itemWithQuantity(2000000 + i, 4); // 4 each: 3 stacks (12) cover the 10 target
            pots.add(p);
            use.addItem(p);
        }
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        try (AutoCloseable seams = withUseSeams(id -> recovery(50, 50), // each restores HP and MP
                    id -> 0, id -> 0, (id, qty) -> 10 * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.SWORD1H);

            var classes = BotInventoryManager.classifyBagUse(bot);
            long runway = pots.stream().filter(p -> classes.get(p).tier() == BotInventoryManager.UseTier.RUNWAY).count();
            long shelf = pots.stream().filter(p -> classes.get(p).tier() == BotInventoryManager.UseTier.SHELF).count();
            assertEquals(3, runway); // 3 x 4 = 12 >= target 10, for both HP and MP
            assertEquals(2, shelf);

            // Even asked to free far more than exists, the target-covering runway is never sold.
            List<Item> sales = BotInventoryManager.collectCrampedUseSales(bot, 99, null);
            assertEquals(2, sales.size());
            assertTrue(sales.stream().allMatch(it ->
                    classes.get(it).tier() == BotInventoryManager.UseTier.SHELF));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            BotManager.cfg.POT_LOW_WARN = prevWarn;
        }
    }

    @Test
    void singleCuresAreJunk_allCureIsReserved() {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        use.addItem(Items.itemWithQuantity(2000004, 5));    // antidote (single cure) -> JUNK
        use.addItem(Items.itemWithQuantity(2050004, 30));   // all cure -> runway reserve
        use.addItem(Items.itemWithQuantity(2050005, 30));   // 2nd all cure stack -> shelf
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        try (AutoCloseable seams = withUseSeams(
                    id -> id == 2000004 ? singleCure() : (id >= 2050004 ? allCure() : null),
                    id -> 0, id -> 0, (id, qty) -> 10 * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.SWORD1H);

            List<Item> trash = BotInventoryManager.collectSellTrashUseItems(bot);
            assertEquals(1, trash.size());
            assertEquals(2000004, trash.get(0).getItemId());

            var classes = BotInventoryManager.classifyBagUse(bot);
            assertEquals(BotInventoryManager.UseTier.RUNWAY, tierOf(classes, 2050004));
            assertEquals(BotInventoryManager.UseTier.SHELF, tierOf(classes, 2050005));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void neverSellTradeGoodsAreProtectedEvenUnderPressure() {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        use.addItem(Items.itemWithQuantity(2999001, 1));   // cheap shelf misc -> sellable
        use.addItem(Items.itemWithQuantity(2999009, 1));   // pricey trade good -> protected
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        try (AutoCloseable seams = withUseSeams(id -> null, id -> 0, id -> 0,
                    (id, qty) -> (id == 2999009 ? 60_000 : 10) * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.SWORD1H);
            List<Item> sales = BotInventoryManager.collectCrampedUseSales(bot, 99, null);
            assertEquals(1, sales.size());
            assertEquals(2999001, sales.get(0).getItemId());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void buffRunwayKeepsRelevantBuffsAndShelvesIrrelevant() {
        Character bot = mock(Character.class);
        when(bot.getJobStyle()).thenReturn(Job.WARRIOR);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        use.addItem(Items.itemWithQuantity(2022501, 5));   // WATK buff -> relevant -> runway
        use.addItem(Items.itemWithQuantity(2022509, 5));   // MATK-only buff -> irrelevant for warrior -> shelf
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        Map<Integer, StatEffect> fx = Map.of(
                2022501, buffEffect(BuffStat.WATK, 10),
                2022509, buffEffect(BuffStat.MATK, 10));
        try (AutoCloseable seams = withUseSeams(fx::get, id -> 0, id -> 0, (id, qty) -> 10 * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.SWORD1H);
            var classes = BotInventoryManager.classifyBagUse(bot);
            assertEquals(BotInventoryManager.UseTier.RUNWAY, tierOf(classes, 2022501));
            assertEquals(BotInventoryManager.UseTier.SHELF, tierOf(classes, 2022509));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void recoveryRunwayCoversResupplyTargetSoItDoesNotRebuy() {
        int prevWarn = BotManager.cfg.POT_LOW_WARN;
        BotManager.cfg.POT_LOW_WARN = 1; // potResupplyTarget = 1 * 5 = 5
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        use.addItem(Items.itemWithQuantity(2000010, 3));    // HP heal
        use.addItem(Items.itemWithQuantity(2000011, 4));    // HP heal
        use.addItem(Items.itemWithQuantity(2000012, 10));   // MP heal
        use.addItem(Items.itemWithQuantity(2000013, 100));  // HP heal surplus (target already met)
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        Map<Integer, StatEffect> fx = Map.of(
                2000010, recovery(50, 0),
                2000011, recovery(50, 0),
                2000012, recovery(0, 50),
                2000013, recovery(10, 0));
        try (AutoCloseable seams = withUseSeams(fx::get, id -> 0, id -> 0, (id, qty) -> 10 * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.SWORD1H);
            var classes = BotInventoryManager.classifyBagUse(bot);
            // Target HP and MP coverage is in the runway; only the surplus HP stack is sellable.
            assertEquals(BotInventoryManager.UseTier.RUNWAY, tierOf(classes, 2000012));
            assertEquals(BotInventoryManager.UseTier.SHELF, tierOf(classes, 2000013));
            List<Item> sales = BotInventoryManager.collectCrampedUseSales(bot, 99, null);
            assertEquals(1, sales.size());
            assertEquals(2000013, sales.get(0).getItemId());
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            BotManager.cfg.POT_LOW_WARN = prevWarn;
        }
    }

    @Test
    void returnScrollRunwayReservesResupplyTargetSoItDoesNotRebuy() {
        Character bot = mock(Character.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 96);
        Item reserved = Items.itemWithQuantity(2030000, BotShopManager.returnScrollReserveTarget()); // resupply target
        Item surplus = Items.itemWithQuantity(2030000, 5);   // beyond target -> shelf
        use.addItem(reserved);
        use.addItem(surplus);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        // Return scrolls are warp scrolls: no heal/cure/buff effect, so they'd fall to the shelf and
        // sell under pressure without the dedicated runway -> then get rebought (the buy/sell loop).
        try (AutoCloseable seams = withUseSeams(id -> null, id -> 0, id -> 0, (id, qty) -> 10 * qty);
             MockedStatic<BotAttackExecutionProvider> attacks = mockStatic(BotAttackExecutionProvider.class)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(client.inventory.WeaponType.SWORD1H);
            var classes = BotInventoryManager.classifyBagUse(bot);
            assertEquals(BotInventoryManager.UseTier.RUNWAY, classes.get(reserved).tier());
            assertEquals(BotInventoryManager.UseTier.SHELF, classes.get(surplus).tier());
            // Under maximum bag pressure only the surplus scroll sells; the reserve is protected.
            List<Item> sales = BotInventoryManager.collectCrampedUseSales(bot, 99, null);
            assertEquals(1, sales.size());
            assertEquals(surplus, sales.get(0));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static StatEffect buffEffect(BuffStat stat, int amount) {
        StatEffect fx = mock(StatEffect.class);
        doReturn(List.of(new Pair<>(stat, amount))).when(fx).getStatups();
        return fx;
    }

    private static BotInventoryManager.UseTier tierOf(
            Map<Item, BotInventoryManager.UseClass> classes, int itemId) {
        return classes.entrySet().stream()
                .filter(e -> e.getKey().getItemId() == itemId)
                .map(e -> e.getValue().tier())
                .findFirst().orElse(null);
    }

    private static StatEffect recovery(int hp, int mp) {
        StatEffect fx = mock(StatEffect.class);
        when(fx.getHp()).thenReturn((short) hp);
        when(fx.getMp()).thenReturn((short) mp);
        doReturn(List.of()).when(fx).getStatups();
        return fx;
    }

    private static StatEffect allCure() {
        StatEffect fx = mock(StatEffect.class);
        doReturn(List.of()).when(fx).getStatups();
        when(fx.curesAnyDebuff()).thenReturn(true);
        when(fx.curesAllAbnormalStatus()).thenReturn(true);
        return fx;
    }

    private static StatEffect singleCure() {
        StatEffect fx = mock(StatEffect.class);
        doReturn(List.of()).when(fx).getStatups();
        when(fx.curesAnyDebuff()).thenReturn(true);
        when(fx.curesAllAbnormalStatus()).thenReturn(false);
        return fx;
    }

    private static AutoCloseable withUseSeams(BotInventoryManager.ItemEffectLookup effect,
            IntUnaryOperator projectileWatk, IntUnaryOperator ammoSetValue,
            BotInventoryManager.SellPriceLookup price) {
        BotInventoryManager.ItemEffectLookup pe = BotInventoryManager.useEffect;
        IntUnaryOperator pp = BotInventoryManager.projectileWatk;
        IntUnaryOperator pa = BotInventoryManager.ammoSetValue;
        IntUnaryOperator pmk = BotInventoryManager.ammoMarketValue;
        BotInventoryManager.SellPriceLookup ps = BotInventoryManager.sellPrice;
        BotInventoryManager.ScrollMarketValueLookup pm = BotInventoryManager.scrollMarketValue;
        java.util.function.IntToDoubleFunction pcc = BotInventoryManager.scrollCombatCeiling;
        BotInventoryManager.ScrollMarketValueLookup poc = BotInventoryManager.ammoObtainCost;
        IntPredicate pq = BotInventoryManager.questItem;
        java.util.function.Predicate<Item> pu = BotInventoryManager.untradeable;
        BotInventoryManager.useEffect = effect;
        BotInventoryManager.projectileWatk = projectileWatk;
        BotInventoryManager.ammoSetValue = ammoSetValue;
        BotInventoryManager.ammoMarketValue = id -> 0;   // hermetic: no DB shop-price lookup in tests
        BotInventoryManager.sellPrice = price;
        BotInventoryManager.scrollMarketValue = (bot, id) -> 0.0;
        BotInventoryManager.scrollCombatCeiling = id -> 0.0;     // hermetic: no WZ stat lookup in tests
        BotInventoryManager.ammoObtainCost = (bot, id) -> 0.0;   // hermetic: no DB shop/farm lookup in tests
        BotInventoryManager.questItem = id -> false;
        BotInventoryManager.untradeable = item -> false;
        return () -> {
            BotInventoryManager.useEffect = pe;
            BotInventoryManager.projectileWatk = pp;
            BotInventoryManager.ammoSetValue = pa;
            BotInventoryManager.ammoMarketValue = pmk;
            BotInventoryManager.sellPrice = ps;
            BotInventoryManager.scrollMarketValue = pm;
            BotInventoryManager.scrollCombatCeiling = pcc;
            BotInventoryManager.ammoObtainCost = poc;
            BotInventoryManager.questItem = pq;
            BotInventoryManager.untradeable = pu;
        };
    }

    // Real WATK per live star tier (WZ incPAD), for the ammo combat-ceiling calibration tests.
    private static int starWatk(int id) {
        return switch (id) {
            case 2070002 -> 19; // Mokbi
            case 2070003, 2070010 -> 21; // Kumbi / Icicles
            case 2070004 -> 23; // Tobi
            case 2070005 -> 25; // Steely
            case 2070006, 2070007 -> 27; // Ilbi / Hwabi
            case 2070016 -> 29; // Crystal Ilbi
            default -> 0;
        };
    }

    @Test
    void floodedAmmoCollapsesToObtainCost_neverBeatsGoodScroll() {
        // Supply binds the worth: a powerful but cheap-to-obtain (flooded) star falls to its obtain cost
        // and stays on the shared real-meso axis — it must not out-value a good scroll just for power.
        IntUnaryOperator pp = BotInventoryManager.projectileWatk;
        IntUnaryOperator pa = BotInventoryManager.ammoSetValue;
        IntUnaryOperator pmk = BotInventoryManager.ammoMarketValue;
        BotInventoryManager.SellPriceLookup ps = BotInventoryManager.sellPrice;
        BotInventoryManager.ScrollMarketValueLookup pm = BotInventoryManager.scrollMarketValue;
        java.util.function.IntToDoubleFunction pcc = BotInventoryManager.scrollCombatCeiling;
        BotInventoryManager.ScrollMarketValueLookup poc = BotInventoryManager.ammoObtainCost;
        BotInventoryManager.projectileWatk = BotInventoryManagerTest::starWatk;
        BotInventoryManager.ammoSetValue = id -> 200;
        BotInventoryManager.ammoMarketValue = id -> 0;
        BotInventoryManager.sellPrice = (id, qty) -> 5 * qty;
        BotInventoryManager.scrollMarketValue = (bot, id) -> 8000.0;   // a genuinely valuable scroll
        BotInventoryManager.scrollCombatCeiling = id -> 0.0;           // keep scroll worth at obtain cost
        BotInventoryManager.ammoObtainCost = (bot, id) -> 3000.0;      // Ilbi flooded: cheap to obtain
        try {
            Character bot = mock(Character.class);
            double ilbi = BotInventoryManager.useShelfKeepValue(bot, Items.itemWithQuantity(2070006, 1));
            double scroll = BotInventoryManager.useShelfKeepValue(bot, Items.itemWithQuantity(2040000, 1));
            assertEquals(3000.0, ilbi, 0.001, "flooded star collapses to its obtain cost");
            assertTrue(scroll > ilbi, "a cheap (flooded) star must not out-value a good scroll");
        } finally {
            BotInventoryManager.projectileWatk = pp;
            BotInventoryManager.ammoSetValue = pa;
            BotInventoryManager.ammoMarketValue = pmk;
            BotInventoryManager.sellPrice = ps;
            BotInventoryManager.scrollMarketValue = pm;
            BotInventoryManager.scrollCombatCeiling = pcc;
            BotInventoryManager.ammoObtainCost = poc;
        }
    }

    @Test
    void scarceAmmoRidesCombatCeiling_steepTowardBest() {
        // When a tier is scarce (obtain cost above its ceiling), worth = the convex combat ceiling, so
        // value climbs steeply toward the best star — Ilbi (27 WATK) far above Steely (25 WATK).
        IntUnaryOperator pp = BotInventoryManager.projectileWatk;
        IntUnaryOperator pa = BotInventoryManager.ammoSetValue;
        IntUnaryOperator pmk = BotInventoryManager.ammoMarketValue;
        BotInventoryManager.ScrollMarketValueLookup poc = BotInventoryManager.ammoObtainCost;
        BotInventoryManager.projectileWatk = BotInventoryManagerTest::starWatk;
        BotInventoryManager.ammoSetValue = id -> 200;
        BotInventoryManager.ammoMarketValue = id -> 0;
        BotInventoryManager.ammoObtainCost = (bot, id) -> 500_000_000.0;   // brutal to farm: ceiling binds
        try {
            Character bot = mock(Character.class);
            double ilbi = BotInventoryManager.useShelfKeepValue(bot, Items.itemWithQuantity(2070006, 1));
            double steely = BotInventoryManager.useShelfKeepValue(bot, Items.itemWithQuantity(2070005, 1));
            // Ceiling binds (well below the 500M obtain cost) and Ilbi's +2 WATK is worth ~4x Steely.
            assertEquals(BotInventoryManager.ammoCombatCeiling(2070006), ilbi, 1.0);
            assertTrue(ilbi > steely * 3, "two WATK steps up should be steeply (>3x) more valuable");
        } finally {
            BotInventoryManager.projectileWatk = pp;
            BotInventoryManager.ammoSetValue = pa;
            BotInventoryManager.ammoMarketValue = pmk;
            BotInventoryManager.ammoObtainCost = poc;
        }
    }

    @Test
    void ammoCombatCeilingTracksLiveStarMarketWithinTolerance() {
        // Calibration guard: the convex ceiling must stay within ~2x of the observed star market across
        // every tier and rise monotonically with WATK — a regression alarm, not an overfit per-point fit.
        IntUnaryOperator pp = BotInventoryManager.projectileWatk;
        BotInventoryManager.projectileWatk = BotInventoryManagerTest::starWatk;
        try {
            // Observed market (SoloMapling reference): id -> meso.
            int[][] ref = {
                {2070002, 75_000}, {2070003, 200_000}, {2070004, 2_000_000},
                {2070005, 8_000_000}, {2070006, 17_500_000}, {2070016, 90_000_000},
            };
            double prev = 0;
            int prevWatk = 0;
            for (int[] r : ref) {
                double ceil = BotInventoryManager.ammoCombatCeiling(r[0]);
                double factor = ceil / r[1];
                assertTrue(factor > 0.5 && factor < 2.0,
                        "tier " + r[0] + " ceiling " + ceil + " within 2x of market " + r[1]);
                if (starWatk(r[0]) > prevWatk) {
                    assertTrue(ceil > prev, "ceiling must rise with WATK");
                    prev = ceil;
                    prevWatk = starWatk(r[0]);
                }
            }
        } finally {
            BotInventoryManager.projectileWatk = pp;
        }
    }

    @Test
    void scrollKeepValueCappedByCombatCeiling() {
        // Same min(obtainCost, combatCeiling) shape as ammo: a combat-weak scroll is pulled down to its
        // ceiling, a strong one is held at obtain cost (supply binds), a stat-less one keeps obtain worth.
        BotInventoryManager.ScrollMarketValueLookup pm = BotInventoryManager.scrollMarketValue;
        java.util.function.IntToDoubleFunction pcc = BotInventoryManager.scrollCombatCeiling;
        BotInventoryManager.SellPriceLookup ps = BotInventoryManager.sellPrice;
        BotInventoryManager.sellPrice = (id, qty) -> 100 * qty;
        BotInventoryManager.scrollMarketValue = (bot, id) -> 500_000.0;   // 500k to obtain
        try {
            Character bot = mock(Character.class);
            BotInventoryManager.scrollCombatCeiling = id -> 75_000.0;     // weak: ceiling below obtain
            double weak = BotInventoryManager.useShelfKeepValue(bot, Items.itemWithQuantity(2041014, 1));
            assertEquals(75_000.0, weak, 0.001, "weak scroll capped to its combat ceiling");

            BotInventoryManager.scrollCombatCeiling = id -> 4_500_000.0;  // strong: obtain binds
            double strong = BotInventoryManager.useShelfKeepValue(bot, Items.itemWithQuantity(2044701, 1));
            assertEquals(500_000.0, strong, 0.001, "strong scroll capped to obtain cost (supply binds)");

            BotInventoryManager.scrollCombatCeiling = id -> 0.0;          // stat-less (clean slate)
            double clean = BotInventoryManager.useShelfKeepValue(bot, Items.itemWithQuantity(2049000, 1));
            assertEquals(500_000.0, clean, 0.001, "stat-less scroll keeps full obtain cost, not crushed");
        } finally {
            BotInventoryManager.scrollMarketValue = pm;
            BotInventoryManager.scrollCombatCeiling = pcc;
            BotInventoryManager.sellPrice = ps;
        }
    }

    @Test
    void bulletCurveFloorAlignsWithWeakestStar() {
        // Bullets (WATK 10-20) share the star slope with the anchor shifted down 5: the weakest bullet
        // (10 WATK) must value exactly the weakest star (15 WATK), and bullets stay below same-WATK stars.
        IntUnaryOperator pp = BotInventoryManager.projectileWatk;
        BotInventoryManager.projectileWatk = id -> id == 2330000 ? 10 : 15; // bullet 10, star 15
        try {
            double bullet10 = BotInventoryManager.ammoCombatCeiling(2330000); // bullet
            double star15 = BotInventoryManager.ammoCombatCeiling(2070000);   // star
            assertEquals(star15, bullet10, 0.001, "10-WATK bullet matches 15-WATK star floor");
            assertTrue(bullet10 > 0);
        } finally {
            BotInventoryManager.projectileWatk = pp;
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
    void shouldCacheEquipTradeClassificationUntilBagChanges() throws Exception {
        // The reserve check (isReservedForOtherRecipients) is the ~150ms cost. With a stable bag a
        // re-classify must reuse the cache and NOT re-run it; a bag change must invalidate.
        IntPredicate prevQuest = BotInventoryManager.questItem;
        java.util.function.Predicate<Item> prevUntradeable = BotInventoryManager.untradeable;
        BotInventoryManager.questItem = id -> false;
        BotInventoryManager.untradeable = item -> false;
        try {
            Character bot = mock(Character.class);
            Character owner = mock(Character.class);
            BotEntry entry = new BotEntry(bot, owner, null);
            Equip e1 = equipWithWatk(1102000, 10);
            Equip e2 = equipWithWatk(1102001, 12);
            Inventory inv = mock(Inventory.class);
            when(inv.getSlotLimit()).thenReturn((byte) 24);
            when(inv.list()).thenReturn(List.of(e1, e2));
            when(inv.getItem((short) 1)).thenReturn(e1);
            when(inv.getItem((short) 2)).thenReturn(e2);
            when(bot.getInventory(InventoryType.EQUIP)).thenReturn(inv);

            int[] reserveChecks = {0};
            Method classify = method(BotInventoryManager.class,
                    "classifyEquipTradeGroups", BotEntry.class, Character.class);
            try (MockedStatic<BotEquipManager> equips = mockStatic(BotEquipManager.class);
                 MockedStatic<BotOfferManager> offers = mockStatic(BotOfferManager.class)) {
                equips.when(() -> BotEquipManager.collectPotentialSelfUpgradeItems(bot))
                        .thenReturn(Set.of());
                offers.when(() -> BotOfferManager.isReservedForOtherRecipients(eq(entry), eq(bot), any()))
                        .thenAnswer(call -> {
                            reserveChecks[0]++;
                            return false;
                        });

                classify.invoke(null, entry, bot);
                assertEquals(2, reserveChecks[0]); // first classify checks both items

                classify.invoke(null, entry, bot);
                assertEquals(2, reserveChecks[0]); // unchanged bag -> cache hit, no re-check

                // Bag grows by one equip: signature changes -> cache miss -> full re-classify.
                Equip e3 = equipWithWatk(1102002, 8);
                when(inv.list()).thenReturn(List.of(e1, e2, e3));
                when(inv.getItem((short) 3)).thenReturn(e3);
                classify.invoke(null, entry, bot);
                assertEquals(5, reserveChecks[0]); // 2 (cached) + 3 (recomputed)
            }
        } finally {
            BotInventoryManager.questItem = prevQuest;
            BotInventoryManager.untradeable = prevUntradeable;
        }
    }

    @Test
    void shouldSellEquipScrollsWithNoJobRelevantStats() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2040001, 3));  // pure HP scroll -> sell
        use.addItem(Items.itemWithQuantity(2040002, 2));  // pure WDEF scroll -> sell
        use.addItem(Items.itemWithQuantity(2040041, 1));  // LUK scroll -> keep (assassin)
        use.addItem(Items.itemWithQuantity(2040045, 1));  // INT scroll -> keep even off-job (trade good)
        use.addItem(Items.itemWithQuantity(2043001, 1));  // ATT scroll -> keep
        use.addItem(Items.itemWithQuantity(2040718, 1));  // speed scroll -> keep for any job
        use.addItem(Items.itemWithQuantity(2040003, 1));  // junk scroll but NPC pays 0 -> keep
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        Map<Integer, Map<String, Integer>> effects = Map.of(
                2040001, Map.of("MHP", 10),
                2040002, Map.of("PDD", 10),
                2040041, Map.of("LUK", 2),
                2040045, Map.of("INT", 2),
                2043001, Map.of("PAD", 2),
                2040718, Map.of("Speed", 1),
                2040003, Map.of("MMP", 10));

        BotInventoryManager.ScrollStatsLookup prevScroll = BotInventoryManager.scrollStats;
        BotInventoryManager.scrollStats = effects::get;
        BotInventoryManager.ItemEffectLookup prevEffect = BotInventoryManager.useEffect;
        BotInventoryManager.useEffect = id -> null; // scrolls aren't pot/cure/buff effects
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
            BotInventoryManager.useEffect = prevEffect;
        }
    }

    // ---- Feature B: stale quest items become sellable -----------------------------------------

    /** Swap the Feature B seams (quest-item predicate, item->quest reverse map, quest status).
     *  Restored on close. */
    private static AutoCloseable withQuestSeams(IntPredicate isQuestItem,
            BotInventoryManager.QuestReqsLookup reqs, BotInventoryManager.QuestStatusLookup status) {
        IntPredicate prevQuest = BotInventoryManager.questItem;
        BotInventoryManager.QuestReqsLookup prevReqs = BotInventoryManager.questReqsLookup;
        BotInventoryManager.QuestStatusLookup prevStatus = BotInventoryManager.questStatus;
        java.util.function.BiPredicate<Character, Integer> prevBugged = BotInventoryManager.questBugged;
        BotInventoryManager.questItem = isQuestItem;
        BotInventoryManager.questReqsLookup = reqs;
        BotInventoryManager.questStatus = status;
        BotInventoryManager.questBugged = (bot, q) -> false; // no proven-unfinishable quests by default
        return () -> {
            BotInventoryManager.questItem = prevQuest;
            BotInventoryManager.questReqsLookup = prevReqs;
            BotInventoryManager.questStatus = prevStatus;
            BotInventoryManager.questBugged = prevBugged;
        };
    }

    private static BotInventoryManager.QuestStatusLookup status(
            Set<Integer> started, Set<Integer> completed) {
        return new BotInventoryManager.QuestStatusLookup() {
            @Override public boolean isStarted(Character bot, int questId) { return started.contains(questId); }
            @Override public boolean isCompleted(Character bot, int questId) { return completed.contains(questId); }
        };
    }

    private static BotInventoryManager.QuestReqsLookup reqs(int itemId,
            BotQuestIndex.QuestItemReq... rs) {
        return id -> id == itemId ? List.of(rs) : List.of();
    }

    @Test
    void staleWhenOnlyUsingQuestIsCompleted() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(50);
        // item 4032000 used only by quest 5000 (lv 20-30), which the bot has COMPLETED.
        try (AutoCloseable s = withQuestSeams(id -> id == 4032000,
                reqs(4032000, new BotQuestIndex.QuestItemReq(5000, 20, 30)),
                status(Set.of(), Set.of(5000)))) {
            assertTrue(BotInventoryManager.isStaleQuestItem(bot, 4032000));
        }
    }

    @Test
    void notStaleWhenAnyUsingQuestIsStarted() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(99);
        // two quests use it; one is STARTED -> never stale even though the other is done.
        try (AutoCloseable s = withQuestSeams(id -> id == 4032000,
                reqs(4032000, new BotQuestIndex.QuestItemReq(5000, 20, 30),
                        new BotQuestIndex.QuestItemReq(5001, 20, 30)),
                status(Set.of(5001), Set.of(5000)))) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    BotInventoryManager.isStaleQuestItem(bot, 4032000));
        }
    }

    @Test
    void notStaleWhenSomeUsingQuestStillDoable() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(25); // not outleveled (cap 30 + margin 30 = 60)
        try (AutoCloseable s = withQuestSeams(id -> id == 4032000,
                reqs(4032000, new BotQuestIndex.QuestItemReq(5000, 20, 30)),
                status(Set.of(), Set.of()))) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    BotInventoryManager.isStaleQuestItem(bot, 4032000));
        }
    }

    @Test
    void staleWhenSeverelyOutleveledAllUsingQuests() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(65); // cap 30 + margin 30 = 60 <= 65 -> way past
        try (AutoCloseable s = withQuestSeams(id -> id == 4032000,
                reqs(4032000, new BotQuestIndex.QuestItemReq(5000, 20, 30)),
                status(Set.of(), Set.of()))) {
            assertTrue(BotInventoryManager.isStaleQuestItem(bot, 4032000));
        }
    }

    @Test
    void notStaleWhenOutlevelButQuestHasNoLevelCap() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(200);
        // cap 0 (no level info) -> undeterminable -> conservative KEEP.
        try (AutoCloseable s = withQuestSeams(id -> id == 4032000,
                reqs(4032000, new BotQuestIndex.QuestItemReq(5000, 0, 0)),
                status(Set.of(), Set.of()))) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    BotInventoryManager.isStaleQuestItem(bot, 4032000));
        }
    }

    @Test
    void notStaleWhenUnknownItem() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(99);
        // a quest item used by NO indexed quest -> out of scope -> not stale.
        try (AutoCloseable s = withQuestSeams(id -> id == 4032000,
                id -> List.of(), status(Set.of(), Set.of()))) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    BotInventoryManager.isStaleQuestItem(bot, 4032000));
        }
        // a non-quest item is never stale (out of scope).
        try (AutoCloseable s = withQuestSeams(id -> false,
                reqs(4032000, new BotQuestIndex.QuestItemReq(5000, 20, 30)),
                status(Set.of(), Set.of(5000)))) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    BotInventoryManager.isStaleQuestItem(bot, 1234567));
        }
    }

    @Test
    void sellTrashEtcIncludesStaleQuestItemAndExcludesActiveOne() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(50);
        Inventory etc = new Inventory(bot, InventoryType.ETC, (byte) 24);
        etc.addItem(Items.itemWithQuantity(4032000, 1)); // stale (completed quest) -> SELL
        etc.addItem(Items.itemWithQuantity(4032001, 1)); // active (started quest) -> KEEP
        when(bot.getInventory(InventoryType.ETC)).thenReturn(etc);

        BotInventoryManager.QuestReqsLookup reqs = id -> switch (id) {
            case 4032000 -> List.of(new BotQuestIndex.QuestItemReq(5000, 20, 30));
            case 4032001 -> List.of(new BotQuestIndex.QuestItemReq(5001, 20, 30));
            default -> List.of();
        };
        BotInventoryManager.SellPriceLookup price = (id, qty) -> 10; // both have NPC price
        IntUnaryOperator leftover = id -> -1;
        IntUnaryOperator dropChance = id -> 600000; // not rare
        try (AutoCloseable sell = withSellSeams(price, leftover, dropChance);
             AutoCloseable quest = withQuestSeams(id -> id == 4032000 || id == 4032001, reqs,
                     status(Set.of(5001) /*4032001's quest started*/, Set.of(5000) /*4032000's done*/))) {
            List<Item> trash = BotInventoryManager.collectSellTrashEtcItems(bot);
            assertTrue(trash.stream().anyMatch(it -> it.getItemId() == 4032000),
                    "a stale quest ETC item should be collected as sell-trash");
            assertTrue(trash.stream().noneMatch(it -> it.getItemId() == 4032001),
                    "an active-quest ETC item must NOT be sold");
        }
    }

    @Test
    void staleUntradeableQuestItemRespectsUntradeableConfig() throws Exception {
        // A stale quest item flagged untradeable (info/tradeBlock=1, ~26% of quest ETC items).
        // Whether it sells depends on the server's UNTRADEABLE_ITEMS_TRADEABLE config, the same
        // gate the rest of the sell pipeline honours - Feature B does not override it:
        //   config true  -> untradeable items are sellable, so a STALE one sells.
        //   config false -> the untradeable gate holds and it stays unsold.
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(50);
        Inventory etc = new Inventory(bot, InventoryType.ETC, (byte) 24);
        etc.addItem(Items.itemWithQuantity(4032000, 1)); // stale AND untradeable
        when(bot.getInventory(InventoryType.ETC)).thenReturn(etc);

        BotInventoryManager.SellPriceLookup price = (id, qty) -> 10;
        IntUnaryOperator leftover = id -> -1;
        IntUnaryOperator dropChance = id -> 600000;
        boolean untradeableSellable = config.YamlConfig.config.server.UNTRADEABLE_ITEMS_TRADEABLE;
        try (AutoCloseable sell = withSellSeams(price, leftover, dropChance);
             AutoCloseable quest = withQuestSeams(id -> id == 4032000,
                     reqs(4032000, new BotQuestIndex.QuestItemReq(5000, 20, 30)),
                     status(Set.of(), Set.of(5000)))) {
            BotInventoryManager.untradeable = item -> item.getItemId() == 4032000;
            List<Item> trash = BotInventoryManager.collectSellTrashEtcItems(bot);
            boolean sold = trash.stream().anyMatch(it -> it.getItemId() == 4032000);
            assertEquals(untradeableSellable, sold,
                    "stale untradeable item must follow the UNTRADEABLE_ITEMS_TRADEABLE config gate");
        }
    }

    @Test
    void buggedQuestMakesItemDisposableEvenWhenStarted() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(50);
        // STARTED (would normally keep the item) but proven unfinishable (unreachable turn-in NPC).
        try (AutoCloseable s = withQuestSeams(id -> id == 4032000,
                reqs(4032000, new BotQuestIndex.QuestItemReq(5000, 20, 30)),
                status(Set.of(5000) /*started*/, Set.of() /*not completed*/))) {
            assertFalse(BotInventoryManager.isStaleQuestItem(bot, 4032000),
                    "a started, finishable quest's item is kept");
            BotInventoryManager.questBugged = (b, q) -> q == 5000;
            assertTrue(BotInventoryManager.isStaleQuestItem(bot, 4032000),
                    "a started-but-bugged quest's item is dead weight -> disposable");
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
        etc.addItem(Items.itemWithQuantity(4000101, 1252)); // crystal leftover >=100, has Maker -> keep
        etc.addItem(Items.itemWithQuantity(4000102, 200));  // crystal leftover >=100, has Maker -> keep
        etc.addItem(Items.itemWithQuantity(4000200, 9));    // NPC pays nothing -> trash (generic 0-sell)
        etc.addItem(Items.itemWithQuantity(4030014, 100));  // omok piece (rare) -> trash (omok whitelist)
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

        // 4030014 (omok) is also a rare drop, proving the omok sell-whitelist overrides the keep.
        BotInventoryManager.SellPriceLookup price = (id, qty) -> id == 4000200 ? -1 : 10;
        IntUnaryOperator leftover = id -> isIn(id, 4000100, 4000101, 4000102) ? 4260000 : -1;
        IntUnaryOperator dropChance = id -> isIn(id, 4000001, 4030014) ? 5000 : 600000;

        try (AutoCloseable seams = withSellSeams(price, leftover, dropChance, 2 /* has Maker */)) {
            List<Item> trash = BotInventoryManager.collectSellTrashEtcItems(bot);
            assertEquals(4, trash.size());
            assertTrue(trash.stream().anyMatch(item -> item.getItemId() == 4000000));
            assertTrue(trash.stream().anyMatch(item ->
                    item.getItemId() == 4000100 && BotInventoryManager.sellTrashQuantity(item) == 45));
            assertTrue(trash.stream().anyMatch(item -> item.getItemId() == 4000200),
                    "0-NPC-price clutter should now be sold");
            assertTrue(trash.stream().anyMatch(item -> item.getItemId() == 4030014),
                    "omok pieces are always sold, even when they read as a rare drop");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void shouldSellCrystalLeftoversWhenBotLacksMakerSkill() {
        Character bot = mock(Character.class);
        Inventory etc = new Inventory(bot, InventoryType.ETC, (byte) 12);
        etc.addItem(Items.itemWithQuantity(4000101, 1252)); // big crystal-leftover stack
        etc.addItem(Items.itemWithQuantity(4000102, 200));  // big crystal-leftover stack
        etc.addItem(Items.itemWithQuantity(4250000, 3));    // maker reagent -> always keep
        when(bot.getInventory(InventoryType.ETC)).thenReturn(etc);

        BotInventoryManager.SellPriceLookup price = (id, qty) -> 10;
        IntUnaryOperator leftover = id -> isIn(id, 4000101, 4000102) ? 4260000 : -1;
        IntUnaryOperator dropChance = id -> 600000;

        try (AutoCloseable seams = withSellSeams(price, leftover, dropChance, 0 /* no Maker */)) {
            List<Item> trash = BotInventoryManager.collectSellTrashEtcItems(bot);
            assertEquals(2, trash.size(), "crystal leftovers are clutter without the Maker skill");
            assertTrue(trash.stream().anyMatch(item -> item.getItemId() == 4000101));
            assertTrue(trash.stream().anyMatch(item -> item.getItemId() == 4000102));
            assertFalse(trash.stream().anyMatch(item -> item.getItemId() == 4250000));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static AutoCloseable withSellSeams(BotInventoryManager.SellPriceLookup price,
                                               IntUnaryOperator leftover,
                                               IntUnaryOperator dropChance) {
        return withSellSeams(price, leftover, dropChance, 0); // default: bot has no Maker skill
    }

    // Swap the ItemInformationProvider/DB-backed seams (restored on close); quest lookup always
    // answers "not a quest item" so collectFromBag's isSafeToDrop stays inert. makerLevel feeds the
    // crystal-leftover keep gate (>=1 = can convert leftovers to monster crystals).
    private static AutoCloseable withSellSeams(BotInventoryManager.SellPriceLookup price,
                                               IntUnaryOperator leftover,
                                               IntUnaryOperator dropChance,
                                               int makerLevel) {
        BotInventoryManager.SellPriceLookup prevPrice = BotInventoryManager.sellPrice;
        BotInventoryManager.ScrollMarketValueLookup prevScrollValue = BotInventoryManager.scrollMarketValue;
        java.util.function.IntToDoubleFunction prevCeiling = BotInventoryManager.scrollCombatCeiling;
        IntUnaryOperator prevLeftover = BotInventoryManager.makerCrystalFromLeftover;
        IntUnaryOperator prevDrop = BotInventoryManager.bestDropChance;
        IntPredicate prevQuest = BotInventoryManager.questItem;
        java.util.function.Predicate<Item> prevUntradeable = BotInventoryManager.untradeable;
        java.util.function.ToIntFunction<Character> prevMaker = BotInventoryManager.makerSkillLevel;
        BotInventoryManager.sellPrice = price;
        BotInventoryManager.scrollMarketValue = (bot, id) -> 0.0;
        BotInventoryManager.scrollCombatCeiling = id -> 0.0;   // hermetic: no WZ stat lookup in tests
        BotInventoryManager.makerCrystalFromLeftover = leftover;
        BotInventoryManager.bestDropChance = dropChance;
        BotInventoryManager.questItem = id -> false;
        BotInventoryManager.untradeable = item -> false;
        BotInventoryManager.makerSkillLevel = bot -> makerLevel;
        return () -> {
            BotInventoryManager.sellPrice = prevPrice;
            BotInventoryManager.scrollMarketValue = prevScrollValue;
            BotInventoryManager.scrollCombatCeiling = prevCeiling;
            BotInventoryManager.makerCrystalFromLeftover = prevLeftover;
            BotInventoryManager.bestDropChance = prevDrop;
            BotInventoryManager.questItem = prevQuest;
            BotInventoryManager.untradeable = prevUntradeable;
            BotInventoryManager.makerSkillLevel = prevMaker;
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
