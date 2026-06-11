package server.bots;

import client.BuffStat;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Shop;
import server.ShopFactory;
import server.life.NPC;
import server.maps.MapleMap;
import testutil.Items;

import java.awt.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotShopManagerTest {
    @Test
    void shouldNotTriggerClawShopVisitWhenBestStarIsAboveThreshold() {
        Character bot = clawBotWithStars(800, 1000, 1000, 1000, 1000, 1000); // 5800 of the best star
        BotEntry entry = new BotEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        try (Seam seam = withStarStats();
             MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            BotShopManager.onMapChange(entry, bot);
        }

        assertFalse(entry.shopVisitPending);
    }

    @Test
    void shouldTriggerClawShopVisitWhenBestStarIsBelowThreshold() {
        Character bot = clawBotWithStars(800, 1000, 1000); // 2800 of the best star
        BotEntry entry = new BotEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        try (Seam seam = withStarStats();
             MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            BotShopManager.onMapChange(entry, bot);
        }

        assertTrue(entry.shopVisitPending);
    }

    @Test
    void shouldTriggerRechargeForDepletedBestStarMaskedByWeakerStars() {
        // Plenty of weak stars (id 2070000) plus a low stack of the BEST star (id 2070018).
        // Total (5800) is above the trigger, but the best star (800) is below it.
        Character bot = clawBotWithStarItems(new int[]{2070000, 2070000, 2070000, 2070000, 2070000},
                new int[]{1000, 1000, 1000, 1000, 1000});
        bot.getInventory(InventoryType.USE).addItem(Items.itemWithQuantity(2070018, 800));

        try (Seam seam = withStarStats()) {
            assertTrue(entryWouldTriggerShopVisit(bot, WeaponType.CLAW));
            assertTrue(BotShopManager.shouldRechargeWhileShopping(bot, WeaponType.CLAW));
        }
    }

    @Test
    void shouldNotRechargeWhenBestStarIsHealthyDespiteLowWeakerStars() {
        // Best star (2070018) is full and above threshold; a near-empty weak star must not trigger.
        Character bot = clawBotWithStarItems(new int[]{2070000, 2070018}, new int[]{50, 5000});

        try (Seam seam = withStarStats()) {
            assertFalse(entryWouldTriggerShopVisit(bot, WeaponType.CLAW));
            assertFalse(BotShopManager.shouldRechargeWhileShopping(bot, WeaponType.CLAW));
        }
    }

    @Test
    void shouldBuyArrowsWhileShoppingWhenBelowTargetButAboveTrigger() {
        Character bot = bowBotWithArrows(4500);

        assertFalse(entryWouldTriggerShopVisit(bot, WeaponType.BOW));
        assertTrue(BotShopManager.shouldBuyFixedAmmoWhileShopping(bot, WeaponType.BOW));
    }

    @Test
    void shouldWantReturnScrollsBelowTenWithoutTriggeringShopVisitByItself() {
        Character bot = bowBotWithArrows(5000);
        bot.getInventory(InventoryType.USE).addItem(Items.itemWithQuantity(2030000, 3));

        assertTrue(BotShopManager.shouldBuyReturnScrollWhileShopping(bot));
        assertFalse(entryWouldTriggerShopVisit(bot, WeaponType.BOW));
    }

    @Test
    void shouldNotWantReturnScrollsAtTen() {
        Character bot = bowBotWithArrows(5000);
        bot.getInventory(InventoryType.USE).addItem(Items.itemWithQuantity(2030000, 10));

        assertFalse(BotShopManager.shouldBuyReturnScrollWhileShopping(bot));
    }

    @Test
    void shouldTriggerSellTrashShopVisitEvenWhenNoResupplyIsNeeded() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        BotEntry entry = new BotEntry(bot, null, null);
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(new Inventory(bot, InventoryType.USE, (byte) 24));
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
             MockedStatic<BotInventoryManager> inventories = mockStatic(BotInventoryManager.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});
            inventories.when(() -> BotInventoryManager.collectSellTrashItems(entry, bot))
                    .thenReturn(List.of(mock(Item.class)));

            BotShopManager.requestSellTrashVisit(entry, bot);
        }

        assertTrue(entry.shopVisitPending);
        assertTrue(entry.shopSellTrashPending);
        assertEquals(new Point(20, 0), entry.shopNpcPos);
    }

    @Test
    void shouldAutoTriggerSellTrashVisitWhenEtcTabIsCramped() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        BotEntry entry = new BotEntry(bot, null, null);
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        Inventory etc = new Inventory(bot, InventoryType.ETC, (byte) 3); // 2 free slots -> cramped
        etc.addItem(Items.itemWithQuantity(4000000, 50));
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(new Inventory(bot, InventoryType.USE, (byte) 24));
        when(bot.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
             MockedStatic<BotInventoryManager> inventories = mockStatic(BotInventoryManager.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});
            inventories.when(() -> BotInventoryManager.collectSellTrashEtcItems(bot))
                    .thenReturn(List.of(mock(Item.class)));

            BotShopManager.onMapChange(entry, bot);
        }

        assertTrue(entry.shopVisitPending);
        assertTrue(entry.shopSellTrashPending);
    }

    @Test
    void shouldNotAutoTriggerSellTrashVisitWithThreeFreeSlots() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, null, null);
        Inventory etc = new Inventory(bot, InventoryType.ETC, (byte) 4);
        etc.addItem(Items.itemWithQuantity(4000000, 50));
        when(bot.getInventory(InventoryType.EQUIP)).thenReturn(null);
        when(bot.getInventory(InventoryType.USE)).thenReturn(null);
        when(bot.getInventory(InventoryType.ETC)).thenReturn(etc);

        assertFalse(BotShopManager.shouldAutoSellTrash(entry, bot));
    }

    @Test
    void shouldSellOnlyEnhancedArrowExcessAbovePartyReserve() throws Exception {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        BotEntry entry = new BotEntry(bot, null, null);
        Point npcPos = new Point(20, 0);
        NPC npc = shopNpc(npcPos);
        Shop shop = mock(Shop.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        Item arrows = Items.itemWithQuantity(2061004, 7_000);
        short slot = use.addItem(arrows);

        entry.shopVisitPending = true;
        entry.shopSequenceActive = true;
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(20, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));

        Method runSellTrashStep = BotShopManager.class.getDeclaredMethod(
                "runSellTrashStep",
                BotEntry.class, Character.class, Point.class, int.class, List.class, Set.class,
                List.class, List.class, Class.forName("server.bots.BotShopManager$BuyReport"));
        runSellTrashStep.setAccessible(true);

        try (MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
             MockedStatic<BotManager> managers =
                     mockStatic(BotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            managers.when(() -> BotManager.after(anyLong(), any(Runnable.class))).thenReturn(null);

            runSellTrashStep.invoke(null, entry, bot, npcPos, 0, new ArrayList<String>(), new HashSet<Item>(),
                    List.of(arrows), List.of(), null);
        }

        verify(shop).sell(any(), eq(InventoryType.USE), eq(slot), eq((short) 2_000));
    }

    private static Character clawBotWithStars(int... quantities) {
        int[] ids = new int[quantities.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 2070000;
        }
        return clawBotWithStarItems(ids, quantities);
    }

    private static Character clawBotWithStarItems(int[] ids, int[] quantities) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        for (int i = 0; i < ids.length; i++) {
            use.addItem(Items.itemWithQuantity(ids[i], quantities[i]));
        }
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        return bot;
    }

    // Stub the ItemInformationProvider-backed seam: star 2070018 is the strongest, all stacks
    // are well under slot-max so any partial stack counts as refillable. Restored on close.
    private static Seam withStarStats() {
        IntUnaryOperator prevWatk = BotShopManager.projectileWatk;
        BotShopManager.SlotMaxLookup prevSlot = BotShopManager.ammoSlotMax;
        BotShopManager.projectileWatk = id -> id == 2070018 ? 50 : 10;
        BotShopManager.ammoSlotMax = (bot, id) -> (short) 10000;
        return new Seam(prevWatk, prevSlot);
    }

    private record Seam(IntUnaryOperator watk, BotShopManager.SlotMaxLookup slot) implements AutoCloseable {
        @Override
        public void close() {
            BotShopManager.projectileWatk = watk;
            BotShopManager.ammoSlotMax = slot;
        }
    }

    private static Character bowBotWithArrows(int quantity) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2060000, quantity));
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        return bot;
    }

    private static boolean entryWouldTriggerShopVisit(Character bot, WeaponType weaponType) {
        BotEntry entry = new BotEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.<server.ShopItem>of());

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(weaponType);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            BotShopManager.onMapChange(entry, bot);
        }

        return entry.shopVisitPending;
    }

    private static NPC shopNpc(Point position) {
        NPC npc = mock(NPC.class);
        when(npc.hasShop()).thenReturn(true);
        when(npc.getId()).thenReturn(9010000);
        when(npc.getPosition()).thenReturn(new Point(position));
        return npc;
    }
}
