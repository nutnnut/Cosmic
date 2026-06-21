package server.bots;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotWorldGraphWebServerTest {

    @Test
    void graphJson_dedupesUndirectedEdgesSkipsSelfLoopsAndFallsBackToId() {
        Map<Integer, int[]> edges = Map.of(
                1, new int[]{2, 3},
                2, new int[]{1},        // (2,1) duplicates (1,2)
                5, new int[]{5});       // self-loop, dropped
        Map<Integer, String> names = Map.of(1, "Henesys", 2, "Ellinia");

        String json = BotWorldGraphWebServer.graphJson(edges, names);

        // nodes: 1,2,3,5  (3 and 5 have no name -> id fallback)
        assertEquals(4, count(json, "\"id\":"), json);
        assertTrue(json.contains("{\"id\":1,\"name\":\"Henesys\"}"), json);
        assertTrue(json.contains("{\"id\":3,\"name\":\"3\"}"), json);
        // edges: exactly (1,2) and (1,3), sorted lo->hi, no self-loop
        assertTrue(json.contains("[1,2]"), json);
        assertTrue(json.contains("[1,3]"), json);
        assertFalse(json.contains("[5,5]"), json);
        assertEquals(2, countPairs(json), json); // only edge pairs match [d,d]; node objects don't
    }

    @Test
    void liveJson_bucketsBotsAndPlayersByMapSortedById() {
        Character botA = onMap(100, "BotA", true);
        Character playerB = onMap(100, "PlayerB", false);
        Character botC = onMap(200, "BotC", true);

        String json = BotWorldGraphWebServer.liveJson(List.of(botA, playerB, botC));

        assertEquals(
                "{\"maps\":{"
                        + "\"100\":{\"players\":[\"PlayerB\"],\"bots\":[\"BotA\"]},"
                        + "\"200\":{\"players\":[],\"bots\":[\"BotC\"]}"
                        + "}}",
                json);
    }

    private static Character onMap(int mapId, String name, boolean bot) {
        Character chr = mock(Character.class);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(bot ? mock(BotClient.class) : mock(Client.class));
        return chr;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static int countPairs(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[\\d+,\\d+\\]").matcher(json);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
