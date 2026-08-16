package server.bots;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** Arrival semantics end to end on a Zakum-shaped world. The maze (280010000) is arrivable ONLY
     *  because Adobis in the Door to Zakum lobby warps a party in; 280011000 by its own portal off the
     *  maze. The Room of Tragedy (280090000) is deliberately excluded: forcedReturn is a recovery route,
     *  not proof that a character can enter the map during normal world travel. A map that can only be
     *  LEFT (922000000, a Toy Factory sector that scrolls to a town but that nothing enters) is excluded
     *  by construction — no prune, no exclusion list. */
    @Test
    void arrivalClosure_admitsScriptEntriesButNotRecoveryOrLeaveOnlyMaps() {
        BotWorldGraph.Index idx = BotWorldGraph.indexOf(
                Map.of(211042300, new int[0],              // Door to Zakum: the spawn-reachable lobby
                        280010000, new int[]{280011000},   // maze entry: only EVENT_ENTRANCES reaches it
                        280011000, new int[]{280010000},   // deeper maze map, portalled off the entry
                        280090000, new int[0],             // Room of Tragedy: only a forcedReturn lands here
                        922000000, new int[0]),          // leave-only: scrolls out, nothing comes in
                Map.of(280010000, 280090000, 922000000, 211042300),   // scroll targets (ways OUT)
                Map.of(280010000, 280090000, 280011000, 280090000));  // recovery routes, not entries

        Set<Integer> shown = BotWorldGraphWebServer.arrivalClosure(idx, Set.of(211042300));

        assertEquals(Set.of(211042300, 280010000, 280011000), shown);
    }

    /** The Zakum row is the one the whole arrival model was built for, so pin it against the script the
     *  table was verified from (scripts/event/ZakumPQ.js entryMap, recruiter 2030008 in 211042300). */
    @Test
    void eventEntrances_carryTheVerifiedZakumRow() {
        BotWorldGraph.EventEntrance zakum = BotWorldGraph.EVENT_ENTRANCES.stream()
                .filter(e -> e.eventScript().equals("ZakumPQ"))
                .findFirst().orElseThrow();

        assertEquals(2030008, zakum.npcId());
        assertEquals(211042300, zakum.lobbyMap());
        assertEquals(280010000, zakum.entryMap());
    }

    /** A leave-only map stays out even when its scroll target is itself arrival-only: scrolls are exits,
     *  never entrances, so no chain of them can pull a map onto the view. Synthetic ids on purpose —
     *  real ones would drag in the live taxi/PQ tables and stop testing just the scroll rule. */
    @Test
    void arrivalClosure_neverAdmitsThroughAScroll() {
        BotWorldGraph.Index idx = BotWorldGraph.indexOf(
                Map.of(555000000, new int[0],   // root
                        555000001, new int[0],   // dump map, itself unreached
                        555000002, new int[0]),  // leave-only: its only link out is a scroll
                Map.of(555000002, 555000001),                        // scrolls into the dump
                Map.of(555000001, 555000000));                       // dump's own forcedReturn

        Set<Integer> shown = BotWorldGraphWebServer.arrivalClosure(idx, Set.of(555000000));

        assertEquals(Set.of(555000000), shown);
    }

    /** Event entry/exit rows are visual relationships only; they must not be mistaken for bot route edges.
     *  A forcedReturn is neither a rendered edge nor an admission path. */
    @Test
    void worldMapEdges_renderVerifiedOrbisNpcAndEventRelationships() {
        BotWorldGraph.Index idx = BotWorldGraph.indexOf(
                Map.of(920010000, new int[0]), Map.of(), Map.of(920010000, 920011200));

        List<BotWorldGraphWebServer.WorldMapEdge> edges = BotWorldGraphWebServer.worldMapEdges(
                idx, Set.of(200080101, 920010000, 920011200));

        assertTrue(edges.contains(new BotWorldGraphWebServer.WorldMapEdge(200080101, 920010000, 'e')));
        assertTrue(edges.contains(new BotWorldGraphWebServer.WorldMapEdge(200080101, 920011200, 'n')));
        assertFalse(edges.contains(new BotWorldGraphWebServer.WorldMapEdge(920010000, 920011200, 'f')));
        assertFalse(edges.contains(new BotWorldGraphWebServer.WorldMapEdge(200080101, 920010000, 'n')));
    }

    @Test
    void arrivalClosure_doesNotAdmitAnEventExitOnlyMap() {
        BotWorldGraph.Index idx = BotWorldGraph.indexOf(
                Map.of(200080101, new int[0], 920010000, new int[0], 920011200, new int[0]),
                Map.of(), Map.of(920010000, 920011200));

        Set<Integer> shown = BotWorldGraphWebServer.arrivalClosure(idx, Set.of(200080101));

        assertTrue(shown.contains(920010000)); // verified Orbis event entry
        assertFalse(shown.contains(920011200)); // verified NPC exit, not an entry
    }

    @Test
    void layoutAdjacency_usesEventEntriesButNotEventExitsOrForcedReturns() {
        Set<Integer> reachable = Set.of(200080101, 920010000, 920011200);
        BotWorldGraphWebServer.GraphData g = new BotWorldGraphWebServer.GraphData(
                reachable, List.of(), Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of());

        Map<Integer, List<Integer>> adjacency = BotWorldGraphWebServer.layoutAdjacency(g);

        assertEquals(List.of(920010000), adjacency.get(200080101));
        assertEquals(List.of(200080101), adjacency.get(920010000));
        assertFalse(adjacency.containsKey(920011200));
    }

    @Test
    void eventExits_carryTheVerifiedOrbisNpcRow() {
        BotWorldGraph.EventExit orbis = BotWorldGraph.EVENT_EXITS.stream()
                .filter(e -> e.eventScript().equals("OrbisPQ"))
                .findFirst().orElseThrow();

        assertEquals(2013001, orbis.npcId());
        assertEquals(920011200, orbis.fromMap());
        assertEquals(200080101, orbis.toMap());
    }

    @Test
    void eventExitRow_matchesTheCurrentOrbisEventAndNpcScripts() throws IOException {
        String event = Files.readString(Path.of("scripts/event/OrbisPQ.js"));
        String npc = Files.readString(Path.of("scripts/npc/2013001.js"));

        assertTrue(event.contains("var exitMap = 920011200;"));
        assertTrue(event.contains("player.changeMap(exitMap, 0);"));
        assertTrue(npc.contains("cm.getPlayer().getMapId() == 920011200"));
        assertTrue(npc.contains("cm.warp(200080101);"));
    }

    @Test
    void worldMapEdges_hidesTheRealOrbisForcedReturnData() {
        BotWorldGraph.Index idx = BotWorldGraph.get();

        assertEquals(920011200, idx.forcedReturn(920010000));
        assertFalse(BotWorldGraphWebServer.worldMapEdges(
                idx, Set.of(200080101, 920010000, 920011200))
                .contains(new BotWorldGraphWebServer.WorldMapEdge(920010000, 920011200, 'f')));
    }

    @Test
    void worldMapJson_omitsTheUnreachableOrbisExitNode() {
        String json = BotWorldGraphWebServer.worldGraphJson();

        assertFalse(json.contains("\"maps\":[920011200]"));
        assertFalse(json.contains("920011200"));
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
