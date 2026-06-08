package server.bots.build;

import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.ChiefBandit;
import constants.skills.Hermit;
import constants.skills.NightLord;
import constants.skills.Rogue;
import constants.skills.Shadower;
import java.util.List;

public final class ThiefBuilds {

    private ThiefBuilds() {
    }

    /**
     * @param variant weapon-aware variant for the shared 1st-job (Rogue) build: "dagger" → Double
     *                Stab (bandit line), anything else → Lucky Seven (claw/assassin line).
     */
    public static List<BuildStep> getBuildOrder(Job job, String variant) {
        return switch (job) {
            case THIEF -> "dagger".equals(variant) ? thiefDaggerBuild() : thiefBuild();
            case ASSASSIN -> assassinBuild();
            case HERMIT -> hermitBuild();
            case NIGHTLORD -> nightLordBuild();
            case BANDIT -> banditBuild();
            case CHIEFBANDIT -> chiefBanditBuild();
            case SHADOWER -> shadowerBuild();
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

    private static List<BuildStep> thiefBuild() {
        return List.of(
                s(Rogue.LUCKY_SEVEN, 1),
                s(Rogue.NIMBLE_BODY, 3),
                s(Rogue.KEEN_EYES, 8),
                s(Rogue.LUCKY_SEVEN, 20),
                s(Rogue.DISORDER, 3),
                s(Rogue.DARK_SIGHT, 20),
                s(Rogue.NIMBLE_BODY, 10)
        );
    }

    // Dagger (bandit) 1st-job: Double Stab instead of the claw's Lucky Seven.
    private static List<BuildStep> thiefDaggerBuild() {
        return List.of(
                s(Rogue.DOUBLE_STAB, 10),
                s(Rogue.DISORDER, 3),
                s(Rogue.DARK_SIGHT, 20),
                s(Rogue.NIMBLE_BODY, 20),
                s(Rogue.KEEN_EYES, 8)
        );
    }

    private static List<BuildStep> banditBuild() {
        return List.of(
                s(Bandit.DAGGER_MASTERY, 19),
                s(Bandit.DAGGER_BOOSTER, 6),
                s(Bandit.SAVAGE_BLOW, 30),
                s(Bandit.HASTE, 6),
                s(Bandit.DAGGER_BOOSTER, 20),
                s(Bandit.HASTE, 20),
                s(Bandit.ENDURE, 20),
                s(Bandit.STEAL, 11),
                s(Bandit.DAGGER_MASTERY, 20)
        );
    }

    private static List<BuildStep> chiefBanditBuild() {
        return List.of(
                s(ChiefBandit.MESO_EXPLOSION, 1),
                s(ChiefBandit.ASSAULTER, 1),
                s(ChiefBandit.CHAKRA, 3),
                s(ChiefBandit.MESO_GUARD, 1),
                s(ChiefBandit.MESO_EXPLOSION, 30),
                s(ChiefBandit.BAND_OF_THIEVES, 30),
                s(ChiefBandit.ASSAULTER, 30),
                s(ChiefBandit.MESO_GUARD, 20),
                s(Bandit.STEAL, 30),
                s(ChiefBandit.CHAKRA, 21),
                s(ChiefBandit.PICKPOCKET, 1)
        );
    }

    private static List<BuildStep> shadowerBuild() {
        return List.of(
                s(Shadower.BOOMERANG_STEP, 1),
                s(Shadower.SMOKE_SCREEN, 1),
                s(Shadower.ASSASSINATE, 30),
                s(Shadower.BOOMERANG_STEP, 30),
                s(Shadower.SMOKE_SCREEN, 30),
                s(Shadower.SHADOW_SHIFTER, 20),
                s(Shadower.HEROS_WILL, 5),
                s(Shadower.MAPLE_WARRIOR, 9),
                s(Shadower.TAUNT, 30),
                s(Shadower.VENOMOUS_STAB, 30),
                s(ChiefBandit.CHAKRA, 30),
                s(Rogue.DISORDER, max(Rogue.DISORDER))
        );
    }

    private static List<BuildStep> assassinBuild() {
        return List.of(
                s(Assassin.CLAW_MASTERY, 3),
                s(Assassin.CRITICAL_THROW, 30),
                s(Assassin.CLAW_MASTERY, 5),
                s(Assassin.CLAW_BOOSTER, 6),
                s(Assassin.HASTE, 6),
                s(Assassin.CLAW_BOOSTER, 20),
                s(Assassin.HASTE, 20),
                s(Assassin.ENDURE, 3),
                s(Assassin.DRAIN, 28),
                s(Assassin.CLAW_MASTERY, 20)
        );
    }

    private static List<BuildStep> hermitBuild() {
        return List.of(
                s(Hermit.AVENGER, 1),
                s(Hermit.SHADOW_PARTNER, 30),
                s(Hermit.AVENGER, 5),
                s(Hermit.AVENGER, 30),
                s(Hermit.SHADOW_WEB, 20),
                s(Hermit.ALCHEMIST, 20),
                s(Assassin.DRAIN, 30),
                s(Rogue.NIMBLE_BODY, 20),
                s(Hermit.MESO_UP, 19)
        );
    }

    private static List<BuildStep> nightLordBuild() {
        return List.of(
                s(NightLord.TRIPLE_THROW, 1),
                s(NightLord.SHADOW_STARS, 1),
                s(NightLord.TAUNT, 1),
                s(NightLord.TRIPLE_THROW, 30),
                s(NightLord.MAPLE_WARRIOR, 9),
                s(NightLord.SHADOW_SHIFTER, 30),
                s(NightLord.SHADOW_STARS, 30),
                s(NightLord.HEROS_WILL, 5),
                s(Assassin.ENDURE, 20),
                s(NightLord.NINJA_STORM, 30),
                s(NightLord.TAUNT, 30),
                s(Hermit.MESO_UP, 20),
                s(NightLord.VENOMOUS_STAR, 30),
                s(Assassin.ENDURE, max(Assassin.ENDURE))
        );
    }
}
