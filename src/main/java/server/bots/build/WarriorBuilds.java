package server.bots.build;

import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Crusader;
import constants.skills.DarkKnight;
import constants.skills.DragonKnight;
import constants.skills.Fighter;
import constants.skills.Hero;
import constants.skills.Page;
import constants.skills.Paladin;
import constants.skills.Spearman;
import constants.skills.Warrior;
import constants.skills.WhiteKnight;
import java.util.List;

public final class WarriorBuilds {

    private WarriorBuilds() {
    }

    public static List<BuildStep> getBuildOrder(Job job, String variant) {
        return switch (job) {
            case WARRIOR -> warriorBuild();
            case FIGHTER -> fighterBuild();
            case CRUSADER -> crusaderBuild();
            case HERO -> "2h".equals(variant) ? hero2hBuild() : hero1hBuild();
            case PAGE -> pageBuild();
            case WHITEKNIGHT -> whiteKnightBuild();
            case SPEARMAN -> spearmanBuild();
            case DRAGONKNIGHT -> dragonKnightBuild();
            case DARKKNIGHT -> darkKnightBuild();
            case PALADIN -> paladinBuild();
            default -> null;
        };
    }

    private static BuildStep s(int id, int to) {
        return new BuildStep(id, to);
    }

    private static int max(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        return skill != null ? skill.getMaxLevel() : 30;
    }

    private static List<BuildStep> warriorBuild() {
        return List.of(
                s(Warrior.IMPROVED_HPREC, 5),
                s(Warrior.IMPROVED_MAXHP, 10),
                s(Warrior.POWER_STRIKE, 1),
                s(Warrior.SLASH_BLAST, 20),
                s(Warrior.POWER_STRIKE, 20),
                s(Warrior.IMPROVED_HPREC, 16)
        );
    }

    private static List<BuildStep> fighterBuild() {
        return List.of(
                s(Fighter.SWORD_MASTERY, 19),
                s(Fighter.SWORD_BOOSTER, 6),
                s(Fighter.RAGE, 3),
                s(Fighter.POWER_GUARD, 30),
                s(Fighter.RAGE, 30),
                s(Fighter.SWORD_BOOSTER, 20),
                s(Fighter.SWORD_MASTERY, 20)
        );
    }

    private static List<BuildStep> crusaderBuild() {
        return List.of(
                s(Crusader.COMBO, 30),
                s(Crusader.SWORD_COMA, 30),
                s(Crusader.SWORD_PANIC, 30),
                s(Crusader.ARMOR_CRASH, 20),
                s(Crusader.SHOUT, 20),
                s(Crusader.SHIELD_MASTERY, 20),
                s(Crusader.IMPROVING_MPREC, 20)
        );
    }

    private static List<BuildStep> hero1hBuild() {
        return List.of(
                s(Hero.RUSH, 1),
                s(Hero.BRANDISH, 30),
                s(Hero.ADVANCED_COMBO, 30),
                s(Hero.STANCE, 30),
                s(Hero.MAPLE_WARRIOR, 13),
                s(Hero.HEROS_WILL, 1),
                s(Hero.MAPLE_WARRIOR, 19),
                s(Hero.ACHILLES, 30),
                s(Hero.HEROS_WILL, 5),
                s(Hero.GUARDIAN, 30),
                s(Hero.ENRAGE, 30),
                s(Hero.RUSH, 30),
                s(Hero.MAPLE_WARRIOR, 30)
        );
    }

    private static List<BuildStep> hero2hBuild() {
        return List.of(
                s(Hero.RUSH, 1),
                s(Hero.BRANDISH, 1),
                s(Hero.ADVANCED_COMBO, 1),
                s(Hero.BRANDISH, 21),
                s(Hero.ADVANCED_COMBO, 30),
                s(Hero.BRANDISH, 30),
                s(Hero.STANCE, 30),
                s(Hero.MAPLE_WARRIOR, 13),
                s(Hero.HEROS_WILL, 1),
                s(Hero.MAPLE_WARRIOR, 19),
                s(Hero.ACHILLES, 30),
                s(Hero.HEROS_WILL, 5),
                s(Hero.GUARDIAN, 30),
                s(Hero.ENRAGE, 30),
                s(Hero.RUSH, 30),
                s(Hero.MAPLE_WARRIOR, 30)
        );
    }

    private static List<BuildStep> pageBuild() {
        return List.of(
                s(Page.SWORD_MASTERY, 20),
                s(Page.THREATEN, 20),
                s(Page.POWER_GUARD, 30),
                s(Page.SWORD_BOOSTER, 20)
        );
    }

    private static List<BuildStep> whiteKnightBuild() {
        return List.of(
                s(WhiteKnight.CHARGE_BLOW, 30),
                s(WhiteKnight.SWORD_LIT_CHARGE, 30),
                s(WhiteKnight.MAGIC_CRASH, 20),
                s(WhiteKnight.SHIELD_MASTERY, 20),
                s(WhiteKnight.IMPROVING_MP_RECOVERY, 20)
        );
    }

    // https://royals.ms/forum/threads/a-guide-to-dark-knight-2026.230387/
    private static List<BuildStep> spearmanBuild() {
        return List.of(
                s(Spearman.SPEAR_MASTERY, 5),
                s(Spearman.SPEAR_BOOSTER, 2),
                s(Spearman.SPEAR_BOOSTER, 11),
                s(Spearman.SPEAR_MASTERY, max(Spearman.SPEAR_MASTERY)),
                s(Spearman.SPEAR_BOOSTER, max(Spearman.SPEAR_BOOSTER)),
                s(Spearman.IRON_WILL, 3),
                s(Spearman.HYPER_BODY, max(Spearman.HYPER_BODY)),
                s(Spearman.POLEARM_MASTERY, max(Spearman.POLEARM_MASTERY)),
                s(Spearman.POLEARM_BOOSTER, max(Spearman.POLEARM_BOOSTER))
        );
    }

    private static List<BuildStep> dragonKnightBuild() {
        return List.of(
                s(DragonKnight.SPEAR_CRUSHER, 1),
                s(DragonKnight.SPEAR_CRUSHER, max(DragonKnight.SPEAR_CRUSHER)),
                s(DragonKnight.SACRIFICE, 1),
                s(DragonKnight.SACRIFICE, 3),
                s(DragonKnight.DRAGON_ROAR, max(DragonKnight.DRAGON_ROAR)),
                s(DragonKnight.SPEAR_DRAGON_FURY, 1),
                s(DragonKnight.SPEAR_DRAGON_FURY, max(DragonKnight.SPEAR_DRAGON_FURY)),
                s(DragonKnight.ELEMENTAL_RESISTANCE, 1),
                s(DragonKnight.ELEMENTAL_RESISTANCE, max(DragonKnight.ELEMENTAL_RESISTANCE)),
                s(DragonKnight.SACRIFICE, 5),
                s(DragonKnight.SACRIFICE, 15),
                s(DragonKnight.DRAGON_BLOOD, 3),
                s(DragonKnight.POWER_CRASH, 2),
                s(DragonKnight.POWER_CRASH, max(DragonKnight.POWER_CRASH))
        );
    }

    private static List<BuildStep> darkKnightBuild() {
        return List.of(
                s(DarkKnight.RUSH, 1),
                s(DarkKnight.BERSERK, 1),
                s(DarkKnight.BEHOLDER, 1),
                s(DarkKnight.BERSERK, max(DarkKnight.BERSERK)),
                s(DarkKnight.STANCE, 1),
                s(DarkKnight.STANCE, max(DarkKnight.STANCE)),
                s(DarkKnight.MONSTER_MAGNET, 1),
                s(DarkKnight.MONSTER_MAGNET, max(DarkKnight.MONSTER_MAGNET)),
                s(DarkKnight.ACHILLES, 1),
                s(DarkKnight.ACHILLES, max(DarkKnight.ACHILLES)),
                s(DarkKnight.BEHOLDER, 2),
                s(DarkKnight.BEHOLDER, max(DarkKnight.BEHOLDER)),
                s(DarkKnight.MAPLE_WARRIOR, 10),
                s(DarkKnight.RUSH, 5),
                s(DarkKnight.RUSH, max(DarkKnight.RUSH)),
                s(DarkKnight.HEROS_WILL, 5)
        );
    }

    // 4th-job skills are book-gated (canLevelSkill caps each at its master level); max() targets just
    // spend whatever SP the unlocked levels allow, in priority order. Trains SWORD_HOLY_CHARGE (sword)
    // to match the sword White Knight build, keeping the weapon line consistent across advancements.
    // https://www.digitaltq.com/maplestory-page-white-knight-paladin-pre-big-bang-skill-build-guide
    // (cross-checked against Haplopelma's MapleRoyals Paladin guide:
    //  https://royals.ms/forum/threads/comprehensive-paladin-guide-haplopelma.161247/)
    private static List<BuildStep> paladinBuild() {
        return List.of(
                s(Paladin.BLAST, 30),                // main single-target attack
                s(Paladin.SWORD_HOLY_CHARGE, 30),    // element charge (sword; commits sword gate)
                s(Paladin.ACHILLES, max(Paladin.ACHILLES)),          // damage reduction
                s(Paladin.ADVANCED_CHARGE, max(Paladin.ADVANCED_CHARGE)), // keeps charge buff + boosts charged dmg
                s(Paladin.STANCE, max(Paladin.STANCE)),              // knockback resist
                s(Paladin.MAPLE_WARRIOR, 20),
                s(Paladin.GUARDIAN, max(Paladin.GUARDIAN)),
                s(Paladin.HEAVENS_HAMMER, max(Paladin.HEAVENS_HAMMER)), // AoE nuke
                s(Paladin.MONSTER_MAGNET, max(Paladin.MONSTER_MAGNET)), // gathers mobs
                s(Paladin.RUSH, max(Paladin.RUSH)),
                s(Paladin.MAPLE_WARRIOR, max(Paladin.MAPLE_WARRIOR)),
                s(Paladin.HEROS_WILL, max(Paladin.HEROS_WILL))
        );
    }
}
