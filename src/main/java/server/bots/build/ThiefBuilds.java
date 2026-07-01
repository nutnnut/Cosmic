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

/**
 * Thief SP builds. The class splits at 1st job into a CLAW path (Lucky Seven -> Assassin/Hermit/
 * NightLord) and a DAGGER path (Double Stab -> Bandit/ChiefBandit/Shadower). The variant is chosen
 * at Rogue advancement and drives the weapon gate ({@code BotEquipManager.isWeaponCompatible} keys
 * off the trained 1st-job attack skill: Lucky Seven -> claw-only, Double Stab -> dagger-only).
 *
 * <p>The dagger path exists primarily so low-level bots can avoid the throwing-star ammo/meso trap
 * (daggers need no ammo) and so dagger thieves actually spend their SP (previously Bandit+ had no
 * build and banked SP forever).
 *
 * <p>Dagger build orders adapted from the MapleRoyals Shadower guide by Donn1e (v83 skill data
 * matches this fork): https://royals.ms/forum/threads/a-guide-to-shadower-2026.252048/
 * Two deliberate bot deviations from the human guide are commented inline.
 */
public final class ThiefBuilds {

    private ThiefBuilds() {
    }

    public static List<BuildStep> getBuildOrder(Job job, String variant) {
        boolean dagger = "dagger".equals(variant);
        return switch (job) {
            case THIEF -> dagger ? daggerRogueBuild() : clawRogueBuild();
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

    // ---- CLAW path (Lucky Seven -> Assassin) ----

    private static List<BuildStep> clawRogueBuild() {
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

    private static List<BuildStep> assassinBuild() {
        return List.of(
                s(Assassin.CLAW_MASTERY, 1),
                s(Assassin.CLAW_MASTERY, 3),
                s(Assassin.CRITICAL_THROW, 30),
                s(Assassin.CLAW_MASTERY, 5),
                s(Assassin.CLAW_BOOSTER, 2),
                s(Assassin.CLAW_BOOSTER, 6),
                s(Assassin.HASTE, 20),
                s(Assassin.CLAW_MASTERY, 20),
                s(Assassin.CLAW_BOOSTER, 20),
                s(Rogue.NIMBLE_BODY, 20),
                s(Assassin.ENDURE, 20),
                s(Assassin.DRAIN, 1)
        );
    }

    private static List<BuildStep> hermitBuild() {
        return List.of(
                s(Hermit.AVENGER, 1),
                s(Hermit.SHADOW_PARTNER, 30),
                s(Hermit.AVENGER, 5),
                s(Hermit.FLASH_JUMP, 20),
                s(Hermit.AVENGER, 30),
                s(Hermit.ALCHEMIST, 20),
                s(Hermit.SHADOW_WEB, 20),
                s(Hermit.MESO_UP, 20),
                s(Hermit.SHADOW_MESO, 1),
                s(Assassin.DRAIN, 11)
        );
    }

    private static List<BuildStep> nightLordBuild() {
        return List.of(
                s(NightLord.SHADOW_STARS, 1),
                s(NightLord.TRIPLE_THROW, 30),
                s(NightLord.MAPLE_WARRIOR, 10),
                s(NightLord.SHADOW_SHIFTER, 30),
                s(NightLord.SHADOW_STARS, 30),
                s(NightLord.HEROS_WILL, 5),
                s(NightLord.NINJA_STORM, 30),
                s(NightLord.VENOMOUS_STAR, 30),
                s(NightLord.TAUNT, 30),
                s(NightLord.MAPLE_WARRIOR, 20),
                s(NightLord.NINJA_AMBUSH, max(NightLord.NINJA_AMBUSH)),
                s(NightLord.MAPLE_WARRIOR, max(NightLord.MAPLE_WARRIOR))
        );
    }

    // ---- DAGGER path (Double Stab -> Bandit -> ChiefBandit -> Shadower) ----

    /** Rogue, dagger variant: Double Stab as the main attack, then the shared passives + Dark Sight.
     *  Deviates from the guide by NOT putting 1 SP in Lucky Seven (the guide's "in case of LPQ"
     *  point): any Lucky Seven level flips the equip weapon gate to claw-only, which would strand a
     *  dagger build. The single leftover SP is harmless if banked. */
    private static List<BuildStep> daggerRogueBuild() {
        return List.of(
                s(Rogue.DOUBLE_STAB, 9),
                s(Rogue.NIMBLE_BODY, 20),
                s(Rogue.DISORDER, 3),   // Disorder 3 unlocks Dark Sight
                s(Rogue.DARK_SIGHT, 20),
                s(Rogue.KEEN_EYES, 8)
        );
    }

    private static List<BuildStep> banditBuild() {
        return List.of(
                s(Bandit.DAGGER_MASTERY, 5),
                s(Bandit.DAGGER_BOOSTER, 11),
                s(Bandit.SAVAGE_BLOW, 30),
                s(Bandit.HASTE, 20),
                s(Bandit.DAGGER_MASTERY, 20),
                s(Bandit.DAGGER_BOOSTER, 20),
                s(Bandit.STEAL, 30),
                s(Bandit.ENDURE, 1)
        );
    }

    /** ChiefBandit. Bot deviation: the human guide maxes Meso Explosion first for meso-bomb power
     *  leveling, but a bot cannot meso-bomb (it requires dropping + detonating meso on the ground),
     *  so Band of Thieves (the AoE mob skill) is prioritized and the leftover SP that the guide
     *  spends on Meso Explosion goes into Pickpocket (passive bonus meso, no wasted attack casts). */
    private static List<BuildStep> chiefBanditBuild() {
        return List.of(
                s(ChiefBandit.CHAKRA, 3),        // Chakra 3 unlocks Meso Guard
                s(ChiefBandit.MESO_GUARD, 1),    // 50% damage cut even at level 1 - cheap early survival
                s(ChiefBandit.BAND_OF_THIEVES, 30),
                s(ChiefBandit.ASSAULTER, 30),
                s(ChiefBandit.MESO_GUARD, 20),
                s(ChiefBandit.CHAKRA, 30),
                s(ChiefBandit.PICKPOCKET, 20),   // absorbs the SP the guide puts in Meso Explosion
                s(Bandit.ENDURE, 12),            // Endure carries over from 2nd job (no ChiefBandit Endure)
                s(ChiefBandit.SHIELD_MASTERY, 20)
        );
    }

    /** Shadower. Bot deviation: Boomerang Step (main AoE mob skill) is maxed before Assassinate
     *  (single-target) because a grinding bot benefits far more from AoE than the guide's
     *  boss-first single-target order. Remaining skills follow the guide. */
    private static List<BuildStep> shadowerBuild() {
        return List.of(
                s(Shadower.BOOMERANG_STEP, 30),
                s(Shadower.ASSASSINATE, 30),     // requires Dark Sight (trained in Rogue)
                s(Shadower.SHADOW_SHIFTER, 30),
                s(Shadower.SMOKE_SCREEN, 30),
                s(Shadower.MAPLE_WARRIOR, 10),
                s(Shadower.HEROS_WILL, 5),
                s(Shadower.TAUNT, 30),           // requires Shadow Shifter 10
                s(Shadower.MAPLE_WARRIOR, 20),
                s(Shadower.HEROS_WILL, max(Shadower.HEROS_WILL)),
                s(Shadower.VENOMOUS_STAB, max(Shadower.VENOMOUS_STAB)),
                s(Shadower.NINJA_AMBUSH, max(Shadower.NINJA_AMBUSH))
        );
    }
}
