package server.bots;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotStarterKitManagerTest {
    @Test
    void shouldExposeExplorerFirstJobStarterKits() {
        assertEquals(List.of(new BotStarterKitManager.ItemGrant(1302077, (short) 1)),
                BotStarterKitManager.starterKitFor(Job.WARRIOR));
        assertEquals(List.of(new BotStarterKitManager.ItemGrant(1372043, (short) 1)),
                BotStarterKitManager.starterKitFor(Job.MAGICIAN));
        assertEquals(List.of(
                        new BotStarterKitManager.ItemGrant(1452051, (short) 1),
                        new BotStarterKitManager.ItemGrant(2060000, (short) 1000)),
                BotStarterKitManager.starterKitFor(Job.BOWMAN));
        assertEquals(List.of(
                        new BotStarterKitManager.ItemGrant(1472061, (short) 1),
                        new BotStarterKitManager.ItemGrant(1332063, (short) 1),
                        new BotStarterKitManager.ItemGrant(2070015, (short) 500)),
                BotStarterKitManager.starterKitFor(Job.THIEF));
        assertEquals(List.of(
                        new BotStarterKitManager.ItemGrant(1492000, (short) 1),
                        new BotStarterKitManager.ItemGrant(1482000, (short) 1),
                        new BotStarterKitManager.ItemGrant(2330000, (short) 1000)),
                BotStarterKitManager.starterKitFor(Job.PIRATE));
    }

    @Test
    void thirdJobOfMapsEveryExplorerSecondJobToItsLoneThirdJob() {
        assertEquals(Job.CRUSADER, BotStarterKitManager.thirdJobOf(Job.FIGHTER));
        assertEquals(Job.WHITEKNIGHT, BotStarterKitManager.thirdJobOf(Job.PAGE));
        assertEquals(Job.DRAGONKNIGHT, BotStarterKitManager.thirdJobOf(Job.SPEARMAN));
        assertEquals(Job.FP_MAGE, BotStarterKitManager.thirdJobOf(Job.FP_WIZARD));
        assertEquals(Job.IL_MAGE, BotStarterKitManager.thirdJobOf(Job.IL_WIZARD));
        assertEquals(Job.PRIEST, BotStarterKitManager.thirdJobOf(Job.CLERIC));
        assertEquals(Job.RANGER, BotStarterKitManager.thirdJobOf(Job.HUNTER));
        assertEquals(Job.SNIPER, BotStarterKitManager.thirdJobOf(Job.CROSSBOWMAN));
        assertEquals(Job.HERMIT, BotStarterKitManager.thirdJobOf(Job.ASSASSIN));
        assertEquals(Job.CHIEFBANDIT, BotStarterKitManager.thirdJobOf(Job.BANDIT));
        assertEquals(Job.MARAUDER, BotStarterKitManager.thirdJobOf(Job.BRAWLER));
        assertEquals(Job.OUTLAW, BotStarterKitManager.thirdJobOf(Job.GUNSLINGER));
        // Not a 2nd job -> no deterministic successor (beginner, 1st, 3rd, 4th, null).
        assertNull(BotStarterKitManager.thirdJobOf(Job.BEGINNER));
        assertNull(BotStarterKitManager.thirdJobOf(Job.WARRIOR));
        assertNull(BotStarterKitManager.thirdJobOf(Job.CRUSADER));
        assertNull(BotStarterKitManager.thirdJobOf(Job.HERO));
        assertNull(BotStarterKitManager.thirdJobOf(null));
    }

    @Test
    void fourthJobOfMapsEveryExplorerThirdJobToItsLoneFourthJob() {
        assertEquals(Job.HERO, BotStarterKitManager.fourthJobOf(Job.CRUSADER));
        assertEquals(Job.PALADIN, BotStarterKitManager.fourthJobOf(Job.WHITEKNIGHT));
        assertEquals(Job.DARKKNIGHT, BotStarterKitManager.fourthJobOf(Job.DRAGONKNIGHT));
        assertEquals(Job.FP_ARCHMAGE, BotStarterKitManager.fourthJobOf(Job.FP_MAGE));
        assertEquals(Job.IL_ARCHMAGE, BotStarterKitManager.fourthJobOf(Job.IL_MAGE));
        assertEquals(Job.BISHOP, BotStarterKitManager.fourthJobOf(Job.PRIEST));
        assertEquals(Job.BOWMASTER, BotStarterKitManager.fourthJobOf(Job.RANGER));
        assertEquals(Job.MARKSMAN, BotStarterKitManager.fourthJobOf(Job.SNIPER));
        assertEquals(Job.NIGHTLORD, BotStarterKitManager.fourthJobOf(Job.HERMIT));
        assertEquals(Job.SHADOWER, BotStarterKitManager.fourthJobOf(Job.CHIEFBANDIT));
        assertEquals(Job.BUCCANEER, BotStarterKitManager.fourthJobOf(Job.MARAUDER));
        assertEquals(Job.CORSAIR, BotStarterKitManager.fourthJobOf(Job.OUTLAW));
        // Not a 3rd job -> null (2nd job, 4th job, beginner, null).
        assertNull(BotStarterKitManager.fourthJobOf(Job.FIGHTER));
        assertNull(BotStarterKitManager.fourthJobOf(Job.HERO));
        assertNull(BotStarterKitManager.fourthJobOf(Job.BEGINNER));
        assertNull(BotStarterKitManager.fourthJobOf(null));
    }

    @Test
    void shouldOnlyGrantKitsForBeginnerToFirstJobAdvancements() {
        assertTrue(BotStarterKitManager.isFirstJobAdvancement(Job.BEGINNER, Job.WARRIOR));
        assertTrue(BotStarterKitManager.isFirstJobAdvancement(Job.BEGINNER, Job.MAGICIAN));
        assertFalse(BotStarterKitManager.isFirstJobAdvancement(Job.WARRIOR, Job.FIGHTER));
        assertFalse(BotStarterKitManager.isFirstJobAdvancement(Job.BEGINNER, Job.FIGHTER));
    }

    @Test
    void advanceJobAlwaysReevaluatesAutoEquip() {
        Character bot = mock(Character.class);
        Character owner = mock(Character.class);
        BotEntry entry = new BotEntry(bot, owner, mock(ScheduledFuture.class));

        when(bot.getJob()).thenReturn(Job.BOWMAN);

        try (MockedStatic<BotBuildManager> buildManager = mockStatic(BotBuildManager.class);
             MockedStatic<BotChatManager> chatManager = mockStatic(BotChatManager.class);
             MockedStatic<BotEquipManager> equipManager = mockStatic(BotEquipManager.class)) {
            BotStarterKitManager.advanceJob(entry, Job.HUNTER);

            verify(bot).changeJob(Job.HUNTER);
            buildManager.verify(() -> BotBuildManager.handleJobAdvance(entry, bot, Job.BOWMAN, Job.HUNTER));
            equipManager.verify(() -> BotEquipManager.autoEquip(bot, owner, null));
            chatManager.verify(() -> BotChatManager.checkBotStatus(entry, bot));
        }
    }
}
