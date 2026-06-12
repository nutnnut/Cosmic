package server;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EquipStatsDiskCacheTest {

    @Test
    void shouldRoundTripEquipStats() {
        Map<Integer, Map<String, Integer>> entries = new LinkedHashMap<>();
        Map<String, Integer> umbrella = new LinkedHashMap<>();
        umbrella.put("MAD", 85);
        umbrella.put("reqJob", 0);
        umbrella.put("reqLevel", 70);
        umbrella.put("tuc", 7);
        entries.put(1302016, umbrella);
        Map<String, Integer> empty = new LinkedHashMap<>();
        entries.put(1002001, empty);

        List<String> lines = EquipStatsDiskCache.serialize(entries);
        assertEquals(entries, EquipStatsDiskCache.parse(lines));
    }

    @Test
    void shouldSerializeSortedByItemId() {
        Map<Integer, Map<String, Integer>> entries = new LinkedHashMap<>();
        entries.put(2000000, Map.of("STR", 1));
        entries.put(1000000, Map.of("DEX", 2));
        List<String> lines = EquipStatsDiskCache.serialize(entries);
        assertEquals("1000000\tDEX=2", lines.get(0));
        assertEquals("2000000\tSTR=1", lines.get(1));
    }
}
