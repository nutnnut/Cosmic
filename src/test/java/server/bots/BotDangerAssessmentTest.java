package server.bots;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.bots.combat.BotDangerAssessment;
import server.combat.CombatFormulaProvider;
import server.life.Monster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class BotDangerAssessmentTest {

    // --- Pure verdict (WZ-free, no mocks): maxHp / touchDamage <= hitsToKill -----------------

    @Test
    void pureVerdict_dangerousWhenKilledWithinHitsToKill() {
        // 100 HP, 40 per contact hit -> dies in 2 hits -> dangerous at hitsToKill=3.
        assertTrue(BotDangerAssessment.isTouchDangerous(100, 40, 3));
        // Exactly hitsToKill hits is still dangerous (<=).
        assertTrue(BotDangerAssessment.isTouchDangerous(120, 40, 3));
        // One-shot is always dangerous.
        assertTrue(BotDangerAssessment.isTouchDangerous(50, 200, 3));
    }

    @Test
    void pureVerdict_safeWhenManyHitsToSurvive() {
        // 1000 HP, 40 per hit -> 25 hits to die -> safe at hitsToKill=3.
        assertFalse(BotDangerAssessment.isTouchDangerous(1000, 40, 3));
    }

    @Test
    void pureVerdict_neverDangerousWithoutDamageOrBadInputs() {
        assertFalse(BotDangerAssessment.isTouchDangerous(100, 0, 3));   // mob can't touch us
        assertFalse(BotDangerAssessment.isTouchDangerous(100, 40, 0));  // nonsensical knob
        assertFalse(BotDangerAssessment.isTouchDangerous(0, 40, 3));    // no HP pool known
    }

    @Test
    void pureVerdict_lowerHitsToKillIsMoreCautious() {
        // 100 HP, 34 dmg -> dies in 2 hits. Dangerous at hitsToKill=2, but the *same* fight is
        // tolerated by a tankier policy that only flags one-shots... here floor(100/34)=2.
        assertTrue(BotDangerAssessment.isTouchDangerous(100, 34, 2));
        assertFalse(BotDangerAssessment.isTouchDangerous(100, 34, 1)); // survives >1 hit
    }

    // --- Integration over the SSOT roll: high-PAD mob vs fragile bot vs geared bot -----------

    @Test
    void rollIntegration_highPadMobIsDangerousToFragileLowWdefBot() {
        try (MockedStatic<CombatFormulaProvider> cfp = mockStatic(CombatFormulaProvider.class)) {
            CombatFormulaProvider formula = mock(CombatFormulaProvider.class);
            cfp.when(CombatFormulaProvider::getInstance).thenReturn(formula);
            when(formula.doesMobHit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(true); // isolate damage magnitude from the hit/miss roll

            Monster mob = stubMob(700, 15);             // very high PAD for the level
            Character fragile = stubBot(Job.BEGINNER, 8, 200, 0); // low level, low WDEF, tiny HP pool

            int dmg = BotDangerAssessment.estimateMaxTouchDamage(fragile, mob);
            assertTrue(dmg > 0, "expected a real contact hit");
            assertTrue(BotDangerAssessment.isTouchDangerous(fragile, mob, 3),
                    "high-PAD mob vs low-HP/low-WDEF bot should be touch-dangerous (dmg=" + dmg + ")");
        }
    }

    @Test
    void rollIntegration_sameMobIsSafeForGearedHighHpBot() {
        try (MockedStatic<CombatFormulaProvider> cfp = mockStatic(CombatFormulaProvider.class)) {
            CombatFormulaProvider formula = mock(CombatFormulaProvider.class);
            cfp.when(CombatFormulaProvider::getInstance).thenReturn(formula);
            when(formula.doesMobHit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(true);

            // Level-appropriate mob (modest PAD) against a geared warrior with a big HP pool + WDEF:
            // contact hits are a tiny fraction of HP, so it should not flee.
            Monster mob = stubMob(120, 15);
            Character geared = stubBot(Job.WARRIOR, 50, 6000, 900);

            assertFalse(BotDangerAssessment.isTouchDangerous(geared, mob, 3),
                    "geared/high-HP bot should not flee from a level-appropriate mob");
        }
    }

    // --- Selection-penalty ordering: a fragile bot scores a dangerous mob strictly worse ---------

    @Test
    void selectionPenalty_fragileBotPenalizesDangerousMobButNotSafeOne() {
        try (MockedStatic<CombatFormulaProvider> cfp = mockStatic(CombatFormulaProvider.class)) {
            CombatFormulaProvider formula = mock(CombatFormulaProvider.class);
            cfp.when(CombatFormulaProvider::getInstance).thenReturn(formula);
            when(formula.doesMobHit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(true);

            Character fragile = stubBot(Job.BEGINNER, 8, 200, 0); // maxHp <= TOUCH_FRAGILE_MAXHP -> fragile
            BotEntry entry = new BotEntry(fragile, null, null);

            Monster dangerous = stubMob(700, 15); // big PAD -> dangerous
            Monster harmless = stubMob(0, 5);      // 0 PAD -> rollPhysicalTouchDamage returns 1, safe

            // fragile flag is now precomputed once per scoring pass (hoisted out of the per-candidate
            // loop); pass true here since `fragile` bot has maxHp <= TOUCH_FRAGILE_MAXHP.
            long dangerScore = BotCombatManager.touchDangerPenalty(true, fragile, dangerous);
            long safeScore = BotCombatManager.touchDangerPenalty(true, fragile, harmless);

            // ADDED to localScore (lower wins), so the dangerous mob must score strictly higher (worse).
            assertTrue(dangerScore > safeScore,
                    "dangerous mob should carry a positive penalty over a harmless one");
            assertEquals(BotCombatManager.cfg.TOUCH_DANGER_PENALTY, dangerScore);
            assertEquals(0L, safeScore);
        }
    }

    private static Monster stubMob(int padamage, int level) {
        Monster mob = mock(Monster.class);
        when(mob.getId()).thenReturn(100100 + level);
        when(mob.getPADamage()).thenReturn(padamage);
        when(mob.getLevel()).thenReturn(level);
        return mob;
    }

    private static Character stubBot(Job job, int level, int maxHp, int wdef) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(job.getId() * 1000 + level); // distinct cache key per stub
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getMaxHp()).thenReturn(maxHp);
        when(bot.getTotalWdef()).thenReturn(wdef);
        when(bot.getTotalStr()).thenReturn(level * 2);
        when(bot.getTotalDex()).thenReturn(level);
        when(bot.getTotalInt()).thenReturn(4);
        when(bot.getTotalLuk()).thenReturn(4);
        return bot;
    }
}
