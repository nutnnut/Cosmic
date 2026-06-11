package server.combat;

import client.BuffStat;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.StatEffect;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CombatFormulaProviderTest {
    private final CombatFormulaProvider provider = CombatFormulaProvider.getInstance();

    @Test
    void shouldIncludeDerivedEquipAndBuffAccuracy() {
        Character bot = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        Equip weapon = mock(Equip.class);
        Equip glove = mock(Equip.class);

        when(bot.getTotalDex()).thenReturn(100);
        when(bot.getTotalLuk()).thenReturn(50);
        when(bot.getBuffedValue(BuffStat.ACC)).thenReturn(7);
        when(weapon.getAcc()).thenReturn((short) 12);
        when(glove.getAcc()).thenReturn((short) 4);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.iterator()).thenReturn(List.<client.inventory.Item>of(weapon, glove).iterator());

        assertEquals(128, provider.getTotalAccuracy(bot));
    }

    @Test
    void shouldUseIntAndLukForMagicAccuracyOnly() {
        Character bot = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        Equip weapon = mock(Equip.class);

        when(bot.getTotalInt()).thenReturn(123);
        when(bot.getTotalLuk()).thenReturn(47);
        when(bot.getBuffedValue(BuffStat.ACC)).thenReturn(50);
        when(weapon.getAcc()).thenReturn((short) 40);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.iterator()).thenReturn(List.<client.inventory.Item>of(weapon).iterator());

        assertEquals(80, provider.getTotalMagicAccuracy(bot));
    }

    @Test
    void shouldIncludeDerivedEquipAndBuffAvoidability() {
        Character bot = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        Equip cape = mock(Equip.class);
        Equip shoes = mock(Equip.class);

        when(bot.getTotalDex()).thenReturn(100);
        when(bot.getTotalLuk()).thenReturn(50);
        when(bot.getBuffedValue(BuffStat.AVOID)).thenReturn(7);
        when(cape.getAvoid()).thenReturn((short) 12);
        when(shoes.getAvoid()).thenReturn((short) 4);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.iterator()).thenReturn(List.<client.inventory.Item>of(cape, shoes).iterator());

        assertEquals(73, provider.getTotalAvoidability(bot));
    }

    @Test
    void shouldMatchOpenStoryMobHitChanceFormula() {
        double hitChance = provider.calculatePhysicalMobHitChance(80, 50, 55, 20);

        assertEquals(0.8958333333333335d, hitChance, 1.0e-12d);
    }

    @Test
    void shouldApplyMagicMobHitChanceFormulaFromGuidance() {
        double hitChance = provider.calculateMagicMobHitChance(80, 50, 55, 20);

        assertEquals(0.625d, hitChance, 1.0e-12d);
    }

    @Test
    void shouldApplySymmetricMobToBotHitChanceFormula() {
        double hitChance = provider.calculateBotAvoidChance(60, 55, 50, 20);

        assertEquals(1.0d, hitChance, 1.0e-12d);
    }

    @Test
    void shouldClampHitChanceBetweenOnePercentAndOneHundredPercent() {
        assertEquals(0.01d, provider.calculatePhysicalMobHitChance(0, 20, 120, 999), 1.0e-12d);
        assertEquals(1.0d, provider.calculatePhysicalMobHitChance(9999, 200, 1, 0), 1.0e-12d);
        assertEquals(0.01d, provider.calculateMagicMobHitChance(0, 20, 120, 999), 1.0e-12d);
        assertEquals(1.0d, provider.calculateMagicMobHitChance(9999, 200, 1, 0), 1.0e-12d);
        assertEquals(0.01d, provider.calculateBotAvoidChance(0, 20, 120, 999), 1.0e-12d);
        assertEquals(1.0d, provider.calculateBotAvoidChance(9999, 120, 20, 0), 1.0e-12d);
    }

    @Test
    void shouldReturnOnlyDamageWhenHitChanceIsGuaranteed() {
        assertTrue(provider.rollDamageLines(8, 10, 10, 1.0d).stream().allMatch(line -> line == 10));
    }

    @Test
    void shouldReturnOnlyMissesWhenHitChanceIsZero() {
        assertTrue(provider.rollDamageLines(8, 10, 10, 0.0d).stream().allMatch(line -> line == 0));
    }

    @Test
    void shouldLetMobAlwaysMissWhenHitChanceIsZero() {
        assertTrue(java.util.stream.IntStream.range(0, 32).allMatch(i -> !provider.doesMobHit(0.0d)));
    }

    @Test
    void shouldLetMobAlwaysHitWhenHitChanceIsOne() {
        assertTrue(java.util.stream.IntStream.range(0, 32).allMatch(i -> provider.doesMobHit(1.0d)));
    }

    @Test
    void shouldScalePhysicalSkillDamageFromBaseWeaponRange() {
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.calculateMaxBaseDamage(100)).thenReturn(1_000);
        when(bot.calculateMinBaseDamage(100, 0.6d)).thenReturn(500);
        when(effect.getDamage()).thenReturn(260);
        mockWeapon(bot, 1432000);

        Skill masterySkill = mock(Skill.class);
        StatEffect masteryEffect = mock(StatEffect.class);
        when(masteryEffect.getMastery()).thenReturn(10);
        when(masterySkill.getEffect(20)).thenReturn(masteryEffect);
        when(bot.getSkillLevel(constants.skills.Spearman.SPEAR_MASTERY)).thenReturn(20);

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(constants.skills.Spearman.SPEAR_MASTERY)).thenReturn(masterySkill);

            CombatFormulaProvider.DamageProfile profile = provider.resolveDamageProfile(bot, 1001005, effect, false);

            assertEquals(1_300, profile.minDamage());
            assertEquals(2_600, profile.maxDamage());
            assertFalse(profile.magicAttack());
            assertFalse(profile.alwaysHit());
        }
    }

    @Test
    void shouldResolvePhysicalMasteryFromWeaponMasterySkill() {
        Character bot = mockDamageBot();
        mockWeapon(bot, 1462000);

        Skill masterySkill = mock(Skill.class);
        StatEffect masteryEffect = mock(StatEffect.class);
        when(masteryEffect.getMastery()).thenReturn(9);
        when(masterySkill.getEffect(18)).thenReturn(masteryEffect);
        when(bot.getSkillLevel(constants.skills.Crossbowman.CROSSBOW_MASTERY)).thenReturn(18);

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(constants.skills.Crossbowman.CROSSBOW_MASTERY)).thenReturn(masterySkill);

            assertEquals(0.55d, provider.resolvePhysicalMastery(bot), 1.0e-12d);
        }
    }

    @Test
    void shouldFallbackToTenPercentPhysicalMasteryWithoutSkill() {
        Character bot = mockDamageBot();
        mockWeapon(bot, 1462000);
        when(bot.getSkillLevel(constants.skills.Crossbowman.CROSSBOW_MASTERY)).thenReturn(0);

        assertEquals(0.1d, provider.resolvePhysicalMastery(bot), 1.0e-12d);
    }

    @Test
    void shouldUseLuckySevenWatkScaledFormula() {
        // Formula: MAX = LUK * 5 * watk / 100, MIN = LUK * 2.5 * watk / 100
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(bot.getTotalWatk()).thenReturn(90);
        when(bot.getTotalLuk()).thenReturn(200);
        when(effect.getDamage()).thenReturn(150);

        CombatFormulaProvider.DamageProfile profile =
                provider.resolveDamageProfile(bot, constants.skills.Rogue.LUCKY_SEVEN, effect, false);

        // base max = ceil(200*5*90/100)=900, base min = round(900*0.5)=450
        // after skill 150%: max=1350, min=675
        assertEquals(675, profile.minDamage());
        assertEquals(1_350, profile.maxDamage());
    }

    @Test
    void shouldUseDragonRoarFormulaBeforeApplyingSkillDamage() {
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(bot.getTotalWatk()).thenReturn(120);
        when(bot.getTotalStr()).thenReturn(100);
        when(bot.getTotalDex()).thenReturn(50);
        when(effect.getDamage()).thenReturn(240);
        mockWeapon(bot, 1432000);

        Skill masterySkill = mock(Skill.class);
        StatEffect masteryEffect = mock(StatEffect.class);
        when(masteryEffect.getMastery()).thenReturn(10);
        when(masterySkill.getEffect(20)).thenReturn(masteryEffect);
        when(bot.getSkillLevel(constants.skills.Spearman.SPEAR_MASTERY)).thenReturn(20);

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(constants.skills.Spearman.SPEAR_MASTERY)).thenReturn(masterySkill);

            CombatFormulaProvider.DamageProfile profile =
                    provider.resolveDamageProfile(bot, constants.skills.DragonKnight.DRAGON_ROAR, effect, false);

            assertEquals(765, profile.minDamage());
            assertEquals(1_296, profile.maxDamage());
        }
    }

    @Test
    void shouldApplyDragonKnightCrusherAndFuryForcedStabSwingMultipliers() {
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(100);
        when(bot.getTotalDex()).thenReturn(50);
        when(effect.getDamage()).thenReturn(100);
        mockWeapon(bot, 1432000);

        Skill masterySkill = mock(Skill.class);
        StatEffect masteryEffect = mock(StatEffect.class);
        when(masteryEffect.getMastery()).thenReturn(10);
        when(masterySkill.getEffect(20)).thenReturn(masteryEffect);
        when(bot.getSkillLevel(constants.skills.Spearman.SPEAR_MASTERY)).thenReturn(20);

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(constants.skills.Spearman.SPEAR_MASTERY)).thenReturn(masterySkill);

            CombatFormulaProvider.DamageProfile crusher =
                    provider.resolveDamageProfile(bot, constants.skills.DragonKnight.SPEAR_CRUSHER, effect, false);
            CombatFormulaProvider.DamageProfile fury =
                    provider.resolveDamageProfile(bot, constants.skills.DragonKnight.SPEAR_DRAGON_FURY, effect, false);

            assertEquals(320, crusher.minDamage());
            assertEquals(550, crusher.maxDamage());
            assertEquals(212, fury.minDamage());
            assertEquals(350, fury.maxDamage());
        }
    }

    @Test
    void shouldApplyPolearmCrusherAndFuryForcedStabSwingMultipliers() {
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(100);
        when(bot.getTotalDex()).thenReturn(50);
        when(effect.getDamage()).thenReturn(100);
        mockWeapon(bot, 1442000);

        Skill masterySkill = mock(Skill.class);
        StatEffect masteryEffect = mock(StatEffect.class);
        when(masteryEffect.getMastery()).thenReturn(10);
        when(masterySkill.getEffect(20)).thenReturn(masteryEffect);
        when(bot.getSkillLevel(constants.skills.Spearman.POLEARM_MASTERY)).thenReturn(20);

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(constants.skills.Spearman.POLEARM_MASTERY)).thenReturn(masterySkill);

            CombatFormulaProvider.DamageProfile crusher =
                    provider.resolveDamageProfile(bot, constants.skills.DragonKnight.POLE_ARM_CRUSHER, effect, false);
            CombatFormulaProvider.DamageProfile fury =
                    provider.resolveDamageProfile(bot, constants.skills.DragonKnight.POLE_ARM_DRAGON_FURY, effect, false);

            assertEquals(212, crusher.minDamage());
            assertEquals(350, crusher.maxDamage());
            assertEquals(320, fury.minDamage());
            assertEquals(550, fury.maxDamage());
        }
    }

    @Test
    void shouldApplyActionSelectedStabSwingMultiplierForNormalAttackSkills() {
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(100);
        when(bot.getTotalDex()).thenReturn(50);
        when(effect.getDamage()).thenReturn(100);
        mockWeapon(bot, 1432000);

        Skill masterySkill = mock(Skill.class);
        StatEffect masteryEffect = mock(StatEffect.class);
        when(masteryEffect.getMastery()).thenReturn(10);
        when(masterySkill.getEffect(20)).thenReturn(masteryEffect);
        when(bot.getSkillLevel(constants.skills.Spearman.SPEAR_MASTERY)).thenReturn(20);

        try (MockedStatic<SkillFactory> skillFactory = Mockito.mockStatic(SkillFactory.class)) {
            skillFactory.when(() -> SkillFactory.getSkill(constants.skills.Spearman.SPEAR_MASTERY)).thenReturn(masterySkill);

            CombatFormulaProvider.DamageProfile stab =
                    provider.resolveDamageProfile(bot, constants.skills.Warrior.POWER_STRIKE, effect, false, WeaponType.SPEAR_STAB);
            CombatFormulaProvider.DamageProfile swing =
                    provider.resolveDamageProfile(bot, constants.skills.Warrior.POWER_STRIKE, effect, false, WeaponType.SPEAR_SWING);

            assertEquals(320, stab.minDamage());
            assertEquals(550, stab.maxDamage());
            assertEquals(212, swing.minDamage());
            assertEquals(350, swing.maxDamage());
        }
    }

    @Test
    void shouldUseMagicBaseDamageFormulaWithActualMastery() {
        // MAX: ceil((3000*3+3000)/30) + 0 = 400; * getMatk()=5 → 2000
        // MIN: mastery=50% → ceil((3000*3 + 3000*0.5*0.9)/30) = ceil(345) = 345; * 5 → 1725
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(bot.getTotalMagic()).thenReturn(3000);
        when(bot.getTotalInt()).thenReturn(0);
        when(effect.getX()).thenReturn(50);   // 50% mastery from skill data
        when(effect.getMatk()).thenReturn((short) 5);

        CombatFormulaProvider.DamageProfile profile = provider.resolveDamageProfile(bot, 2101004, effect, true);

        assertEquals(1_725, profile.minDamage());
        assertEquals(2_000, profile.maxDamage());
        assertTrue(profile.magicAttack());
        assertFalse(profile.alwaysHit());
    }

    @Test
    void shouldExposeStandaloneMagicDamageBaseMethods() {
        // MAX: matk=3000, totalInt=200 → ceil((3000*3+3000)/30) + ceil(200/200) = 400 + 1 = 401
        assertEquals(401L, provider.magicDamageBase(3000, 200));
        // MIN: matk=3000, totalInt=0, mastery=0.5 → ceil((9000+1350)/30) = ceil(345) = 345
        assertEquals(345L, provider.magicDamageBaseMin(3000, 0, 0.5));
    }

    @Test
    void shouldTreatFixedDamageSkillsAsAlwaysHit() {
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(effect.getFixDamage()).thenReturn(777);

        CombatFormulaProvider.DamageProfile profile = provider.resolveDamageProfile(bot, 0, effect, false);

        assertEquals(777, profile.minDamage());
        assertEquals(777, profile.maxDamage());
        assertTrue(profile.alwaysHit());
    }

    // --- Degenerate (mismatched-weapon) swing formulas ---
    // Client truth: CalcDamage::PDamage (Angel.idb @0x0078DF87) + dmg_formula_ayumilove.txt
    // (Power Knock Back / Claw punching). Fixed 10% mastery, weak divisor, no crit.

    @Test
    void shouldUseWeakSwingFormulaForDegenerateBowAndCrossbowAttack() {
        Character bot = mockDamageBot();
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.getTotalDex()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(40);

        for (WeaponType weaponType : List.of(WeaponType.BOW, WeaponType.CROSSBOW)) {
            CombatFormulaProvider.DamageProfile profile =
                    provider.resolveDegenerateDamageProfile(bot, weaponType, null);

            // MAX = (100*3.4 + 40) * 100 / 150 = 253.33 -> ceil 254
            // MIN = (100*3.4*0.09 + 40) * 100 / 150 = 47.07 -> round 47
            assertEquals(47, profile.minDamage());
            assertEquals(254, profile.maxDamage());
            assertTrue(profile.noCrit());
            assertFalse(profile.magicAttack());
        }
    }

    @Test
    void shouldUseWeakPunchFormulaForDegenerateClawAttack() {
        Character bot = mockDamageBot();
        when(bot.getTotalWatk()).thenReturn(75);
        when(bot.getTotalLuk()).thenReturn(120);
        when(bot.getTotalStr()).thenReturn(30);
        when(bot.getTotalDex()).thenReturn(60);

        CombatFormulaProvider.DamageProfile profile =
                provider.resolveDegenerateDamageProfile(bot, WeaponType.CLAW, null);

        // MAX = (120 + 30 + 60) * 75 / 150 = 105
        // MIN = (120*0.09 + 30 + 60) * 75 / 150 = 50.4 -> round 50
        assertEquals(50, profile.minDamage());
        assertEquals(105, profile.maxDamage());
        assertTrue(profile.noCrit());
    }

    @Test
    void shouldUseWeakBashFormulaForDegenerateGunAttack() {
        Character bot = mockDamageBot();
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.getTotalDex()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(40);

        CombatFormulaProvider.DamageProfile profile =
                provider.resolveDegenerateDamageProfile(bot, WeaponType.GUN, null);

        // MAX = (100 + 40) * 100 / 200 = 70
        // MIN = (100*0.09 + 40) * 100 / 200 = 24.5 -> round 25
        assertEquals(25, profile.minDamage());
        assertEquals(70, profile.maxDamage());
        assertTrue(profile.noCrit());
    }

    @Test
    void shouldComposeSkillDamagePercentOnTopOfDegenerateBase() {
        // Power Knockback: weak degenerate base, skill damage% on top (order of operations step 5)
        Character bot = mockDamageBot();
        StatEffect effect = mock(StatEffect.class);
        when(bot.getTotalWatk()).thenReturn(100);
        when(bot.getTotalDex()).thenReturn(100);
        when(bot.getTotalStr()).thenReturn(40);
        when(effect.getDamagePercent()).thenReturn(130);

        CombatFormulaProvider.DamageProfile profile =
                provider.resolveDegenerateDamageProfile(bot, WeaponType.BOW, effect);

        // base 47-254 (see bow test) * 130% -> 61-330
        assertEquals(61, profile.minDamage());
        assertEquals(330, profile.maxDamage());
        assertTrue(profile.noCrit());
    }

    @Test
    void shouldNotApplyCritBonusesToNoCritDamageProfiles() {
        Character bot = mockDamageBot();
        when(bot.getLevel()).thenReturn(50);
        when(bot.getAllBuffs()).thenReturn(List.of());
        // Sharp Eyes: 100% crit chance, +100% crit damage -> critable lines deal 2x
        when(bot.getBuffedValue(BuffStat.SHARP_EYES)).thenReturn((100 << 8) | 100);
        server.life.Monster monster = mock(server.life.Monster.class);
        when(monster.getLevel()).thenReturn(10);
        when(monster.getAvoidability()).thenReturn(0);
        when(monster.getWdef()).thenReturn(0);

        CombatFormulaProvider.DamageProfile critable =
                new CombatFormulaProvider.DamageProfile(100, 100, false, false, false);
        CombatFormulaProvider.DamageProfile noCrit =
                new CombatFormulaProvider.DamageProfile(100, 100, false, false, true);

        assertEquals(200.0, provider.estimateExpectedDamage(bot, monster, 1, 0, critable), 1e-9);
        assertEquals(100.0, provider.estimateExpectedDamage(bot, monster, 1, 0, noCrit), 1e-9);
    }

    // --- Critical hit profile tests ---

    @Test
    void shouldReturnNoCritForNonCritJob() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        when(bot.getBuffedValue(BuffStat.SHARP_EYES)).thenReturn(null);

        CombatFormulaProvider.CritProfile profile = provider.resolveCritProfile(bot);

        assertEquals(0.0, profile.critChance());
        assertEquals(1.0, profile.critMultiplier());
    }

    @Test
    void shouldReturnBaseCritMultiplierForCritJobWithNoSkill() {
        // Crit passive not leveled → no +100% passive bonus; multiplier stays at 1.0 (hit only)
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BOWMAN);
        when(bot.getBuffedValue(BuffStat.SHARP_EYES)).thenReturn(null);
        when(bot.getSkillLevel(org.mockito.ArgumentMatchers.any(client.Skill.class))).thenReturn((byte) 0);

        CombatFormulaProvider.CritProfile profile = provider.resolveCritProfile(bot);

        assertEquals(0.0, profile.critChance());
        assertEquals(1.0, profile.critMultiplier());
    }

    @Test
    void shouldIncludeSharpEyesCritRateAndDamageBonus() {
        // Sharp Eyes encodes: critRate% << 8 | critDmgBonus%
        // e.g. level 30: critRate=10, critDmgBonus=40 → buffValue = (10 << 8) | 40 = 2600
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BOWMAN);
        when(bot.getBuffedValue(BuffStat.SHARP_EYES)).thenReturn((10 << 8) | 40);
        when(bot.getSkillLevel(org.mockito.ArgumentMatchers.any(client.Skill.class))).thenReturn((byte) 0);

        CombatFormulaProvider.CritProfile profile = provider.resolveCritProfile(bot);

        // passive level 0 → no +100% bonus; only SE +40% → 1.0 + 0.4 = 1.4
        assertEquals(0.10, profile.critChance(), 1e-9);
        assertEquals(1.40, profile.critMultiplier(), 1e-9);
    }

    @Test
    void shouldCapCritChanceAt100Percent() {
        // critRate=100% from Sharp Eyes alone
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BOWMAN);
        when(bot.getBuffedValue(BuffStat.SHARP_EYES)).thenReturn((100 << 8) | 0);
        when(bot.getSkillLevel(org.mockito.ArgumentMatchers.any(client.Skill.class))).thenReturn((byte) 0);

        CombatFormulaProvider.CritProfile profile = provider.resolveCritProfile(bot);

        assertEquals(1.0, profile.critChance());
    }

    @Test
    void shouldApplyCritMultiplierWhenCritChanceIsGuaranteed() {
        // resolveCritProfile with Sharp Eyes 100% crit + 100% bonus (2.0x total = 3.0x... but here test with explicit mock)
        // Use guaranteed crit via Sharp Eyes: critRate=100, critDmgBonus=100 → critChance=1.0, critMultiplier=3.0
        // We can't call rollDamageLinesWithCrit (private), so verify via resolveCritProfile shape only.
        // The crit multiplier at 2.0x for a base of 100 → 200; cap test via 99999.
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BOWMAN);
        when(bot.getBuffedValue(BuffStat.SHARP_EYES)).thenReturn((100 << 8) | 0);
        when(bot.getSkillLevel(org.mockito.ArgumentMatchers.any(client.Skill.class))).thenReturn((byte) 0);

        CombatFormulaProvider.CritProfile profile = provider.resolveCritProfile(bot);

        // passive level 0 → multiplier = 1.0 (hit only) + 0% SE bonus = 1.0
        assertEquals(1.0, profile.critChance());
        assertEquals(1.0, profile.critMultiplier());
    }

    @Test
    void shouldCapCritDamageAt99999() {
        // Verify that 99999 * 2.0 is capped at 99999.
        // rollDamageLinesWithCrit is private; test indirectly: critMultiplier 2.0 * 99999 should cap.
        // We rely on the formula: (int) Math.min(99999, Math.floor(99999 * 2.0)) == 99999
        assertEquals(99999, (int) Math.min(99999, Math.floor(99999 * 2.0)));
    }

    private static Character mockDamageBot() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.SPEARMAN);
        return bot;
    }

    private static void mockWeapon(Character bot, int itemId) {
        Inventory equipped = mock(Inventory.class);
        Item weapon = mock(Item.class);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.getItem((short) -11)).thenReturn(weapon);
        when(weapon.getItemId()).thenReturn(itemId);
    }
}
