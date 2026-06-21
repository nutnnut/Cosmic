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
    void firstJobChoicesAreTheFiveExplorerClasses() {
        assertEquals(List.of(Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.PIRATE),
                BotStarterKitManager.firstJobChoices());
    }

    @Test
    void secondJobChoicesMirrorEachBranchOptionSet() {
        assertEquals(List.of(Job.FIGHTER, Job.PAGE, Job.SPEARMAN),
                BotStarterKitManager.secondJobChoices(Job.WARRIOR));
        assertEquals(List.of(Job.FP_WIZARD, Job.IL_WIZARD, Job.CLERIC),
                BotStarterKitManager.secondJobChoices(Job.MAGICIAN));
        assertEquals(List.of(Job.HUNTER, Job.CROSSBOWMAN),
                BotStarterKitManager.secondJobChoices(Job.BOWMAN));
        assertEquals(List.of(Job.ASSASSIN, Job.BANDIT),
                BotStarterKitManager.secondJobChoices(Job.THIEF));
        assertEquals(List.of(Job.BRAWLER, Job.GUNSLINGER),
                BotStarterKitManager.secondJobChoices(Job.PIRATE));
        // No 2nd-job topology for a non-1st-job (or null) -> empty, so the picker falls back.
        assertTrue(BotStarterKitManager.secondJobChoices(Job.BEGINNER).isEmpty());
        assertTrue(BotStarterKitManager.secondJobChoices(Job.FIGHTER).isEmpty());
        assertTrue(BotStarterKitManager.secondJobChoices(null).isEmpty());
    }

    @Test
    void jobChangeNpcForRoutesEachBranchTo_ItsVerifiedTownInstructor() {
        // Branch = job id / 100. Each 1st-job (X00) and a 2nd-job (X10/X20/X30) maps to the same
        // town instructor (npc, map). Verified vs Map.wz + handbook/NPC.txt.
        assertJobNpc(Job.WARRIOR, 1022000, 102000003);   // 1st
        assertJobNpc(Job.FIGHTER, 1022000, 102000003);   // 2nd, same branch instructor
        assertJobNpc(Job.MAGICIAN, 1032001, 101000003);
        assertJobNpc(Job.CLERIC, 1032001, 101000003);
        assertJobNpc(Job.BOWMAN, 1012100, 100000201);
        assertJobNpc(Job.HUNTER, 1012100, 100000201);
        assertJobNpc(Job.THIEF, 1052001, 103000003);
        assertJobNpc(Job.ASSASSIN, 1052001, 103000003);
        assertJobNpc(Job.PIRATE, 1090000, 120000101);
        assertJobNpc(Job.BRAWLER, 1090000, 120000101);
    }

    private static void assertJobNpc(Job target, int npcId, int mapId) {
        BotStarterKitManager.JobChangeNpc npc = BotStarterKitManager.jobChangeNpcFor(target);
        assertEquals(npcId, npc.npcId(), target + " npc");
        assertEquals(mapId, npc.mapId(), target + " map");
    }

    @Test
    void thirdAndFourthJobRouteToTheirVerifiedInstructor() {
        // 3rd job: Door of Dimension (1061009) in each branch's hidden dungeon map.
        assertJobNpc(Job.CRUSADER, 1061009, 105070001);    // Warrior  - Ant Tunnel Park
        assertJobNpc(Job.FP_MAGE, 1061009, 100040106);     // Magician - Forest of Evil II
        assertJobNpc(Job.RANGER, 1061009, 105040305);      // Bowman   - Sleepy Dungeon V
        assertJobNpc(Job.HERMIT, 1061009, 107000402);      // Thief    - Monkey Swamp II
        assertJobNpc(Job.MARAUDER, 1061009, 105070200);    // Pirate   - Cave of Evil Eye II
        // 4th job: per-branch master in Leafre - Forest of the Priest (240010501).
        assertJobNpc(Job.HERO, 2081100, 240010501);        // Harmonia
        assertJobNpc(Job.BISHOP, 2081200, 240010501);      // Gritto
        assertJobNpc(Job.BOWMASTER, 2081300, 240010501);   // Legor
        assertJobNpc(Job.NIGHTLORD, 2081400, 240010501);   // Hellin
        assertJobNpc(Job.BUCCANEER, 2081500, 240010501);   // Samuel
    }

    @Test
    void beginnerAndNullDoNotRouteThroughAnInstructor() {
        assertNull(BotStarterKitManager.jobChangeNpcFor(Job.BEGINNER), "Beginner should not route");
        assertFalse(BotStarterKitManager.routesThroughNpc(Job.BEGINNER));
        assertNull(BotStarterKitManager.jobChangeNpcFor(null));
        assertFalse(BotStarterKitManager.routesThroughNpc(null));
    }

    @Test
    void routesThroughNpcAcceptsEveryFirstAndSecondJob() {
        for (Job j : List.of(Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.PIRATE,
                Job.FIGHTER, Job.PAGE, Job.SPEARMAN, Job.FP_WIZARD, Job.IL_WIZARD, Job.CLERIC,
                Job.HUNTER, Job.CROSSBOWMAN, Job.ASSASSIN, Job.BANDIT, Job.BRAWLER, Job.GUNSLINGER)) {
            assertTrue(BotStarterKitManager.routesThroughNpc(j), j + " (1st/2nd job) should route");
            assertEquals(j.getId() / 100,
                    BotStarterKitManager.jobChangeNpcFor(j) == null ? -1 : (j.getId() / 100),
                    j + " maps to a non-null instructor");
        }
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

        var savedReply = BotStarterKitManager.reply;
        BotStarterKitManager.reply = (e, t) -> { }; // avoid the BotManager singleton in the unit test
        try (MockedStatic<BotBuildManager> buildManager = mockStatic(BotBuildManager.class);
             MockedStatic<BotChatManager> chatManager = mockStatic(BotChatManager.class);
             MockedStatic<BotEquipManager> equipManager = mockStatic(BotEquipManager.class)) {
            BotStarterKitManager.advanceJob(entry, Job.HUNTER);

            verify(bot).changeJob(Job.HUNTER);
            buildManager.verify(() -> BotBuildManager.handleJobAdvance(entry, bot, Job.BOWMAN, Job.HUNTER));
            equipManager.verify(() -> BotEquipManager.autoEquip(bot, owner, null));
            chatManager.verify(() -> BotChatManager.checkBotStatus(entry, bot));
        } finally {
            BotStarterKitManager.reply = savedReply;
        }
    }
}
