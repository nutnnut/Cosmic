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
import constants.skills.Spearman;
import constants.skills.Warrior;
import constants.skills.Paladin;
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
            case PALADIN -> paladinBuild();
            case SPEARMAN -> spearmanBuild();
            case DRAGONKNIGHT -> dragonKnightBuild();
            case DARKKNIGHT -> darkKnightBuild();
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
                s(Warrior.ENDURE, 3),
                s(Warrior.IRON_BODY, 3)
        );
    }

    private static List<BuildStep> fighterBuild() {
        return List.of(
                s(Fighter.SWORD_MASTERY, 5),
                s(Fighter.SWORD_BOOSTER, 6),
                s(Fighter.SWORD_MASTERY, 19),
                s(Fighter.SWORD_BOOSTER, 20),
                s(Fighter.SWORD_MASTERY, 20),
                s(Fighter.POWER_GUARD, 30),
                s(Fighter.FINAL_ATTACK_SWORD, 30),
                s(Fighter.RAGE, 20),
                s(Warrior.IRON_BODY, 4)
        );
    }

    private static List<BuildStep> crusaderBuild() {
        return List.of(
                s(Crusader.COMBO, 30),
                s(Crusader.SWORD_PANIC, 30),
                s(Crusader.SWORD_COMA, 30),
                s(Crusader.SHOUT, 30),
                s(Crusader.ARMOR_CRASH, 20),
                s(Crusader.IMPROVING_MPREC, 11)
        );
    }

    private static List<BuildStep> hero1hBuild() {
        return List.of(
                s(Hero.ADVANCED_COMBO, 1),
                s(Hero.RUSH, 1),
                s(Hero.BRANDISH, 30),
                s(Hero.ADVANCED_COMBO, 30),
                s(Hero.STANCE, 30),
                s(Hero.MONSTER_MAGNET, 30),
                s(Hero.HEROS_WILL, 1),
                s(Hero.MAPLE_WARRIOR, 9),
                s(Hero.RUSH, 28),
                s(Hero.HEROS_WILL, 5),
                s(Hero.ACHILLES, 30),
                s(Hero.ENRAGE, 30),
                s(Crusader.IMPROVING_MPREC, max(Crusader.IMPROVING_MPREC))
        );
    }

    private static List<BuildStep> hero2hBuild() {
        return List.of(
                s(Hero.ADVANCED_COMBO, 1),
                s(Hero.RUSH, 1),
                s(Hero.BRANDISH, 30),
                s(Hero.ADVANCED_COMBO, 30),
                s(Hero.STANCE, 30),
                s(Hero.MONSTER_MAGNET, 30),
                s(Hero.HEROS_WILL, 1),
                s(Hero.MAPLE_WARRIOR, 9),
                s(Hero.RUSH, 28),
                s(Hero.HEROS_WILL, 5),
                s(Hero.ACHILLES, 30),
                s(Hero.ENRAGE, 30),
                s(Crusader.IMPROVING_MPREC, max(Crusader.IMPROVING_MPREC))
        );
    }

    private static List<BuildStep> pageBuild() {
        return List.of(
                s(Page.SWORD_MASTERY, 5),
                s(Page.SWORD_BOOSTER, 6),
                s(Page.SWORD_MASTERY, 19),
                s(Page.SWORD_BOOSTER, 20),
                s(Page.SWORD_MASTERY, 20),
                s(Page.POWER_GUARD, 30),
                s(Page.FINAL_ATTACK_SWORD, 30),
                s(Page.THREATEN, 20),
                s(Warrior.IRON_BODY, 4)
        );
    }

    private static List<BuildStep> whiteKnightBuild() {
        return List.of(
                s(WhiteKnight.SWORD_LIT_CHARGE, 30),
                s(WhiteKnight.SWORD_ICE_CHARGE, 30),
                s(WhiteKnight.SWORD_FIRE_CHARGE, 30),
                s(WhiteKnight.MAGIC_CRASH, 20),
                s(WhiteKnight.IMPROVING_MP_RECOVERY, 11),
                s(WhiteKnight.CHARGE_BLOW, 30)
        );
    }

    private static List<BuildStep> paladinBuild() {
        return List.of(
                s(Paladin.RUSH, 1),
                s(Paladin.HEAVENS_HAMMER, 1),
                s(Paladin.ADVANCED_CHARGE, 10),
                s(Paladin.BLAST, 30),
                s(Paladin.STANCE, 30),
                s(Paladin.MONSTER_MAGNET, 30),
                s(Paladin.HEROS_WILL, 1),
                s(Paladin.HEAVENS_HAMMER, 30),
                s(Paladin.MAPLE_WARRIOR, 9),
                s(Paladin.SWORD_HOLY_CHARGE, 30),
                s(Paladin.RUSH, 28),
                s(Paladin.HEROS_WILL, 5),
                s(Paladin.ACHILLES, 30),
                s(WhiteKnight.IMPROVING_MP_RECOVERY, max(WhiteKnight.IMPROVING_MP_RECOVERY))
        );
    }

    // https://royals.ms/forum/threads/a-guide-to-dark-knight-2026.230387/
    private static List<BuildStep> spearmanBuild() {
        return List.of(
                s(Spearman.SPEAR_MASTERY, 5),
                s(Spearman.SPEAR_BOOSTER, 6),
                s(Spearman.SPEAR_MASTERY, 19),
                s(Spearman.SPEAR_BOOSTER, 20),
                s(Spearman.SPEAR_MASTERY, 20),
                s(Spearman.IRON_WILL, 3),
                s(Spearman.HYPER_BODY, 30),
                s(Spearman.POLEARM_MASTERY, 20),
                s(Spearman.POLEARM_BOOSTER, 20),
                s(Spearman.IRON_WILL, 11)
        );
    }

    private static List<BuildStep> dragonKnightBuild() {
        return List.of(
                s(DragonKnight.SPEAR_DRAGON_FURY, 1),
                s(DragonKnight.SPEAR_CRUSHER, 15),
                s(DragonKnight.SPEAR_CRUSHER, 30),
                s(DragonKnight.SACRIFICE, 3),
                s(DragonKnight.DRAGON_ROAR, 30),
                s(DragonKnight.SPEAR_DRAGON_FURY, 30),
                s(DragonKnight.ELEMENTAL_RESISTANCE, 20),
                s(DragonKnight.DRAGON_BLOOD, 3),
                s(DragonKnight.POWER_CRASH, 20),
                s(DragonKnight.SACRIFICE, 18)
        );
    }

    private static List<BuildStep> darkKnightBuild() {
        return List.of(
                s(DarkKnight.RUSH, 1),
                s(DarkKnight.BEHOLDER, 1),
                s(DarkKnight.BERSERK, 30),
                s(DarkKnight.STANCE, 30),
                s(DarkKnight.BEHOLDER, 10),
                s(DarkKnight.MONSTER_MAGNET, 30),
                s(DarkKnight.ACHILLES, 30),
                s(DarkKnight.HEROS_WILL, 1),
                s(DarkKnight.MAPLE_WARRIOR, 9),
                s(DarkKnight.HEX_OF_BEHOLDER, 21),
                s(DarkKnight.RUSH, 28),
                s(DarkKnight.HEROS_WILL, 5),
                s(DarkKnight.AURA_OF_BEHOLDER, 30),
                s(DarkKnight.HEX_OF_BEHOLDER, max(DarkKnight.HEX_OF_BEHOLDER))
        );
    }
}
