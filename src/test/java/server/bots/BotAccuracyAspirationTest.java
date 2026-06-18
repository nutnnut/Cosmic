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
