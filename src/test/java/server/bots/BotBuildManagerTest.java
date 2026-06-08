package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.game.GameConstants;
import constants.skills.Archer;
import constants.skills.Bishop;
import constants.skills.Magician;
import constants.skills.Rogue;
import constants.skills.Warrior;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        // Level 16 → 1 + 3*(16-10) = 19 SP, matching the 19 points the build below allocates.
        when(bot.getLevel()).thenReturn(16);
        when(bot.getSkills()).thenReturn(learnedSkills);
        stubSkillState(bot, remainingSps, skillLevels);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);

        try (MockedStatic<SkillFactory> skillFactory = mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(anyInt()))
                    .thenAnswer(invocation -> skills.get(invocation.getArgument(0)));

            assertEquals("ok, maxed my earlier jobs and rebuilt this one with 19 sp", BotBuildManager.respecSp(entry, bot));
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
}
