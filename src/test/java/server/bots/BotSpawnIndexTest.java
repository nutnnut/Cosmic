package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test over the real WZ tree (no DB needed). Ground truth for 104040000
 * (Henesys Hunting Ground I): 39 visible mob spawn points — 8x Slime(100101),
 * 10x 120100, 12x Orange Mushroom(130101), 9x Pig(1210100) — verified by grepping
 * the map XML's life node directly.
 */
class BotSpawnIndexTest {

    @Test
    void shouldIndexKnownGrindMapFromWz() {
        BotSpawnIndex.Index index = BotSpawnIndex.get();

        assertTrue(index.byMap().size() > 4000, "expected thousands of maps, got " + index.byMap().size());

        BotSpawnIndex.MapSpawns henesysHg1 = index.byMap().get(104040000);
        assertNotNull(henesysHg1);
        assertFalse(henesysHg1.town());
        assertEquals(12, henesysHg1.mobCounts().get(130101));
        assertEquals(39, henesysHg1.totalSpawnPoints());

        assertTrue(BotSpawnIndex.spawnSites(130101).stream()
                        .anyMatch(s -> s.mapId() == 104040000 && s.spawnPoints() == 12),
                "reverse index should list 104040000 as an Orange Mushroom site");

        // Towns carry the flag (Henesys).
        BotSpawnIndex.MapSpawns henesys = index.byMap().get(100000000);
        assertNotNull(henesys);
        assertTrue(henesys.town());
    }
}
