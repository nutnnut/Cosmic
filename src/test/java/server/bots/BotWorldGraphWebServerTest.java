package server.bots;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotWorldGraphWebServerTest {

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
}
