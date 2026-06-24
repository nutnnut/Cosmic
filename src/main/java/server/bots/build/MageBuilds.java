package server.bots.build;

import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Bishop;
import constants.skills.Cleric;
import constants.skills.FPArchMage;
import constants.skills.FPMage;
import constants.skills.FPWizard;
import constants.skills.ILArchMage;
import constants.skills.ILMage;
import constants.skills.ILWizard;
import constants.skills.Magician;
import constants.skills.Priest;
import java.util.List;

public final class MageBuilds {

    private MageBuilds() {
    }

    public static List<BuildStep> getBuildOrder(Job job) {
        return switch (job) {
            case MAGICIAN -> magicianBuild();
            case IL_WIZARD -> ilWizardBuild();
            case IL_MAGE -> ilMageBuild();
            case IL_ARCHMAGE -> ilArchMageBuild();
            case FP_WIZARD -> fpWizardBuild();
            case FP_MAGE -> fpMageBuild();
            case FP_ARCHMAGE -> fpArchMageBuild();
            case CLERIC -> clericBuild();
            case PRIEST -> priestBuild();
            case BISHOP -> bishopBuild();
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

    private static List<BuildStep> magicianBuild() {
        return List.of(
                s(Magician.ENERGY_BOLT, 1),
                s(Magician.IMPROVED_MP_RECOVERY, 5),
                s(Magician.IMPROVED_MAX_MP_INCREASE, 10),
                s(Magician.IMPROVED_MP_RECOVERY, 16),
                s(Magician.MAGIC_CLAW, 20),
                s(Magician.MAGIC_GUARD, 20)
        );
    }

    // https://forum.maplelegends.com/index.php?threads/ice-lightning-mage-guide.12054/
    private static List<BuildStep> ilWizardBuild() {
        return List.of(
                s(ILWizard.TELEPORT, 1),
                s(ILWizard.THUNDERBOLT, max(ILWizard.THUNDERBOLT)),
                s(ILWizard.MEDITATION, 20),
                s(ILWizard.MP_EATER, 20),
                s(ILWizard.TELEPORT, 20),
                s(ILWizard.COLD_BEAM, max(ILWizard.COLD_BEAM))
        );
    }

    private static List<BuildStep> ilMageBuild() {
        return List.of(
                s(ILMage.ELEMENT_AMPLIFICATION, 1),
                s(ILMage.ICE_STRIKE, max(ILMage.ICE_STRIKE)),
                s(ILMage.SPELL_BOOSTER, 11),
                s(ILMage.ELEMENT_AMPLIFICATION, max(ILMage.ELEMENT_AMPLIFICATION)),
                s(ILMage.SPELL_BOOSTER, 20),
                s(ILMage.ELEMENT_COMPOSITION, max(ILMage.ELEMENT_COMPOSITION)),
                s(ILMage.SEAL, 20),
                s(ILMage.PARTIAL_RESISTANCE, max(ILMage.PARTIAL_RESISTANCE))
        );
    }

    private static List<BuildStep> ilArchMageBuild() {
        return List.of(
                s(ILArchMage.BLIZZARD, 3),
                s(ILArchMage.BLIZZARD, 10),
                s(ILArchMage.MAPLE_WARRIOR, 9),
                s(ILArchMage.BLIZZARD, max(ILArchMage.BLIZZARD)),
                s(ILArchMage.MAPLE_WARRIOR, 19),
                s(ILArchMage.CHAIN_LIGHTNING, max(ILArchMage.CHAIN_LIGHTNING)),
                s(ILArchMage.ICE_DEMON, 5),
                s(ILArchMage.IFRIT, max(ILArchMage.IFRIT)),
                s(ILArchMage.ICE_DEMON, max(ILArchMage.ICE_DEMON)),
                s(ILArchMage.MAPLE_WARRIOR, max(ILArchMage.MAPLE_WARRIOR)),
                s(ILArchMage.BIG_BANG, max(ILArchMage.BIG_BANG)),
                s(ILArchMage.INFINITY, max(ILArchMage.INFINITY)),
                s(ILArchMage.MANA_REFLECTION, max(ILArchMage.MANA_REFLECTION)),
                s(ILArchMage.HEROS_WILL, max(ILArchMage.HEROS_WILL))
        );
    }

    // https://www.digitaltq.com/maplestory-fire-poison-pre-big-bang-skill-build-guide
    // (cross-checked against GoodDoodoo's MapleRoyals F/P guide:
    //  https://royals.ms/forum/threads/the-best-fire-poison-guide-there-was-is-and-ever-will-be.27168/)
    private static List<BuildStep> fpWizardBuild() {
        return List.of(
                s(FPWizard.FIRE_ARROW, max(FPWizard.FIRE_ARROW)),   // main single-target nuke
                s(FPWizard.MEDITATION, max(FPWizard.MEDITATION)),   // +MATK party buff (big damage boost)
                s(FPWizard.TELEPORT, max(FPWizard.TELEPORT)),       // mobility
                s(FPWizard.MP_EATER, max(FPWizard.MP_EATER)),       // MP sustain
                s(FPWizard.POISON_BREATH, max(FPWizard.POISON_BREATH)), // poison DoT
                s(FPWizard.SLOW, max(FPWizard.SLOW))
        );
    }

    private static List<BuildStep> fpMageBuild() {
        return List.of(
                s(FPMage.ELEMENT_AMPLIFICATION, 10),  // immediate damage multiplier
                s(FPMage.EXPLOSION, max(FPMage.EXPLOSION)),  // main AoE attack
                s(FPMage.POISON_MIST, max(FPMage.POISON_MIST)), // DoT cloud
                s(FPMage.SPELL_BOOSTER, max(FPMage.SPELL_BOOSTER)), // cast speed
                s(FPMage.ELEMENT_AMPLIFICATION, max(FPMage.ELEMENT_AMPLIFICATION)),
                s(FPMage.ELEMENT_COMPOSITION, max(FPMage.ELEMENT_COMPOSITION)), // strong dual-element attack
                s(FPMage.PARTIAL_RESISTANCE, max(FPMage.PARTIAL_RESISTANCE)),
                s(FPMage.SEAL, max(FPMage.SEAL))
        );
    }

    /** 4th-job skills are book-gated (canLevelSkill caps each at its master level); max() targets just
     *  spend whatever SP the unlocked levels allow, in priority order. */
    private static List<BuildStep> fpArchMageBuild() {
        return List.of(
                s(FPArchMage.PARALYZE, max(FPArchMage.PARALYZE)),         // efficient main attack
                s(FPArchMage.METEOR_SHOWER, max(FPArchMage.METEOR_SHOWER)), // heavy AoE nuke
                s(FPArchMage.MAPLE_WARRIOR, 20),
                s(FPArchMage.FIRE_DEMON, max(FPArchMage.FIRE_DEMON)),     // DoT + element resist down
                s(FPArchMage.INFINITY, max(FPArchMage.INFINITY)),        // MP/damage sustain
                s(FPArchMage.ELQUINES, max(FPArchMage.ELQUINES)),        // summon
                s(FPArchMage.MANA_REFLECTION, max(FPArchMage.MANA_REFLECTION)),
                s(FPArchMage.BIG_BANG, max(FPArchMage.BIG_BANG)),
                s(FPArchMage.MAPLE_WARRIOR, max(FPArchMage.MAPLE_WARRIOR)),
                s(FPArchMage.HEROS_WILL, max(FPArchMage.HEROS_WILL))
        );
    }

    private static List<BuildStep> clericBuild() {
        return List.of(
                s(Cleric.HEAL, 30),
                s(Cleric.INVINCIBLE, 5),
                s(Cleric.BLESS, 20),
                s(Cleric.TELEPORT, 20),
                s(Cleric.MP_EATER, 20),
                s(Cleric.INVINCIBLE, 20),
                s(Cleric.HOLY_ARROW, 11)
        );
    }

    private static List<BuildStep> priestBuild() {
        return List.of(
                s(Priest.SHINING_RAY, 1),
                s(Priest.DISPEL, 3),
                s(Priest.ELEMENTAL_RESISTANCE, 1),
                s(Priest.MYSTIC_DOOR, 1),
                s(Priest.HOLY_SYMBOL, max(Priest.HOLY_SYMBOL)),
                s(Priest.SHINING_RAY, max(Priest.SHINING_RAY)),
                s(Priest.ELEMENTAL_RESISTANCE, max(Priest.ELEMENTAL_RESISTANCE)),
                s(Priest.SUMMON_DRAGON, max(Priest.SUMMON_DRAGON)),
                s(Priest.DISPEL, max(Priest.DISPEL)),
                s(Priest.MYSTIC_DOOR, max(Priest.MYSTIC_DOOR)),
                s(Priest.DOOM, 1)
        );
    }

    private static List<BuildStep> bishopBuild() {
        return List.of(
                s(Bishop.GENESIS, 10),
                s(Bishop.MAPLE_WARRIOR, 9),
                s(Bishop.RESURRECTION, max(Bishop.RESURRECTION)),
                s(Bishop.ANGEL_RAY, max(Bishop.ANGEL_RAY)),
                s(Bishop.BAHAMUT, max(Bishop.BAHAMUT)),
                s(Bishop.GENESIS, max(Bishop.GENESIS)),
                s(Bishop.MAPLE_WARRIOR, max(Bishop.MAPLE_WARRIOR)),
                s(Bishop.BIG_BANG, max(Bishop.BIG_BANG)),
                s(Bishop.HOLY_SHIELD, max(Bishop.HOLY_SHIELD)),
                s(Bishop.INFINITY, max(Bishop.INFINITY)),
                s(Bishop.MANA_REFLECTION, max(Bishop.MANA_REFLECTION)),
                s(Bishop.HEROS_WILL, max(Bishop.HEROS_WILL))
        );
    }
}
