package server.bots;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import server.bots.BotAutopilotManager.PartyInputs;
import server.bots.BotGrindPlanner.GearProspect;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.PartyScoring;
import server.maps.MapleMap;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The "autopilot debug" report: rendered straight off the same {@link BotGrindPlanner#scorePartyBest}
 * intermediate the live decider takes its plan from. These tests prove (1) the dump is non-empty and
 * carries every section, and (2) the pick it reports is exactly the production {@code planPartyBest}
 * pick for the same inputs (debug path and real path agree, no forked scoring).
 */
class BotAutopilotDebugTest {

    private static final int WEAPON_MAP = 20;
    private static final int CAPE_MAP = 10;

    private static MobCandidate mob(int mobId, int exp, int mapId, List<GearProspect> drops) {
        return new MobCandidate(mobId, "mob" + mobId, 40, exp, 2.0, mapId, "map" + mapId, 8, drops);
    }

    /** A mock bot with an empty EQUIPPED inventory (composition shows weapon=none) and plain stats. */
    private static Character bot(String name, int mapId) {
        Character c = mock(Character.class);
        when(c.getName()).thenReturn(name);
        when(c.getLevel()).thenReturn(65);
        when(c.getMapId()).thenReturn(mapId);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapName()).thenReturn("map" + mapId);
        when(c.getMap()).thenReturn(map);
        Inventory equipped = mock(Inventory.class);
        when(equipped.list()).thenReturn(List.of());
        when(c.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        return c;
    }

    @Test
    void reportIsNonEmptyAndPickMatchesProductionPlan() {
        // Member 0: a weapon prospect (high score) on WEAPON_MAP. Member 1: a stat-scroll cape on
        // CAPE_MAP. The weapon map should dominate the party sum so the pick is deterministic.
        GearProspect skanda = new GearProspect(1472000, "Maple Skanda", 0.02, 60.0, 0.30);
        GearProspect cape = new GearProspect(1102000, "Cape STR Scroll", 0.02, 8.0, 0.05);

        List<List<MobCandidate>> perMember = List.of(
                List.of(mob(1, 80, WEAPON_MAP, List.of(skanda)), mob(2, 80, CAPE_MAP, List.of())),
                List.of(mob(3, 80, CAPE_MAP, List.of(cape)), mob(4, 80, WEAPON_MAP, List.of())));
        List<IntToDoubleFunction> weights = List.of(m -> 1.0, m -> 1.0);

        BotEntry e0 = new BotEntry(bot("Clawer", WEAPON_MAP), null, null);
        BotEntry e1 = new BotEntry(bot("Buddy", CAPE_MAP), null, null);
        List<BotEntry> members = List.of(e0, e1);
        PartyInputs in = new PartyInputs(Set.of(WEAPON_MAP, CAPE_MAP), weights, perMember);

        int seed = 7;
        PartyScoring scoring = BotGrindPlanner.scorePartyBest(perMember, weights, new Random(seed));
        assertNotNull(scoring);
        BotGrindPlanner.PartyPlan prod = BotGrindPlanner.planPartyBest(perMember, weights, new Random(seed));

        // ii is only dereferenced for worn equips; with empty EQUIPPED inventories it is never
        // touched, so null keeps the test free of the DB-backed ItemInformationProvider.
        String report = BotAutopilotDebug.renderReport(null, e0.bot, members, in, scoring);

        assertNotNull(report);
        assertTrue(report.length() > 200, "report should be substantial");
        assertTrue(report.contains("a. PARTY COMPOSITION"), report);
        assertTrue(report.contains("b. REACHABILITY"), report);
        assertTrue(report.contains("c. PER-MEMBER CANDIDATES"), report);
        assertTrue(report.contains("d. PARTY SUM"), report);
        assertTrue(report.contains("e. DECISION"), report);
        // The weapon prospect is rendered next to the others so its valuation is visible.
        assertTrue(report.contains("Maple Skanda"), report);
        assertTrue(report.contains("Cape STR Scroll"), report);
        // Debug path and real path agree on the chosen map.
        assertEquals(prod.mapId(), scoring.plan().mapId());
        assertTrue(report.contains("chosen map: " + prod.mapId()), report);
    }

    @Test
    void soloFallbackStillRenders() {
        // No gear anywhere -> pure exp; a single-member "party" must still produce a full report.
        List<List<MobCandidate>> perMember = List.of(
                List.of(mob(1, 50, CAPE_MAP, List.of()), mob(2, 200, WEAPON_MAP, List.of())));
        List<IntToDoubleFunction> weights = List.of(m -> 1.0);
        BotEntry e0 = new BotEntry(bot("Solo", WEAPON_MAP), null, null);
        PartyInputs in = new PartyInputs(Set.of(WEAPON_MAP, CAPE_MAP), weights, perMember);
        PartyScoring scoring = BotGrindPlanner.scorePartyBest(perMember, weights, new Random(1));

        String report = BotAutopilotDebug.renderReport(null, e0.bot, List.of(e0), in, scoring);
        assertNotNull(report);
        assertTrue(report.contains("e. DECISION"), report);
        assertTrue(report.contains("chosen map: " + WEAPON_MAP), report);
    }
}
