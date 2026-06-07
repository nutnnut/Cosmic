package server.bots.build;

import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Archer;
import constants.skills.Bowmaster;
import constants.skills.Crossbowman;
import constants.skills.Hunter;
import constants.skills.Marksman;
import constants.skills.Ranger;
import constants.skills.Sniper;
import java.util.List;

public final class BowmanBuilds {

    private BowmanBuilds() {
    }

    public static List<BuildStep> getBuildOrder(Job job) {
        return switch (job) {
            case BOWMAN -> bowmanBuild();
            case HUNTER -> hunterBuild();
            case RANGER -> rangerBuild();
            case BOWMASTER -> bowmasterBuild();
            case CROSSBOWMAN -> crossbowmanBuild();
            case SNIPER -> sniperBuild();
            case MARKSMAN -> marksmanBuild();
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

    private static List<BuildStep> bowmanBuild() {
        return List.of(
                s(Archer.ARROW_BLOW, 1),
                s(Archer.DOUBLE_SHOT, 1),
                s(Archer.BLESSING_OF_AMAZON, 3),
                s(Archer.EYE_OF_AMAZON, 8),
                s(Archer.CRITICAL_SHOT, 20),
                s(Archer.DOUBLE_SHOT, 20),
                s(Archer.FOCUS, 9)
        );
    }

//    https://forum.maplelegends.com/index.php?threads/comprehensive-guide-from-archer-to-bowmaster.2749/
    private static List<BuildStep> hunterBuild() {
        return List.of(
                s(Hunter.ARROW_BOMB, 1),
                s(Hunter.BOW_MASTERY, 5),
                s(Hunter.BOW_BOOSTER, 6),
                s(Hunter.BOW_MASTERY, 19),
                s(Hunter.ARROW_BOMB, 30),
                s(Hunter.BOW_BOOSTER, 20),
                s(Hunter.POWER_KNOCKBACK, 20),
                s(Hunter.SOUL_ARROW, 7),
                s(Archer.FOCUS, 20),
                s(Hunter.BOW_MASTERY, 20),
                s(Hunter.SOUL_ARROW, 20)
        );
    }

    private static List<BuildStep> rangerBuild() {
        return List.of(
                s(Ranger.STRAFE, 1),
                s(Ranger.MORTAL_BLOW, 5),
                s(Ranger.ARROW_RAIN, 30),
                s(Ranger.STRAFE, 30),
                s(Ranger.PUPPET, 6),
                s(Ranger.INFERNO, 30),
                s(Ranger.PUPPET, 20),
                s(Ranger.THRUST, 7),
                s(Ranger.SILVER_HAWK, 29)
        );
    }

//    https://royals.ms/forum/threads/a-comprehensive-guide-to-marksman.89785/
    private static List<BuildStep> crossbowmanBuild() {
        return List.of(
                s(Crossbowman.IRON_ARROW, 1),
                s(Crossbowman.CROSSBOW_MASTERY, 5),
                s(Crossbowman.CROSSBOW_BOOSTER, 6),
                s(Crossbowman.CROSSBOW_MASTERY, 19),
                s(Crossbowman.IRON_ARROW, 30),
                s(Crossbowman.CROSSBOW_BOOSTER, 20),
                s(Crossbowman.POWER_KNOCKBACK, 20),
                s(Crossbowman.SOUL_ARROW, 7),
                s(Archer.FOCUS, 20),
                s(Crossbowman.CROSSBOW_MASTERY, 20),
                s(Crossbowman.SOUL_ARROW, 20)
        );
    }

    private static List<BuildStep> sniperBuild() {
        return List.of(
                s(Sniper.STRAFE, 1),
                s(Sniper.BLIZZARD, 1),
                s(Sniper.MORTAL_BLOW, 5),
                s(Sniper.ARROW_ERUPTION, 30),
                s(Sniper.STRAFE, 30),
                s(Sniper.PUPPET, 6),
                s(Sniper.BLIZZARD, 21),
                s(Sniper.PUPPET, 20),
                s(Sniper.THRUST, 20),
                s(Sniper.BLIZZARD, 30),
                s(Sniper.GOLDEN_EAGLE, 15),
                s(Sniper.MORTAL_BLOW, 6)
        );
    }

    private static List<BuildStep> bowmasterBuild() {
        return List.of(
                s(Bowmaster.HURRICANE, 1),
                s(Bowmaster.BOW_EXPERT, 1),
                s(Bowmaster.DRAGONS_BREATH, 1),
                s(Bowmaster.SHARP_EYES, 30),
                s(Bowmaster.HURRICANE, 30),
                s(Bowmaster.PHOENIX, 1),
                s(Bowmaster.BOW_EXPERT, 26),
                s(Bowmaster.MAPLE_WARRIOR, 9),
                s(Bowmaster.HEROS_WILL, 1),
                s(Bowmaster.BOW_EXPERT, 30),
                s(Bowmaster.HAMSTRING, 30),
                s(Bowmaster.PHOENIX, 30),
                s(Bowmaster.HEROS_WILL, 5),
                s(Bowmaster.DRAGONS_BREATH, 21),
                s(Bowmaster.CONCENTRATE, 30),
                s(Ranger.MORTAL_BLOW, max(Ranger.MORTAL_BLOW))
        );
    }

    private static List<BuildStep> marksmanBuild() {
        return List.of(
                s(Marksman.SNIPE, 1),
                s(Marksman.FROST_PREY, 1),
                s(Marksman.MARKSMAN_BOOST, 1),
                s(Marksman.SHARP_EYES, 30),
                s(Marksman.DRAGONS_BREATH, 1),
                s(Marksman.SNIPE, 30),
                s(Marksman.MARKSMAN_BOOST, 30),
                s(Marksman.HEROS_WILL, 1),
                s(Marksman.PIERCING_ARROW, 30),
                s(Marksman.MAPLE_WARRIOR, 9),
                s(Marksman.BLIND, 30),
                s(Marksman.FROST_PREY, 30),
                s(Marksman.HEROS_WILL, 5),
                s(Marksman.DRAGONS_BREATH, 21),
                s(Sniper.MORTAL_BLOW, max(Sniper.MORTAL_BLOW))
                );
    }
}
