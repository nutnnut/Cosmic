package server.bots;

import client.BuffStat;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import constants.game.CharacterStance;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.Beginner;
import constants.skills.Bowmaster;
import constants.skills.Cleric;
import constants.skills.DragonKnight;
import constants.skills.Hunter;
import constants.skills.ILWizard;
import constants.skills.Magician;
import constants.skills.Rogue;
import constants.skills.Spearman;
import constants.skills.Warrior;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import server.StatEffect;
import server.bots.combat.BotAttackDataProvider;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.HexTool;

class BotCombatManagerTest {
    @Test
    void shouldMatchOpenStoryBasicAttackStanceIds() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();
        assertEquals(23, provider.getAttackStanceId("swingO1"));
        assertEquals(11, provider.getAttackStanceId("shoot1"));
        assertEquals(10, provider.getAttackStanceId("shot"));
        assertEquals(0, provider.getAttackStanceId("stand1"));
    }

    @Test
    void shouldUseOpenStoryFallbackAttackGroupsByWeaponType() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();
        BotAttackDataProvider.AttackAnimationSpec gunSpec = provider.getBasicAttackSpec(WeaponType.GUN);
        assertEquals(9, gunSpec.display());
        assertEquals("handgun", gunSpec.primaryAction());

        BotAttackDataProvider.AttackAnimationSpec bowSpec = provider.getBasicAttackSpec(WeaponType.BOW);
        assertEquals(3, bowSpec.display());
        assertEquals("shoot1", bowSpec.primaryAction());

        BotAttackDataProvider.AttackAnimationSpec twoHandedSpec = provider.getBasicAttackSpec(WeaponType.SWORD2H);
        assertEquals(5, twoHandedSpec.display());
        assertTrue(twoHandedSpec.actions().contains("swingT1"));
        assertTrue(twoHandedSpec.actions().contains("stabO1"));

        BotAttackDataProvider.AttackAnimationSpec degenerateBowSpec = provider.getBasicAttackSpec(WeaponType.BOW, true);
        assertEquals(3, degenerateBowSpec.display());
        assertTrue(degenerateBowSpec.actions().contains("swingT1"));
        assertTrue(degenerateBowSpec.actions().contains("swingT3"));
    }

    @Test
    void shouldUseExplicitSkillActionBeforeWeaponFallback() {
        Skill skill = new Skill(3121004);
        skill.setAction0("doublefire");

        String action = BotAttackExecutionProvider.resolveSkillAttackAction(null, skill, 1, WeaponType.BOW);

        assertEquals("doublefire", action);
    }

    @Test
    void shouldMatchRealMagicGuardSpecialMovePacketLayout() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);

        byte[] packet = BotCombatManager.buildSupportSpecialMovePacket(bot, Magician.MAGIC_GUARD, 20, 0x009195A5);

        assertArrayEquals(HexTool.toBytes("5B 00 A5 95 91 00 6A 88 1E 00 14 00 00"), packet);
    }

    @Test
    void shouldMatchRealBlessSpecialMovePacketLayout() {
        Character bot = mockBot(new Point(0x155D, 0x01C6), mock(MapleMap.class), 20_000, null);
        when(bot.isFacingLeft()).thenReturn(true);

        byte[] packet = BotCombatManager.buildSupportSpecialMovePacket(bot, Cleric.BLESS, 9, 0x00919AAF);

        assertArrayEquals(HexTool.toBytes("5B 00 AF 9A 91 00 4C 1C 23 00 09 5D 15 C6 01 80 00 00"), packet);
    }

    @Test
    void shouldNotFireAtTargetKilledBeforeSendGate() {
        // Repro for "bot shoots but hits no mob": a target validated alive at selection time gets killed
        // (cross-thread, or via a cadenced plan reused after death) before attackMonster builds the packet.
        Character bot = mockBot(new Point(0, 0), mock(MapleMap.class), 20_000, null);
        BotEntry entry = new BotEntry(bot, null, null);
        Monster dead = mockMob(new Point(10, 0), 1110101);
        when(dead.isAlive()).thenReturn(false);
        when(dead.getHp()).thenReturn(-1);
        BotCombatManager.AttackPlan plan = new BotCombatManager.AttackPlan(
                0, 0, 1, new Rectangle(-100, -100, 200, 200), List.of(dead),
                BotCombatManager.AttackRoute.CLOSE, 0, 0, 0, 0, 0, 0, 600, WeaponType.GUN);

        BotCombatManager.attackMonster(entry, bot, plan);

        assertEquals("blocked:dead-target", entry.dbgAttackExecResult);
        assertEquals(0, entry.attackCooldownMs, "no cooldown/packet committed when target already dead");
    }

    @Test
    void shouldPreferPowerStrikeOverBeginnerAttackForSingleTargetSlot() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        when(bot.getLevel()).thenReturn(16);

        Skill threeSnails = skillWithAttack(Beginner.THREE_SNAILS, 1, 1, 40);
        Skill powerStrike = skillWithAttack(Warrior.POWER_STRIKE, 1, 1, 260);
        Skill slashBlast = skillWithAttack(Warrior.SLASH_BLAST, 1, 6, 130);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(threeSnails, null);
        skills.put(powerStrike, null);
        skills.put(slashBlast, null);

        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            if (skill.getId() == threeSnails.getId()) {
                return (byte) 1;
            }
            if (skill.getId() == powerStrike.getId()) {
                return (byte) 1;
            }
            if (skill.getId() == slashBlast.getId()) {
                return (byte) 1;
            }
            return (byte) 0;
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(Warrior.POWER_STRIKE, entry.attackSkillId);
        assertEquals(Warrior.SLASH_BLAST, entry.aoeSkillId);
    }

    @Test
    void shouldNotTreatMpEaterAsActiveAttackSkill() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.IL_WIZARD);
        when(bot.getLevel()).thenReturn(35);

        Skill thunderbolt = skillWithAttack(ILWizard.THUNDERBOLT, 1, 6, 115);
        Skill mpEater = passiveOverTimeSkillWithCombatMetadata(ILWizard.MP_EATER, 115, 1, 1);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(mpEater, null);
        skills.put(thunderbolt, null);
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == ILWizard.MP_EATER || skill.getId() == ILWizard.THUNDERBOLT ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(ILWizard.THUNDERBOLT, entry.aoeSkillId);
        assertEquals(0, entry.attackSkillId);
        assertFalse(entry.buffSkillIds.contains(ILWizard.MP_EATER));
    }

    @Test
    void shouldCacheActionBackedSupportBuffButNotPassiveOverTimeSkill() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.CLERIC);
        when(bot.getLevel()).thenReturn(35);

        Skill bless = skillWithBuffAction(Cleric.BLESS);
        Skill mpEater = passiveOverTimeSkillWithCombatMetadata(Cleric.MP_EATER, 0, 1, 1);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(mpEater, null);
        skills.put(bless, null);
        when(bot.getSkills()).thenReturn(skills);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertFalse(entry.buffSkillIds.contains(Cleric.MP_EATER));
        assertTrue(entry.buffSkillIds.contains(Cleric.BLESS));
    }

    @Test
    void shouldClassifySummonIntoOwnBucketNotBuffs() {
        SkillFactory.loadAllSkills();
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.BOWMASTER);
        when(bot.getLevel()).thenReturn(120);

        // Phoenix is a CIRCLE_FOLLOW summon (SUMMON statup). Sharp Eyes is a real party buff.
        Set<Integer> skillIds = Set.of(Bowmaster.PHOENIX, Bowmaster.SHARP_EYES);
        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        for (int skillId : skillIds) {
            Skill skill = SkillFactory.getSkill(skillId);
            assertTrue(skill != null, "missing real WZ skill " + skillId);
            skills.put(skill, null);
        }
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skillIds.contains(skill.getId()) ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertTrue(entry.summonSkillIds.contains(Bowmaster.PHOENIX));
        assertFalse(entry.buffSkillIds.contains(Bowmaster.PHOENIX));
        assertTrue(entry.buffSkillIds.contains(Bowmaster.SHARP_EYES));
    }

    @Test
    void shouldStillCacheMagicClawAsActiveAttackSkill() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.MAGICIAN);
        when(bot.getLevel()).thenReturn(18);

        Skill magicClaw = skillWithAttack(Magician.MAGIC_CLAW, 2, 1, 40);
        Skill fakePassive = passiveSkillWithCombatMetadata(ILWizard.MP_EATER, 115, 1, 1);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(fakePassive, null);
        skills.put(magicClaw, null);
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == ILWizard.MP_EATER || skill.getId() == Magician.MAGIC_CLAW ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(Magician.MAGIC_CLAW, entry.attackSkillId);
    }

    @Test
    void shouldStillCacheDoubleShotAsActiveAttackSkill() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.BOWMAN);
        when(bot.getLevel()).thenReturn(12);

        Skill doubleShot = skillWithAttack(Archer.DOUBLE_SHOT, 2, 1, 130);
        Skill fakePassive = passiveSkillWithCombatMetadata(Archer.CRITICAL_SHOT, 200, 1, 1);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(fakePassive, null);
        skills.put(doubleShot, null);
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == Archer.CRITICAL_SHOT || skill.getId() == Archer.DOUBLE_SHOT ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(Archer.DOUBLE_SHOT, entry.attackSkillId);
    }

    @Test
    void shouldStillCacheLuckySevenAsActiveAttackSkill() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.THIEF);
        when(bot.getLevel()).thenReturn(14);

        Skill luckySeven = skillWithAttack(Rogue.LUCKY_SEVEN, 2, 1, 150);
        Skill fakePassive = passiveSkillWithCombatMetadata(Archer.CRITICAL_SHOT, 200, 1, 1);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(fakePassive, null);
        skills.put(luckySeven, null);
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == Archer.CRITICAL_SHOT || skill.getId() == Rogue.LUCKY_SEVEN ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(Rogue.LUCKY_SEVEN, entry.attackSkillId);
    }

    @Test
    void shouldStillCacheThunderboltAsActiveAttackSkill() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.IL_WIZARD);
        when(bot.getLevel()).thenReturn(35);

        Skill thunderbolt = skillWithAttack(ILWizard.THUNDERBOLT, 1, 6, 115);
        Skill fakePassive = passiveSkillWithCombatMetadata(ILWizard.MP_EATER, 115, 1, 1);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(fakePassive, null);
        skills.put(thunderbolt, null);
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == ILWizard.MP_EATER || skill.getId() == ILWizard.THUNDERBOLT ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(ILWizard.THUNDERBOLT, entry.aoeSkillId);
    }

    @Test
    void shouldIgnorePassiveCombatMetadataWithoutActiveSkillAction() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.BOWMAN);
        when(bot.getLevel()).thenReturn(12);

        Skill criticalShot = new Skill(Archer.CRITICAL_SHOT);
        StatEffect passiveEffect = mock(StatEffect.class);
        when(passiveEffect.getDamage()).thenReturn(200);
        when(passiveEffect.getAttackCount()).thenReturn(1);
        when(passiveEffect.getBulletCount()).thenReturn((short) 0);
        when(passiveEffect.getMobCount()).thenReturn(1);
        when(passiveEffect.isOverTime()).thenReturn(false);
        criticalShot.addLevelEffect(passiveEffect);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(criticalShot, null);
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == Archer.CRITICAL_SHOT ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(0, entry.attackSkillId);
        assertEquals(0, entry.aoeSkillId);
        assertTrue(entry.buffSkillIds.isEmpty());
    }

    @Test
    void shouldIgnoreFinalAttackPassiveDamageMetadata() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.HUNTER);
        when(bot.getLevel()).thenReturn(35);

        Skill finalAttack = passiveSkillWithCombatMetadata(Hunter.FINAL_ATTACK, 105, 1, 1);
        finalAttack.setSkillType(3);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(finalAttack, null);
        when(bot.getSkills()).thenReturn(skills);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(0, entry.attackSkillId);
        assertEquals(0, entry.aoeSkillId);
        assertTrue(entry.buffSkillIds.isEmpty());
    }

    @Test
    void shouldClassifyInspectedSecondJobSkillsFromRealWzData() {
        SkillFactory.loadAllSkills();

        // Slow is a mob-targeting debuff (mobCount + bbox, no caster statup), not a rebuffable
        // self-buff: the bot only casts buffs via a self/party SPECIAL_MOVE, so it is excluded.
        assertRealWzCache(Job.IL_WIZARD, 35,
                Set.of(ILWizard.MP_EATER, ILWizard.MEDITATION, ILWizard.SLOW, ILWizard.COLD_BEAM, ILWizard.THUNDERBOLT),
                ILWizard.COLD_BEAM, ILWizard.THUNDERBOLT,
                Set.of(ILWizard.MEDITATION),
                Set.of(ILWizard.MP_EATER, ILWizard.SLOW));
        assertRealWzCache(Job.CLERIC, 35,
                Set.of(Cleric.MP_EATER, Cleric.HEAL, Cleric.INVINCIBLE, Cleric.BLESS, Cleric.HOLY_ARROW),
                Cleric.HOLY_ARROW, 0,
                Set.of(Cleric.INVINCIBLE, Cleric.BLESS),
                Set.of(Cleric.MP_EATER));
        assertRealWzCache(Job.HUNTER, 35,
                Set.of(Archer.DOUBLE_SHOT, Hunter.FINAL_ATTACK, Hunter.BOW_BOOSTER, Hunter.SOUL_ARROW, Hunter.ARROW_BOMB),
                Archer.DOUBLE_SHOT, Hunter.ARROW_BOMB,
                Set.of(Hunter.BOW_BOOSTER, Hunter.SOUL_ARROW),
                Set.of(Hunter.FINAL_ATTACK));
        assertRealWzCache(Job.ASSASSIN, 35,
                Set.of(Rogue.LUCKY_SEVEN, Assassin.CRITICAL_THROW, Assassin.CLAW_BOOSTER, Assassin.HASTE, Assassin.DRAIN),
                Rogue.LUCKY_SEVEN, 0,
                Set.of(Assassin.CLAW_BOOSTER, Assassin.HASTE),
                Set.of(Assassin.CRITICAL_THROW));
        assertRealWzCache(Job.SPEARMAN, 35,
                Set.of(Warrior.POWER_STRIKE, Warrior.SLASH_BLAST, Spearman.FINAL_ATTACK_SPEAR,
                        Spearman.FINAL_ATTACK_POLEARM, Spearman.SPEAR_BOOSTER, Spearman.IRON_WILL, Spearman.HYPER_BODY),
                Warrior.POWER_STRIKE, Warrior.SLASH_BLAST,
                Set.of(Spearman.SPEAR_BOOSTER, Spearman.IRON_WILL, Spearman.HYPER_BODY),
                Set.of(Spearman.FINAL_ATTACK_SPEAR, Spearman.FINAL_ATTACK_POLEARM));
    }

    @Test
    void shouldCacheDragonKnightAttackCandidatesButExcludePowerCrash() {
        SkillFactory.loadAllSkills();
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.DRAGONKNIGHT);
        when(bot.getLevel()).thenReturn(100);

        Set<Integer> skillIds = Set.of(
                DragonKnight.SPEAR_CRUSHER,
                DragonKnight.SPEAR_DRAGON_FURY,
                DragonKnight.DRAGON_ROAR,
                DragonKnight.POWER_CRASH,
                DragonKnight.DRAGON_BLOOD);
        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        for (int skillId : skillIds) {
            Skill skill = SkillFactory.getSkill(skillId);
            assertTrue(skill != null, "missing real WZ skill " + skillId);
            skills.put(skill, null);
        }
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skillIds.contains(skill.getId()) ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertTrue(entry.attackSkillIds.contains(DragonKnight.SPEAR_CRUSHER));
        assertTrue(entry.attackSkillIds.contains(DragonKnight.SPEAR_DRAGON_FURY));
        assertTrue(entry.attackSkillIds.contains(DragonKnight.DRAGON_ROAR));
        assertFalse(entry.attackSkillIds.contains(DragonKnight.POWER_CRASH));
        assertFalse(entry.buffSkillIds.contains(DragonKnight.POWER_CRASH));
        assertTrue(entry.buffSkillIds.contains(DragonKnight.DRAGON_BLOOD));
    }

    // Regression: Teleport's WZ omits the "damage" attribute, so StatEffect's loader
    // defaults damage to 100. Before hasDamage() was plumbed through, isActiveAttackSkill
    // accepted Teleport as the bot's attack skill on a mid-build I/L Wizard that hadn't
    // learned Cold Beam yet, producing illegal 1-damage magic attacks at the WZ bbox
    // (500x300 around the bot).
    @Test
    void shouldNotPickTeleportAsAttackSkillJustBecauseDamageDefaults() {
        SkillFactory.loadAllSkills();
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(Job.IL_WIZARD);
        when(bot.getLevel()).thenReturn(40);

        Set<Integer> skillIds = Set.of(ILWizard.TELEPORT, ILWizard.MEDITATION);
        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        for (int skillId : skillIds) {
            Skill skill = SkillFactory.getSkill(skillId);
            assertTrue(skill != null, "missing real WZ skill " + skillId);
            skills.put(skill, null);
        }
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skillIds.contains(skill.getId()) ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(0, entry.attackSkillId);
        assertFalse(entry.attackSkillIds.contains(ILWizard.TELEPORT));
    }

    // Regression: Hunter.ARROW_BOMB declares no "damage" in WZ; the damage % lives in "x"
    // (72 at level 1). getDamagePercent() must fall back to x instead of returning the
    // loader-default 100, otherwise Arrow Bomb deals base weapon damage and the AoE
    // scorer over-weights it as a 100% skill.
    @Test
    void arrowBombShouldDeriveDamagePercentFromXNotLoaderDefault() {
        SkillFactory.loadAllSkills();
        Skill arrowBomb = SkillFactory.getSkill(Hunter.ARROW_BOMB);
        assertTrue(arrowBomb != null, "missing real WZ skill Hunter.ARROW_BOMB");
        StatEffect lvl1 = arrowBomb.getEffect(1);
        assertFalse(lvl1.hasDamage(), "Arrow Bomb WZ must not declare 'damage'");
        assertEquals(72, lvl1.getDamagePercent(),
                "level-1 'x' = 72 should be returned as damage %");
    }

    @Test
    void shouldNotUseDragonRoarBelowTargetThresholdWithoutNearbyHealer() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        when(bot.calculateMaxBaseDamage(100)).thenReturn(20);
        when(bot.calculateMinBaseDamage(100, 0.1d)).thenReturn(10);
        Skill cheaperAoe = skillWithAttackBox(Warrior.SLASH_BLAST, 1, 6, 200,
                new Rectangle(80, 150, 220, 100));
        Skill roar = skillWithAttackBox(DragonKnight.DRAGON_ROAR, 1, 15, 240,
                new Rectangle(-300, -200, 800, 500));
        List<Monster> mobs = List.of(
                mockMob(new Point(140, 200), 9300300),
                mockMob(new Point(150, 200), 9300301),
                mockMob(new Point(160, 200), 9300302));
        when(map.getAllMonsters()).thenReturn(mobs);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillIds.add(cheaperAoe.getId());
        entry.attackSkillIds.add(roar.getId());

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(cheaperAoe.getId())).thenReturn(cheaperAoe);
            skillFactory.when(() -> SkillFactory.getSkill(roar.getId())).thenReturn(roar);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, mobs.get(0));

            assertEquals(Warrior.SLASH_BLAST, plan.skillId);
        }
    }

    @Test
    void shouldUseDragonRoarWhenLargeClusterMeetsTargetThreshold() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        when(bot.calculateMaxBaseDamage(100)).thenReturn(20);
        when(bot.calculateMinBaseDamage(100, 0.1d)).thenReturn(10);
        Skill cheaperAoe = skillWithAttackBox(Warrior.SLASH_BLAST, 1, 6, 200,
                new Rectangle(80, 150, 220, 100));
        Skill roar = skillWithAttackBox(DragonKnight.DRAGON_ROAR, 1, 15, 240,
                new Rectangle(-300, -200, 800, 500));
        List<Monster> mobs = List.of(
                mockMob(new Point(140, 200), 9300400),
                mockMob(new Point(150, 200), 9300401),
                mockMob(new Point(160, 200), 9300402),
                mockMob(new Point(170, 200), 9300403),
                mockMob(new Point(180, 200), 9300404),
                mockMob(new Point(190, 200), 9300405),
                mockMob(new Point(200, 200), 9300406),
                mockMob(new Point(210, 200), 9300407),
                mockMob(new Point(220, 200), 9300408),
                mockMob(new Point(230, 200), 9300409));
        when(map.getAllMonsters()).thenReturn(mobs);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillIds.add(cheaperAoe.getId());
        entry.attackSkillIds.add(roar.getId());

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(cheaperAoe.getId())).thenReturn(cheaperAoe);
            skillFactory.when(() -> SkillFactory.getSkill(roar.getId())).thenReturn(roar);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, mobs.get(0));

            assertEquals(DragonKnight.DRAGON_ROAR, plan.skillId);
            assertEquals(10, plan.targets.size());
        }
    }

    @Test
    void shouldAllowDragonRoarBelowTargetThresholdWhenNearbyHealerAndDamageWins() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Character healer = mockBot(new Point(120, 200), map, 20_000, null);
        when(healer.getId()).thenReturn(2);
        when(healer.getSkillLevel(Cleric.HEAL)).thenReturn(1);
        when(bot.getPartyMembersOnSameMap()).thenReturn(List.of(healer));
        when(bot.calculateMaxBaseDamage(100)).thenReturn(20);
        when(bot.calculateMinBaseDamage(100, 0.1d)).thenReturn(10);
        Skill cheaperAoe = skillWithAttackBox(Warrior.SLASH_BLAST, 1, 6, 100,
                new Rectangle(80, 150, 220, 100));
        Skill roar = skillWithAttackBox(DragonKnight.DRAGON_ROAR, 1, 15, 240,
                new Rectangle(-300, -200, 800, 500));
        List<Monster> mobs = List.of(
                mockMob(new Point(140, 200), 9300410),
                mockMob(new Point(150, 200), 9300411),
                mockMob(new Point(160, 200), 9300412));
        when(map.getAllMonsters()).thenReturn(mobs);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillIds.add(cheaperAoe.getId());
        entry.attackSkillIds.add(roar.getId());

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(cheaperAoe.getId())).thenReturn(cheaperAoe);
            skillFactory.when(() -> SkillFactory.getSkill(roar.getId())).thenReturn(roar);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, mobs.get(0));

            assertEquals(DragonKnight.DRAGON_ROAR, plan.skillId);
        }
    }

    @Test
    void shouldNotForceDragonRoarWithNearbyHealerWhenAlternativeDamageWins() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Character healer = mockBot(new Point(120, 200), map, 20_000, null);
        when(healer.getId()).thenReturn(2);
        when(healer.getSkillLevel(Cleric.HEAL)).thenReturn(1);
        when(bot.getPartyMembersOnSameMap()).thenReturn(List.of(healer));
        when(bot.calculateMaxBaseDamage(100)).thenReturn(20);
        when(bot.calculateMinBaseDamage(100, 0.1d)).thenReturn(10);
        Skill cheaperAoe = skillWithAttackBox(Warrior.SLASH_BLAST, 1, 6, 500,
                new Rectangle(80, 150, 220, 100));
        Skill roar = skillWithAttackBox(DragonKnight.DRAGON_ROAR, 1, 15, 10,
                new Rectangle(-300, -200, 800, 500));
        List<Monster> mobs = List.of(
                mockMob(new Point(140, 200), 9300420),
                mockMob(new Point(150, 200), 9300421),
                mockMob(new Point(160, 200), 9300422));
        when(map.getAllMonsters()).thenReturn(mobs);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillIds.add(cheaperAoe.getId());
        entry.attackSkillIds.add(roar.getId());

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(cheaperAoe.getId())).thenReturn(cheaperAoe);
            skillFactory.when(() -> SkillFactory.getSkill(roar.getId())).thenReturn(roar);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, mobs.get(0));

            assertEquals(Warrior.SLASH_BLAST, plan.skillId);
        }
    }

    @Test
    void shouldNotUseDragonRoarAtOrBelowHalfHp() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 10_000, null);
        when(bot.getCurrentMaxHp()).thenReturn(20_000);
        when(bot.calculateMaxBaseDamage(100)).thenReturn(20);
        when(bot.calculateMinBaseDamage(100, 0.1d)).thenReturn(10);
        Skill roar = skillWithAttackBox(DragonKnight.DRAGON_ROAR, 1, 15, 240,
                new Rectangle(-300, -200, 800, 500));
        List<Monster> mobs = List.of(
                mockMob(new Point(140, 200), 9300430),
                mockMob(new Point(150, 200), 9300431),
                mockMob(new Point(160, 200), 9300432),
                mockMob(new Point(170, 200), 9300433),
                mockMob(new Point(180, 200), 9300434),
                mockMob(new Point(190, 200), 9300435),
                mockMob(new Point(200, 200), 9300436),
                mockMob(new Point(210, 200), 9300437),
                mockMob(new Point(220, 200), 9300438),
                mockMob(new Point(230, 200), 9300439));
        when(map.getAllMonsters()).thenReturn(mobs);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillIds.add(roar.getId());

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(roar.getId())).thenReturn(roar);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, mobs.get(0));

            assertEquals(0, plan.skillId);
        }
    }

    @Test
    void shouldSkipPolearmDragonKnightSkillsWhenSpearIsEquipped() {
        assertTrue(BotCombatManager.canUseAttackSkillWithWeapon(DragonKnight.SPEAR_CRUSHER, WeaponType.SPEAR_STAB));
        assertTrue(BotCombatManager.canUseAttackSkillWithWeapon(DragonKnight.SPEAR_DRAGON_FURY, WeaponType.SPEAR_STAB));
        assertFalse(BotCombatManager.canUseAttackSkillWithWeapon(DragonKnight.POLE_ARM_CRUSHER, WeaponType.SPEAR_STAB));
        assertFalse(BotCombatManager.canUseAttackSkillWithWeapon(DragonKnight.POLE_ARM_DRAGON_FURY, WeaponType.SPEAR_STAB));
        assertTrue(BotCombatManager.canUseAttackSkillWithWeapon(DragonKnight.POLE_ARM_CRUSHER, WeaponType.POLE_ARM_SWING));
    }

    @Test
    void shouldChooseSingleTargetSkillWhenMobDefenseCollapsesLowDamageAoeLines() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Skill powerStrike = skillWithAttack(Warrior.POWER_STRIKE, 1, 1, 260);
        Skill slashBlast = skillWithAttack(Warrior.SLASH_BLAST, 6, 6, 20);
        Monster primary = mockMob(new Point(140, 200), 9300100);
        Monster secondary = mockMob(new Point(150, 200), 9300101);
        when(primary.getWdef()).thenReturn(500);
        when(secondary.getWdef()).thenReturn(500);
        when(map.getAllMonsters()).thenReturn(List.of(primary, secondary));
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == powerStrike.getId() || skill.getId() == slashBlast.getId() ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillId = powerStrike.getId();
        entry.aoeSkillId = slashBlast.getId();

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(powerStrike.getId())).thenReturn(powerStrike);
            skillFactory.when(() -> SkillFactory.getSkill(slashBlast.getId())).thenReturn(slashBlast);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, primary);

            assertEquals(Warrior.POWER_STRIKE, plan.skillId);
            assertEquals(List.of(primary), plan.targets);
        }
    }

    @Test
    void shouldNotUseWeakAoeOnlyBecauseCurrentHpIsLow() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Skill powerStrike = skillWithAttack(Warrior.POWER_STRIKE, 1, 1, 260);
        Skill slashBlast = skillWithAttack(Warrior.SLASH_BLAST, 1, 6, 120);
        Monster primary = mockMob(new Point(140, 200), 9300200);
        Monster secondary = mockMob(new Point(150, 200), 9300201);
        when(primary.getHp()).thenReturn(100);
        when(map.getAllMonsters()).thenReturn(List.of(primary, secondary));
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == powerStrike.getId() || skill.getId() == slashBlast.getId() ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillId = powerStrike.getId();
        entry.aoeSkillId = slashBlast.getId();

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(powerStrike.getId())).thenReturn(powerStrike);
            skillFactory.when(() -> SkillFactory.getSkill(slashBlast.getId())).thenReturn(slashBlast);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, primary);

            assertEquals(Warrior.POWER_STRIKE, plan.skillId);
            assertEquals(List.of(primary), plan.targets);
        }
    }

    @Test
    void shouldRepositionToClusterCentroidWhenAoeDpsBeatsSingleTarget() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Skill powerStrike = skillWithAttack(Warrior.POWER_STRIKE, 1, 1, 260);
        // AoE box authored bot-relative: at the bot (x=100) it covers x[20,180], catching only the
        // edge mob; after the bot steps to the centroid the same box (translated) catches all three.
        Skill slashBlast = skillWithAttackBox(Warrior.SLASH_BLAST, 4, 6, 50,
                new Rectangle(20, 170, 160, 60));
        Monster primary = mockMob(new Point(140, 200), 9300500);
        Monster mid = mockMob(new Point(200, 200), 9300501);
        Monster far = mockMob(new Point(260, 200), 9300502);
        when(map.getAllMonsters()).thenReturn(List.of(primary, mid, far));
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == powerStrike.getId() || skill.getId() == slashBlast.getId() ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillId = powerStrike.getId();
        entry.aoeSkillId = slashBlast.getId();
        entry.aoeSkillMobs = 6;

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(powerStrike.getId())).thenReturn(powerStrike);
            skillFactory.when(() -> SkillFactory.getSkill(slashBlast.getId())).thenReturn(slashBlast);

            // At the bot's current position the AoE catches only the edge mob, so the single-target
            // skill wins on DPS — this is the fire-now plan that would otherwise trigger immediately.
            BotCombatManager.AttackPlan fireNow = BotCombatManager.planAttack(entry, bot, primary);
            assertEquals(Warrior.POWER_STRIKE, fireNow.skillId);
            assertEquals(List.of(primary), fireNow.targets);

            Point reposition = BotCombatManager.aoeRepositionTarget(entry, bot, primary, fireNow);
            assertNotNull(reposition, "should defer the single-target shot to step into the cluster");
            assertEquals(200, reposition.x, "should walk to the 3-mob centroid (140+200+260)/3");
        }
    }

    @Test
    void shouldNotRepositionWhenToggleDisabled() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Skill powerStrike = skillWithAttack(Warrior.POWER_STRIKE, 1, 1, 260);
        Skill slashBlast = skillWithAttackBox(Warrior.SLASH_BLAST, 4, 6, 50,
                new Rectangle(20, 170, 160, 60));
        Monster primary = mockMob(new Point(140, 200), 9300540);
        Monster mid = mockMob(new Point(200, 200), 9300541);
        Monster far = mockMob(new Point(260, 200), 9300542);
        when(map.getAllMonsters()).thenReturn(List.of(primary, mid, far));
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == powerStrike.getId() || skill.getId() == slashBlast.getId() ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillId = powerStrike.getId();
        entry.aoeSkillId = slashBlast.getId();
        entry.aoeSkillMobs = 6;

        boolean original = BotCombatManager.cfg.AOE_REPOSITION_ENABLED;
        BotCombatManager.cfg.AOE_REPOSITION_ENABLED = false;
        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(powerStrike.getId())).thenReturn(powerStrike);
            skillFactory.when(() -> SkillFactory.getSkill(slashBlast.getId())).thenReturn(slashBlast);

            BotCombatManager.AttackPlan fireNow = BotCombatManager.planAttack(entry, bot, primary);
            assertNull(BotCombatManager.aoeRepositionTarget(entry, bot, primary, fireNow));
        } finally {
            BotCombatManager.cfg.AOE_REPOSITION_ENABLED = original;
        }
    }

    @Test
    void shouldNotRepositionWhenNoAoeSkill() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Skill powerStrike = skillWithAttack(Warrior.POWER_STRIKE, 1, 1, 260);
        Monster primary = mockMob(new Point(140, 200), 9300510);
        Monster mid = mockMob(new Point(200, 200), 9300511);
        Monster far = mockMob(new Point(260, 200), 9300512);
        when(map.getAllMonsters()).thenReturn(List.of(primary, mid, far));
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillId = powerStrike.getId();
        // No aoeSkillId / aoeSkillMobs left at default 1.

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(powerStrike.getId())).thenReturn(powerStrike);

            BotCombatManager.AttackPlan fireNow = BotCombatManager.planAttack(entry, bot, primary);
            assertNull(BotCombatManager.aoeRepositionTarget(entry, bot, primary, fireNow));
        }
    }

    @Test
    void shouldNotRepositionWhenFireNowPlanIsAlreadyAoe() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Monster primary = mockMob(new Point(140, 200), 9300520);
        Monster mid = mockMob(new Point(200, 200), 9300521);
        when(map.getAllMonsters()).thenReturn(List.of(primary, mid));

        BotEntry entry = new BotEntry(bot, null, null);
        entry.aoeSkillId = Warrior.SLASH_BLAST;
        entry.aoeSkillMobs = 6;

        // Fire-now plan is the AoE itself — nothing to upgrade by repositioning.
        BotCombatManager.AttackPlan aoePlan = new BotCombatManager.AttackPlan(
                Warrior.SLASH_BLAST, 1, 1, new Rectangle(20, 170, 160, 60), List.of(primary),
                BotCombatManager.AttackRoute.CLOSE, 0, 0, 0, 0, 0, 0, 100, null);
        assertNull(BotCombatManager.aoeRepositionTarget(entry, bot, primary, aoePlan));
    }

    @Test
    void shouldStepCloserForAStrongerOutOfReachSkill() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        // Weak long-reach skill (the "Avenger from afar" analog): wide box x[20,320] reaches the
        // target at x=240, low damage -> this is the fire-now plan.
        Skill weakFar = skillWithAttackBox(Warrior.SLASH_BLAST, 1, 1, 10, new Rectangle(20, 170, 300, 60));
        // Strong short-reach skill: box x[60,160] does NOT reach the target now, high damage.
        Skill strongNear = skillWithAttackBox(Warrior.POWER_STRIKE, 1, 1, 400, new Rectangle(60, 170, 100, 60));
        Monster primary = mockMob(new Point(240, 200), 9300560);
        when(map.getAllMonsters()).thenReturn(List.of(primary));
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillId = strongNear.getId();
        entry.aoeSkillId = weakFar.getId();

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(strongNear.getId())).thenReturn(strongNear);
            skillFactory.when(() -> SkillFactory.getSkill(weakFar.getId())).thenReturn(weakFar);

            // The strong skill is out of reach now, so the only in-range plan is the weak far skill.
            BotCombatManager.AttackPlan fireNow = new BotCombatManager.AttackPlan(
                    weakFar.getId(), 1, 1, new Rectangle(20, 170, 300, 60), List.of(primary),
                    BotCombatManager.AttackRoute.CLOSE, 0, 0, 0, 0, 0, 0, 100, null);

            Point step = BotCombatManager.betterReachRepositionTarget(entry, bot, primary, fireNow);
            assertNotNull(step, "should step closer so the much stronger skill lands");
            // reach(strong) = maxX(160) - bot(100) = 60; dist = 140; step = 140 - 60 + arrival(20) = 100.
            assertEquals(100 + 100, step.x);
        }
    }

    @Test
    void shouldNotStepCloserWhenToggleDisabled() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Skill weakFar = skillWithAttackBox(Warrior.SLASH_BLAST, 1, 1, 10, new Rectangle(20, 170, 300, 60));
        Skill strongNear = skillWithAttackBox(Warrior.POWER_STRIKE, 1, 1, 400, new Rectangle(60, 170, 100, 60));
        Monster primary = mockMob(new Point(240, 200), 9300561);
        when(map.getAllMonsters()).thenReturn(List.of(primary));
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillId = strongNear.getId();
        entry.aoeSkillId = weakFar.getId();

        boolean original = BotCombatManager.cfg.BETTER_REACH_REPOSITION_ENABLED;
        BotCombatManager.cfg.BETTER_REACH_REPOSITION_ENABLED = false;
        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(strongNear.getId())).thenReturn(strongNear);
            skillFactory.when(() -> SkillFactory.getSkill(weakFar.getId())).thenReturn(weakFar);

            BotCombatManager.AttackPlan fireNow = new BotCombatManager.AttackPlan(
                    weakFar.getId(), 1, 1, new Rectangle(20, 170, 300, 60), List.of(primary),
                    BotCombatManager.AttackRoute.CLOSE, 0, 0, 0, 0, 0, 0, 100, null);
            assertNull(BotCombatManager.betterReachRepositionTarget(entry, bot, primary, fireNow));
        } finally {
            BotCombatManager.cfg.BETTER_REACH_REPOSITION_ENABLED = original;
        }
    }

    @Test
    void shouldNotRepositionForLoneMob() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Skill powerStrike = skillWithAttack(Warrior.POWER_STRIKE, 1, 1, 260);
        Skill slashBlast = skillWithAttackBox(Warrior.SLASH_BLAST, 4, 6, 50,
                new Rectangle(20, 170, 160, 60));
        Monster primary = mockMob(new Point(140, 200), 9300530);
        when(map.getAllMonsters()).thenReturn(List.of(primary));
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skill.getId() == powerStrike.getId() || skill.getId() == slashBlast.getId() ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillId = powerStrike.getId();
        entry.aoeSkillId = slashBlast.getId();
        entry.aoeSkillMobs = 6;

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(powerStrike.getId())).thenReturn(powerStrike);
            skillFactory.when(() -> SkillFactory.getSkill(slashBlast.getId())).thenReturn(slashBlast);

            BotCombatManager.AttackPlan fireNow = BotCombatManager.planAttack(entry, bot, primary);
            assertNull(BotCombatManager.aoeRepositionTarget(entry, bot, primary, fireNow));
        }
    }

    @Test
    void shouldDetectAoeBotSingleTargeting() {
        Monster mob = mockMob(new Point(140, 200), 9300560);
        BotEntry entry = new BotEntry(mock(Character.class), null, null);
        entry.aoeSkillId = Warrior.SLASH_BLAST;
        entry.aoeSkillMobs = 6;

        BotCombatManager.AttackPlan singleTarget = new BotCombatManager.AttackPlan(
                Warrior.POWER_STRIKE, 1, 1, new Rectangle(0, 0, 1, 1), List.of(mob),
                BotCombatManager.AttackRoute.CLOSE, 0, 0, 0, 0, 0, 0, 100, null);
        assertTrue(BotCombatManager.isAoeBotSingleTargeting(entry, singleTarget));

        BotCombatManager.AttackPlan aoePlan = new BotCombatManager.AttackPlan(
                Warrior.SLASH_BLAST, 1, 1, new Rectangle(0, 0, 1, 1), List.of(mob),
                BotCombatManager.AttackRoute.CLOSE, 0, 0, 0, 0, 0, 0, 100, null);
        assertFalse(BotCombatManager.isAoeBotSingleTargeting(entry, aoePlan));

        entry.aoeSkillId = 0;
        assertFalse(BotCombatManager.isAoeBotSingleTargeting(entry, singleTarget));
    }

    @Test
    void shouldCountAoeClusterSizeCappedAtSkillMobs() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Monster anchor = mockMob(new Point(140, 200), 9300570);
        Monster near1 = mockMob(new Point(180, 200), 9300571);
        Monster near2 = mockMob(new Point(220, 200), 9300572);
        Monster far = mockMob(new Point(600, 200), 9300573); // outside AOE_CLUSTER_RADIUS_PX
        when(map.getAllMonsters()).thenReturn(List.of(anchor, near1, near2, far));

        BotEntry entry = new BotEntry(bot, null, null);
        entry.aoeSkillId = Warrior.SLASH_BLAST;
        entry.aoeSkillMobs = 6;
        assertEquals(3, BotCombatManager.aoeClusterSize(entry, bot, anchor));

        entry.aoeSkillMobs = 2; // cap below cluster size
        assertEquals(2, BotCombatManager.aoeClusterSize(entry, bot, anchor));
    }

    @Test
    void shouldTreatBasicStaffAttacksAsCloseRange() {
        assertEquals(BotCombatManager.AttackRoute.CLOSE, BotAttackExecutionProvider.determineBasicWeaponRoute(WeaponType.STAFF));
    }

    @Test
    void shouldTreatNonMageSkillsWithStaffAsCloseRange() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);

        try (MockedStatic<BotAttackExecutionProvider> attacks =
                     Mockito.mockStatic(BotAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            attacks.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.STAFF);

            assertEquals(BotCombatManager.AttackRoute.CLOSE,
                    BotAttackExecutionProvider.determineSkillRoute(bot, Warrior.POWER_STRIKE));
            assertEquals(BotCombatManager.AttackRoute.MAGIC,
                    BotAttackExecutionProvider.determineSkillRoute(bot, Magician.MAGIC_CLAW));
        }
    }

    @Test
    void shouldUseDpsInsteadOfRawDamageForSlowDragonRoar() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        when(bot.calculateMaxBaseDamage(100)).thenReturn(1_000);
        when(bot.calculateMinBaseDamage(100, 0.1d)).thenReturn(500);
        Skill slashBlast = skillWithAttackBox(Warrior.SLASH_BLAST, 1, 6, 200,
                new Rectangle(80, 150, 220, 100), 500);
        Skill roar = skillWithAttackBox(DragonKnight.DRAGON_ROAR, 1, 15, 240,
                new Rectangle(-300, -200, 800, 500), 4_000);
        List<Monster> mobs = List.of(
                mockMob(new Point(140, 200), 9300440),
                mockMob(new Point(150, 200), 9300441),
                mockMob(new Point(160, 200), 9300442),
                mockMob(new Point(170, 200), 9300443),
                mockMob(new Point(180, 200), 9300444),
                mockMob(new Point(190, 200), 9300445),
                mockMob(new Point(200, 200), 9300446),
                mockMob(new Point(210, 200), 9300447),
                mockMob(new Point(220, 200), 9300448),
                mockMob(new Point(230, 200), 9300449));
        when(map.getAllMonsters()).thenReturn(mobs);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        BotEntry entry = new BotEntry(bot, null, null);
        entry.attackSkillIds.add(slashBlast.getId());
        entry.attackSkillIds.add(roar.getId());

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(slashBlast.getId())).thenReturn(slashBlast);
            skillFactory.when(() -> SkillFactory.getSkill(roar.getId())).thenReturn(roar);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, mobs.get(0));

            assertEquals(Warrior.SLASH_BLAST, plan.skillId);
        }
    }

    @Test
    void shouldSkipPartySupportBuffsWhenMapHasNoLivingMobs() {
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(List.of());

        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Character ally = mock(Character.class);
        when(ally.getId()).thenReturn(2);
        when(ally.isAlive()).thenReturn(true);
        when(ally.getPosition()).thenReturn(new Point(120, 200));
        when(ally.getBuffedValue(BuffStat.WATK)).thenReturn(null);
        when(bot.getPartyMembersOnSameMap()).thenReturn(List.of(ally));

        BotEntry entry = new BotEntry(bot, null, null);
        entry.following = true;
        entry.buffSkillIds.add(Cleric.BLESS);
        entry.nextSupportBuffAt.put(Cleric.BLESS, 0L);

        Skill bless = new Skill(Cleric.BLESS);
        StatEffect effect = mock(StatEffect.class);
        when(effect.isOverTime()).thenReturn(true);
        when(effect.getStatups()).thenReturn(List.of(new tools.Pair<>(BuffStat.WATK, 10)));
        bless.addLevelEffect(effect);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 1);

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(Cleric.BLESS)).thenReturn(bless);

            BotCombatManager.tickBuffs(entry, bot);
        }

        assertEquals("no skill buff checks yet", entry.lastSkillBuffActionSummary);
        assertFalse(entry.nextSupportBuffAt.containsKey(Cleric.BLESS) && entry.nextSupportBuffAt.get(Cleric.BLESS) > 0L);
        assertEquals(0, entry.attackCooldownMs);
    }

    @Test
    void shouldMatchOpenStoryGroundMobKnockbackWhenHitFromRight() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Monster mob = mockMob(new Point(140, 200), 9300000);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.facingDir = 1;

        runWithStubbedBotAfter(() -> BotCombatManager.applyMobHit(entry, bot, mob));

        assertTrue(entry.inAir);
        assertFalse(entry.climbing);
        assertTrue(entry.climbUpIntent);
        assertEquals(new Point(100, 200), bot.getPosition());
        assertEquals(-Math.round(1.5f * BotMovementManager.cfg.TICK_MS / 8.0f), entry.airVelX);
        assertEquals(-3.5f * BotMovementManager.cfg.TICK_MS / 8.0f, entry.velY, 1.0e-4f);
        assertEquals(1, entry.facingDir);
        assertEquals(CharacterStance.JUMP_RIGHT_STANCE, bot.getStance());
        assertDamageDirection(map, bot, 2, 0);
    }

    @Test
    void shouldOnlyRedirectHorizontalVelocityWhenMobHitOccursMidAir() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Monster mob = mockMob(new Point(60, 200), 9300001);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        entry.physX = 100;
        entry.physY = 200;
        entry.velY = 12.5f;
        entry.airVelX = -4;
        entry.facingDir = -1;

        runWithStubbedBotAfter(() -> BotCombatManager.applyMobHit(entry, bot, mob));

        assertTrue(entry.inAir);
        assertTrue(entry.climbUpIntent);
        assertEquals(new Point(100, 200), bot.getPosition());
        assertEquals(12.5f, entry.velY, 1.0e-4f);
        assertEquals(Math.round(1.5f * BotMovementManager.cfg.TICK_MS / 8.0f), entry.airVelX);
        assertEquals(-1, entry.facingDir);
        assertEquals(CharacterStance.JUMP_LEFT_STANCE, bot.getStance());
        assertDamageDirection(map, bot, 2, 1);
    }

    @Test
    void shouldSkipMobKnockbackWhenStanceBuffIsMaxed() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, 100);
        Monster mob = mockMob(new Point(140, 200), 9300002);
        BotEntry entry = new BotEntry(bot, null, null);

        runWithStubbedBotAfter(() -> BotCombatManager.applyMobHit(entry, bot, mob));

        assertFalse(entry.inAir);
        assertFalse(entry.climbing);
        assertEquals(new Point(100, 200), bot.getPosition());
        assertEquals(0, entry.airVelX);
        assertEquals(0.0f, entry.velY, 1.0e-4f);
        assertDamageDirection(map, bot, 1, 0);
    }

    @Test
    void shouldKeepDirectionAwareDeadStanceAfterFatalMobHit() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 1, null);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.facingDir = -1;
        Monster mob = mockMob(new Point(140, 200), 9300003);

        runWithStubbedBotAfter(() -> BotCombatManager.applyMobHit(entry, bot, mob));

        assertEquals(CharacterStance.DEAD_LEFT_STANCE, bot.getStance());
        assertTrue(entry.deadUntil > 0);
        assertFalse(entry.inAir);
        assertFalse(entry.climbing);
    }

    @Test
    void shouldNotTargetFriendlyMonsterWhenSearchingForClosestTarget() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Monster friendly = mockFriendlyMob(new Point(140, 200), 9300500);
        Monster hostile = mockMob(new Point(160, 200), 9300501);

        // Only a friendly mob on the map -> no attackable target, even though it is in range.
        when(map.getAllMonsters()).thenReturn(List.of(friendly));
        assertNull(BotCombatManager.findClosestAliveMonster(bot, 1_000_000d));

        // Friendly mob is closer, but the bot must skip it and pick the farther hostile mob.
        when(map.getAllMonsters()).thenReturn(List.of(friendly, hostile));
        assertEquals(hostile, BotCombatManager.findClosestAliveMonster(bot, 1_000_000d));
    }

    @Test
    void shouldNotTakeContactDamageFromFriendlyMonster() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Monster friendly = mockFriendlyMob(new Point(100, 200), 9300502);
        when(map.getAllMonsters()).thenReturn(List.of(friendly));
        BotEntry entry = new BotEntry(bot, null, null);

        try (MockedStatic<BotCombatManager> combat =
                     Mockito.mockStatic(BotCombatManager.class, Mockito.CALLS_REAL_METHODS)) {
            combat.when(() -> BotCombatManager.isMobTouchingBot(any(Rectangle.class),
                    any(Monster.class))).thenReturn(true);
            runWithStubbedBotAfter(() -> BotCombatManager.tickMobDamage(entry, bot));
        }

        assertEquals(20_000, bot.getHp());
        verify(map, never()).broadcastMessage(any(Character.class), any(Packet.class), anyBoolean());
    }

    @Test
    void shouldTakeContactDamageFromHostileMonster() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Monster hostile = mockMob(new Point(100, 200), 9300503);
        when(map.getAllMonsters()).thenReturn(List.of(hostile));
        BotEntry entry = new BotEntry(bot, null, null);

        try (MockedStatic<BotCombatManager> combat =
                     Mockito.mockStatic(BotCombatManager.class, Mockito.CALLS_REAL_METHODS)) {
            combat.when(() -> BotCombatManager.isMobTouchingBot(any(Rectangle.class),
                    any(Monster.class))).thenReturn(true);
            runWithStubbedBotAfter(() -> BotCombatManager.tickMobDamage(entry, bot));
        }

        assertTrue(bot.getHp() < 20_000, "hostile contact should reduce bot HP");
    }

    @Test
    void shouldCreateFallbackHitBoxForCloseRangeSkillWithoutBoundingBox() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getRange()).thenReturn(0);

        Rectangle hitBox = BotCombatManager.fallbackCloseRangeSkillHitBox(effect, bot, null, false);

        assertEquals(new Rectangle(100, 150, 80, 70), hitBox);
    }

    @Test
    void shouldExpandFallbackHitBoxUsingSkillRange() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getRange()).thenReturn(130);

        Rectangle hitBox = BotCombatManager.fallbackCloseRangeSkillHitBox(effect, bot, null, true);

        assertEquals(new Rectangle(-30, 150, 130, 70), hitBox);
    }

    @Test
    void shouldCreateFallbackHitBoxForRangedSkillWithoutBoundingBox() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getRange()).thenReturn(0);

        Rectangle hitBox = BotCombatManager.fallbackSkillHitBox(effect, bot, false, BotCombatManager.AttackRoute.RANGED, 0, null);

        assertEquals(new Rectangle(105, 150, 395, 100), hitBox);
    }

    @Test
    void shouldCreateFallbackHitBoxForMagicSkillWithoutBoundingBox() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getRange()).thenReturn(0);

        Rectangle hitBox = BotCombatManager.fallbackSkillHitBox(effect, bot, true, BotCombatManager.AttackRoute.MAGIC, 0, null);

        assertEquals(new Rectangle(-300, 150, 395, 100), hitBox);
    }

    @Test
    void shouldScaleFallbackProjectileHitBoxUsingSkillRangeMultiplier() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getRange()).thenReturn(150);

        Rectangle hitBox = BotCombatManager.fallbackSkillHitBox(effect, bot, false, BotCombatManager.AttackRoute.MAGIC, 0, null);

        assertEquals(new Rectangle(105, 150, 595, 100), hitBox);
    }

    @Test
    void shouldUseTightVerticalReachForIronArrow() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getRange()).thenReturn(0);

        // Iron Arrow: yAbove=32, yBelow=-28 → rect from y=168 to y=172 (height 4)
        Rectangle hitBox = BotCombatManager.fallbackSkillHitBox(effect, bot, false,
                BotCombatManager.AttackRoute.RANGED, constants.skills.Crossbowman.IRON_ARROW, null);

        assertEquals(168, hitBox.y);
        assertEquals(4, hitBox.height);
    }

    @Test
    void shouldUseWideVerticalReachForAvenger() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getRange()).thenReturn(0);

        // Avenger: yAbove=60, yBelow=0 → rect from y=140 to y=200 (height 60)
        Rectangle hitBox = BotCombatManager.fallbackSkillHitBox(effect, bot, false,
                BotCombatManager.AttackRoute.RANGED, constants.skills.Hermit.AVENGER, null);

        assertEquals(140, hitBox.y);
        assertEquals(60, hitBox.height);
    }

    @Test
    void shouldAddPassiveRangeSkillsToProjectileRange() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Skill eyeOfAmazon = new Skill(Archer.EYE_OF_AMAZON);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getRange()).thenReturn(120);
        eyeOfAmazon.addLevelEffect(effect);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(eyeOfAmazon, null);
        when(bot.getSkills()).thenReturn(skills);
        when(bot.getSkillLevel(eyeOfAmazon)).thenReturn((byte) 1);

        Rectangle hitBox = BotCombatManager.clientProjectileHitBox(bot, false, 1.0f);

        assertEquals(new Rectangle(105, 150, 515, 100), hitBox);
    }

    @Test
    void shouldFallbackToBasicAttackTimingWhenSkillAnimationDelayMissing() {
        BotAttackExecutionProvider.SkillAttackTiming timing =
                BotAttackExecutionProvider.resolveSkillAttackTiming(0, 4, 300, 590);

        assertEquals(300, timing.hitDelayMs());
        assertEquals(590, timing.cooldownMs());
    }

    @Test
    void shouldNotUnlockSkillFasterThanBasicAttackCooldown() {
        BotAttackExecutionProvider.SkillAttackTiming timing =
                BotAttackExecutionProvider.resolveSkillAttackTiming(450, 4, 300, 590);

        assertEquals(197, timing.hitDelayMs());
        assertEquals(590, timing.cooldownMs());
    }

    @Test
    void shouldApplyAttackSpeedScalingToSkillAnimationTiming() {
        BotAttackExecutionProvider.SkillAttackTiming timing =
                BotAttackExecutionProvider.resolveSkillAttackTiming(520, 2, 120, 0);

        assertEquals(203, timing.hitDelayMs());
        assertEquals(406, timing.cooldownMs());
    }

    @Test
    void shouldUseDegenerateCloseAttackPoolForBowAtPointBlankRange() {
        assertTrue(BotAttackExecutionProvider.shouldDegenerateRangedAttack(WeaponType.BOW, new Point(100, 200), new Point(145, 200)));
        BotAttackDataProvider.AttackAnimationSpec bowSpec = BotAttackDataProvider.getInstance().getBasicAttackSpec(WeaponType.BOW, true);

        assertEquals(3, bowSpec.display());
        assertTrue(bowSpec.actions().contains("swingT1"));
        assertTrue(bowSpec.actions().contains("swingT3"));
    }

    @Test
    void shouldKeepBowOutOfDegenerateModeWhenTargetIsNotCrowding() {
        assertFalse(BotAttackExecutionProvider.shouldDegenerateRangedAttack(WeaponType.BOW, new Point(100, 200), new Point(300, 200)));
        BotAttackDataProvider.AttackAnimationSpec bowSpec = BotAttackDataProvider.getInstance().getBasicAttackSpec(WeaponType.BOW, false);

        assertEquals("shoot1", bowSpec.primaryAction());
    }

    @Test
    void shouldRetreatFromNearbyRangedTargetsInsideRetreatBand() {
        Point botPos = new Point(100, 200);
        Point retreatBandTarget = new Point(botPos.x + BotCombatManager.cfg.RANGED_RETREAT_THRESHOLD_X, 200);
        Point pointBlankTarget = new Point(botPos.x + 1, 200);
        Point justOutsideRetreatTarget = new Point(botPos.x + BotCombatManager.cfg.RANGED_RETREAT_THRESHOLD_X + 1, 200);
        Point farTarget = new Point(botPos.x + BotCombatManager.cfg.RANGED_DEGENERATE_RANGE_X + 100, 200);

        assertTrue(BotAttackExecutionProvider.shouldRetreatFromNearbyTarget(WeaponType.BOW, botPos, retreatBandTarget));
        assertEquals(new Point(botPos.x - BotCombatManager.cfg.RANGED_RETREAT_DISTANCE_X, 200),
                BotAttackExecutionProvider.retreatTargetPosition(botPos, retreatBandTarget));
        assertTrue(BotAttackExecutionProvider.shouldRetreatFromNearbyTarget(WeaponType.BOW, botPos, pointBlankTarget));
        assertFalse(BotAttackExecutionProvider.shouldRetreatFromNearbyTarget(WeaponType.BOW, botPos, justOutsideRetreatTarget));
        assertFalse(BotAttackExecutionProvider.shouldRetreatFromNearbyTarget(WeaponType.BOW, botPos, farTarget));
    }

    @Test
    void shouldAllowDiagonalJumpAttackForCloseRangeTargetsSlightlyAbove() {
        assertTrue(BotCombatManager.isTargetJumpable(true, new Point(100, 200), new Point(230, 135)));
    }

    @Test
    void shouldRejectJumpAttackForNonCloseRangeRoutes() {
        assertFalse(BotCombatManager.isTargetJumpable(false, new Point(100, 200), new Point(170, 135)));
    }

    @Test
    void shouldRejectAirborneRangedAttackPlansForWeaponsThatCannotJumpShoot() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.inAir = true;
        BotCombatManager.AttackPlan rangedBowPlan = new BotCombatManager.AttackPlan(
                Hunter.ARROW_BOMB, 1, 1, new Rectangle(100, 150, 300, 100),
                List.of(mockMob(new Point(180, 200), 9300200)), BotCombatManager.AttackRoute.RANGED,
                0, 11, 11, 11, 4, 300, 600, null);
        BotCombatManager.AttackPlan closePlan = new BotCombatManager.AttackPlan(
                0, 0, 1, new Rectangle(100, 150, 80, 70),
                List.of(mockMob(new Point(120, 200), 9300201)), BotCombatManager.AttackRoute.CLOSE,
                4, 1, 1, 0, 4, 300, 600, null);

        assertFalse(BotCombatManager.canUseAttackPlanNow(entry, WeaponType.BOW, rangedBowPlan));
        assertFalse(BotCombatManager.canUseAttackPlanNow(entry, WeaponType.CROSSBOW, rangedBowPlan));
        assertFalse(BotCombatManager.canUseAttackPlanNow(entry, WeaponType.GUN, rangedBowPlan));
        assertTrue(BotCombatManager.canUseAttackPlanNow(entry, WeaponType.CLAW, rangedBowPlan));
        assertTrue(BotCombatManager.canUseAttackPlanNow(entry, WeaponType.BOW, closePlan));
    }

    @Test
    void shouldRememberLeftFacingAttackForNextStandingStance() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.facingDir = 1;

        BotCombatManager.rememberAttackFacing(entry, BotAttackExecutionProvider.attackPacketStance(true));

        assertEquals(-1, entry.facingDir);
        assertEquals(CharacterStance.STAND_LEFT_STANCE, bot.getStance());
    }

    @Test
    void shouldRememberRightFacingAttackForNextStandingStance() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.facingDir = -1;

        BotCombatManager.rememberAttackFacing(entry, BotAttackExecutionProvider.attackPacketStance(false));

        assertEquals(1, entry.facingDir);
        assertEquals(CharacterStance.STAND_RIGHT_STANCE, bot.getStance());
    }

    @Test
    void shouldAnchorArrowBombOnClosestMobInProjectilePath() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mockBot(new Point(100, 200), map, 20_000, null);
        Monster closest = mockMob(new Point(260, 200), 9300300);
        Monster splash = mockMob(new Point(275, 200), 9300301);
        Monster farSelected = mockMob(new Point(390, 200), 9300302);
        doReturn(List.of(farSelected, splash, closest)).when(map).getAllMonsters();

        Skill arrowBomb = skillWithAnchoredAoe(Hunter.ARROW_BOMB, 1, 6, 260);
        when(bot.getSkillLevel(arrowBomb)).thenReturn((byte) 1);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.aoeSkillId = Hunter.ARROW_BOMB;

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class);
             MockedStatic<BotAttackExecutionProvider> attackExecution =
                     Mockito.mockStatic(BotAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            skillFactory.when(() -> SkillFactory.getSkill(Hunter.ARROW_BOMB)).thenReturn(arrowBomb);
            attackExecution.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.BOW);

            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, farSelected);

            assertEquals(Hunter.ARROW_BOMB, plan.skillId);
            assertEquals(closest, plan.targets.get(0));
            assertTrue(plan.targets.contains(splash));
            assertFalse(plan.targets.contains(farSelected));
        }
    }

    @Test
    void shouldRejectJumpAttackWhenTargetIsTooHighOrTooFar() {
        assertFalse(BotCombatManager.isTargetJumpable(true, new Point(100, 200), new Point(241, 135)));
        assertFalse(BotCombatManager.isTargetJumpable(true, new Point(100, 200), new Point(170, 60)));
    }

    @Test
    void shouldUseClientStyleMobTouchSweepForStationaryBot() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        BotEntry entry = new BotEntry(bot, null, null);

        Rectangle bounds = BotCombatManager.getBotTouchBounds(entry, bot);

        assertEquals(new Rectangle(100, 150, 1, 51), bounds);
    }

    @Test
    void shouldIgnoreMobThatOnlyBrushesBotSpriteSide() {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        BotEntry entry = new BotEntry(bot, null, null);
        Monster mob = mockMob(new Point(122, 200), 100100);
        when(mob.isFacingLeft()).thenReturn(false);

        assertFalse(BotCombatManager.isMobTouchingBot(entry, bot, mob));
    }

    @Test
    void shouldDetectMobTouchAcrossBotMovementSweep() {
        Character bot = mockBot(new Point(120, 200), mock(MapleMap.class), 20_000, null);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.lastMobTouchCheckPos = new Point(80, 200);
        entry.lastMobTouchMapId = 0;
        when(bot.getMapId()).thenReturn(0);
        Monster mob = mockMob(new Point(96, 200), 100100);
        when(mob.isFacingLeft()).thenReturn(false);

        assertTrue(BotCombatManager.isMobTouchingBot(entry, bot, mob));
    }

    @Test
    void shouldAllowPathScoringToBeatFarCurrentFootholdTarget() {
        MapleMap realMap = new MapleMap(910009050, 0, 0, 910009050, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(300, 100), 1));
        footholds.insert(new Foothold(new Point(0, 200), new Point(300, 200), 2));
        realMap.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(realMap);   // build on the bare map; rebuildGraph through a spy OOMs
        MapleMap map = spy(realMap);

        Character bot = mockBot(new Point(100, 100), map, 20_000, null);
        Monster currentFootholdMob = mockMob(new Point(180, 100), 100100);
        Monster otherRegionMob = mockMob(new Point(105, 200), 100100);
        doReturn(List.of(otherRegionMob, currentFootholdMob)).when(map).getAllMonsters();

        Monster target = BotCombatManager.findGrindTarget(new BotEntry(bot, null, null), bot);

        assertEquals(otherRegionMob, target);
    }

    @Test
    void shouldPreferAoeClusterAnchorOverLoneCloseMobWhenBotHasAoeSkill() {
        // bot at (100, 100). lone "low-hp" mob CLOSE in one direction, 3-mob cluster
        // farther in the opposite direction. without an AoE skill the close lone mob
        // wins on distance score; with an AoE skill, the cluster anchor must win so
        // planAttack can fire an AoE plan that out-DPSes the basic single shot.
        MapleMap realMap = new MapleMap(910009060, 0, 0, 910009060, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(-400, 100), new Point(400, 100), 1));
        realMap.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(realMap);   // build on the bare map; rebuildGraph through a spy OOMs
        MapleMap map = spy(realMap);

        Character bot = mockBot(new Point(100, 100), map, 20_000, null);
        Monster loneClose = mockMob(new Point(160, 100), 100100);
        Monster clusterAnchor = mockMob(new Point(-100, 100), 100100);
        Monster clusterNeighbor1 = mockMob(new Point(-130, 100), 100100);
        Monster clusterNeighbor2 = mockMob(new Point(-160, 100), 100100);
        doReturn(List.of(loneClose, clusterAnchor, clusterNeighbor1, clusterNeighbor2))
                .when(map).getAllMonsters();

        BotEntry noAoeEntry = new BotEntry(bot, null, null);
        // Sanity: without AoE skill, the lone close mob wins on plain distance score.
        assertEquals(loneClose, BotCombatManager.findGrindTarget(noAoeEntry, bot));

        BotEntry aoeEntry = new BotEntry(bot, null, null);
        aoeEntry.aoeSkillId = Hunter.POWER_KNOCKBACK;
        aoeEntry.aoeSkillMobs = 6;
        // With AoE skill that hits up to 6, cluster anchor's bonus overcomes the lone
        // mob's distance advantage and the bot switches target to the cluster.
        assertEquals(clusterAnchor, BotCombatManager.findGrindTarget(aoeEntry, bot));
    }

    @Test
    void shouldPreferLessOccupiedGrindRegionWhenPathCostIsClose() throws Exception {
        MapleMap map = spy(new MapleMap(910009052, 0, 0, 910009052, 1.0f));
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold startFoothold = new Foothold(new Point(0, 100), new Point(100, 100), 1);
        Foothold occupiedFoothold = new Foothold(new Point(200, 100), new Point(300, 100), 2);
        Foothold openFoothold = new Foothold(new Point(320, 100), new Point(420, 100), 3);
        footholds.insert(startFoothold);
        footholds.insert(occupiedFoothold);
        footholds.insert(openFoothold);
        map.setFootholds(footholds);

        BotNavigationGraph.Region startRegion = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(startFoothold)));
        BotNavigationGraph.Region occupiedRegion = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(occupiedFoothold)));
        BotNavigationGraph.Region openRegion = new BotNavigationGraph.Region(
                3, List.of(new BotNavigationGraph.Segment(openFoothold)));
        BotNavigationGraph graph = new BotNavigationGraph(
                map.getId(),
                1,
                BotMovementProfile.base(),
                List.of(startRegion, occupiedRegion, openRegion),
                Map.of(1, startRegion, 2, occupiedRegion, 3, openRegion),
                Map.of(1, 1, 2, 2, 3, 3),
                Map.of(1, List.of(
                        new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.WALK,
                                new Point(100, 100), new Point(200, 100), 0, 0, 0, 0, 0, 100),
                        new BotNavigationGraph.Edge(1, 3, BotNavigationGraph.EdgeType.WALK,
                                new Point(100, 100), new Point(320, 100), 0, 0, 0, 0, 0, 150))),
                Set.of());

        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(909052);
        Character bot = mockBot(new Point(50, 100), map, 20_000, null);
        Character siblingBot = mockBot(new Point(220, 100), map, 20_000, null);
        Monster occupiedTarget = mockMob(new Point(220, 100), 9300400);
        Monster openTarget = mockMob(new Point(340, 100), 9300401);
        doReturn(List.of(occupiedTarget, openTarget)).when(map).getAllMonsters();

        BotEntry entry = new BotEntry(bot, owner, null);
        entry.grinding = true;
        BotEntry siblingEntry = new BotEntry(siblingBot, owner, null);
        siblingEntry.grinding = true;

        BotManager manager = BotManager.getInstance();
        Map<Integer, List<BotEntry>> bots = botEntries(manager);
        bots.put(owner.getId(), new CopyOnWriteArrayList<>(List.of(entry, siblingEntry)));
        try (MockedStatic<BotNavigationGraphProvider> graphProvider =
                     Mockito.mockStatic(BotNavigationGraphProvider.class, Mockito.CALLS_REAL_METHODS)) {
            graphProvider.when(() -> BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base()))
                    .thenReturn(graph);

            Monster target = BotCombatManager.findGrindTarget(entry, bot);

            assertEquals(openTarget, target);
        } finally {
            bots.remove(owner.getId());
        }
    }

    @Test
    void shouldUseRangedHitBoxTargetOutsideCurrentRegionWithoutPathingThere() {
        MapleMap realMap = new MapleMap(910009051, 0, 0, 910009051, 1.0f);
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(200, 100), 1));
        footholds.insert(new Foothold(new Point(250, 130), new Point(450, 130), 2));
        realMap.setFootholds(footholds);
        BotNavigationGraphProvider.rebuildGraph(realMap);   // build on the bare map; rebuildGraph through a spy OOMs
        MapleMap map = spy(realMap);

        Character bot = mockBot(new Point(100, 100), map, 20_000, null);
        Monster otherRegionMob = mockMob(new Point(300, 130), 100100);
        doReturn(List.of(otherRegionMob)).when(map).getAllMonsters();

        BotEntry entry = new BotEntry(bot, null, null);

        try (MockedStatic<BotAttackExecutionProvider> attackExecution =
                     Mockito.mockStatic(BotAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            attackExecution.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.BOW);

            assertEquals(otherRegionMob, BotCombatManager.findFollowAttackTarget(entry, bot));
            assertEquals(otherRegionMob, BotCombatManager.findGrindTarget(entry, bot));
            assertTrue(BotCombatManager.isReachableGrindTarget(entry, bot, otherRegionMob));
        }
    }

    @Test
    void shouldExcludeOneWayPatrolNeighborFromRoamTargeting() {
        MapleMap map = spy(new MapleMap(910009053, 0, 0, 910009053, 1.0f));
        server.maps.FootholdTree footholds = new server.maps.FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold homeFoothold = new Foothold(new Point(0, 100), new Point(100, 100), 1);
        Foothold oneWayFoothold = new Foothold(new Point(200, 140), new Point(300, 140), 2);
        Foothold returnableFoothold = new Foothold(new Point(400, 100), new Point(500, 100), 3);
        footholds.insert(homeFoothold);
        footholds.insert(oneWayFoothold);
        footholds.insert(returnableFoothold);
        map.setFootholds(footholds);

        BotNavigationGraph.Region homeRegion = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(homeFoothold)));
        BotNavigationGraph.Region oneWayRegion = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(oneWayFoothold)));
        BotNavigationGraph.Region returnableRegion = new BotNavigationGraph.Region(
                3, List.of(new BotNavigationGraph.Segment(returnableFoothold)));
        BotNavigationGraph graph = new BotNavigationGraph(
                map.getId(),
                1,
                BotMovementProfile.base(),
                List.of(homeRegion, oneWayRegion, returnableRegion),
                Map.of(1, homeRegion, 2, oneWayRegion, 3, returnableRegion),
                Map.of(1, 1, 2, 2, 3, 3),
                Map.of(
                        1, List.of(
                                new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.DROP,
                                        new Point(100, 100), new Point(200, 140), 0, 0, 0, 0, 0, 100),
                                new BotNavigationGraph.Edge(1, 3, BotNavigationGraph.EdgeType.WALK,
                                        new Point(100, 100), new Point(400, 100), 0, 0, 0, 0, 0, 120)),
                        3, List.of(new BotNavigationGraph.Edge(3, 1, BotNavigationGraph.EdgeType.WALK,
                                new Point(400, 100), new Point(100, 100), 0, 0, 0, 0, 0, 120))),
                Set.of());

        Character bot = mockBot(new Point(50, 100), map, 20_000, null);
        Monster oneWayTarget = mockMob(new Point(240, 140), 9300402);
        Monster returnableTarget = mockMob(new Point(440, 100), 9300403);
        doReturn(List.of(oneWayTarget, returnableTarget)).when(map).getAllMonsters();

        BotEntry entry = new BotEntry(bot, null, null);
        entry.patrolRegionId = 1;

        try (MockedStatic<BotNavigationGraphProvider> graphProvider =
                     Mockito.mockStatic(BotNavigationGraphProvider.class, Mockito.CALLS_REAL_METHODS)) {
            graphProvider.when(() -> BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base()))
                    .thenReturn(graph);

            Monster target = BotCombatManager.findPatrolTarget(entry, bot);

            assertEquals(returnableTarget, target);
        }
    }

    private static void assertDamageDirection(MapleMap map, Character bot, int expectedBroadcasts, int expectedDirection) {
        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(map, times(expectedBroadcasts)).broadcastMessage(eq(bot), packets.capture(), eq(false));
        byte[] payload = packets.getAllValues().get(0).getBytes();
        assertEquals(expectedDirection, Byte.toUnsignedInt(payload[15]));
    }

    private static void runWithStubbedBotAfter(Runnable action) {
        try (MockedStatic<BotManager> botManager = Mockito.mockStatic(BotManager.class, Mockito.CALLS_REAL_METHODS)) {
            botManager.when(() -> BotManager.after(anyLong(), any(Runnable.class)))
                    .thenReturn(mock(ScheduledFuture.class));
            action.run();
        }
    }

    private static Character mockBot(Point startPosition, MapleMap map, int startingHp, Integer stancePercent) {
        Character bot = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        AtomicInteger hp = new AtomicInteger(startingHp);
        AtomicInteger stance = new AtomicInteger(CharacterStance.STAND_RIGHT_STANCE);

        when(bot.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(bot).setPosition(any(Point.class));
        when(bot.getMap()).thenReturn(map);
        when(bot.getHp()).thenAnswer(invocation -> hp.get());
        when(bot.getCurrentMaxHp()).thenReturn(startingHp);
        doAnswer(invocation -> {
            hp.addAndGet(invocation.getArgument(0));
            return null;
        }).when(bot).addMPHPAndTriggerAutopot(anyInt(), anyInt());
        when(bot.getStance()).thenAnswer(invocation -> stance.get());
        doAnswer(invocation -> {
            stance.set(invocation.getArgument(0));
            return null;
        }).when(bot).setStance(anyInt());
        when(bot.getId()).thenReturn(1);
        when(bot.getMapId()).thenReturn(0);
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        when(bot.getLevel()).thenReturn(200);
        when(bot.isAlive()).thenReturn(true);
        when(bot.isFacingLeft()).thenReturn(false);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getTotalWdef()).thenReturn(0);
        when(bot.getTotalStr()).thenReturn(4);
        when(bot.getTotalDex()).thenReturn(4);
        when(bot.getTotalInt()).thenReturn(4);
        when(bot.getTotalLuk()).thenReturn(4);
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.getEnergyBar()).thenReturn(0);
        when(bot.getAllBuffs()).thenReturn(Collections.emptyList());
        when(bot.calculateMaxBaseDamage(anyInt())).thenReturn(1_000);
        when(bot.calculateMinBaseDamage(anyInt())).thenReturn(500);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.getItem((short) -11)).thenReturn(null);
        when(equipped.iterator()).thenReturn(Collections.emptyIterator());
        if (stancePercent != null) {
            when(bot.getBuffedValue(BuffStat.STANCE)).thenReturn(stancePercent);
        }
        return bot;
    }

    private static Monster mockMob(Point position, int id) {
        Monster mob = mock(Monster.class);
        when(mob.getPosition()).thenReturn(new Point(position));
        when(mob.getId()).thenReturn(id);
        when(mob.getObjectId()).thenReturn(id);
        when(mob.getPADamage()).thenReturn(1_000);
        when(mob.getLevel()).thenReturn(1);
        when(mob.getAccuracy()).thenReturn(9_999);
        when(mob.getAvoidability()).thenReturn(0);
        when(mob.getWdef()).thenReturn(0);
        when(mob.getMdef()).thenReturn(0);
        when(mob.getHp()).thenReturn(10_000);
        when(mob.getMaxHp()).thenReturn(10_000);
        when(mob.isAlive()).thenReturn(true);
        return mob;
    }

    private static Monster mockFriendlyMob(Point position, int id) {
        Monster mob = mockMob(position, id);
        MonsterStats stats = mock(MonsterStats.class);
        when(stats.isFriendly()).thenReturn(true);
        when(mob.getStats()).thenReturn(stats);
        return mob;
    }

    private static Skill skillWithAttack(int skillId, int attackCount, int mobCount, int damage) {
        Skill skill = new Skill(skillId);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getAttackCount()).thenReturn(attackCount);
        when(effect.getMobCount()).thenReturn(mobCount);
        when(effect.getDamage()).thenReturn(damage);
        when(effect.getDamagePercent()).thenReturn(damage);
        when(effect.hasDamage()).thenReturn(true);
        when(effect.getDuration()).thenReturn(0);
        when(effect.getMpCon()).thenReturn((short) 1);
        when(effect.canPaySkillCost(any(Character.class))).thenReturn(true);
        skill.addLevelEffect(effect);
        return skill;
    }

    private static Skill skillWithAttackBox(int skillId, int attackCount, int mobCount, int damage, Rectangle hitBox) {
        Skill skill = skillWithAttack(skillId, attackCount, mobCount, damage);
        return skillWithAttackBox(skill, hitBox);
    }

    private static Skill skillWithAttackBox(int skillId, int attackCount, int mobCount, int damage,
                                            Rectangle hitBox, int animationTimeMs) {
        Skill skill = skillWithAttack(skillId, attackCount, mobCount, damage);
        skill.setAction0("testSkillDelay" + skillId);
        skill.setAnimationTime(animationTimeMs);
        return skillWithAttackBox(skill, hitBox);
    }

    private static Skill skillWithAttackBox(Skill skill, Rectangle hitBox) {
        StatEffect effect = skill.getEffect(1);
        when(effect.hasBoundingBox()).thenReturn(true);
        when(effect.calculateBoundingBox(any(Point.class), anyBoolean())).thenReturn(new Rectangle(hitBox));
        return skill;
    }

    private static Skill passiveOverTimeSkillWithCombatMetadata(int skillId, int damage, int attackCount, int mobCount) {
        Skill skill = new Skill(skillId);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getDamage()).thenReturn(damage);
        when(effect.getAttackCount()).thenReturn(attackCount);
        when(effect.getBulletCount()).thenReturn((short) 0);
        when(effect.getMobCount()).thenReturn(mobCount);
        when(effect.isOverTime()).thenReturn(true);
        skill.addLevelEffect(effect);
        return skill;
    }

    private static Skill passiveSkillWithCombatMetadata(int skillId, int damage, int attackCount, int mobCount) {
        Skill skill = new Skill(skillId);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getDamage()).thenReturn(damage);
        when(effect.getAttackCount()).thenReturn(attackCount);
        when(effect.getBulletCount()).thenReturn((short) 0);
        when(effect.getMobCount()).thenReturn(mobCount);
        when(effect.isOverTime()).thenReturn(false);
        skill.addLevelEffect(effect);
        return skill;
    }

    private static Skill skillWithBuffAction(int skillId) {
        Skill skill = new Skill(skillId);
        skill.setAction(true);
        StatEffect effect = mock(StatEffect.class);
        when(effect.isOverTime()).thenReturn(true);
        // Real support buffs are duration-based and grant the caster a statup; isActiveSupportSkill
        // now requires both, so the mock must mirror that.
        when(effect.getDuration()).thenReturn(900_000);
        when(effect.getStatups()).thenReturn(List.of(new tools.Pair<>(BuffStat.WDEF, 20)));
        skill.addLevelEffect(effect);
        return skill;
    }

    private static void assertRealWzCache(Job job, int level, Set<Integer> skillIds,
                                          int expectedAttackSkillId, int expectedAoeSkillId,
                                          Set<Integer> expectedBuffSkillIds, Set<Integer> excludedSkillIds) {
        Character bot = mockBot(new Point(100, 200), mock(MapleMap.class), 20_000, null);
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(level);

        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        for (int skillId : skillIds) {
            Skill skill = SkillFactory.getSkill(skillId);
            assertTrue(skill != null, "missing real WZ skill " + skillId);
            skills.put(skill, null);
        }
        when(bot.getSkills()).thenReturn(skills);
        doAnswer(invocation -> {
            Skill skill = invocation.getArgument(0);
            return (byte) (skillIds.contains(skill.getId()) ? 1 : 0);
        }).when(bot).getSkillLevel(any(Skill.class));

        BotEntry entry = new BotEntry(bot, null, null);
        BotCombatManager.rebuildSkillCacheIfNeeded(entry, bot);

        assertEquals(expectedAttackSkillId, entry.attackSkillId);
        assertEquals(expectedAoeSkillId, entry.aoeSkillId);
        for (int skillId : expectedBuffSkillIds) {
            assertTrue(entry.buffSkillIds.contains(skillId), "expected cached buff " + skillId);
        }
        for (int skillId : excludedSkillIds) {
            assertFalse(entry.buffSkillIds.contains(skillId), "unexpected cached buff " + skillId);
            assertFalse(entry.attackSkillId == skillId || entry.aoeSkillId == skillId,
                    "unexpected cached attack " + skillId);
        }
    }

    @Test
    void shouldUseWeakDegenerateProfileForCloseRouteWithRangedWeapon() {
        Character bot = mock(Character.class);
        when(bot.getTotalWatk()).thenReturn(150);
        when(bot.getTotalDex()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(50);

        try (MockedStatic<BotAttackExecutionProvider> attackExecution =
                     Mockito.mockStatic(BotAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            attackExecution.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(WeaponType.BOW);

            server.combat.CombatFormulaProvider.DamageProfile degenerate =
                    BotCombatManager.resolveAttackDamageProfile(bot, 0, 0,
                            BotCombatManager.AttackRoute.CLOSE, null);

            // Degenerate bow swing: MAX = (100*3.4 + 50) * 150 / 150 = 390,
            // MIN = (100*3.4*0.09 + 50) * 150 / 150 = 80.6 -> 81. No crit ever.
            assertEquals(81, degenerate.minDamage());
            assertEquals(390, degenerate.maxDamage());
            assertTrue(degenerate.noCrit());
        }
    }

    @Test
    void shouldKeepNormalCritableProfileForRangedRouteWithRangedWeapon() {
        Character bot = mock(Character.class);
        when(bot.getTotalWatk()).thenReturn(150);
        Inventory equipped = mock(Inventory.class);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);

        try (MockedStatic<BotAttackExecutionProvider> attackExecution =
                     Mockito.mockStatic(BotAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            attackExecution.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(WeaponType.BOW);

            server.combat.CombatFormulaProvider.DamageProfile ranged =
                    BotCombatManager.resolveAttackDamageProfile(bot, 0, 0,
                            BotCombatManager.AttackRoute.RANGED, null);

            assertFalse(ranged.noCrit());
        }
    }

    @Test
    void shouldApplyPowerKnockbackSkillDamageOnTopOfDegenerateBase() {
        Character bot = mock(Character.class);
        when(bot.getTotalWatk()).thenReturn(150);
        when(bot.getTotalDex()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(50);
        Skill pkb = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(effect.getDamagePercent()).thenReturn(130);
        when(pkb.getEffect(1)).thenReturn(effect);

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class);
             MockedStatic<BotAttackExecutionProvider> attackExecution =
                     Mockito.mockStatic(BotAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS)) {
            skillFactory.when(() -> SkillFactory.getSkill(Hunter.POWER_KNOCKBACK)).thenReturn(pkb);
            attackExecution.when(() -> BotAttackExecutionProvider.getEquippedWeaponType(bot))
                    .thenReturn(WeaponType.BOW);

            server.combat.CombatFormulaProvider.DamageProfile profile =
                    BotCombatManager.resolveAttackDamageProfile(bot, Hunter.POWER_KNOCKBACK, 1,
                            BotCombatManager.AttackRoute.CLOSE, null);

            // Degenerate base 81-390 (see degenerate bow test) * 130% skill damage
            assertEquals(105, profile.minDamage());
            assertEquals(507, profile.maxDamage());
            assertTrue(profile.noCrit());
        }
    }

    private static Skill skillWithAnchoredAoe(int skillId, int attackCount, int mobCount, int damage) {
        Skill skill = skillWithAttack(skillId, attackCount, mobCount, damage);
        StatEffect effect = skill.getEffect(1);
        when(effect.hasBoundingBox()).thenReturn(true);
        when(effect.calculateBoundingBox(any(Point.class), anyBoolean())).thenAnswer(invocation -> {
            Point anchor = invocation.getArgument(0);
            return new Rectangle(anchor.x - 30, anchor.y - 50, 80, 100);
        });
        return skill;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, List<BotEntry>> botEntries(BotManager manager) throws Exception {
        Field field = BotManager.class.getDeclaredField("bots");
        field.setAccessible(true);
        return (Map<Integer, List<BotEntry>>) field.get(manager);
    }
}
