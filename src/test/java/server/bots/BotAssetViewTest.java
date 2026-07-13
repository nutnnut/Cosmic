package server.bots;

import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.maps.PlayerShopItem;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAssetViewTest {

    private static Item item(int id, int qty) {
        return new Item(id, (short) 0, (short) qty);
    }

    private static Map<InventoryType, List<Item>> bags(List<Item> use) {
        Map<InventoryType, List<Item>> bags = new EnumMap<>(InventoryType.class);
        bags.put(InventoryType.USE, use);
        return bags;
    }

    @Test
    void tagsLocationsAndAvailability() {
        Item worn = item(1082089, 1);
        Item bagged = item(2040804, 5);
        Item listed = item(2070004, 10);
        Item stored = item(1432008, 1);
        Item escrowed = item(2044501, 1);
        Item leftover = item(1002140, 1);

        BotAssetView.Snapshot snap = BotAssetView.assemble(
                List.of(worn), bags(List.of(bagged)),
                List.of(new PlayerShopItem(listed, (short) 2, 50000)),
                List.of(stored), List.of(escrowed), List.of(leftover),
                0, 0, 0, 0);

        assertEquals(6, snap.assets().size(), "every container's items are visible");
        assertEquals(1, snap.at(BotAssetView.Location.EQUIPPED).size());
        assertEquals(1, snap.at(BotAssetView.Location.BAG).size());
        assertEquals(1, snap.at(BotAssetView.Location.STALL).size());
        assertEquals(1, snap.at(BotAssetView.Location.STORAGE).size());
        assertEquals(1, snap.at(BotAssetView.Location.TRADE_ESCROW).size());
        assertEquals(1, snap.at(BotAssetView.Location.FREDRICK).size());

        assertEquals(2, snap.inHand().size(), "only equipped+bag are in hand");
        assertTrue(snap.at(BotAssetView.Location.EQUIPPED).get(0).inHand());
        assertTrue(snap.at(BotAssetView.Location.BAG).get(0).inHand());
        assertFalse(snap.at(BotAssetView.Location.STALL).get(0).inHand(), "stall stock is not wearable/stageable");
        assertFalse(snap.at(BotAssetView.Location.STORAGE).get(0).inHand(), "stored items are not wearable/stageable");
        assertFalse(snap.at(BotAssetView.Location.TRADE_ESCROW).get(0).inHand());
        assertFalse(snap.at(BotAssetView.Location.FREDRICK).get(0).inHand());
    }

    @Test
    void stallQuantityExpandsBundlesAndSkipsSoldOut() {
        PlayerShopItem live = new PlayerShopItem(item(2070004, 10), (short) 3, 50000);
        PlayerShopItem soldOut = new PlayerShopItem(item(2040804, 1), (short) 0, 550000);
        PlayerShopItem removed = new PlayerShopItem(item(2040805, 1), (short) 4, 1100000);
        removed.setDoesExist(false);

        BotAssetView.Snapshot snap = BotAssetView.assemble(
                List.of(), bags(List.of()), List.of(live, soldOut, removed),
                List.of(), List.of(), List.of(), 0, 0, 0, 0);

        List<BotAssetView.Asset> stall = snap.at(BotAssetView.Location.STALL);
        assertEquals(1, stall.size(), "sold-out and delisted entries are excluded");
        assertEquals(30, stall.get(0).quantity(), "3 bundles x 10 per bundle");
    }

    @Test
    void wealthSumsEveryWallet() {
        BotAssetView.Snapshot snap = BotAssetView.assemble(
                List.of(), bags(List.of()), List.of(),
                List.of(), List.of(), List.of(),
                1_000_000, 250_000, 40_000, 9_000);

        assertEquals(1_000_000, snap.liquidMeso());
        assertEquals(250_000, snap.merchantMeso(), "stall earnings count toward wealth");
        assertEquals(40_000, snap.storageMeso());
        assertEquals(9_000, snap.tradeEscrowMeso());
        assertEquals(1_299_000, snap.totalMeso());
    }
}
