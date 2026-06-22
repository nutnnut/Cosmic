package server.bots;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotWorldGraphWebServerTest {

    @Test
    void graphJson_serialisesNodesWithPositionsAndEdges() {
        List<BotWorldGraphWebServer.GNode> nodes = List.of(
                new BotWorldGraphWebServer.GNode(1, "Henesys", 0, 0, false, false, true),  // anchor hub
                new BotWorldGraphWebServer.GNode(2, "Ellinia", 120, -80, true, false, false),  // unreturnable
                new BotWorldGraphWebServer.GNode(3, "3", 240, 0, false, true, false)); // dead-end leaf, id fallback
        List<int[]> edges = List.of(new int[]{1, 2, 0}, new int[]{1, 3, 1}); // portal, taxi/ferry

        String json = BotWorldGraphWebServer.graphJson(nodes, edges);

        assertTrue(json.contains("{\"id\":1,\"name\":\"Henesys\",\"x\":0,\"y\":0,\"danger\":false,\"leaf\":false,\"anchor\":true}"), json);
        assertTrue(json.contains("{\"id\":2,\"name\":\"Ellinia\",\"x\":120,\"y\":-80,\"danger\":true,\"leaf\":false,\"anchor\":false}"), json);
        assertTrue(json.contains("{\"id\":3,\"name\":\"3\",\"x\":240,\"y\":0,\"danger\":false,\"leaf\":true,\"anchor\":false}"), json);
        assertTrue(json.contains("[1,2,\"p\"]"), json);
        assertTrue(json.contains("[1,3,\"t\"]"), json);
        assertEquals(2, countPairs(json), json);
    }

    @Test
    void liveJson_bucketsBotsAndPlayersByMapSortedById() {
        Character botA = onMap(100, "BotA", true);
        Character playerB = onMap(100, "PlayerB", false);
        Character botC = onMap(200, "BotC", true);

        String json = BotWorldGraphWebServer.liveJson(List.of(botA, playerB, botC));

        assertEquals(
                "{\"maps\":{"
                        + "\"100\":{\"players\":[{\"n\":\"PlayerB\",\"l\":10,\"j\":\"BEGINNER\"}],"
                        + "\"bots\":[{\"n\":\"BotA\",\"l\":10,\"j\":\"BEGINNER\"}]},"
                        + "\"200\":{\"players\":[],\"bots\":[{\"n\":\"BotC\",\"l\":10,\"j\":\"BEGINNER\"}]}"
                        + "}}",
                json);
    }

    private static Character onMap(int mapId, String name, boolean bot) {
        Character chr = mock(Character.class);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getName()).thenReturn(name);
        when(chr.getLevel()).thenReturn(10);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getClient()).thenReturn(bot ? mock(BotClient.class) : mock(Client.class));
        return chr;
    }

    private static int countPairs(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[\\d+,\\d+,\"[pt]\"\\]").matcher(json);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
