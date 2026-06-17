package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WZ-free test for the NPC->map reverse index ({@link BotSpawnIndex#withMobIndex}) that lets a bot
 *  resolve where a quest NPC stands and walk to it. */
class BotSpawnIndexNpcTest {

    private static BotSpawnIndex.MapSpawns map(int id, List<Integer> npcs) {
        return new BotSpawnIndex.MapSpawns(id, false, 0, Map.of(), npcs);
    }

    @Test
    void reverseIndexGroupsMapsByNpc() {
        Map<Integer, BotSpawnIndex.MapSpawns> byMap = Map.of(
                10000, map(10000, List.of(2100, 2101, 2102)),       // Mushroom Town tutorial NPCs
                104000000, map(104000000, List.of(2101)));          // 2101 also stands here (multi-map)

        BotSpawnIndex.Index idx = BotSpawnIndex.withMobIndex(byMap);

        assertEquals(List.of(10000), idx.mapsByNpc().get(2100), "single-map NPC resolves to its one map");
        assertEquals(List.of(10000), idx.mapsByNpc().get(2102));
        List<Integer> for2101 = idx.mapsByNpc().get(2101);
        assertEquals(2, for2101.size(), "multi-map NPC lists every hosting map");
        assertTrue(for2101.containsAll(List.of(10000, 104000000)));
        assertNull(idx.mapsByNpc().get(9999), "an unplaced NPC has no entry");
    }

    @Test
    void mapWithNoNpcsContributesNothing() {
        BotSpawnIndex.Index idx = BotSpawnIndex.withMobIndex(Map.of(
                100000000, map(100000000, List.of())));
        assertTrue(idx.mapsByNpc().isEmpty());
    }
}
