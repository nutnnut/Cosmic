package server.bots;

import client.Character;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import server.combat.CombatFormulaProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The accuracy DEX floor must aim at the bot's ASPIRATIONAL grind mob (the map it would farm if it
 * could hit), not the easy map it's stuck on — that's what breaks the low-DEX vicious cycle. Models
 * the live "alone" bot: lv18 warrior, DEX 9 / LUK 4 (total accuracy 9), no flat accuracy.
 */
class BotAccuracyAspirationTest {

    private static BotEntry warriorBot(int level, int totalDex, int baseDex, int totalLuk) {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getTotalDex()).thenReturn(totalDex);
        when(bot.getDex()).thenReturn(baseDex);
        when(bot.getTotalLuk()).thenReturn(totalLuk);
        // getMap() left null: the cached-aspirational path never touches it; the fallback returns 0.
        return new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
    }

    @Test
    void floorAimsAtAspirationalMobNotCurrentMap() {
        BotEntry entry = warriorBot(18, 9, 9, 4); // total accuracy = floor(9*0.8 + 4*0.5) = 9
        // Aspirational = a Bubbling-like lv15 avoid-5 mob (the bot wants its drops/exp but whiffs ~1%).
        entry.aspirationalMobLevel = 15;
        entry.aspirationalMobAvoid = 5;
        int floor = BotBuildManager.accuracyDexFloor(entry, entry.getBot());
        // Inverting the hit formula for 25% hit on avoid 5 (levelDelta 0) -> needs base DEX 12 (> the 9
        // it's parked at), so the AP build now invests DEX instead of dumping everything into STR.
        assertEquals(12, floor);
        assertTrue(floor > entry.getBot().getDex(), "must raise DEX above the parked value");
    }

    @Test
    void tougherAspirationalTargetInvestsMoreDex() {
        BotEntry easy = warriorBot(18, 9, 9, 4);
        easy.aspirationalMobLevel = 15;
        easy.aspirationalMobAvoid = 5;
        BotEntry hard = warriorBot(18, 9, 9, 4);
        hard.aspirationalMobLevel = 25; // higher level + avoid => steeper accuracy requirement
        hard.aspirationalMobAvoid = 20;
        int easyFloor = BotBuildManager.accuracyDexFloor(easy, easy.getBot());
        int hardFloor = BotBuildManager.accuracyDexFloor(hard, hard.getBot());
        assertTrue(hardFloor > easyFloor,
                "aiming at a tougher map should demand more DEX (" + hardFloor + " vs " + easyFloor + ")");
    }

    @Test
    void unsetAspirationalAndNoMapMeansNoFloor() {
        BotEntry entry = warriorBot(18, 9, 9, 4); // aspirationalMobAvoid defaults to -1, getMap() null
        assertEquals(0, BotBuildManager.accuracyDexFloor(entry, entry.getBot()));
    }

    private static BotGrindAdvisor.MobProfile mob(int id, int exp, double rawKill) {
        return new BotGrindAdvisor.MobProfile(id, "m" + id, 10, 5, exp, rawKill, rawKill, java.util.List.of());
    }

    @Test
    void aspirationalSkipsMobsTheBotOneShots() {
        // Trivial: a dense low mob killed in ~1 swing (raw kill floored near the attack cycle) has the
        // highest raw exp/sec, but the bot already crushes it -> not aspirational.
        BotGrindAdvisor.MobProfile trivial = mob(1, 10, 0.72); // rate 13.9
        BotGrindAdvisor.MobProfile chunky = mob(2, 30, 3.0);   // rate 10.0, but takes real hits
        BotGrindAdvisor.MobProfile picked =
                BotGrindAdvisor.pickAspirational(java.util.List.of(trivial, chunky));
        assertEquals(2, picked.mobId(), "should aspire to the non-trivial mob despite lower raw exp/sec");
    }

    @Test
    void aspirationalFallsBackToBestWhenEverythingIsTrivial() {
        // Very over-geared: one-shots everything -> no mob clears the frontier -> fall back to best exp/sec.
        BotGrindAdvisor.MobProfile a = mob(1, 10, 0.72);
        BotGrindAdvisor.MobProfile b = mob(2, 25, 0.72); // higher exp, same (floored) kill time
        BotGrindAdvisor.MobProfile picked = BotGrindAdvisor.pickAspirational(java.util.List.of(a, b));
        assertEquals(2, picked.mobId(), "fallback picks the best raw exp/sec when all mobs are trivial");
    }

    @Test
    void moreAccuracyRaisesHitChance_underpinsTheGearAccuracyFactor() {
        // The gear accuracy hit-factor (BotGrindAdvisor) is hit(accWithItem)/hit(accNow); it only makes
        // accuracy gear enticing if hit chance is monotonic in accuracy. Guard that against Bubbling.
        CombatFormulaProvider f = CombatFormulaProvider.getInstance();
        double low = f.calculatePhysicalMobHitChance(9, 18, 15, 5);   // DEX-9 warrior
        double high = f.calculatePhysicalMobHitChance(14, 18, 15, 5);  // +5 acc (a Fish Spear's incACC)
        assertTrue(high > low, "more accuracy must raise hit chance (" + high + " > " + low + ")");
    }
}
