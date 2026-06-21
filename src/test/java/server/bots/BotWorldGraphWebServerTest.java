package server.bots;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotWorldGraphWebServerTest {

    @Test
    void graphJson_serialisesNodesWithPositionsAndEdges() {
        List<BotWorldGraphWebServer.GNode> nodes = List.of(
                new BotWorldGraphWebServer.GNode(1, "Henesys", 0, 0, false),
                new BotWorldGraphWebServer.GNode(2, "Ellinia", 120, -80, true),  // unreturnable
                new BotWorldGraphWebServer.GNode(3, "3", 240, 0, false)); // no name -> id fallback by caller
        List<int[]> edges = List.of(new int[]{1, 2}, new int[]{1, 3});

        String json = BotWorldGraphWebServer.graphJson(nodes, edges);

        assertTrue(json.contains("{\"id\":1,\"name\":\"Henesys\",\"x\":0,\"y\":0,\"danger\":false}"), json);
        assertTrue(json.contains("{\"id\":2,\"name\":\"Ellinia\",\"x\":120,\"y\":-80,\"danger\":true}"), json);
        assertTrue(json.contains("{\"id\":3,\"name\":\"3\",\"x\":240,\"y\":0,\"danger\":false}"), json);
        assertTrue(json.contains("[1,2]"), json);
        assertTrue(json.contains("[1,3]"), json);
        assertEquals(2, countPairs(json), json);
    }

    @Test
    void nearestFreeCell_returnsRequestedWhenFreeThenDistinctFreeCellsWhenTaken() {
        Set<Long> occ = new HashSet<>();
        // free cell -> returned as-is
        assertEquals(BotWorldGraphWebServer.cellKey(5, 5), BotWorldGraphWebServer.nearestFreeCell(occ, 5, 5));

        occ.add(BotWorldGraphWebServer.cellKey(0, 0));
        long a = BotWorldGraphWebServer.nearestFreeCell(occ, 0, 0);
        assertNotEquals(BotWorldGraphWebServer.cellKey(0, 0), a);
        assertFalse(occ.contains(a));
        occ.add(a);
        long b = BotWorldGraphWebServer.nearestFreeCell(occ, 0, 0);
        assertNotEquals(a, b);          // never reuses an occupied cell
        assertFalse(occ.contains(b));
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

    private static int countPairs(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[\\d+,\\d+\\]").matcher(json);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
