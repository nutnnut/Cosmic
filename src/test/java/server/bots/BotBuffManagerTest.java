package server.bots;

import client.BuffStat;
import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapleMap;
import tools.Pair;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotBuffManagerTest {
    @Test
    void shouldOnlyUseMatkForMagesAndWatkForNonMages() {
        Character mage = mock(Character.class);
        when(mage.getJobStyle()).thenReturn(Job.MAGICIAN);

        Character nonMage = mock(Character.class);
        when(nonMage.getJobStyle()).thenReturn(Job.WARRIOR);

        assertTrue(BotBuffManager.isRelevantBuffStat(mage, BuffStat.MATK));
        assertFalse(BotBuffManager.isRelevantBuffStat(mage, BuffStat.WATK));

        assertFalse(BotBuffManager.isRelevantBuffStat(nonMage, BuffStat.MATK));
        assertTrue(BotBuffManager.isRelevantBuffStat(nonMage, BuffStat.WATK));

        assertTrue(BotBuffManager.isRelevantBuffStat(mage, BuffStat.ACC));
        assertTrue(BotBuffManager.isRelevantBuffStat(nonMage, BuffStat.ACC));
    }

    @Test
    void cheapCapSkipsAtkBuffsStrongerThanTwelve() {
        Character warrior = mock(Character.class);
        when(warrior.getJobStyle()).thenReturn(Job.WARRIOR);

        Character mage = mock(Character.class);
        when(mage.getJobStyle()).thenReturn(Job.MAGICIAN);

        // +12 WATK is the cap: kept.
        assertFalse(BotBuffManager.exceedsCheapAtkCap(warrior, fxWith(BuffStat.WATK, 12)));
        // +13 WATK exceeds the cap: skipped in cheap mode.
        assertTrue(BotBuffManager.exceedsCheapAtkCap(warrior, fxWith(BuffStat.WATK, 13)));
        // +20 MATK for a mage exceeds the cap.
        assertTrue(BotBuffManager.exceedsCheapAtkCap(mage, fxWith(BuffStat.MATK, 20)));

        // Irrelevant stat for the job is ignored: a warrior never cares about MATK.
        assertFalse(BotBuffManager.exceedsCheapAtkCap(warrior, fxWith(BuffStat.MATK, 99)));
        // Non-atk stats are never capped.
        assertFalse(BotBuffManager.exceedsCheapAtkCap(warrior, fxWith(BuffStat.ACC, 99)));
    }

    @Test
    void idleLeechPausesBuffPots() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.buffConsumablesEnabled = true;
        entry.idleLeech = true;

        BotBuffManager.tick(entry, bot);

        assertEquals("idle-leech: buff pots paused", entry.lastBuffActionSummary);
        verify(map, never()).getAllMonsters();
    }

    @Test
    void autoBuffEngagesOnToughMapDisengagesOnEasyAndRespectsManual() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, null, null);
        MapleMap map = mock(MapleMap.class);
        Monster mob = mock(Monster.class);
        when(bot.getMap()).thenReturn(map);
        when(map.getAllMonsters()).thenReturn(List.of(mob));
        when(mob.isAlive()).thenReturn(true);
        when(mob.getMaxHp()).thenReturn(300);

        try (MockedStatic<BotAutopilotManager> autopilot = mockStatic(BotAutopilotManager.class);
             MockedStatic<BotCombatManager> combat = mockStatic(BotCombatManager.class)) {
            autopilot.when(() -> BotAutopilotManager.isActive(entry)).thenReturn(true);

            // Tough map: 300 / 50 = 6 shots/kill >= 3 -> autopilot turns cheap buffs on.
            combat.when(() -> BotCombatManager.estimateBestSkillHitDamage(entry, bot, mob)).thenReturn(50.0);
            entry.lastAutoBuffEvalMs = 0;
            BotBuffManager.autoEngageForToughMobs(entry, bot);
            assertTrue(entry.buffConsumablesEnabled);
            assertTrue(entry.buffCheapMode);
            assertTrue(entry.autoBuffEngaged);

            // Easy map: 300 / 200 = 1.5 < 2.5 -> autopilot undoes its own enable.
            combat.when(() -> BotCombatManager.estimateBestSkillHitDamage(entry, bot, mob)).thenReturn(200.0);
            entry.lastAutoBuffEvalMs = 0;
            BotBuffManager.autoEngageForToughMobs(entry, bot);
            assertFalse(entry.buffConsumablesEnabled);
            assertFalse(entry.autoBuffEngaged);

            // Owner manually enabled (not auto-engaged): an easy map must NOT auto-disable it.
            entry.buffConsumablesEnabled = true;
            entry.autoBuffEngaged = false;
            entry.lastAutoBuffEvalMs = 0;
            BotBuffManager.autoEngageForToughMobs(entry, bot);
            assertTrue(entry.buffConsumablesEnabled);
        }
    }

    private static StatEffect fxWith(BuffStat stat, int value) {
        StatEffect fx = mock(StatEffect.class);
        when(fx.getStatups()).thenReturn(List.of(new Pair<>(stat, value)));
        return fx;
    }
}
