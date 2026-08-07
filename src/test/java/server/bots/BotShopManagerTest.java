package server.bots;

import client.BuffStat;
import client.Client;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.server.channel.Channel;
import server.Shop;
import server.ShopFactory;
import server.ShopItem;
import server.life.NPC;
import server.maps.MapManager;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotShopManagerTest {
    @Test
    void shouldPreferLeafreSlyDepartmentStoreByNeedCoverageNotLocation() {
        int field = 240_030_100;
        int localShopMapId = 240_030_106;
        int returnTown = 240_000_000;
        int townShopMapId = 240_000_002;

        Character bot = bowBotWithArrows(0);
        MapleMap currentMap = bot.getMap();
        MapleMap townMap = mock(MapleMap.class);
        MapleMap localShopMap = shopMap(9100001);
        MapleMap townShopMap = shopMap(2080001); // Sly
        Shop localShop = shopWithItems(2060000);
        Shop townShop = shopWithItems(2060000, 2030000);
        Client client = mock(Client.class);
        Channel channel = mock(Channel.class);
        MapManager maps = mock(MapManager.class);

        when(bot.getId()).thenReturn(777001);
        when(bot.getMapId()).thenReturn(field);
        when(bot.getClient()).thenReturn(client);
        when(currentMap.getId()).thenReturn(field);
        when(currentMap.getReturnMap()).thenReturn(townMap);
        when(townMap.getId()).thenReturn(returnTown);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(maps);
        when(maps.getMap(localShopMapId)).thenReturn(localShopMap);
        when(maps.getMap(townShopMapId)).thenReturn(townShopMap);

        try (MockedStatic<BotAutopilotManager> routes =
                     mockStatic(BotAutopilotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotAttackExecutionProvider> attacks =
                     mockStatic(BotAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotPotionManager> potions = mockStatic(BotPotionManager.class);
             MockedStatic<BotInventoryManager> inventories =
                     mockStatic(BotInventoryManager.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(9100001)).thenReturn(localShop);
            when(factory.getShopForNPC(2080001)).thenReturn(townShop);
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.BOW);
            potions.when(() -> BotPotionManager.countPotions(bot)).thenReturn(new int[]{9999, 9999});
            inventories.when(() -> BotInventoryManager.isRecoveryPotion(anyInt())).thenReturn(false);
            routes.when(() -> BotAutopilotManager.reachableForBot(eq(bot), anyInt(), anyInt(), any()))
                    .thenAnswer(inv -> {
                        int from = inv.getArgument(1);
                        int hops = inv.getArgument(2);
                        if (from == field) {
                            return hops >= 6 ? Set.of(field, localShopMapId) : Set.of(field);
                        }
                        if (from == returnTown) {
                            return hops >= 2 ? Set.of(returnTown, townShopMapId) : Set.of(returnTown);
                        }
                        return Set.of(from);
                    });
            routes.when(() -> BotAutopilotManager.routeForBot(eq(bot), eq(field), anyInt(), anyInt(), any()))
                    .thenAnswer(inv -> {
                        int to = inv.getArgument(2);
                        if (to == localShopMapId) return List.of(1, 2, 3, 4, 5, localShopMapId);
                        if (to == townShopMapId) return List.of(11, 12, 13, 14, 15, 16, 17, returnTown, 240000001, townShopMapId);
                        return null;
                    });

            assertEquals(townShopMapId, BotShopManager.findNearestShopMap(bot, false));
        }
    }

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
    void shouldSellWholePlannedAmmoStackWithNoPartyReserveCarveOut() throws Exception {
        // Party-arrow reserve removed: a stack the planner put on the sell list is shed whole; ammo
        // reserves are now decided upstream by the runway/shelf model, not a per-item quantity guard.
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
                List.class, Set.class, List.class, Class.forName("server.bots.BotShopManager$BuyReport"));
        runSellTrashStep.setAccessible(true);

        try (MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
             MockedStatic<BotManager> managers =
                     mockStatic(BotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            managers.when(() -> BotManager.after(anyLong(), any(Runnable.class))).thenReturn(null);

            runSellTrashStep.invoke(null, entry, bot, npcPos, 0, new ArrayList<String>(), new HashSet<Item>(),
                    List.of(arrows), new HashSet<Item>(), List.of(), null);
        }

        verify(shop).sell(any(), eq(InventoryType.USE), eq(slot), eq((short) 7_000));
    }

    @Test
    void shouldSellTrashAtEndOfAnyShopVisitEvenWhenNotFlaggedForSelling() throws Exception {
        // SSOT: a plain resupply visit (shopSellTrashPending = false) must still unload trash once
        // the purchase actions are done, as long as there is sellable junk. Drive runPurchaseStep
        // to its terminal index and verify it schedules the sell step (500ms cadence).
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        BotEntry entry = new BotEntry(bot, null, null);
        Point npcPos = new Point(20, 0);
        NPC npc = shopNpc(npcPos);
        Shop shop = mock(Shop.class);

        entry.shopVisitPending = true;
        entry.shopSequenceActive = true;
        entry.shopSellTrashPending = false; // incidental visit, not an explicit sell request
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(20, 0));
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));

        Class<?> buyReport = Class.forName("server.bots.BotShopManager$BuyReport");
        Class<?> purchaseSequence = Class.forName("server.bots.BotShopManager$PurchaseSequence");
        var seqCtor = purchaseSequence.getDeclaredConstructor(
                BotEntry.class, Character.class, Point.class, List.class, List.class, buyReport);
        seqCtor.setAccessible(true);
        Object sequence = seqCtor.newInstance(
                entry, bot, npcPos, List.of(), new ArrayList<String>(), null);

        Method runPurchaseStep = BotShopManager.class.getDeclaredMethod(
                "runPurchaseStep", purchaseSequence, int.class);
        runPurchaseStep.setAccessible(true);

        try (MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
             MockedStatic<BotInventoryManager> inventories = mockStatic(BotInventoryManager.class);
             MockedStatic<BotManager> managers =
                     mockStatic(BotManager.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            inventories.when(() -> BotInventoryManager.collectSellTrashItems(entry, bot))
                    .thenReturn(List.of(mock(Item.class)));
            managers.when(() -> BotManager.after(anyLong(), any(Runnable.class))).thenReturn(null);

            runPurchaseStep.invoke(null, sequence, 0); // index 0 >= 0 actions -> terminal sell tail

            // Scheduling the 500ms sell step (SELL_TRASH_STEP_DELAY_MS) proves the sell tail ran
            // despite shopSellTrashPending = false. The old gated code would have finished the
            // purchase here instead, never touching the sell path.
            managers.verify(() -> BotManager.after(eq(500L), any(Runnable.class)));
        }
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

    private static MapleMap shopMap(int npcId) {
        MapleMap map = mock(MapleMap.class);
        NPC npc = mock(NPC.class);
        when(npc.hasShop()).thenReturn(true);
        when(npc.getId()).thenReturn(npcId);
        when(npc.getPosition()).thenReturn(new Point(0, 0));
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        return map;
    }

    private static Shop shopWithItems(int... itemIds) {
        Shop shop = mock(Shop.class);
        List<ShopItem> items = new ArrayList<>();
        for (int itemId : itemIds) {
            ShopItem item = mock(ShopItem.class);
            when(item.getItemId()).thenReturn(itemId);
            when(item.getPrice()).thenReturn(itemId == 2030000 ? 400 : 1);
            items.add(item);
        }
        when(shop.getItems()).thenReturn(items);
        return shop;
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
