package server.bots;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import constants.skills.Assassin;
import constants.skills.Crusader;
import constants.skills.DragonKnight;
import constants.skills.Fighter;
import constants.skills.Pirate;
import constants.skills.Rogue;
import constants.skills.Spearman;
import org.junit.jupiter.api.Test;
import server.life.MonsterStats;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotEquipManagerTest {

    // ---- recommendSecondaryTarget / chooseSecondaryTarget (autonomous AP build) ----------------
    // floors/base/gear are int[4] indexed [STR, DEX, INT, LUK]. Warrior: primary STR ('s'),
    // secondary DEX ('d'). cycleMs 0 keeps DPS == raw so the arithmetic is exact in assertions.

    private static BotEquipManager.WeaponCand sword(int reqDex, int watk) {
        return new BotEquipManager.WeaponCand(reqDex, watk, WeaponType.SWORD1H, 0);
    }

    @Test
    void chooseSecondaryTargetStaysAtFloorWhenGearCoversRequirement() {
        int[] floors = {4, 4, 4, 4};
        int[] base = {4, 4, 4, 4};
        int[] gear = {0, 30, 0, 0}; // +30 DEX from gear
        // Weapon needs 20 DEX; gear alone covers it -> no AP into DEX -> near-pure.
        int target = BotEquipManager.chooseSecondaryTarget(Job.WARRIOR, 's', 'd', 4,
                floors, base, gear, 50, List.of(sword(20, 100)), 0);
        assertEquals(4, target);
    }

    @Test
    void chooseSecondaryTargetRaisesToRequirementMinusGearWhenGearIsShort() {
        int[] floors = {4, 4, 4, 4};
        int[] base = {4, 4, 4, 4};
        int[] gear = {0, 10, 0, 0}; // +10 DEX from gear
        // Weapon needs 40 DEX; gear supplies 10 -> AP must cover the remaining 30.
        int target = BotEquipManager.chooseSecondaryTarget(Job.WARRIOR, 's', 'd', 4,
                floors, base, gear, 100, List.of(sword(40, 100)), 0);
        assertEquals(30, target);
    }

    @Test
    void chooseSecondaryTargetKeepsFloorWhenWeaponUpgradeIsNotWorthTheApLoss() {
        int[] floors = {4, 4, 4, 4};
        int[] base = {4, 4, 4, 4};
        int[] gear = {0, 0, 0, 0};
        // Cheap weapon (no extra DEX) vs a 80-DEX weapon with only a tiny WATK bump: the AP lost
        // from STR to fund 76 DEX outweighs the WATK gain, so the bot stays pure on the cheap one.
        int target = BotEquipManager.chooseSecondaryTarget(Job.WARRIOR, 's', 'd', 4,
                floors, base, gear, 100, List.of(sword(4, 100), sword(80, 105)), 0);
        assertEquals(4, target);
    }

    @Test
    void chooseSecondaryTargetInvestsSecondaryWhenWeaponUpgradeIsWorthIt() {
        int[] floors = {4, 4, 4, 4};
        int[] base = {4, 4, 4, 4};
        int[] gear = {0, 0, 0, 0};
        // Cheap weak weapon vs a 30-DEX weapon with a large WATK jump: paying 26 AP into DEX wins.
        int target = BotEquipManager.chooseSecondaryTarget(Job.WARRIOR, 's', 'd', 4,
                floors, base, gear, 100, List.of(sword(4, 50), sword(30, 300)), 0);
        assertEquals(30, target);
    }

    @Test
    void chooseSecondaryTargetNeverStrandsTheEquippedWeapon() {
        int[] floors = {4, 4, 4, 4};
        int[] base = {4, 4, 4, 4};
        int[] gear = {0, 0, 0, 0};
        // Best candidate needs no DEX, but the currently-equipped weapon needs 50 DEX: the target
        // is floored at the equipped requirement so reallocation doesn't unequip the worn weapon.
        int target = BotEquipManager.chooseSecondaryTarget(Job.WARRIOR, 's', 'd', 4,
                floors, base, gear, 200, List.of(sword(4, 200)), 50);
        assertEquals(50, target);
    }

    @Test
    void chooseSecondaryTargetFallsBackToFloorWithNoCandidates() {
        int[] floors = {4, 4, 4, 4};
        int[] base = {4, 4, 4, 4};
        int[] gear = {0, 0, 0, 0};
        int target = BotEquipManager.chooseSecondaryTarget(Job.WARRIOR, 's', 'd', 4,
                floors, base, gear, 100, List.of(), 0);
        assertEquals(4, target);
    }

    @Test
    void firstJobBowmanPrefersBowAndCrossbowOnly() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BOWMAN);

        assertTrue(BotEquipManager.isPreferredWeapon(bot, WeaponType.BOW));
        assertTrue(BotEquipManager.isPreferredWeapon(bot, WeaponType.CROSSBOW));
        assertFalse(BotEquipManager.isPreferredWeapon(bot, WeaponType.CLAW));
    }

    @Test
    void hunterPrefersBowsButCanFallbackToCrossbow() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.HUNTER);

        assertTrue(BotEquipManager.isPreferredWeapon(bot, WeaponType.BOW));
        assertFalse(BotEquipManager.isPreferredWeapon(bot, WeaponType.CROSSBOW));
        assertTrue(BotEquipManager.isWeaponCompatible(bot, WeaponType.CROSSBOW));
    }

    @Test
    void firstJobPirateUsesSkillsToPickGunOrKnuckle() {
        Character gunPirate = mock(Character.class);
        when(gunPirate.getJob()).thenReturn(Job.PIRATE);
        when(gunPirate.getSkillLevel(Pirate.DOUBLE_SHOT)).thenReturn(1);
        when(gunPirate.getSkillLevel(Pirate.FLASH_FIST)).thenReturn(0);
        when(gunPirate.getSkillLevel(Pirate.SOMERSAULT_KICK)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(gunPirate, WeaponType.GUN));
        assertFalse(BotEquipManager.isPreferredWeapon(gunPirate, WeaponType.KNUCKLE));

        Character knucklePirate = mock(Character.class);
        when(knucklePirate.getJob()).thenReturn(Job.PIRATE);
        when(knucklePirate.getSkillLevel(Pirate.DOUBLE_SHOT)).thenReturn(0);
        when(knucklePirate.getSkillLevel(Pirate.FLASH_FIST)).thenReturn(1);
        when(knucklePirate.getSkillLevel(Pirate.SOMERSAULT_KICK)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(knucklePirate, WeaponType.KNUCKLE));
        assertFalse(BotEquipManager.isPreferredWeapon(knucklePirate, WeaponType.GUN));
    }

    @Test
    void fighterUsesSwordOrAxeSkillsToChooseWeaponFamily() {
        Character swordFighter = mock(Character.class);
        when(swordFighter.getJob()).thenReturn(Job.FIGHTER);
        when(swordFighter.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(1);
        when(swordFighter.getSkillLevel(Fighter.SWORD_BOOSTER)).thenReturn(0);
        when(swordFighter.getSkillLevel(Fighter.AXE_MASTERY)).thenReturn(0);
        when(swordFighter.getSkillLevel(Fighter.AXE_BOOSTER)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(swordFighter, WeaponType.SWORD1H));
        assertTrue(BotEquipManager.isPreferredWeapon(swordFighter, WeaponType.SWORD2H));
        assertFalse(BotEquipManager.isPreferredWeapon(swordFighter, WeaponType.GENERAL1H_SWING));

        Character axeFighter = mock(Character.class);
        when(axeFighter.getJob()).thenReturn(Job.FIGHTER);
        when(axeFighter.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(0);
        when(axeFighter.getSkillLevel(Fighter.SWORD_BOOSTER)).thenReturn(0);
        when(axeFighter.getSkillLevel(Fighter.AXE_MASTERY)).thenReturn(1);
        when(axeFighter.getSkillLevel(Fighter.AXE_BOOSTER)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(axeFighter, WeaponType.GENERAL1H_SWING));
        assertTrue(BotEquipManager.isPreferredWeapon(axeFighter, WeaponType.GENERAL2H_SWING));
        assertFalse(BotEquipManager.isPreferredWeapon(axeFighter, WeaponType.SWORD1H));
    }

    @Test
    void spearmanUsesSpearOrPolearmSkillsToChooseWeaponFamily() {
        Character spearBot = mock(Character.class);
        when(spearBot.getJob()).thenReturn(Job.SPEARMAN);
        when(spearBot.getSkillLevel(Spearman.SPEAR_MASTERY)).thenReturn(1);
        when(spearBot.getSkillLevel(Spearman.SPEAR_BOOSTER)).thenReturn(0);
        when(spearBot.getSkillLevel(Spearman.POLEARM_MASTERY)).thenReturn(0);
        when(spearBot.getSkillLevel(Spearman.POLEARM_BOOSTER)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(spearBot, WeaponType.SPEAR_STAB));
        assertTrue(BotEquipManager.isPreferredWeapon(spearBot, WeaponType.SPEAR_SWING));
        assertFalse(BotEquipManager.isPreferredWeapon(spearBot, WeaponType.POLE_ARM_SWING));

        Character polearmBot = mock(Character.class);
        when(polearmBot.getJob()).thenReturn(Job.SPEARMAN);
        when(polearmBot.getSkillLevel(Spearman.SPEAR_MASTERY)).thenReturn(0);
        when(polearmBot.getSkillLevel(Spearman.SPEAR_BOOSTER)).thenReturn(0);
        when(polearmBot.getSkillLevel(Spearman.POLEARM_MASTERY)).thenReturn(1);
        when(polearmBot.getSkillLevel(Spearman.POLEARM_BOOSTER)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(polearmBot, WeaponType.POLE_ARM_SWING));
        assertTrue(BotEquipManager.isPreferredWeapon(polearmBot, WeaponType.POLE_ARM_STAB));
        assertFalse(BotEquipManager.isPreferredWeapon(polearmBot, WeaponType.SPEAR_STAB));
    }

    @Test
    void fourthJobWarriorsKeepTheirThirdJobWeaponFamily() {
        Character hero = mock(Character.class);
        when(hero.getJob()).thenReturn(Job.HERO);
        when(hero.getSkillLevel(Crusader.SWORD_COMA)).thenReturn(1);
        when(hero.getSkillLevel(Crusader.SWORD_PANIC)).thenReturn(0);
        when(hero.getSkillLevel(Crusader.AXE_COMA)).thenReturn(0);
        when(hero.getSkillLevel(Crusader.AXE_PANIC)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(hero, WeaponType.SWORD1H));
        assertFalse(BotEquipManager.isPreferredWeapon(hero, WeaponType.GENERAL1H_SWING));

        Character darkKnight = mock(Character.class);
        when(darkKnight.getJob()).thenReturn(Job.DARKKNIGHT);
        when(darkKnight.getSkillLevel(DragonKnight.SPEAR_CRUSHER)).thenReturn(0);
        when(darkKnight.getSkillLevel(DragonKnight.SPEAR_DRAGON_FURY)).thenReturn(0);
        when(darkKnight.getSkillLevel(DragonKnight.POLE_ARM_CRUSHER)).thenReturn(1);
        when(darkKnight.getSkillLevel(DragonKnight.POLE_ARM_DRAGON_FURY)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(darkKnight, WeaponType.POLE_ARM_SWING));
        assertFalse(BotEquipManager.isPreferredWeapon(darkKnight, WeaponType.SPEAR_STAB));
    }

    @Test
    void luckySevenThiefPrefersClaws() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.THIEF);
        when(bot.getSkillLevel(Rogue.LUCKY_SEVEN)).thenReturn(1);
        when(bot.getSkillLevel(Rogue.DOUBLE_STAB)).thenReturn(0);

        assertTrue(BotEquipManager.isPreferredWeapon(bot, WeaponType.CLAW));
        assertFalse(BotEquipManager.isPreferredWeapon(bot, WeaponType.DAGGER_OTHER));
    }

    @Test
    void mageAcceptsOffTypeWeaponCarryingMatk() {
        // Black Umbrella field case: a reqJob-0 ONE-HANDED SWORD with MAD. v83 magic damage
        // reads total MATK only — casting ignores weapon type — so any wearable MAD weapon
        // is a real mage weapon candidate.
        Character mage = mock(Character.class);
        when(mage.getJob()).thenReturn(Job.CLERIC);

        Equip blackUmbrella = matkWeapon(1302026, 92);
        assertTrue(BotEquipManager.isPreferredWeapon(mage, WeaponType.SWORD1H, blackUmbrella));

        // A 0-MAD off-type weapon adds nothing for a mage and stays incompatible.
        Equip plainSword = matkWeapon(1302000, 0);
        assertFalse(BotEquipManager.isPreferredWeapon(mage, WeaponType.SWORD1H, plainSword));
    }

    @Test
    void physicalClassTreatsOffTypeWeaponAsFallbackNotPreferred() {
        Character sin = mock(Character.class);
        when(sin.getJob()).thenReturn(Job.ASSASSIN);

        Equip blackUmbrella = matkWeapon(1302026, 92);
        assertFalse(BotEquipManager.isPreferredWeapon(sin, WeaponType.SWORD1H, blackUmbrella));
        assertTrue(BotEquipManager.isWeaponCompatible(sin, WeaponType.SWORD1H, blackUmbrella));
    }

    @Test
    void mageSelfReservesWearableOffTypeMatkWeapon() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.MAGICIAN);

        Equip umbrella = matkWeapon(1302026, 92);
        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.MAGICIAN, umbrella, "Wp", 70, 0, 6, 6, 6, 6, 0);
        when(hooks.getWeaponType(1302026)).thenReturn(WeaponType.SWORD1H);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks, List.of(umbrella));

        assertTrue(keep.contains(umbrella),
                "mage must RESV-SELF an any-job MAD sword instead of letting it fall to sell-trash");
    }

    @Test
    void offTypeMatkWeaponTracksByHandednessAgainstMageBaseline() {
        // 1H umbrella maps to the wand-side track: a dominating WORN 2H staff must not
        // suppress it (1H frees the shield slot), but a 2H off-type MAD weapon competes in
        // the staff track and IS dominated.
        Character mage = mock(Character.class);
        when(mage.getJob()).thenReturn(Job.MAGICIAN);
        Inventory equipped = mock(Inventory.class);
        when(mage.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);

        Equip wornStaff = matkWeapon(1382005, 100);
        when(equipped.list()).thenReturn(List.of(wornStaff));

        Equip umbrella1H = matkWeapon(1302026, 92);
        Equip matkPolearm2H = matkWeapon(1442999, 92);

        BotEquipManager.EquipUsefulnessHooks hooks = mock(BotEquipManager.EquipUsefulnessHooks.class);
        for (Equip e : List.of(wornStaff, umbrella1H, matkPolearm2H)) {
            when(hooks.getEquipmentSlot(e.getItemId())).thenReturn("Wp");
            when(hooks.meetsReqs(e, Job.MAGICIAN, Short.MAX_VALUE,
                    Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                    Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 0)).thenReturn(true);
        }
        when(hooks.getWeaponType(1382005)).thenReturn(WeaponType.STAFF);
        when(hooks.getWeaponType(1302026)).thenReturn(WeaponType.SWORD1H);
        when(hooks.getWeaponType(1442999)).thenReturn(WeaponType.POLE_ARM_SWING);
        when(hooks.isTwoHanded(1382005)).thenReturn(true);
        when(hooks.isTwoHanded(1442999)).thenReturn(true);

        assertTrue(BotEquipManager.isEquipUsefulToBot(mage, hooks, umbrella1H),
                "1H MAD weapon rivals wands, so the stronger worn staff must not dominate it");
        assertFalse(BotEquipManager.isEquipUsefulToBot(mage, hooks, matkPolearm2H),
                "2H MAD weapon rivals staves and is dominated by the stronger worn staff");
    }

    @Test
    void mageUsefulGearScoreIgnoresDexAndValuesInt() {
        Equip dexOverall = mock(Equip.class);
        when(dexOverall.getDex()).thenReturn((short) 10);
        Equip intOverall = mock(Equip.class);
        when(intOverall.getInt()).thenReturn((short) 5);

        assertTrue(BotEquipManager.usefulStatSum(intOverall, Job.MAGICIAN)
                > BotEquipManager.usefulStatSum(dexOverall, Job.MAGICIAN));
    }

    @Test
    void expectedDamageReducesToMidMinusWdefBelowMin() {
        // wdef <= rawMin: every roll is unclamped, expected = (rawMin + rawMax)/2 - wdef
        assertEquals(60.0, BotEquipManager.expectedDamageAfterDef(100, 15), 0.01);
    }

    @Test
    void expectedDamageClampsToOneWhenWdefExceedsMax() {
        assertEquals(1.0, BotEquipManager.expectedDamageAfterDef(100, 200), 0.01);
    }

    @Test
    void expectedDamagePreservesUpperTailWhenWdefExceedsMid() {
        // rawMax=100, rawMin=50, wdef=80 (between mid=75 and max=100). Old formula would have
        // returned max(1, 75-80)=1, erasing the [80,100] upper tail. Integral of clamped uniform
        // here equals 0.6 (clamped fraction) + 400/(2*50) = 0.6 + 4 = 4.6.
        assertEquals(4.6, BotEquipManager.expectedDamageAfterDef(100, 80), 0.01);
    }

    @Test
    void expectedDamageDifferentiatesCandidatesAgainstHighWdefMob() {
        // Two candidates with slightly different rawMax against a high-WDEF mob: old formula
        // collapsed both to 1; new formula keeps a meaningful gap.
        double weaker = BotEquipManager.expectedDamageAfterDef(100, 80);
        double stronger = BotEquipManager.expectedDamageAfterDef(120, 80);
        assertTrue(stronger > weaker + 0.5,
                "expected stronger > weaker by margin; got " + stronger + " vs " + weaker);
    }

    @Test
    void mapDamageProfilePrefersHigherLevelThenAvoidability() {
        MonsterStats weaker = new MonsterStats();
        weaker.setLevel(48);
        weaker.setAvoidability(10);
        weaker.setPDDamage(120);

        MonsterStats moreEvasive = new MonsterStats();
        moreEvasive.setLevel(48);
        moreEvasive.setAvoidability(25);
        moreEvasive.setPDDamage(80);

        BotEquipManager.MapDamageProfile profile = BotEquipManager.MapDamageProfile.fromStats(
                List.of(weaker, moreEvasive));

        assertEquals(48, profile.mobLevel());
        assertEquals(25, profile.mobAvoid());
        assertEquals(80, profile.mobWdef());
    }

    /**
     * Reproduces equiplog-Clawer-2026-05-04T134011.txt: a level-41 ASSASSIN with weak naked
     * stats had Bronze Wolfskin (LUK0) and Blue Moon Gloves (LUK2) reserved as "RESERVED_FOR_SELF"
     * even though Purple Work Gloves (LUK4) was already equipped and dominated them on the
     * stat track that matters for an assassin (LUK). The new baseline-beat rule should keep
     * only items that exceed the bot's currently-wearable best on a relevant stat (LUK/DEX/WATK
     * for thieves), and trash everything else.
     */
    @Test
    void selectItemsBeatingBaselineReproducesAssassinGloveLog() {
        // baseline = items the bot can wear with naked stats (incl. currently-equipped)
        Equip purpleWork = glove(/*luk*/ 4, /*dex*/ 0, /*watk*/ 0);    // equipped
        Equip redMarker = glove(/*luk*/ 0, /*dex*/ 0, /*watk*/ 0);     // INT3 — irrelevant for sin
        Equip bronzeWolfskinBaseline = glove(0, 0, 0);                 // baseline copy
        Equip dexEq2 = glove(/*luk*/ 0, /*dex*/ 2, /*watk*/ 0);
        Equip dexEq4 = glove(/*luk*/ 0, /*dex*/ 4, /*watk*/ 0);

        // candidates = bag items (subset of which are also in baseline if naked-wearable)
        Equip bronzeWolfskin = bronzeWolfskinBaseline;                 // naked-wearable
        Equip blueMoon = glove(/*luk*/ 2, 0, 0);                       // not naked-wearable (dex90 req)
        Equip orichalcon = glove(/*luk*/ 7, 0, 0);                     // not naked-wearable (dex70 req)
        Equip dexUneq3 = glove(0, /*dex*/ 3, 0);                       // not naked-wearable
        Equip dexUneq5 = glove(0, /*dex*/ 5, 0);                       // not naked-wearable

        List<Equip> baseline = List.of(purpleWork, redMarker, bronzeWolfskinBaseline, dexEq2, dexEq4);
        List<Equip> bagItems = List.of(redMarker, bronzeWolfskin, blueMoon, orichalcon,
                                       dexEq2, dexEq4, dexUneq3, dexUneq5);

        Set<Equip> keep = BotEquipManager.selectItemsBeatingBaseline(
                BotEquipManager.relevantStatsFor(Job.ASSASSIN), bagItems, baseline);

        // Identity comparison: orichalcon beats LUK baseline 4, dexEq4 ties DEX baseline 4,
        // dexUneq5 beats DEX baseline 4. Everything else fails on every relevant stat.
        Set<Equip> expected = identitySet(orichalcon, dexEq4, dexUneq5);
        assertEquals(expected, identitySet(keep.toArray(new Equip[0])),
                "expected only Orichalcon, +4 DEX equippable, and +5 DEX unequippable");

        // Spot-check trash exclusions for the original log items
        assertFalse(keep.contains(bronzeWolfskin), "Bronze Wolfskin (LUK0) should be trash for sin");
        assertFalse(keep.contains(blueMoon), "Blue Moon (LUK2 < baseline 4) should be trash");
        assertFalse(keep.contains(redMarker), "Red Marker (INT-only) should be trash for sin");
        assertFalse(keep.contains(dexEq2), "+2 DEX equippable (< baseline 4) should be trash");
        assertFalse(keep.contains(dexUneq3), "+3 DEX unequippable (< baseline 4) should be trash");
    }

    /**
     * Balanced gear must survive when baseline only has lopsided alternatives. With baseline
     * gloves at (LUK10,DEX0) and (LUK0,DEX10), candidates (9,9), (10,8), and (1,10) are all
     * Pareto-non-dominated on the {LUK,DEX} subset and must be kept; only candidates strictly
     * dominated by some baseline item (e.g. (9,0) dominated by (10,0)) get trashed.
     */
    @Test
    void selectItemsBeatingBaselineKeepsParetoNonDominatedCombinations() {
        Equip pureLuk = glove(/*luk*/ 10, 0, 0);
        Equip pureDex = glove(0, /*dex*/ 10, 0);
        List<Equip> baseline = List.of(pureLuk, pureDex);

        Equip balanced99 = glove(9, 9, 0);
        Equip dexHeavy108 = glove(8, 10, 0);
        Equip lukHeavy101 = glove(10, 1, 0);
        Equip strictlyWorseLuk = glove(9, 0, 0);   // dominated by pureLuk
        Equip strictlyWorseDex = glove(0, 9, 0);   // dominated by pureDex

        List<Equip> bagItems = List.of(balanced99, dexHeavy108, lukHeavy101,
                strictlyWorseLuk, strictlyWorseDex);

        Set<Equip> keep = BotEquipManager.selectItemsBeatingBaseline(
                BotEquipManager.relevantStatsFor(Job.ASSASSIN), bagItems, baseline);

        assertTrue(keep.contains(balanced99), "(9 LUK, 9 DEX) should be kept — not dominated by either pure baseline");
        assertTrue(keep.contains(dexHeavy108), "(8 LUK, 10 DEX) should be kept");
        assertTrue(keep.contains(lukHeavy101), "(10 LUK, 1 DEX) should be kept");
        assertFalse(keep.contains(strictlyWorseLuk), "(9 LUK, 0 DEX) should be trash — dominated by pure-LUK baseline");
        assertFalse(keep.contains(strictlyWorseDex), "(0 LUK, 9 DEX) should be trash — dominated by pure-DEX baseline");
    }

    /**
     * Future-tier candidates do NOT compete with each other — only the naked-wearable
     * baseline pool can trash them. Two future gloves where one strictly dominates the
     * other (LUK8/DEX10 vs LUK7/DEX10) must both survive, since the bot may unlock them
     * at different stat thresholds and we don't want to prematurely throw away any path.
     */
    @Test
    void selectItemsBeatingBaselineKeepsAllFutureTierEvenWhenOneDominatesAnother() {
        Equip pureLuk = glove(10, 0, 0);
        Equip pureDex = glove(0, 10, 0);
        List<Equip> baseline = List.of(pureLuk, pureDex);

        Equip dexHeavy108 = glove(/*luk*/ 8, /*dex*/ 10, 0);
        Equip dexHeavy107 = glove(/*luk*/ 7, /*dex*/ 10, 0);

        Set<Equip> keep = BotEquipManager.selectItemsBeatingBaseline(
                BotEquipManager.relevantStatsFor(Job.ASSASSIN),
                List.of(dexHeavy108, dexHeavy107), baseline);

        assertTrue(keep.contains(dexHeavy108), "(LUK8, DEX10) should be kept");
        assertTrue(keep.contains(dexHeavy107),
                "(LUK7, DEX10) should also be kept — future-tier candidates don't compete with each other");
    }

    /**
     * Naked-wearable items in the same slot dominate each other on the relevant subset:
     * a (LUK15, DEX1) glove evicts a pure (LUK15, DEX0) glove from the keep set because
     * the former is strictly better on the relevant-stat vector and both are wearable now.
     */
    @Test
    void selectItemsBeatingBaselineLuk15Dex1DominatesPureLuk15() {
        Equip pureLuk15 = glove(/*luk*/ 15, /*dex*/ 0, 0);
        Equip luk15Dex1 = glove(/*luk*/ 15, /*dex*/ 1, 0);
        List<Equip> baseline = List.of(pureLuk15, luk15Dex1);   // both naked-wearable
        List<Equip> bagItems = List.of(pureLuk15, luk15Dex1);

        Set<Equip> keep = BotEquipManager.selectItemsBeatingBaseline(
                BotEquipManager.relevantStatsFor(Job.ASSASSIN), bagItems, baseline);

        assertTrue(keep.contains(luk15Dex1), "(LUK15, DEX1) should be kept");
        assertFalse(keep.contains(pureLuk15),
                "(LUK15, DEX0) should be trash — dominated by naked-wearable (LUK15, DEX1)");
    }

    @Test
    void selectItemsBeatingBaselineUsesConservativeWatkMainStatEquivalence() {
        EnumSet<BotEquipManager.RelevantStat> assassin = BotEquipManager.relevantStatsFor(Job.ASSASSIN);
        Equip tenAtkTwoLuk = glove(/*luk*/ 2, 0, /*watk*/ 10);
        Equip nineAtkFourLuk = glove(/*luk*/ 4, 0, /*watk*/ 9);
        Equip nineAtkTenLuk = glove(/*luk*/ 10, 0, /*watk*/ 9);
        Equip elevenAtkZeroLuk = glove(/*luk*/ 0, 0, /*watk*/ 11);
        Equip tenAtkOneLuk = glove(/*luk*/ 1, 0, /*watk*/ 10);

        Set<Equip> first = BotEquipManager.selectItemsBeatingBaseline(
                assassin, List.of(nineAtkFourLuk), List.of(tenAtkTwoLuk));
        assertFalse(first.contains(nineAtkFourLuk),
                "10 atk + 2 main should dominate 9 atk + 4 main under conservative equivalence");

        Set<Equip> second = BotEquipManager.selectItemsBeatingBaseline(
                assassin, List.of(elevenAtkZeroLuk), List.of(nineAtkTenLuk));
        assertFalse(second.contains(elevenAtkZeroLuk),
                "9 atk + 10 main should dominate 11 atk + 0 main under conservative equivalence");

        Set<Equip> nearMiss = BotEquipManager.selectItemsBeatingBaseline(
                assassin, List.of(nineAtkFourLuk), List.of(tenAtkOneLuk));
        assertTrue(nearMiss.contains(nineAtkFourLuk),
                "equivalence must stay conservative: 10 atk + 1 main should not dominate 9 atk + 4 main");
    }

    @Test
    void relevantStatsForJobMatchesClassRoles() {
        assertEquals(EnumSet.of(BotEquipManager.RelevantStat.LUK, BotEquipManager.RelevantStat.DEX,
                                BotEquipManager.RelevantStat.WATK),
                BotEquipManager.relevantStatsFor(Job.ASSASSIN));
        assertEquals(EnumSet.of(BotEquipManager.RelevantStat.STR, BotEquipManager.RelevantStat.DEX,
                                BotEquipManager.RelevantStat.WATK, BotEquipManager.RelevantStat.ACC),
                BotEquipManager.relevantStatsFor(Job.FIGHTER));
        assertEquals(EnumSet.of(BotEquipManager.RelevantStat.STR, BotEquipManager.RelevantStat.DEX,
                                BotEquipManager.RelevantStat.WATK, BotEquipManager.RelevantStat.ACC),
                BotEquipManager.relevantStatsFor(Job.BRAWLER));
        assertEquals(EnumSet.of(BotEquipManager.RelevantStat.DEX, BotEquipManager.RelevantStat.STR,
                                BotEquipManager.RelevantStat.WATK),
                BotEquipManager.relevantStatsFor(Job.GUNSLINGER));
        assertEquals(EnumSet.of(BotEquipManager.RelevantStat.DEX, BotEquipManager.RelevantStat.STR,
                                BotEquipManager.RelevantStat.WATK),
                BotEquipManager.relevantStatsFor(Job.HUNTER));
        assertEquals(EnumSet.of(BotEquipManager.RelevantStat.INT, BotEquipManager.RelevantStat.LUK,
                                BotEquipManager.RelevantStat.MATK),
                BotEquipManager.relevantStatsFor(Job.CLERIC));
    }

    private static Equip glove(int luk, int dex, int watk) {
        Equip e = mock(Equip.class);
        when(e.getStr()).thenReturn((short) 0);
        when(e.getDex()).thenReturn((short) dex);
        when(e.getInt()).thenReturn((short) 0);
        when(e.getLuk()).thenReturn((short) luk);
        when(e.getWatk()).thenReturn((short) watk);
        when(e.getMatk()).thenReturn((short) 0);
        when(e.getAcc()).thenReturn((short) 0);
        return e;
    }

    @Test
    void pureIntOverallUsefulToMageSiblingButNotToAssassin() {
        // Reproduces Blue-Calas scenario: a mage overall has positive INT (mage-relevant)
        // but zero LUK/DEX/WATK, so it has no relevant stat for an Assassin.
        // This validates the Pareto predicate shared by self-reserve and sibling-reserve.
        Equip pureInt = mageOverall(/*int*/ 7, /*luk*/ 0);

        Set<Equip> usefulToMage = BotEquipManager.selectItemsBeatingBaseline(
                BotEquipManager.relevantStatsFor(Job.MAGICIAN), List.of(pureInt), List.of());
        Set<Equip> usefulToSin = BotEquipManager.selectItemsBeatingBaseline(
                BotEquipManager.relevantStatsFor(Job.ASSASSIN), List.of(pureInt), List.of());

        assertTrue(usefulToMage.contains(pureInt),
                "INT overall beats empty baseline for mage sibling");
        assertFalse(usefulToSin.contains(pureInt),
                "INT-only overall has no LUK/DEX/WATK — not useful to Assassin");
    }

    @Test
    void siblingItemNotUsefulWhenEquippedBaselineDominates() {
        Equip blueCalas    = mageOverall(/*int*/ 7,  /*luk*/ 0);
        Equip betterOverall = mageOverall(/*int*/ 10, /*luk*/ 0);

        Set<Equip> useful = BotEquipManager.selectItemsBeatingBaseline(
                BotEquipManager.relevantStatsFor(Job.MAGICIAN),
                List.of(blueCalas), List.of(betterOverall));

        assertFalse(useful.contains(blueCalas),
                "item should not be reserved when mage sibling already wears a strictly better overall");
    }

    @Test
    void shouldReserveUsefulHunterShoesForSelfFromJohnLog() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.HUNTER);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));

        Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
        Equip wornBoots = mock(Equip.class);
        Equip redPierre = mock(Equip.class);
        when(equipped.list()).thenReturn(List.of(wornBoots));

        when(wornBoots.getItemId()).thenReturn(1072082);
        when(wornBoots.getStr()).thenReturn((short) 0);
        when(wornBoots.getDex()).thenReturn((short) 5);
        when(wornBoots.getInt()).thenReturn((short) 0);
        when(wornBoots.getLuk()).thenReturn((short) 0);
        when(wornBoots.getWatk()).thenReturn((short) 0);
        when(wornBoots.getMatk()).thenReturn((short) 0);
        when(wornBoots.getAcc()).thenReturn((short) 0);

        when(redPierre.getItemId()).thenReturn(1072132);
        when(redPierre.getStr()).thenReturn((short) 8);
        when(redPierre.getDex()).thenReturn((short) 0);
        when(redPierre.getInt()).thenReturn((short) 0);
        when(redPierre.getLuk()).thenReturn((short) 0);
        when(redPierre.getWatk()).thenReturn((short) 0);
        when(redPierre.getMatk()).thenReturn((short) 0);
        when(redPierre.getAcc()).thenReturn((short) 0);

        BotEquipManager.EquipUsefulnessHooks hooks = mock(BotEquipManager.EquipUsefulnessHooks.class);
        when(hooks.isCash(1072082)).thenReturn(false);
        when(hooks.isCash(1072132)).thenReturn(false);
        when(hooks.getEquipmentSlot(1072082)).thenReturn("So");
        when(hooks.getEquipmentSlot(1072132)).thenReturn("So");
        when(hooks.meetsReqs(wornBoots, Job.HUNTER, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 0)).thenReturn(true);
        when(hooks.meetsReqs(redPierre, Job.HUNTER, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 0)).thenReturn(true);
        assertTrue(BotEquipManager.shouldReserveOwnedItem(bot, hooks, redPierre),
                "John's +8 STR Red Pierre Shoes should stay reserved for self ahead of owner/sibling offers");
    }

    @Test
    void bagGloveNotReservedWhenEquippedGloveAlreadyDominatesJohnCase() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.HUNTER);

        Equip equippedBlueWork = equipWithIdStats(1082001, 0, 7, 0);
        Equip bagMithrilScaler = equipWithIdStats(1082047, 0, 2, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.HUNTER, equippedBlueWork, "Gv", 10, 0, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.HUNTER, bagMithrilScaler, "Gv", 35, 4, 0, 115, 0, 0, 0);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(equippedBlueWork, bagMithrilScaler));

        assertTrue(keep.contains(equippedBlueWork), "equipped baseline glove should stay in the reserve set");
        assertFalse(keep.contains(bagMithrilScaler),
                "bag glove should not be reserved when equipped glove already dominates it");
    }

    @Test
    void shouldReserveSwordUpgradeEvenWhenEquippedAxeIsStronger() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        when(bot.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Fighter.SWORD_BOOSTER)).thenReturn(0);
        when(bot.getSkillLevel(Fighter.AXE_MASTERY)).thenReturn(0);
        when(bot.getSkillLevel(Fighter.AXE_BOOSTER)).thenReturn(0);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));

        Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
        Equip equippedAxe = equipWithIdStats(1300001, 12, 0, 0);
        Equip candidateSword = equipWithIdStats(1300002, 8, 0, 0);
        when(equipped.list()).thenReturn(List.of(equippedAxe));

        BotEquipManager.EquipUsefulnessHooks hooks = mock(BotEquipManager.EquipUsefulnessHooks.class);
        when(hooks.isCash(1300001)).thenReturn(false);
        when(hooks.isCash(1300002)).thenReturn(false);
        when(hooks.getEquipmentSlot(1300001)).thenReturn("Wp");
        when(hooks.getEquipmentSlot(1300002)).thenReturn("Wp");
        when(hooks.getWeaponType(1300001)).thenReturn(WeaponType.GENERAL1H_SWING);
        when(hooks.getWeaponType(1300002)).thenReturn(WeaponType.SWORD1H);
        when(hooks.meetsReqs(equippedAxe, Job.FIGHTER, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 0)).thenReturn(true);
        when(hooks.meetsReqs(candidateSword, Job.FIGHTER, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 0)).thenReturn(true);

        assertTrue(BotEquipManager.shouldReserveOwnedItem(bot, hooks, candidateSword),
                "equipped axe should not block a sword-spec fighter from reserving a sword");
    }

    @Test
    void shouldReserveSwordAndAxeWhenBuildHasBothMasteries() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        when(bot.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Fighter.SWORD_BOOSTER)).thenReturn(0);
        when(bot.getSkillLevel(Fighter.AXE_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Fighter.AXE_BOOSTER)).thenReturn(0);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));

        Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
        Equip equippedSword = equipWithIdStats(1400001, 10, 0, 0);
        Equip candidateAxe = equipWithIdStats(1300001, 8, 0, 0);
        when(equipped.list()).thenReturn(List.of(equippedSword));

        BotEquipManager.EquipUsefulnessHooks hooks = mock(BotEquipManager.EquipUsefulnessHooks.class);
        when(hooks.isCash(1400001)).thenReturn(false);
        when(hooks.isCash(1300001)).thenReturn(false);
        when(hooks.getEquipmentSlot(1400001)).thenReturn("Wp");
        when(hooks.getEquipmentSlot(1300001)).thenReturn("Wp");
        when(hooks.getWeaponType(1400001)).thenReturn(WeaponType.SWORD1H);
        when(hooks.getWeaponType(1300001)).thenReturn(WeaponType.GENERAL1H_SWING);
        when(hooks.meetsReqs(equippedSword, Job.FIGHTER, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 0)).thenReturn(true);
        when(hooks.meetsReqs(candidateAxe, Job.FIGHTER, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 0)).thenReturn(true);

        assertTrue(BotEquipManager.shouldReserveOwnedItem(bot, hooks, equippedSword),
                "dual-mastery fighter should keep sword family");
        assertTrue(BotEquipManager.shouldReserveOwnedItem(bot, hooks, candidateAxe),
                "dual-mastery fighter should also keep axe family");
    }

    @Test
    void selfReserveSameReqSameItemIdKeepsOnlyBestIvoryCopies() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.SPEARMAN);

        Equip ivoryTop8 = equipWithIdStats(1040087, 0, 8, 0);
        Equip ivoryTop7 = equipWithIdStats(1040087, 0, 7, 0);
        Equip ivoryTop3 = equipWithIdStats(1040087, 0, 3, 0);
        Equip ivoryPant6 = equipWithIdStats(1060076, 0, 6, 4);
        Equip ivoryPant2 = equipWithIdStats(1060076, 0, 2, 0);
        Equip ivoryPant1 = equipWithIdStats(1060076, 0, 1, 2);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.SPEARMAN, ivoryTop8, "Ma", 50, 1, 180, 0, 0, 0, 20);
        stubReserveItem(hooks, Job.SPEARMAN, ivoryTop7, "Ma", 50, 1, 180, 0, 0, 0, 20);
        stubReserveItem(hooks, Job.SPEARMAN, ivoryTop3, "Ma", 50, 1, 180, 0, 0, 0, 20);
        stubReserveItem(hooks, Job.SPEARMAN, ivoryPant6, "Pn", 50, 1, 180, 0, 0, 0, 20);
        stubReserveItem(hooks, Job.SPEARMAN, ivoryPant2, "Pn", 50, 1, 180, 0, 0, 0, 20);
        stubReserveItem(hooks, Job.SPEARMAN, ivoryPant1, "Pn", 50, 1, 180, 0, 0, 0, 20);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(ivoryTop8, ivoryTop7, ivoryTop3, ivoryPant6, ivoryPant2, ivoryPant1));

        assertTrue(keep.contains(ivoryTop8));
        assertFalse(keep.contains(ivoryTop7));
        assertFalse(keep.contains(ivoryTop3));
        assertTrue(keep.contains(ivoryPant6));
        assertFalse(keep.contains(ivoryPant2));
        assertFalse(keep.contains(ivoryPant1));
    }

    @Test
    void selfReserveSameReqSameRelevantStatsKeepsBestDuplicateByFullStats() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        when(bot.getSkillLevel(Assassin.CLAW_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Assassin.CLAW_BOOSTER)).thenReturn(0);

        Equip bronzeWdef0 = clawWithStats(1472010, 2, 21, 0);
        Equip bronzeWdef1 = clawWithStats(1472010, 2, 21, 1);
        Equip bronzeWdef2 = clawWithStats(1472010, 2, 21, 2);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.ASSASSIN, bronzeWdef0, "Wp", 35, 8, 0, 70, 0, 95, 0);
        stubReserveItem(hooks, Job.ASSASSIN, bronzeWdef1, "Wp", 35, 8, 0, 70, 0, 95, 0);
        stubReserveItem(hooks, Job.ASSASSIN, bronzeWdef2, "Wp", 35, 8, 0, 70, 0, 95, 0);
        when(hooks.getWeaponType(1472010)).thenReturn(WeaponType.CLAW);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(bronzeWdef0, bronzeWdef1, bronzeWdef2));

        assertFalse(keep.contains(bronzeWdef0));
        assertFalse(keep.contains(bronzeWdef1));
        assertTrue(keep.contains(bronzeWdef2),
                "same-id same-req duplicate with equal LUK/WATK should keep only best full-stat copy");
    }

    @Test
    void selfReserveKeepsLowerStatItemWhenSlotsCanBeatFinishedItem() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.MAGICIAN);

        Equip finishedEarring = equipWithSlots(1032000, 8, 0, 0, 0);
        Equip cleanEarring = equipWithSlots(1032000, 0, 0, 0, 5);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.MAGICIAN, finishedEarring, "Ae", 15, 0, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.MAGICIAN, cleanEarring, "Ae", 15, 0, 0, 0, 0, 0, 0);
        when(hooks.maxScrollOffenseGainPerSlot(bot, 1032000)).thenReturn(3.0);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(finishedEarring, cleanEarring));

        assertTrue(keep.contains(finishedEarring));
        assertTrue(keep.contains(cleanEarring),
                "clean earring should survive because its open slots can exceed the finished copy");
    }

    @Test
    void selfReserveFinishedGloveDominatesCleanGloveWhenSlotsCannotCatchUp() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);

        Equip finishedGlove = equipWithSlots(1082000, 0, 0, 15, 0);
        Equip cleanGlove = equipWithSlots(1082000, 0, 0, 0, 5);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.ASSASSIN, finishedGlove, "Gv", 10, 0, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.ASSASSIN, cleanGlove, "Gv", 10, 0, 0, 0, 0, 0, 0);
        when(hooks.maxScrollOffenseGainPerSlot(bot, 1082000)).thenReturn(15.0);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(finishedGlove, cleanGlove));

        assertTrue(keep.contains(finishedGlove));
        assertFalse(keep.contains(cleanGlove),
                "15 ATT finished glove should dominate 0 ATT/5-slot glove because the clean copy cannot exceed it");
    }

    @Test
    void selfReserveKeepsOneDuplicateCleanScrollableItem() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);

        List<Equip> gloves = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Equip glove = equipWithSlots(1082000, 0, 0, 0, 5);
            when(glove.getPosition()).thenReturn((short) i);
            gloves.add(glove);
        }

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        for (Equip glove : gloves) {
            stubReserveItem(hooks, Job.ASSASSIN, glove, "Gv", 10, 0, 0, 0, 0, 0, 0);
        }
        when(hooks.maxScrollOffenseGainPerSlot(bot, 1082000)).thenReturn(15.0);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks, gloves);

        assertEquals(1, keep.size(), "identical clean full-slot duplicates should collapse to one reserve item");
        assertTrue(keep.contains(gloves.get(0)), "lowest bag slot should win the duplicate tiebreak");
    }

    @Test
    void selfReserveTrackCapKeepsOnlyTopThreeByCeiling() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);

        // Five mutually non-dominated gloves (LUK up, DEX down): Pareto keeps all five,
        // the track cap must keep only the three with the highest offense ceiling.
        Equip luk10 = capGlove(1082000, 10, 1, 0);
        Equip luk8 = capGlove(1082000, 8, 2, 0);
        Equip luk6 = capGlove(1082000, 6, 3, 0);
        Equip luk4 = capGlove(1082000, 4, 4, 0);
        Equip luk2 = capGlove(1082000, 2, 5, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        for (Equip e : List.of(luk10, luk8, luk6, luk4, luk2)) {
            stubReserveItem(hooks, Job.ASSASSIN, e, "Gv", 10, 0, 0, 0, 0, 0, 0);
        }

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(luk10, luk8, luk6, luk4, luk2));

        assertEquals(BotEquipManager.SELF_RESERVE_TRACK_CAP, keep.size(),
                "track cap should bound the Pareto front");
        assertTrue(keep.contains(luk10));
        assertTrue(keep.contains(luk8));
        assertTrue(keep.contains(luk6));
        assertFalse(keep.contains(luk4), "lowest-ceiling survivors should be demoted by the cap");
        assertFalse(keep.contains(luk2), "lowest-ceiling survivors should be demoted by the cap");
    }

    @Test
    void selfReserveTrackCapRanksScrollUpsideAboveFlatStats() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);

        // Slotted project glove: luk 3 + 7 open slots * 3.0 gain -> ceiling 24, beats the
        // flat luk-4 glove (ceiling 5.2) even though its current stats are lower.
        Equip slottedProject = capGlove(1082000, 3, 0, 7);
        Equip flatLuk10 = capGlove(1082000, 10, 1, 0);
        Equip flatLuk8 = capGlove(1082000, 8, 2, 0);
        Equip flatLuk4 = capGlove(1082000, 4, 3, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        for (Equip e : List.of(slottedProject, flatLuk10, flatLuk8, flatLuk4)) {
            stubReserveItem(hooks, Job.ASSASSIN, e, "Gv", 10, 0, 0, 0, 0, 0, 0);
        }
        when(hooks.maxScrollOffenseGainPerSlot(bot, 1082000)).thenReturn(3.0);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(slottedProject, flatLuk10, flatLuk8, flatLuk4));

        assertEquals(BotEquipManager.SELF_RESERVE_TRACK_CAP, keep.size());
        assertTrue(keep.contains(slottedProject),
                "scroll upside counts toward the ceiling, so the slotted project should survive the cap");
        assertTrue(keep.contains(flatLuk10));
        assertTrue(keep.contains(flatLuk8));
        assertFalse(keep.contains(flatLuk4),
                "slightly higher current stats should not outrank a high-ceiling scroll project");
    }

    private static Equip capGlove(int itemId, int luk, int dex, int slots) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getStr()).thenReturn((short) 0);
        when(e.getDex()).thenReturn((short) dex);
        when(e.getInt()).thenReturn((short) 0);
        when(e.getLuk()).thenReturn((short) luk);
        when(e.getWatk()).thenReturn((short) 0);
        when(e.getMatk()).thenReturn((short) 0);
        when(e.getAcc()).thenReturn((short) 0);
        when(e.getUpgradeSlots()).thenReturn((byte) slots);
        return e;
    }

    @Test
    void selfReserveCurrentTotalWearableCrossbowDominatesWeakerZeroReqCrossbow() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.CROSSBOWMAN);
        when(bot.getLevel()).thenReturn(64);
        when(bot.getFame()).thenReturn(0);
        when(bot.getTotalStr()).thenReturn(52);
        when(bot.getTotalDex()).thenReturn(250);
        when(bot.getTotalInt()).thenReturn(4);
        when(bot.getTotalLuk()).thenReturn(4);

        Equip strongerCurrentTotalWearable = equipWithSlots(1462001, 0, 0, 86, 0);
        Equip weakerZeroReq = equipWithSlots(1462002, 0, 0, 66, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.CROSSBOWMAN, strongerCurrentTotalWearable, "Wp", 61, 4, 38, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.CROSSBOWMAN, weakerZeroReq, "Wp", 43, 4, 0, 0, 0, 0, 0);
        when(hooks.getWeaponType(1462001)).thenReturn(WeaponType.CROSSBOW);
        when(hooks.getWeaponType(1462002)).thenReturn(WeaponType.CROSSBOW);
        when(hooks.meetsReqs(strongerCurrentTotalWearable, Job.CROSSBOWMAN, 64,
                52, 250, 4, 4, 0)).thenReturn(true);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(strongerCurrentTotalWearable, weakerZeroReq));

        assertTrue(keep.contains(strongerCurrentTotalWearable));
        assertFalse(keep.contains(weakerZeroReq),
                "weaker zero-req crossbow should not be reserved once the stronger higher-req crossbow is wearable with current totals");
    }

    @Test
    void selfReserveSameReqDifferentItemIdDoesDominate() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.SPEARMAN);

        Equip itemA = equipWithIdStats(2000001, 0, 8, 0);
        Equip itemB = equipWithIdStats(2000002, 0, 7, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.SPEARMAN, itemA, "Ma", 50, 1, 180, 0, 0, 0, 20);
        stubReserveItem(hooks, Job.SPEARMAN, itemB, "Ma", 50, 1, 180, 0, 0, 0, 20);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks, List.of(itemA, itemB));

        assertTrue(keep.contains(itemA));
        assertFalse(keep.contains(itemB),
                "items sharing a requirement profile should dominate freely across item ids");
    }

    @Test
    void selfReserveWeightsDexIntoAccForWarriorDominance_redBandanaDominated() {
        // From equiplog-Leroy-2026-05-11T031549.txt: Spearman with Red Bandana#1 (acc=4, dex=0)
        // and Yellow Metal Gear#18 (dex=7, acc=0). Without DEX→ACC weighting, neither dominates
        // (Red has more raw ACC). With 1:1 weighting Yellow's effective acc = 7 > 4 = Red's.
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.SPEARMAN);

        Equip redBandana = equipWithIdStats(1002022, 0, 0, 4);
        Equip yellowMetalGear = equipWithIdStats(1002053, 0, 7, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.SPEARMAN, redBandana, "Cp", 10, 0, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.SPEARMAN, yellowMetalGear, "Cp", 10, 0, 0, 0, 0, 0, 0);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(redBandana, yellowMetalGear));

        assertTrue(keep.contains(yellowMetalGear));
        assertFalse(keep.contains(redBandana),
                "Red Bandana (acc=4) should be dominated by Yellow Metal Gear (dex=7) under DEX→ACC weighting");
    }

    @Test
    void selfReserveWeightsDexIntoAccForWarriorDominance_ivoryPantsDominated() {
        // Two Ivory Shouldermail Pants from the same log: #10 (dex=6, acc=4 → weighted-acc=10)
        // and #21 (dex=8, acc=3 → weighted-acc=11). Same req profile, same item id, but #21
        // strictly dominates #10 on DEX and weighted-ACC.
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.SPEARMAN);

        Equip ivoryPants10 = equipWithIdStats(1060076, 0, 6, 4);
        Equip ivoryPants21 = equipWithIdStats(1060076, 0, 8, 3);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.SPEARMAN, ivoryPants10, "Pn", 50, 1, 180, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.SPEARMAN, ivoryPants21, "Pn", 50, 1, 180, 0, 0, 0, 0);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(ivoryPants10, ivoryPants21));

        assertTrue(keep.contains(ivoryPants21));
        assertFalse(keep.contains(ivoryPants10),
                "Ivory Pants dex=6/acc=4 should be dominated by dex=8/acc=3 under DEX→ACC weighting");
    }

    @Test
    void selfReserveSpearmanWithSpearMasteryRejectsAxeAndPolearm() {
        // Spearman who has only put SP into Spear Mastery (not Polearm Mastery) should not
        // reserve polearms or axes from inventory.
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.SPEARMAN);
        when(bot.getSkillLevel(Spearman.SPEAR_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Spearman.SPEAR_BOOSTER)).thenReturn(0);
        when(bot.getSkillLevel(Spearman.POLEARM_MASTERY)).thenReturn(0);
        when(bot.getSkillLevel(Spearman.POLEARM_BOOSTER)).thenReturn(0);

        Equip spear = equipWithIdStats(1432012, 2, 0, 0);
        Equip polearm = equipWithIdStats(1442012, 4, 0, 0);
        Equip axe = equipWithIdStats(1312012, 6, 0, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.SPEARMAN, spear, "Wp", 43, 1, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.SPEARMAN, polearm, "Wp", 43, 1, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.SPEARMAN, axe, "Wp", 43, 1, 0, 0, 0, 0, 0);
        when(hooks.getWeaponType(1432012)).thenReturn(WeaponType.SPEAR_STAB);
        when(hooks.getWeaponType(1442012)).thenReturn(WeaponType.POLE_ARM_SWING);
        when(hooks.getWeaponType(1312012)).thenReturn(WeaponType.GENERAL2H_SWING);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(spear, polearm, axe));

        assertTrue(keep.contains(spear), "spear-mastery spearman should keep spears");
        assertFalse(keep.contains(polearm),
                "spear-only spearman should not reserve polearms");
        assertFalse(keep.contains(axe),
                "spearman should never reserve axes regardless of mastery");
    }

    @Test
    void selfReserveSpearmanDoesNotReserveMapleDoomSinger() {
        // From equiplog-Leroy-2026-05-11T031549.txt: Maple Doom Singer (2-handed mace) was
        // appearing in the spearman's reserved set. As a non-spear/non-polearm weapon, it must
        // never be reserved regardless of mastery layout.
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.SPEARMAN);
        when(bot.getSkillLevel(Spearman.SPEAR_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Spearman.POLEARM_MASTERY)).thenReturn(1);

        Equip mapleImpaler = equipWithIdStats(1432012, 2, 0, 0);
        Equip mapleDoomSinger = equipWithIdStats(1422014, 4, 0, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.SPEARMAN, mapleImpaler, "Wp", 43, 1, 0, 0, 0, 0, 0);
        // Maple Doom Singer (item 1422014) is a 2-handed weapon — text slot "WpSi", not "Wp".
        // The earlier bug was that isWeaponSlot only matched "Wp", so 2H weapons skipped the
        // mastery filter and stayed in the reserved set.
        stubReserveItem(hooks, Job.SPEARMAN, mapleDoomSinger, "WpSi", 43, 1, 0, 0, 0, 0, 0);
        when(hooks.getWeaponType(1432012)).thenReturn(WeaponType.SPEAR_STAB);
        when(hooks.getWeaponType(1422014)).thenReturn(WeaponType.GENERAL2H_SWING);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(mapleImpaler, mapleDoomSinger));

        assertTrue(keep.contains(mapleImpaler));
        assertFalse(keep.contains(mapleDoomSinger),
                "spearman must not reserve Maple Doom Singer (2-handed mace)");
    }

    @Test
    void selfReserveSpearmanWithBothMasteriesKeepsSpearAndPolearmRejectsAxe() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.SPEARMAN);
        when(bot.getSkillLevel(Spearman.SPEAR_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Spearman.SPEAR_BOOSTER)).thenReturn(0);
        when(bot.getSkillLevel(Spearman.POLEARM_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Spearman.POLEARM_BOOSTER)).thenReturn(0);

        Equip spear = equipWithIdStats(1432012, 2, 0, 0);
        Equip polearm = equipWithIdStats(1442012, 4, 0, 0);
        Equip axe = equipWithIdStats(1312012, 6, 0, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.SPEARMAN, spear, "Wp", 43, 1, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.SPEARMAN, polearm, "Wp", 43, 1, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.SPEARMAN, axe, "Wp", 43, 1, 0, 0, 0, 0, 0);
        when(hooks.getWeaponType(1432012)).thenReturn(WeaponType.SPEAR_STAB);
        when(hooks.getWeaponType(1442012)).thenReturn(WeaponType.POLE_ARM_SWING);
        when(hooks.getWeaponType(1312012)).thenReturn(WeaponType.GENERAL2H_SWING);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(spear, polearm, axe));

        assertTrue(keep.contains(spear));
        assertTrue(keep.contains(polearm));
        assertFalse(keep.contains(axe));
    }

    @Test
    void selfReserveRejectsStrictlyWorseHigherReqBowmanGlove() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.HUNTER);

        Equip currentAllClassGlove = equipWithIdStats(1082000, 0, 8, 0);
        Equip worseBowmanGlove = equipWithIdStats(1082100, 0, 2, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.HUNTER, currentAllClassGlove, "Gv", 10, 0, 0, 0, 0, 0, 0);
        stubReserveItem(hooks, Job.HUNTER, worseBowmanGlove, "Gv", 35, 4, 0, 0, 0, 0, 0);

        Set<Equip> keep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(currentAllClassGlove, worseBowmanGlove));

        assertTrue(keep.contains(currentAllClassGlove));
        assertFalse(keep.contains(worseBowmanGlove),
                "proactive future pool should not keep a strictly worse glove with higher level/job reqs");
    }

    @Test
    void statOnlyBlockedAllowsMissingStatsButRejectsLevelOrFameBlockedItems() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        when(bot.getLevel()).thenReturn(43);
        when(bot.getFame()).thenReturn(1);

        Equip statBlocked = mock(Equip.class);
        Equip levelBlocked = mock(Equip.class);
        BotEquipManager.EquipUsefulnessHooks hooks = mock(BotEquipManager.EquipUsefulnessHooks.class);

        when(hooks.meetsReqs(statBlocked, Job.ASSASSIN, 43,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 1)).thenReturn(true);
        when(hooks.meetsReqs(levelBlocked, Job.ASSASSIN, 43,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 1)).thenReturn(false);

        assertTrue(BotEquipManager.statOnlyBlocked(bot, hooks, statBlocked),
                "immediate optimizer may consider gear that only needs more stats");
        assertFalse(BotEquipManager.statOnlyBlocked(bot, hooks, levelBlocked),
                "immediate optimizer must skip gear blocked by current level/job/fame");
    }

    @Test
    void selfReserveKeepsFutureGearBlockedByCurrentLevelOrFame() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        when(bot.getLevel()).thenReturn(43);
        when(bot.getFame()).thenReturn(1);
        when(bot.getSkillLevel(Assassin.CLAW_MASTERY)).thenReturn(1);
        when(bot.getSkillLevel(Assassin.CLAW_BOOSTER)).thenReturn(0);

        Equip wearable = clawWithStats(1472022, 2, 21, 0);
        Equip levelBlocked = clawWithStats(1472052, 7, 27, 0);
        Equip fameBlocked = clawWithStats(1472053, 4, 28, 0);

        BotEquipManager.SelfReserveHooks hooks = mock(BotEquipManager.SelfReserveHooks.class);
        stubReserveItem(hooks, Job.ASSASSIN, wearable, "Wp", 35, 8, 0, 70, 0, 95, 0);
        stubReserveItem(hooks, Job.ASSASSIN, levelBlocked, "Wp", 50, 8, 0, 90, 0, 140, 0);
        stubReserveItem(hooks, Job.ASSASSIN, fameBlocked, "Wp", 50, 8, 0, 90, 0, 140, 20);
        when(hooks.getWeaponType(1472022)).thenReturn(WeaponType.CLAW);
        when(hooks.getWeaponType(1472052)).thenReturn(WeaponType.CLAW);
        when(hooks.getWeaponType(1472053)).thenReturn(WeaponType.CLAW);
        when(hooks.meetsReqs(wearable, Job.ASSASSIN, 43,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 1)).thenReturn(true);
        when(hooks.meetsReqs(levelBlocked, Job.ASSASSIN, 43,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 1)).thenReturn(false);
        when(hooks.meetsReqs(fameBlocked, Job.ASSASSIN, 43,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 1)).thenReturn(false);
        when(hooks.meetsReqs(levelBlocked, Job.ASSASSIN, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, Short.MAX_VALUE)).thenReturn(true);
        when(hooks.meetsReqs(fameBlocked, Job.ASSASSIN, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, Short.MAX_VALUE)).thenReturn(true);

        Set<Equip> levelKeep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(wearable, levelBlocked));
        Set<Equip> fameKeep = BotEquipManager.selectOwnedItemsForSelfReserve(bot, hooks,
                List.of(wearable, fameBlocked));

        assertTrue(levelKeep.contains(wearable));
        assertTrue(levelKeep.contains(levelBlocked),
                "self-reserve should keep future own-class gear blocked only by current level");
        assertTrue(fameKeep.contains(wearable));
        assertTrue(fameKeep.contains(fameBlocked),
                "self-reserve should keep future own-class gear blocked only by current fame");
    }

    @Test
    void autoEquipTriggerIsThrottledPerBotUnlessForced() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(9_876_543);

        assertTrue(BotEquipManager.shouldRunAutoEquip(bot, 1_000L, false));
        assertFalse(BotEquipManager.shouldRunAutoEquip(bot, 5_000L, false),
                "duplicate mode-command triggers should not rerun the optimizer");
        assertTrue(BotEquipManager.shouldRunAutoEquip(bot, 6_000L, true),
                "explicit autoequip command should bypass the throttle");
        assertFalse(BotEquipManager.shouldRunAutoEquip(bot, 7_000L, false),
                "forced runs still refresh the normal throttle window");
        assertTrue(BotEquipManager.shouldRunAutoEquip(bot, 36_001L, false));
    }

    @Test
    void clawerFullEquipLogDoesNotHitParetoCap() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.ASSASSIN);

        LogEquipFixture f = new LogEquipFixture();
        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();

        Equip mapleKandayo = f.equip(1472032, -11, "Wp", 0, 0, 0, 0, 34, 0, 0, 0, 0, 25, 82, 84, 43, 8, 0, 0, 0, 0, 0);
        Equip blueAvenger = f.equip(1051025, -5, "MaPn", 0, 10, 0, 0, 0, 0, 45, 0, 0, 0, 62, 65, 35, 8, 0, 0, 0, 0, 0);
        Equip squishyShoes = f.equip(1072005, -7, "So", 0, 6, 4, 4, 0, 0, 12, 9, 0, 0, 52, 57, 30, 0, 0, 0, 0, 0, 0);
        Equip purpleWorkGloves = f.equip(1082007, -8, "Gv", 0, 1, 0, 4, 0, 0, 1, 0, 2, 0, 48, 43, 10, 0, 0, 0, 0, 0, 0);
        Equip brownBambooHat = f.equip(1002021, -1, "Cp", 0, 0, 0, 3, 0, 0, 17, 0, 0, 0, 0, 0, 25, 0, 0, 0, 0, 0, 0);
        Equip oldRaggedyCape = f.equip(1102000, -9, "Sr", 0, 0, 0, 0, 0, 0, 0, 0, 0, 12, 41, 45, 25, 0, 0, 0, 0, 0, 0);
        Equip sapphireEarrings = f.equip(1032015, -4, "Ae", 0, 0, 0, 0, 0, 0, 0, 19, 0, 0, 59, 37, 35, 0, 0, 0, 0, 0, 0);
        Equip medal = f.equip(1142000, -49, "Me", 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 38, 35, 0, 0, 0, 0, 0, 0, 0);

        add(bySlot, (short) -11, f.equip(1472052, 18, "Wp", 0, 0, 0, 7, 27, 0, 0, 0, 0, 8, 76, 87, 50, 8, 0, 90, 0, 140, 0));
        add(bySlot, (short) -11, f.equip(1472029, 7, "Wp", 0, 2, 0, 0, 19, 0, 2, 0, 0, 0, 0, 0, 40, 8, 0, 80, 0, 120, 0));
        add(bySlot, (short) -11, f.equip(1472030, 8, "Wp", 0, 1, 0, 0, 24, 0, 4, 0, 0, 0, 0, 0, 40, 8, 0, 80, 0, 120, 0));
        add(bySlot, (short) -11, f.equip(1472031, 10, "Wp", 0, 0, 0, 8, 23, 0, 8, 0, 0, 0, 68, 62, 40, 8, 0, 80, 0, 120, 0));
        add(bySlot, (short) -11, f.equip(1472017, 33, "Wp", 0, 0, 0, 2, 20, 0, 3, 0, 0, 0, 0, 0, 30, 8, 0, 60, 0, 80, 0));
        add(bySlot, (short) -11, f.equip(1472022, 39, "Wp", 0, 0, 0, 2, 21, 0, 2, 0, 0, 0, 0, 0, 35, 8, 0, 70, 0, 95, 0));
        add(bySlot, (short) -11, f.equip(1472018, 19, "Wp", 0, 0, 0, 3, 19, 0, 1, 0, 0, 0, 0, 0, 30, 8, 0, 60, 0, 80, 0));
        add(bySlot, (short) -11, f.equip(1472019, 30, "Wp", 0, 0, 0, 5, 17, 0, 8, 0, 0, 0, 44, 59, 30, 8, 0, 60, 0, 80, 0));
        add(bySlot, (short) -11, f.equip(1472053, 2, "Wp", 0, 0, 0, 4, 28, 0, 0, 0, 0, 5, 82, 56, 50, 8, 0, 90, 0, 140, 0));
        add(bySlot, (short) -11, mapleKandayo);

        add(bySlot, (short) -5, f.equip(1040105, 15, "Ma", 0, 1, 0, 6, 0, 0, 27, 0, 0, 0, 36, 51, 30, 8, 0, 60, 0, 80, 0));
        add(bySlot, (short) -5, f.equip(1040108, 25, "Ma", 0, 3, 0, 6, 0, 0, 46, 0, 0, 0, 62, 83, 50, 8, 0, 90, 0, 140, 0));
        add(bySlot, (short) -5, f.equip(1040106, 20, "Ma", 0, 7, 0, 0, 0, 0, 27, 0, 0, 0, 45, 41, 30, 8, 0, 60, 0, 80, 0));
        add(bySlot, (short) -5, f.equip(1051026, 13, "MaPn", 0, 0, 0, 9, 0, 0, 49, 0, 0, 0, 66, 74, 35, 8, 0, 70, 0, 95, 0));
        add(bySlot, (short) -5, blueAvenger);

        add(bySlot, (short) -8, f.equip(1082074, 17, "Gv", 0, 0, 0, 7, 0, 0, 14, 0, 0, 0, 53, 49, 35, 8, 0, 70, 0, 95, 0));
        add(bySlot, (short) -8, f.equip(1082068, 9, "Gv", 0, 1, 0, 7, 0, 0, 20, 0, 0, 0, 79, 51, 50, 8, 0, 90, 0, 140, 0));
        add(bySlot, (short) -8, f.equip(1082069, 21, "Gv", 0, 4, 0, 6, 0, 0, 19, 0, 0, 0, 70, 61, 50, 8, 0, 90, 0, 140, 0));
        add(bySlot, (short) -8, purpleWorkGloves);

        add(bySlot, (short) -7, f.equip(1072136, 37, "So", 0, 3, 0, 7, 0, 0, 35, 0, 0, 0, 53, 85, 50, 8, 0, 90, 0, 140, 0));
        add(bySlot, (short) -7, f.equip(1072039, 3, "So", 0, 0, 0, 5, 0, 0, 18, 0, 0, 0, 47, 41, 30, 8, 0, 60, 0, 80, 0));
        add(bySlot, (short) -7, f.equip(1072125, 27, "So", 0, 5, 0, 6, 0, 0, 25, 0, 0, 0, 49, 60, 40, 8, 0, 80, 0, 110, 0));
        add(bySlot, (short) -7, squishyShoes);

        add(bySlot, (short) -6, f.equip(1060094, 1, "Pn", 0, 0, 0, 2, 0, 0, 24, 0, 0, 0, 0, 11, 40, 8, 0, 80, 0, 110, 0));
        add(bySlot, (short) -6, f.equip(1060095, 11, "Pn", 0, 6, 0, 0, 0, 0, 31, 0, 0, 0, 74, 61, 40, 8, 0, 80, 0, 110, 0));
        add(bySlot, (short) -6, f.equip(1060107, 38, "Pn", 0, 0, 0, 5, 0, 0, 29, 0, 3, 0, 78, 70, 50, 8, 0, 90, 0, 140, 0));

        add(bySlot, (short) -1, f.equip(1002155, 4, "Cp", 0, 0, 0, 5, 0, 0, 32, 0, 0, 0, 47, 50, 40, 8, 0, 80, 0, 110, 0));
        add(bySlot, (short) -1, f.equip(1002156, 6, "Cp", 0, 6, 0, 0, 0, 0, 30, 0, 0, 0, 38, 59, 35, 8, 0, 70, 0, 95, 0));
        add(bySlot, (short) -1, f.equip(1002157, 14, "Cp", 0, 6, 0, 0, 0, 0, 27, 0, 0, 0, 54, 52, 35, 8, 0, 70, 0, 95, 0));
        add(bySlot, (short) -1, brownBambooHat);

        add(bySlot, (short) -9, oldRaggedyCape);
        add(bySlot, (short) -4, sapphireEarrings);
        add(bySlot, (short) -49, medal);

        List<Short> dpSlots = List.of((short) -1, (short) -4, (short) -5, (short) -6,
                (short) -7, (short) -8, (short) -9, (short) -49);
        Map<Short, Equip> currentBySlot = Map.of(
                (short) -11, mapleKandayo,
                (short) -5, blueAvenger,
                (short) -7, squishyShoes,
                (short) -8, purpleWorkGloves,
                (short) -1, brownBambooHat,
                (short) -9, oldRaggedyCape,
                (short) -4, sapphireEarrings,
                (short) -49, medal);
        BotEquipManager.StatSnapshot naked = new BotEquipManager.StatSnapshot(
                4, 35, 4, 227, 21, 24, 25, 50, 2, Job.ASSASSIN);
        BotEquipManager.MapDamageProfile mob = new BotEquipManager.MapDamageProfile(120, 16, 41);

        long startedAt = System.nanoTime();
        boolean anyCap = false;
        BotEquipManager.DpResult best = null;
        for (Equip weapon : bySlot.get((short) -11)) {
            BotEquipManager.DpResult result = BotEquipManager.solveForWeapon(
                    bot, f.hooks(), naked, weapon, dpSlots, currentBySlot, bySlot, mob);
            if (result == null) continue;
            anyCap |= result.paretoCapHit();
            if (best == null || result.score().damage() > best.score().damage()) {
                best = result;
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        System.out.println("clawer full equip optimizer benchmark: " + elapsedMs
                + " ms, capHit=" + anyCap
                + ", bestDamage=" + (best != null ? best.score().damage() : -1));

        assertFalse(anyCap, "full Clawer log should optimize without hitting the Pareto cap");
    }

    private static Equip matkWeapon(int itemId, int matk) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getMatk()).thenReturn((short) matk);
        return e;
    }

    private static Equip mageOverall(int int_, int luk) {
        Equip e = mock(Equip.class);
        when(e.getStr()).thenReturn((short) 0);
        when(e.getDex()).thenReturn((short) 0);
        when(e.getInt()).thenReturn((short) int_);
        when(e.getLuk()).thenReturn((short) luk);
        when(e.getWatk()).thenReturn((short) 0);
        when(e.getMatk()).thenReturn((short) 0);
        when(e.getAcc()).thenReturn((short) 0);
        return e;
    }

    private static Equip clawWithStats(int itemId, int luk, int watk, int wdef) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getStr()).thenReturn((short) 0);
        when(e.getDex()).thenReturn((short) 0);
        when(e.getInt()).thenReturn((short) 0);
        when(e.getLuk()).thenReturn((short) luk);
        when(e.getWatk()).thenReturn((short) watk);
        when(e.getMatk()).thenReturn((short) 0);
        when(e.getWdef()).thenReturn((short) wdef);
        when(e.getMdef()).thenReturn((short) 0);
        when(e.getAcc()).thenReturn((short) 0);
        when(e.getAvoid()).thenReturn((short) 0);
        when(e.getHp()).thenReturn((short) 0);
        when(e.getMp()).thenReturn((short) 0);
        when(e.getSpeed()).thenReturn((short) 0);
        when(e.getJump()).thenReturn((short) 0);
        return e;
    }

    private static Equip equipWithIdStats(int itemId, int str, int dex, int acc) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getStr()).thenReturn((short) str);
        when(e.getDex()).thenReturn((short) dex);
        when(e.getInt()).thenReturn((short) 0);
        when(e.getLuk()).thenReturn((short) 0);
        when(e.getWatk()).thenReturn((short) 0);
        when(e.getMatk()).thenReturn((short) 0);
        when(e.getAcc()).thenReturn((short) acc);
        return e;
    }

    private static Equip equipWithSlots(int itemId, int int_, int luk, int watk, int slots) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getStr()).thenReturn((short) 0);
        when(e.getDex()).thenReturn((short) 0);
        when(e.getInt()).thenReturn((short) int_);
        when(e.getLuk()).thenReturn((short) luk);
        when(e.getWatk()).thenReturn((short) watk);
        when(e.getMatk()).thenReturn((short) 0);
        when(e.getWdef()).thenReturn((short) 0);
        when(e.getMdef()).thenReturn((short) 0);
        when(e.getAcc()).thenReturn((short) 0);
        when(e.getAvoid()).thenReturn((short) 0);
        when(e.getHp()).thenReturn((short) 0);
        when(e.getMp()).thenReturn((short) 0);
        when(e.getSpeed()).thenReturn((short) 0);
        when(e.getJump()).thenReturn((short) 0);
        when(e.getUpgradeSlots()).thenReturn((byte) slots);
        return e;
    }

    private static void add(Map<Short, List<Equip>> bySlot, short slot, Equip equip) {
        bySlot.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(equip);
    }

    private static final class LogEquipFixture {
        private final Map<Integer, String> slots = new HashMap<>();
        private final Map<Integer, WeaponType> weaponTypes = new HashMap<>();
        private final Set<Integer> overalls = new HashSet<>();
        private final Map<Integer, Map<String, Integer>> reqs = new HashMap<>();

        Equip equip(int itemId, int pos, String slot, int str, int dex, int int_, int luk,
                   int watk, int matk, int wdef, int mdef, int acc, int avoid, int hp, int mp,
                   int reqLevel, int reqJob, int reqStr, int reqDex, int reqInt, int reqLuk, int reqPop) {
            Equip e = mock(Equip.class);
            when(e.getItemId()).thenReturn(itemId);
            when(e.getPosition()).thenReturn((short) pos);
            when(e.getStr()).thenReturn((short) str);
            when(e.getDex()).thenReturn((short) dex);
            when(e.getInt()).thenReturn((short) int_);
            when(e.getLuk()).thenReturn((short) luk);
            when(e.getWatk()).thenReturn((short) watk);
            when(e.getMatk()).thenReturn((short) matk);
            when(e.getWdef()).thenReturn((short) wdef);
            when(e.getMdef()).thenReturn((short) mdef);
            when(e.getAcc()).thenReturn((short) acc);
            when(e.getAvoid()).thenReturn((short) avoid);
            when(e.getHp()).thenReturn((short) hp);
            when(e.getMp()).thenReturn((short) mp);
            slots.put(itemId, slot);
            weaponTypes.put(itemId, "Wp".equals(slot) ? WeaponType.CLAW : WeaponType.NOT_A_WEAPON);
            if ("MaPn".equals(slot)) {
                overalls.add(itemId);
            }
            reqs.put(itemId, Map.of(
                    "reqLevel", reqLevel,
                    "reqJob", reqJob,
                    "reqSTR", reqStr,
                    "reqDEX", reqDex,
                    "reqINT", reqInt,
                    "reqLUK", reqLuk,
                    "reqPOP", reqPop));
            return e;
        }

        BotEquipManager.OptimizerHooks hooks() {
            return new BotEquipManager.OptimizerHooks() {
                @Override public boolean isTwoHanded(int itemId) { return false; }
                @Override public WeaponType getWeaponType(int itemId) { return weaponTypes.getOrDefault(itemId, WeaponType.NOT_A_WEAPON); }
                @Override public boolean isOverall(int itemId) { return overalls.contains(itemId); }
                @Override public boolean meetsReqs(Equip equip, Job job, int level, int str, int dex,
                                                   int int_, int luk, int fame) {
                    Map<String, Integer> r = reqs.get(equip.getItemId());
                    if (r == null) return true;
                    return level >= r.getOrDefault("reqLevel", 0)
                            && reqJobMatches(job, r.getOrDefault("reqJob", 0))
                            && str >= r.getOrDefault("reqSTR", 0)
                            && dex >= r.getOrDefault("reqDEX", 0)
                            && int_ >= r.getOrDefault("reqINT", 0)
                            && luk >= r.getOrDefault("reqLUK", 0)
                            && fame >= r.getOrDefault("reqPOP", 0);
                }
                @Override public Map<String, Integer> getEquipStats(int itemId) {
                    return reqs.getOrDefault(itemId, Map.of());
                }
            };
        }

        private static boolean reqJobMatches(Job job, int reqJob) {
            if (reqJob == 0) return true;
            return job != null && (reqJob & 0x8) != 0 && job.isA(Job.THIEF);
        }
    }

    private static void stubReserveItem(BotEquipManager.SelfReserveHooks hooks, Job job, Equip equip, String slot,
                                        int reqLevel, int reqJob, int reqStr, int reqDex,
                                        int reqInt, int reqLuk, int reqPop) {
        int itemId = equip.getItemId();
        when(hooks.isCash(itemId)).thenReturn(false);
        when(hooks.getEquipmentSlot(itemId)).thenReturn(slot);
        when(hooks.meetsReqs(equip, job, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, Short.MAX_VALUE)).thenReturn(true);
        when(hooks.meetsReqs(equip, job, Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, 0)).thenReturn(true);
        when(hooks.getEquipLevelReq(itemId)).thenReturn(reqLevel);
        when(hooks.getEquipStats(itemId)).thenReturn(java.util.Map.of(
                "reqJob", reqJob,
                "reqSTR", reqStr,
                "reqDEX", reqDex,
                "reqINT", reqInt,
                "reqLUK", reqLuk,
                "reqPOP", reqPop));
    }

    private static Set<Equip> identitySet(Equip... items) {
        Set<Equip> set = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Equip e : items) set.add(e);
        return set;
    }
}
