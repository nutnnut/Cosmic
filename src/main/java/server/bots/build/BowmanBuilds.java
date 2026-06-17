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
                s(Archer.DOUBLE_SHOT, 20),
                s(Archer.CRITICAL_SHOT, 20),
                s(Archer.FOCUS, 9)
        );
    }

//    https://forum.maplelegends.com/index.php?threads/comprehensive-guide-from-archer-to-bowmaster.2749/
    private static List<BuildStep> hunterBuild() {
        return List.of(
                s(Hunter.ARROW_BOMB, 1),
                s(Hunter.BOW_MASTERY, 19),
                s(Hunter.BOW_BOOSTER, 6),
                s(Hunter.SOUL_ARROW, 2),
                s(Hunter.ARROW_BOMB, 3),
                s(Hunter.POWER_KNOCKBACK, 1),
                s(Hunter.ARROW_BOMB, 30),
                s(Hunter.POWER_KNOCKBACK, 20),
                s(Hunter.BOW_BOOSTER, 7),
                s(Hunter.SOUL_ARROW, 3),
                s(Hunter.BOW_BOOSTER, 12),
                s(Hunter.SOUL_ARROW, 4),
                s(Hunter.BOW_BOOSTER, 14),
                s(Hunter.SOUL_ARROW, 5),
                s(Hunter.BOW_BOOSTER, 16),
                s(Hunter.SOUL_ARROW, 6),
                s(Hunter.BOW_BOOSTER, 20),
                s(Hunter.SOUL_ARROW, 7),
                s(Archer.FOCUS, 10),
                s(Archer.FOCUS, 20),
                s(Hunter.BOW_MASTERY, 20),
                s(Hunter.SOUL_ARROW, 20)
        );
    }

    private static List<BuildStep> rangerBuild() {
        return List.of(
                s(Ranger.STRAFE, 1),
                s(Ranger.MORTAL_BLOW, 5),
                s(Ranger.ARROW_RAIN, 21),
                s(Ranger.STRAFE, 20),
                s(Ranger.ARROW_RAIN, 28),
                s(Ranger.INFERNO, 1),
                s(Ranger.STRAFE, 21),
                s(Ranger.STRAFE, 30),
                s(Ranger.ARROW_RAIN, 29),
                s(Ranger.PUPPET, 2),
                s(Ranger.ARROW_RAIN, 30),
                s(Ranger.INFERNO, 3),
                s(Ranger.INFERNO, 16),
                s(Ranger.PUPPET, 4),
                s(Ranger.PUPPET, 5),
                s(Ranger.SILVER_HAWK, 2),
                s(Ranger.SILVER_HAWK, 29),
                s(Ranger.THRUST, 6),
                s(Ranger.PUPPET, 8),
                s(Ranger.PUPPET, 20),
                s(Ranger.MORTAL_BLOW, 20)
        );
    }

//    https://royals.ms/forum/threads/a-comprehensive-guide-to-marksman.89785/
    private static List<BuildStep> crossbowmanBuild() {
        return List.of(
                s(Crossbowman.CROSSBOW_MASTERY, 5),
                s(Crossbowman.CROSSBOW_BOOSTER, 6),
                s(Crossbowman.CROSSBOW_MASTERY, 20),
                s(Crossbowman.IRON_ARROW, 30),
                s(Crossbowman.SOUL_ARROW, 20),
                s(Crossbowman.CROSSBOW_BOOSTER, 20),
                s(Crossbowman.POWER_KNOCKBACK, 20),
                // The 2nd-job core above is 110 SP, but a crossbowman earns ~121 by lv70. Spend the
                // surplus on a 1st-job skill HERE (while it's earned as a 2nd job) instead of letting
                // it bank and get dumped into 3rd-job skills at advancement — a real player can't spend
                // 2nd-job-era SP on 3rd-job skills, so neither should a bot (play legally, rule #1).
                s(Archer.FOCUS, max(Archer.FOCUS))
        );
    }

    private static List<BuildStep> sniperBuild() {
        return List.of(
                s(Sniper.PUPPET, 5),
                s(Sniper.GOLDEN_EAGLE, 1),
                s(Sniper.STRAFE, 30),
                s(Sniper.ARROW_ERUPTION, 30),
                s(Sniper.BLIZZARD, 30),
                s(Sniper.GOLDEN_EAGLE, 15),
                s(Sniper.PUPPET, 20),
                s(Sniper.MORTAL_BLOW, max(Sniper.MORTAL_BLOW)),
                s(Sniper.THRUST, max(Sniper.THRUST))
        );
    }

    private static List<BuildStep> bowmasterBuild() {
        return List.of(
                s(Bowmaster.HURRICANE, 1),
                s(Bowmaster.SHARP_EYES, 7),
                s(Bowmaster.BOW_EXPERT, 1),
                s(Bowmaster.SHARP_EYES, 9),
                s(Bowmaster.DRAGONS_BREATH, 1),
                s(Bowmaster.SHARP_EYES, 11),
                s(Bowmaster.PHOENIX, 1),
                s(Bowmaster.SHARP_EYES, 29),
                s(Bowmaster.HURRICANE, 21),
                s(Bowmaster.BOW_EXPERT, 27),
                s(Bowmaster.MAPLE_WARRIOR, 7),
                s(Bowmaster.HURRICANE, 23),
                s(Bowmaster.MAPLE_WARRIOR, 9),
                s(Bowmaster.HURRICANE, 30),
                s(Bowmaster.MAPLE_WARRIOR, 19),
                s(Bowmaster.SHARP_EYES, 30),
                s(Bowmaster.BOW_EXPERT, 30),
                s(Bowmaster.HEROS_WILL, 5),
                s(Bowmaster.CONCENTRATE, 30),
                s(Bowmaster.PHOENIX, max(Bowmaster.PHOENIX)),
                s(Bowmaster.HAMSTRING, 15),
                s(Bowmaster.DRAGONS_BREATH, 21),
                s(Bowmaster.HAMSTRING, max(Bowmaster.HAMSTRING)),
                s(Bowmaster.MAPLE_WARRIOR, max(Bowmaster.MAPLE_WARRIOR))
        );
    }

    private static List<BuildStep> marksmanBuild() {
        return List.of(
                s(Marksman.DRAGONS_BREATH, 1),
                s(Marksman.FROST_PREY, 1),
                s(Marksman.PIERCING_ARROW, 1),
                s(Marksman.SNIPE, 1),
                s(Marksman.SHARP_EYES, 6),
                s(Marksman.MARKSMAN_BOOST, 6),
                s(Marksman.SHARP_EYES, 10),
                s(Marksman.MARKSMAN_BOOST, 10),
                s(Marksman.SHARP_EYES, 20),
                s(Marksman.MARKSMAN_BOOST, 20),
                s(Marksman.SHARP_EYES, 30),
                s(Marksman.MARKSMAN_BOOST, 30),
                s(Marksman.PIERCING_ARROW, 30),
                s(Marksman.MAPLE_WARRIOR, 30),
                s(Marksman.SNIPE, max(Marksman.SNIPE)),
                s(Marksman.FROST_PREY, max(Marksman.FROST_PREY)),
                s(Marksman.DRAGONS_BREATH, max(Marksman.DRAGONS_BREATH)),
                s(Marksman.BLIND, max(Marksman.BLIND)),
                s(Marksman.HEROS_WILL, 5)
                );
    }
}
