package server.bots;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotWorldGraphWebServerTest {

    @Test
    void liveJson_bucketsBotsAndPlayersByMapWithIdAndCommandableFlag() {
        Character botA = onMap(11, 100, "BotA", true);
        Character playerB = onMap(22, 100, "PlayerB", false);
        Character botC = onMap(33, 200, "BotC", true);

        String json = BotWorldGraphWebServer.liveJson(List.of(botA, playerB, botC));

        // id is carried for targeting; c (commandable) is 0 here — no real BotEntry is registered, so
        // the managed-bot lookup degrades to false (the live server fills it from the bot registry).
        assertEquals(
                "{\"maps\":{"
                        + "\"100\":{\"players\":[{\"id\":22,\"n\":\"PlayerB\",\"l\":10,\"j\":\"BEGINNER\",\"c\":0,\"p\":0,\"g\":0}],"
                        + "\"bots\":[{\"id\":11,\"n\":\"BotA\",\"l\":10,\"j\":\"BEGINNER\",\"c\":0,\"p\":0,\"g\":0}]},"
                        + "\"200\":{\"players\":[],\"bots\":[{\"id\":33,\"n\":\"BotC\",\"l\":10,\"j\":\"BEGINNER\",\"c\":0,\"p\":0,\"g\":0}]}"
                        + "}}",
                json);
    }

    // Chain a-b-c-d (1-2-3-4); maps b+c are merged into one clicked node. The per-bot MOVE rule picks
    // the constituent map nearest the bot, and prefers a hub when one is present.
    private static final Map<Integer, List<Integer>> CHAIN = Map.of(
            1, List.of(2), 2, List.of(1, 3), 3, List.of(2, 4), 4, List.of(3));

    @Test
    void resolveMoveTarget_picksNearestConstituentMap() {
        assertEquals(2, BotWorldGraphWebServer.resolveMoveTargetFrom(CHAIN, Set.of(), 1, List.of(2, 3)));
        assertEquals(3, BotWorldGraphWebServer.resolveMoveTargetFrom(CHAIN, Set.of(), 4, List.of(2, 3)));
    }

    @Test
    void resolveMoveTarget_prefersHubOverNearest() {
        // from map 1, nearest is 2, but 3 is a hub -> 3 wins
        assertEquals(3, BotWorldGraphWebServer.resolveMoveTargetFrom(CHAIN, Set.of(3), 1, List.of(2, 3)));
    }

    @Test
    void resolveMoveTarget_singleMapIsTrivial() {
        assertEquals(9, BotWorldGraphWebServer.resolveMoveTargetFrom(CHAIN, Set.of(), 1, List.of(9)));
    }

    @Test
    void worldMapImageAvailability_matchesServedPngFolder() {
        assertEquals(true, BotWorldGraphWebServer.hasWorldMapImage("000"));
        assertEquals(false, BotWorldGraphWebServer.hasWorldMapImage("999999"));
    }

    private static Character onMap(int id, int mapId, String name, boolean bot) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getName()).thenReturn(name);
        when(chr.getLevel()).thenReturn(10);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getClient()).thenReturn(bot ? mock(BotClient.class) : mock(Client.class));
        return chr;
    }
}
