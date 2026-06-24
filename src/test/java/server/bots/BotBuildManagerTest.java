package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.Stat;
import client.processor.stat.AssignAPProcessor;
import constants.game.GameConstants;
import constants.skills.Archer;
import constants.skills.Bishop;
import constants.skills.Magician;
import constants.skills.Pirate;
import constants.skills.Rogue;
import constants.skills.Warrior;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotBuildManagerTest {

    @Test
    void initialSyncWarriorSpendsPendingSpWithoutPrompt() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        int warriorBook = GameConstants.getSkillBook(Warrior.IMPROVED_HPREC / 10000);
        int[] remainingSps = new int[5];
        remainingSps[warriorBook] = 1;
        Map<Integer, Integer> skillLevels = new HashMap<>();

        when(bot.getLevel()).thenReturn(10);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        when(bot.getRemainingAp()).thenReturn(0);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> mockSkill(invocation.getArgument(0), 20, false));

            BotBuildManager.checkLevelUp(entry, bot);
        }

        assertEquals(10, entry.lastKnownLevel);
        assertEquals(0, remainingSps[warriorBook]);
        assertEquals(1, skillLevels.getOrDefault(Warrior.IMPROVED_HPREC, 0));
    }

    @Test
    void ownerAutoOptionMirrorsOwnerlessApAssignment() {
        // Owner replies "auto": the bot must flip onto the same self-managed AP path an ownerless bot
        // uses (resolve now + ratchet later), even though it HAS an online owner (not ownerless).
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        when(bot.getLevel()).thenReturn(20);
        when(bot.getRemainingAp()).thenReturn(5);
        when(bot.getStr()).thenReturn(40);
        when(bot.getDex()).thenReturn(4);
        when(bot.getTotalDex()).thenReturn(4);
        when(bot.getTotalLuk()).thenReturn(4);

        String reply = BotBuildManager.setAutoApBuild(entry, bot);

        assertTrue(entry.apAuto, "auto should flip the bot onto self-managed AP");
        assertTrue(entry.apBuild != null, "auto should resolve a build immediately");
        assertEquals(BotBuildManager.StatType.STR, entry.apBuild.primaryStat);
        assertEquals(BotBuildManager.StatType.DEX, entry.apBuild.secondaryStat);
        assertTrue(reply != null && !reply.isEmpty());
    }

    @Test
    void initialSyncHeroKeepsPendingSpUntilVariantIsChosen() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        int[] remainingSps = new int[5];
        remainingSps[3] = 1;

        when(bot.getLevel()).thenReturn(120);
        when(bot.getJob()).thenReturn(Job.HERO);
        when(bot.getRemainingSps()).thenReturn(remainingSps);
        when(bot.getRemainingAp()).thenReturn(0);

        BotBuildManager.checkLevelUp(entry, bot);

        assertEquals(120, entry.lastKnownLevel);
        assertEquals(1, remainingSps[3]);
        verify(bot, never()).gainSp(anyInt(), anyInt(), anyBoolean());
        verify(bot, never()).changeSkillLevel(any(Skill.class), anyByte(), anyInt(), anyLong());
    }

    @Test
    void warriorLevel16BuildStartsPowerStrikeAndSlashBlastAfterHpSkills() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        int warriorBook = GameConstants.getSkillBook(Warrior.IMPROVED_HPREC / 10000);
        int[] remainingSps = new int[5];
        remainingSps[warriorBook] = 19;
        Map<Integer, Integer> skillLevels = new HashMap<>();
        Map<Integer, Skill> skills = new HashMap<>();

        skills.put(Warrior.IMPROVED_HPREC, mockSkill(Warrior.IMPROVED_HPREC, 20, false));
        skills.put(Warrior.IMPROVED_MAXHP, mockSkill(Warrior.IMPROVED_MAXHP, 10, false));
        skills.put(Warrior.POWER_STRIKE, mockSkill(Warrior.POWER_STRIKE, 20, false));
        skills.put(Warrior.SLASH_BLAST, mockSkill(Warrior.SLASH_BLAST, 20, false));

        when(bot.getJob()).thenReturn(Job.WARRIOR);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> skills.get(invocation.getArgument(0)));

            BotBuildManager.autoAssignSp(entry, bot);
        }

        assertEquals(0, remainingSps[warriorBook]);
        assertEquals(5, skillLevels.getOrDefault(Warrior.IMPROVED_HPREC, 0));
        assertEquals(10, skillLevels.getOrDefault(Warrior.IMPROVED_MAXHP, 0));
        assertEquals(1, skillLevels.getOrDefault(Warrior.POWER_STRIKE, 0));
        assertEquals(3, skillLevels.getOrDefault(Warrior.SLASH_BLAST, 0));
        assertNull(skillLevels.get(Warrior.ENDURE));
        assertNull(skillLevels.get(Warrior.IRON_BODY));
    }

    @Test
    void warriorRespecRebuildsIncorrectFirstJobAllocation() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        int warriorBook = GameConstants.getSkillBook(Warrior.IMPROVED_HPREC / 10000);
        int[] remainingSps = new int[5];
        Map<Integer, Integer> skillLevels = new HashMap<>();
        Map<Integer, Skill> skills = new HashMap<>();

        skills.put(Warrior.IMPROVED_HPREC, mockSkill(Warrior.IMPROVED_HPREC, 20, false));
        skills.put(Warrior.IMPROVED_MAXHP, mockSkill(Warrior.IMPROVED_MAXHP, 10, false));
        skills.put(Warrior.POWER_STRIKE, mockSkill(Warrior.POWER_STRIKE, 20, false));
        skills.put(Warrior.SLASH_BLAST, mockSkill(Warrior.SLASH_BLAST, 20, false));

        skillLevels.put(Warrior.IMPROVED_HPREC, 9);
        skillLevels.put(Warrior.IMPROVED_MAXHP, 10);

        Map<Skill, Character.SkillEntry> learnedSkills = new LinkedHashMap<>();
        learnedSkills.put(skills.get(Warrior.IMPROVED_HPREC), new Character.SkillEntry((byte) 9, 0, -1));
        learnedSkills.put(skills.get(Warrior.IMPROVED_MAXHP), new Character.SkillEntry((byte) 10, 0, -1));

        when(bot.getJob()).thenReturn(Job.WARRIOR);
        when(bot.getSkills()).thenReturn(learnedSkills);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> skills.get(invocation.getArgument(0)));

            assertEquals("ok, rebuilt my sp using the bot build", BotBuildManager.respecSp(entry, bot));
        }

        assertEquals(0, remainingSps[warriorBook]);
        assertEquals(5, skillLevels.getOrDefault(Warrior.IMPROVED_HPREC, 0));
        assertEquals(10, skillLevels.getOrDefault(Warrior.IMPROVED_MAXHP, 0));
        assertEquals(1, skillLevels.getOrDefault(Warrior.POWER_STRIKE, 0));
        assertEquals(3, skillLevels.getOrDefault(Warrior.SLASH_BLAST, 0));
    }

    @Test
    void magicianBuildFollowsRequestedFirstJobOrder() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        int mageBook = GameConstants.getSkillBook(Magician.ENERGY_BOLT / 10000);
        int[] remainingSps = new int[5];
        remainingSps[mageBook] = 7;
        Map<Integer, Integer> skillLevels = new HashMap<>();
        Map<Integer, Skill> skills = new HashMap<>();

        skills.put(Magician.ENERGY_BOLT, mockSkill(Magician.ENERGY_BOLT, 20, false));
        skills.put(Magician.IMPROVED_MP_RECOVERY, mockSkill(Magician.IMPROVED_MP_RECOVERY, 16, false));
        skills.put(Magician.IMPROVED_MAX_MP_INCREASE, mockSkill(Magician.IMPROVED_MAX_MP_INCREASE, 10, false));
        skills.put(Magician.MAGIC_CLAW, mockSkill(Magician.MAGIC_CLAW, 20, false));
        skills.put(Magician.MAGIC_GUARD, mockSkill(Magician.MAGIC_GUARD, 20, false));

        when(bot.getJob()).thenReturn(Job.MAGICIAN);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> skills.get(invocation.getArgument(0)));

            BotBuildManager.autoAssignSp(entry, bot);
        }

        assertEquals(0, remainingSps[mageBook]);
        assertEquals(1, skillLevels.getOrDefault(Magician.ENERGY_BOLT, 0));
        assertEquals(5, skillLevels.getOrDefault(Magician.IMPROVED_MP_RECOVERY, 0));
        assertEquals(1, skillLevels.getOrDefault(Magician.IMPROVED_MAX_MP_INCREASE, 0));
    }

    @Test
    void bowmanBuildFollowsRequestedFirstJobOrder() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        int bowmanBook = GameConstants.getSkillBook(Archer.ARROW_BLOW / 10000);
        int[] remainingSps = new int[5];
        remainingSps[bowmanBook] = 7;
        Map<Integer, Integer> skillLevels = new HashMap<>();
        Map<Integer, Skill> skills = new HashMap<>();

        skills.put(Archer.ARROW_BLOW, mockSkill(Archer.ARROW_BLOW, 20, false));
        skills.put(Archer.DOUBLE_SHOT, mockSkill(Archer.DOUBLE_SHOT, 20, false));
        skills.put(Archer.BLESSING_OF_AMAZON, mockSkill(Archer.BLESSING_OF_AMAZON, 15, false));
        skills.put(Archer.EYE_OF_AMAZON, mockSkill(Archer.EYE_OF_AMAZON, 8, false));
        skills.put(Archer.CRITICAL_SHOT, mockSkill(Archer.CRITICAL_SHOT, 20, false));
        skills.put(Archer.FOCUS, mockSkill(Archer.FOCUS, 20, false));

        when(bot.getJob()).thenReturn(Job.BOWMAN);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> skills.get(invocation.getArgument(0)));

            BotBuildManager.autoAssignSp(entry, bot);
        }

        assertEquals(0, remainingSps[bowmanBook]);
        assertEquals(1, skillLevels.getOrDefault(Archer.ARROW_BLOW, 0));
        assertEquals(1, skillLevels.getOrDefault(Archer.DOUBLE_SHOT, 0));
        assertEquals(3, skillLevels.getOrDefault(Archer.BLESSING_OF_AMAZON, 0));
        assertEquals(2, skillLevels.getOrDefault(Archer.EYE_OF_AMAZON, 0));
    }

    @Test
    void thiefBuildFollowsRequestedFirstJobOrder() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        int thiefBook = GameConstants.getSkillBook(Rogue.LUCKY_SEVEN / 10000);
        int[] remainingSps = new int[5];
        remainingSps[thiefBook] = 7;
        Map<Integer, Integer> skillLevels = new HashMap<>();
        Map<Integer, Skill> skills = new HashMap<>();

        skills.put(Rogue.LUCKY_SEVEN, mockSkill(Rogue.LUCKY_SEVEN, 20, false));
        skills.put(Rogue.NIMBLE_BODY, mockSkill(Rogue.NIMBLE_BODY, 20, false));
        skills.put(Rogue.KEEN_EYES, mockSkill(Rogue.KEEN_EYES, 8, false));
        skills.put(Rogue.DISORDER, mockSkill(Rogue.DISORDER, 20, false));
        skills.put(Rogue.DARK_SIGHT, mockSkill(Rogue.DARK_SIGHT, 20, false));

        when(bot.getJob()).thenReturn(Job.THIEF);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> skills.get(invocation.getArgument(0)));

            BotBuildManager.autoAssignSp(entry, bot);
        }

        assertEquals(0, remainingSps[thiefBook]);
        assertEquals(1, skillLevels.getOrDefault(Rogue.LUCKY_SEVEN, 0));
        assertEquals(3, skillLevels.getOrDefault(Rogue.NIMBLE_BODY, 0));
        assertEquals(3, skillLevels.getOrDefault(Rogue.KEEN_EYES, 0));
    }

    @Test
    void bishopBuildHoldsSpUntilGenesisIsUnlocked() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        int bishopBook = GameConstants.getSkillBook(Bishop.GENESIS / 10000);
        int[] remainingSps = new int[5];
        remainingSps[bishopBook] = 5;
        Map<Integer, Integer> maxLevels = new HashMap<>();

        maxLevels.put(Bishop.GENESIS, 30);
        maxLevels.put(Bishop.MAPLE_WARRIOR, 30);
        maxLevels.put(Bishop.RESURRECTION, 10);
        maxLevels.put(Bishop.ANGEL_RAY, 30);
        maxLevels.put(Bishop.BAHAMUT, 30);
        maxLevels.put(Bishop.BIG_BANG, 30);
        maxLevels.put(Bishop.HOLY_SHIELD, 20);
        maxLevels.put(Bishop.INFINITY, 30);
        maxLevels.put(Bishop.MANA_REFLECTION, 20);
        maxLevels.put(Bishop.HEROS_WILL, 5);

        when(bot.getJob()).thenReturn(Job.BISHOP);
        when(bot.getRemainingSps()).thenReturn(remainingSps);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 0);
        when(bot.getMasterLevel(any(Skill.class))).thenAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return skill.getId() == Bishop.GENESIS ? 0 : maxLevels.getOrDefault(skill.getId(), 10);
        });
        when(bot.getSkillExpiration(any(Skill.class))).thenReturn(0L);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt())).thenAnswer(invocation -> {
                int skillId = invocation.getArgument(0);
                return mockSkill(skillId, maxLevels.getOrDefault(skillId, 30), true);
            });

            BotBuildManager.autoAssignSp(entry, bot);
        }

        assertEquals(5, remainingSps[bishopBook]);
        verify(bot, never()).gainSp(anyInt(), anyInt(), anyBoolean());
        verify(bot, never()).changeSkillLevel(any(Skill.class), anyByte(), anyInt(), anyLong());
    }

    @Test
    void mageLuklessBuildDumpsRemainingApIntoInt() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.apBuild = new BotBuildManager.ApBuild(BotBuildManager.StatType.INT, BotBuildManager.StatType.LUK, 4);

        when(bot.getRemainingAp()).thenReturn(5);
        when(bot.getLuk()).thenReturn(4);

        BotBuildManager.autoAssignAp(entry, bot);

        verify(bot).assignStrDexIntLuk(0, 0, 5, 0);
    }

    @Test
    void thiefFixedDexBuildFillsDexBeforeLuk() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.apBuild = new BotBuildManager.ApBuild(BotBuildManager.StatType.LUK, BotBuildManager.StatType.DEX, 25);

        when(bot.getRemainingAp()).thenReturn(5);
        when(bot.getDex()).thenReturn(22);

        BotBuildManager.autoAssignAp(entry, bot);

        verify(bot).assignStrDexIntLuk(0, 3, 0, 2);
    }

    @Test
    void bowmanStrlessBuildDumpsRemainingApIntoDex() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.apBuild = new BotBuildManager.ApBuild(BotBuildManager.StatType.DEX, BotBuildManager.StatType.STR, 4);

        when(bot.getRemainingAp()).thenReturn(5);
        when(bot.getStr()).thenReturn(4);

        BotBuildManager.autoAssignAp(entry, bot);

        verify(bot).assignStrDexIntLuk(0, 5, 0, 0);
    }

    @Test
    void apRespecResetsToBaseStatsThenRebuildsUsingSavedPlan() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.apBuild = new BotBuildManager.ApBuild(BotBuildManager.StatType.INT, BotBuildManager.StatType.LUK, 4);

        AtomicInteger str = new AtomicInteger(35);
        AtomicInteger dex = new AtomicInteger(24);
        AtomicInteger intStat = new AtomicInteger(60);
        AtomicInteger luk = new AtomicInteger(18);
        AtomicInteger remainingAp = new AtomicInteger(0);

        when(bot.getJob()).thenReturn(Job.MAGICIAN);
        when(bot.getStr()).thenAnswer(invocation -> str.get());
        when(bot.getDex()).thenAnswer(invocation -> dex.get());
        when(bot.getInt()).thenAnswer(invocation -> intStat.get());
        when(bot.getLuk()).thenAnswer(invocation -> luk.get());
        when(bot.getRemainingAp()).thenAnswer(invocation -> remainingAp.get());
        doAnswer(invocation -> {
            int deltaStr = invocation.getArgument(0);
            int deltaDex = invocation.getArgument(1);
            int deltaInt = invocation.getArgument(2);
            int deltaLuk = invocation.getArgument(3);
            str.addAndGet(deltaStr);
            dex.addAndGet(deltaDex);
            intStat.addAndGet(deltaInt);
            luk.addAndGet(deltaLuk);
            remainingAp.addAndGet(-(deltaStr + deltaDex + deltaInt + deltaLuk));
            return true;
        }).when(bot).assignStrDexIntLuk(anyInt(), anyInt(), anyInt(), anyInt());

        assertEquals("ok, rebuilt my ap using the bot build", BotBuildManager.respecAp(entry, bot));

        verify(bot, times(2)).assignStrDexIntLuk(anyInt(), anyInt(), anyInt(), anyInt());
        assertEquals(4, str.get());
        assertEquals(4, dex.get());
        assertEquals(125, intStat.get());
        assertEquals(4, luk.get());
        assertEquals(0, remainingAp.get());
    }

    @Test
    void apRespecKeepsFirstJobRequiredDexForThiefBuilds() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.apBuild = new BotBuildManager.ApBuild(BotBuildManager.StatType.LUK, BotBuildManager.StatType.DEX, 4);

        AtomicInteger str = new AtomicInteger(20);
        AtomicInteger dex = new AtomicInteger(60);
        AtomicInteger intStat = new AtomicInteger(4);
        AtomicInteger luk = new AtomicInteger(80);
        AtomicInteger remainingAp = new AtomicInteger(0);

        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        when(bot.getStr()).thenAnswer(invocation -> str.get());
        when(bot.getDex()).thenAnswer(invocation -> dex.get());
        when(bot.getInt()).thenAnswer(invocation -> intStat.get());
        when(bot.getLuk()).thenAnswer(invocation -> luk.get());
        when(bot.getRemainingAp()).thenAnswer(invocation -> remainingAp.get());
        doAnswer(invocation -> {
            int deltaStr = invocation.getArgument(0);
            int deltaDex = invocation.getArgument(1);
            int deltaInt = invocation.getArgument(2);
            int deltaLuk = invocation.getArgument(3);
            str.addAndGet(deltaStr);
            dex.addAndGet(deltaDex);
            intStat.addAndGet(deltaInt);
            luk.addAndGet(deltaLuk);
            remainingAp.addAndGet(-(deltaStr + deltaDex + deltaInt + deltaLuk));
            return true;
        }).when(bot).assignStrDexIntLuk(anyInt(), anyInt(), anyInt(), anyInt());

        assertEquals("ok, rebuilt my ap using the bot build", BotBuildManager.respecAp(entry, bot));

        assertEquals(4, str.get());
        assertEquals(25, dex.get());
        assertEquals(4, intStat.get());
        assertEquals(131, luk.get());
        assertEquals(0, remainingAp.get());
    }

    // ---- autonomous (ownerless) job picker + AP resolver ---------------------------------------

    @Test
    void weightedPickHonorsZeroWeightsDeterministically() {
        List<Job> choices = List.of(Job.WARRIOR, Job.MAGICIAN);
        // A weight of 0 is never chosen while another option has a positive weight.
        assertEquals(Job.WARRIOR,
                BotBuildManager.weightedPick(choices, Map.of(Job.WARRIOR, 1, Job.MAGICIAN, 0)));
        assertEquals(Job.MAGICIAN,
                BotBuildManager.weightedPick(choices, Map.of(Job.WARRIOR, 0, Job.MAGICIAN, 1)));
    }

    @Test
    void pickWeightedJobReturnsValidFirstAndSecondJobs() {
        assertTrue(List.of(Job.FIGHTER, Job.PAGE, Job.SPEARMAN)
                .contains(BotBuildManager.pickWeightedJob(Job.WARRIOR)));
        assertTrue(List.of(Job.ASSASSIN, Job.BANDIT)
                .contains(BotBuildManager.pickWeightedJob(Job.THIEF)));
    }

    @Test
    void pickWeightedJobOnlyPicksBuildableClassesIncludingPirate() {
        // All five explorer 1st jobs are now buildable (each has an SP build + an AP build), pirate
        // included. The pick must stay within that set, and pirate must actually be reachable.
        List<Job> buildable = List.of(Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.PIRATE);
        boolean sawPirate = false;
        for (int i = 0; i < 400; i++) {
            Job pick = BotBuildManager.pickWeightedJob(Job.BEGINNER);
            assertTrue(buildable.contains(pick));
            if (pick == Job.PIRATE) {
                sawPirate = true;
            }
        }
        assertTrue(sawPirate, "pirate is now buildable and must be eligible");
    }

    @Test
    void pirateGunVariantTrainsDoubleShotNotKnuckleSkills() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.spVariant = "gun"; // commit the gun line so getBuildOrder picks the gun pirate build
        int pirateBook = GameConstants.getSkillBook(Pirate.DOUBLE_SHOT / 10000);
        int[] remainingSps = new int[5];
        remainingSps[pirateBook] = 7;
        Map<Integer, Integer> skillLevels = new HashMap<>();
        Map<Integer, Skill> skills = new HashMap<>();
        skills.put(Pirate.DOUBLE_SHOT, mockSkill(Pirate.DOUBLE_SHOT, 20, false));
        skills.put(Pirate.DASH, mockSkill(Pirate.DASH, 20, false));
        skills.put(Pirate.BULLET_TIME, mockSkill(Pirate.BULLET_TIME, 20, false));

        when(bot.getJob()).thenReturn(Job.PIRATE);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> skills.get(invocation.getArgument(0)));
            BotBuildManager.autoAssignSp(entry, bot);
        }

        assertEquals(0, remainingSps[pirateBook]);
        assertEquals(7, skillLevels.getOrDefault(Pirate.DOUBLE_SHOT, 0)); // gun main attack maxed first
        assertNull(skillLevels.get(Pirate.FLASH_FIST));
        assertNull(skillLevels.get(Pirate.SOMERSAULT_KICK));
    }

    @Test
    void pirateKnuckleVariantTrainsFlashFistAndSomersaultNotDoubleShot() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.spVariant = "knuckle";
        int pirateBook = GameConstants.getSkillBook(Pirate.FLASH_FIST / 10000);
        int[] remainingSps = new int[5];
        remainingSps[pirateBook] = 7;
        Map<Integer, Integer> skillLevels = new HashMap<>();
        Map<Integer, Skill> skills = new HashMap<>();
        skills.put(Pirate.FLASH_FIST, mockSkill(Pirate.FLASH_FIST, 20, false));
        skills.put(Pirate.SOMERSAULT_KICK, mockSkill(Pirate.SOMERSAULT_KICK, 20, false));
        skills.put(Pirate.DASH, mockSkill(Pirate.DASH, 20, false));
        skills.put(Pirate.BULLET_TIME, mockSkill(Pirate.BULLET_TIME, 20, false));

        when(bot.getJob()).thenReturn(Job.PIRATE);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> skills.get(invocation.getArgument(0)));
            BotBuildManager.autoAssignSp(entry, bot);
        }

        assertEquals(0, remainingSps[pirateBook]);
        assertEquals(1, skillLevels.getOrDefault(Pirate.FLASH_FIST, 0));     // commits the knuckle gate
        assertEquals(6, skillLevels.getOrDefault(Pirate.SOMERSAULT_KICK, 0)); // main attack next
        assertNull(skillLevels.get(Pirate.DOUBLE_SHOT));
    }

    @Test
    void resolveApBuildForGunPirateIsDexPrimaryStrSecondary() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.spVariant = "gun"; // gun line is DEX-primary; base Pirate orients off the variant
        when(bot.getJob()).thenReturn(Job.PIRATE);

        BotBuildManager.ApBuild build = BotBuildManager.resolveApBuild(entry, bot);

        assertEquals(BotBuildManager.StatType.DEX, build.primaryStat);
        assertEquals(BotBuildManager.StatType.STR, build.secondaryStat);
    }

    @Test
    void resolveApBuildReadsTrainedGunSkillWhenVariantUnset() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        // spVariant intentionally left null: orientation must be read off the trained skill (the SSOT).
        when(bot.getJob()).thenReturn(Job.PIRATE);
        when(bot.getSkillLevel(Pirate.DOUBLE_SHOT)).thenReturn(1); // trained the gun attack

        BotBuildManager.ApBuild build = BotBuildManager.resolveApBuild(entry, bot);

        assertEquals(BotBuildManager.StatType.DEX, build.primaryStat);
        assertEquals(BotBuildManager.StatType.STR, build.secondaryStat);
    }

    @Test
    void resolveApBuildReadsTrainedKnuckleSkillWhenVariantUnset() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        when(bot.getJob()).thenReturn(Job.PIRATE);
        when(bot.getSkillLevel(Pirate.FLASH_FIST)).thenReturn(1); // trained a knuckle attack

        BotBuildManager.ApBuild build = BotBuildManager.resolveApBuild(entry, bot);

        assertEquals(BotBuildManager.StatType.STR, build.primaryStat);
        assertEquals(BotBuildManager.StatType.DEX, build.secondaryStat);
    }

    @Test
    void resolveApBuildForMageParksSecondaryAtFloor() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        when(bot.getJob()).thenReturn(Job.MAGICIAN);

        BotBuildManager.ApBuild build = BotBuildManager.resolveApBuild(entry, bot);

        // Mage damage and wand/staff reqs ignore LUK, so the secondary stays parked at the job floor.
        assertEquals(BotBuildManager.StatType.INT, build.primaryStat);
        assertEquals(BotBuildManager.StatType.LUK, build.secondaryStat);
        assertEquals(AssignAPProcessor.getMinStatFloor(Job.MAGICIAN, Stat.LUK), build.secondaryTarget);
    }

    @Test
    void ownerlessWarriorResolvesApBuildInsteadOfPrompting() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, bot, mock(ScheduledFuture.class)); // self-owned: owner == bot
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        when(bot.getRemainingAp()).thenReturn(5);

        try (MockedStatic<BotManager> bm = mockStatic(BotManager.class)) {
            bm.when(() -> BotManager.isAutopilotActive(entry)).thenReturn(true);
            bm.when(() -> BotManager.hasOnlinePlayerOwner(entry)).thenReturn(false);
            // cfg is a static field (untouched by mockStatic); inventory/WZ is absent so the resolver
            // falls back to the DEX floor (pure) rather than throwing.
            assertNull(BotBuildManager.buildApPrompt(entry, bot)); // no owner prompt: resolved autonomously
        }

        assertEquals(BotBuildManager.StatType.STR, entry.apBuild.primaryStat);
        assertEquals(BotBuildManager.StatType.DEX, entry.apBuild.secondaryStat);
        assertEquals(AssignAPProcessor.getMinStatFloor(Job.WARRIOR, Stat.DEX), entry.apBuild.secondaryTarget);
    }

    @Test
    void ownerlessBeginnerAutoAdvancesAtLevel10InsteadOfPrompting() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, bot, mock(ScheduledFuture.class)); // self-owned: owner == bot
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        when(bot.getLevel()).thenReturn(10);

        try (MockedStatic<BotManager> bm = mockStatic(BotManager.class)) {
            bm.when(() -> BotManager.isAutopilotActive(entry)).thenReturn(true);
            bm.when(() -> BotManager.hasOnlinePlayerOwner(entry)).thenReturn(false);
            // BotManager.after/randMs default to no-op under mockStatic, so scheduleAutoAdvance does
            // not run the deferred advance here; we assert the branch was taken, not the advance itself.
            assertNull(BotBuildManager.buildJobPrompt(entry, bot)); // no owner prompt: advance scheduled
        }

        assertEquals(10, entry.jobPromptSent);
    }

    private static void stubSkillState(Character bot, int[] remainingSps, Map<Integer, Integer> skillLevels) {
        when(bot.getRemainingSps()).thenReturn(remainingSps);
        when(bot.getSkillLevel(any(Skill.class))).thenAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) skillLevels.getOrDefault(skill.getId(), 0).intValue();
        });
        when(bot.getSkillExpiration(any(Skill.class))).thenReturn(0L);
        doAnswer(invocation -> {
            int delta = invocation.getArgument(0);
            int book = invocation.getArgument(1);
            remainingSps[book] += delta;
            return null;
        }).when(bot).gainSp(anyInt(), anyInt(), anyBoolean());
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            byte newLevel = invocation.getArgument(1);
            skillLevels.put(skill.getId(), (int) newLevel);
            return null;
        }).when(bot).changeSkillLevel(any(Skill.class), anyByte(), anyInt(), anyLong());
    }

    private static Skill mockSkill(int skillId, int maxLevel, boolean fourthJob) {
        Skill skill = mock(Skill.class);
        when(skill.getId()).thenReturn(skillId);
        when(skill.getMaxLevel()).thenReturn(maxLevel);
        when(skill.isFourthJob()).thenReturn(fourthJob);
        return skill;
    }

    @Test
    void aboutOnePercentOfBotsAreLockedLifelongBeginners() {
        int locked = 0;
        long lockedSeed = 0;
        for (long s = 1; s <= 50_000; s++) {
            if (BotPersonality.random(s).permanentBeginner()) {
                locked++;
                if (lockedSeed == 0) {
                    lockedSeed = s;
                }
            }
        }
        assertTrue(locked > 300 && locked < 700, "locked rate should be ~1%, was " + locked + "/50000");

        // A locked bot never auto-advances, even long past a milestone.
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        entry.personality = BotPersonality.random(lockedSeed);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        when(bot.getLevel()).thenReturn(50);
        assertNull(BotBuildManager.autoAdvanceTarget(entry, bot), "locked beginner must never advance");
    }

    @Test
    void magicianFirstJobIsEligibleAtLevel8ButOthersAtLevel10() {
        Character bot = mock(Character.class);
        BotEntry entry = new BotEntry(bot, mock(Character.class), mock(ScheduledFuture.class));
        when(bot.getJob()).thenReturn(Job.BEGINNER);

        // Magician is the lv8 exception.
        entry.personality = BotPersonality.defaults().withPlannedJobs(Job.MAGICIAN, null);
        when(bot.getLevel()).thenReturn(7);
        assertNull(BotBuildManager.autoAdvanceTarget(entry, bot));
        when(bot.getLevel()).thenReturn(8);
        assertEquals(Job.MAGICIAN, BotBuildManager.autoAdvanceTarget(entry, bot));

        // Every other 1st job must wait until lv10.
        entry.personality = BotPersonality.defaults().withPlannedJobs(Job.WARRIOR, null);
        when(bot.getLevel()).thenReturn(8);
        assertNull(BotBuildManager.autoAdvanceTarget(entry, bot));
        when(bot.getLevel()).thenReturn(9);
        assertNull(BotBuildManager.autoAdvanceTarget(entry, bot));
        when(bot.getLevel()).thenReturn(10);
        assertEquals(Job.WARRIOR, BotBuildManager.autoAdvanceTarget(entry, bot));
    }
}
