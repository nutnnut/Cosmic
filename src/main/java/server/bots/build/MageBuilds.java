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
            case FP_WIZARD -> fpWizardBuild();
            case FP_MAGE -> fpMageBuild();
            case FP_ARCHMAGE -> fpArchMageBuild();
            case IL_WIZARD -> ilWizardBuild();
            case IL_MAGE -> ilMageBuild();
            case IL_ARCHMAGE -> ilArchMageBuild();
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
                s(Magician.MAGIC_CLAW, 20),
                s(Magician.IMPROVED_MP_RECOVERY, 16),
                s(Magician.MAGIC_GUARD, 20)
        );
    }

    private static List<BuildStep> fpWizardBuild() {
        return List.of(
                s(FPWizard.TELEPORT, 1),
                s(FPWizard.FIRE_ARROW, 30),
                s(FPWizard.POISON_BREATH, 30),
                s(FPWizard.TELEPORT, 20),
                s(FPWizard.MP_EATER, 20),
                s(FPWizard.MEDITATION, 20),
                s(FPWizard.SLOW, 1)
        );
    }

    private static List<BuildStep> fpMageBuild() {
        return List.of(
                s(FPMage.POISON_MIST, 30),
                s(FPMage.ELEMENT_AMPLIFICATION, 3),
                s(FPMage.SPELL_BOOSTER, 11),
                s(FPMage.EXPLOSION, 1),
                s(FPMage.SEAL, 20),
                s(FPMage.EXPLOSION, 30),
                s(FPMage.ELEMENT_AMPLIFICATION, 30),
                s(FPMage.SPELL_BOOSTER, 20),
                s(FPMage.PARTIAL_RESISTANCE, 20),
                s(FPMage.ELEMENT_COMPOSITION, 1)
        );
    }

    private static List<BuildStep> fpArchMageBuild() {
        return List.of(
                s(FPArchMage.PARALYZE, 1),
                s(FPArchMage.METEOR_SHOWER, 1),
                s(FPArchMage.MAPLE_WARRIOR, 9),
                s(FPArchMage.METEOR_SHOWER, 30),
                s(FPArchMage.PARALYZE, 30),
                s(FPArchMage.FIRE_DEMON, 5),
                s(FPArchMage.ELQUINES, 30),
                s(FPArchMage.INFINITY, 30),
                s(FPArchMage.HEROS_WILL, 1),
                s(FPArchMage.FIRE_DEMON, 30),
                s(FPArchMage.BIG_BANG, 30),
                s(FPArchMage.HEROS_WILL, 5),
                s(FPArchMage.MANA_REFLECTION, 30),
                s(FPArchMage.MAPLE_WARRIOR, 10),
                s(FPWizard.SLOW, max(FPWizard.SLOW))
        );
    }

    // https://forum.maplelegends.com/index.php?threads/ice-lightning-mage-guide.12054/
    private static List<BuildStep> ilWizardBuild() {
        return List.of(
                s(ILWizard.TELEPORT, 1),
                s(ILWizard.COLD_BEAM, 1),
                s(ILWizard.THUNDERBOLT, 30),
                s(ILWizard.MP_EATER, 1),
                s(ILWizard.TELEPORT, 20),
                s(ILWizard.COLD_BEAM, 30),
                s(ILWizard.MP_EATER, 20),
                s(ILWizard.MEDITATION, 20),
                s(ILWizard.SLOW, 1)
        );
    }

    private static List<BuildStep> ilMageBuild() {
        return List.of(
                s(ILMage.ICE_STRIKE, 30),
                s(ILMage.ELEMENT_AMPLIFICATION, 3),
                s(ILMage.SEAL, 1),
                s(ILMage.SPELL_BOOSTER, 11),
                s(ILMage.ELEMENT_AMPLIFICATION, 30),
                s(ILMage.SPELL_BOOSTER, 20),
                s(ILMage.SEAL, 20),
                s(ILMage.ELEMENT_COMPOSITION, 30),
                s(ILMage.PARTIAL_RESISTANCE, 20),
                s(ILMage.THUNDER_SPEAR, 1)
        );
    }

    private static List<BuildStep> ilArchMageBuild() {
        return List.of(
                s(ILArchMage.BLIZZARD, 1),
                s(ILArchMage.CHAIN_LIGHTNING, 1),
                s(ILArchMage.MAPLE_WARRIOR, 9),
                s(ILArchMage.BLIZZARD, 30),
                s(ILArchMage.CHAIN_LIGHTNING, 30),
                s(ILArchMage.ICE_DEMON, 5),
                s(ILArchMage.IFRIT, 30),
                s(ILArchMage.INFINITY, 30),
                s(ILArchMage.HEROS_WILL, 1),
                s(ILArchMage.ICE_DEMON, 30),
                s(ILArchMage.BIG_BANG, 30),
                s(ILArchMage.HEROS_WILL, 5),
                s(ILArchMage.MANA_REFLECTION, 30),
                s(ILArchMage.MAPLE_WARRIOR, 10),
                s(ILWizard.SLOW, max(ILWizard.SLOW))
        );
    }

    private static List<BuildStep> clericBuild() {
        return List.of(
                s(Cleric.TELEPORT, 1),
                s(Cleric.HEAL, 30),
                s(Cleric.MP_EATER, 1),
                s(Cleric.TELEPORT, 20),
                s(Cleric.MP_EATER, 20),
                s(Cleric.INVINCIBLE, 20),
                s(Cleric.BLESS, 20),
                s(Cleric.HOLY_ARROW, 11)
        );
    }

    private static List<BuildStep> priestBuild() {
        return List.of(
                s(Priest.SHINING_RAY, 1),
                s(Priest.DISPEL, 3),
                s(Priest.HOLY_SYMBOL, 30),
                s(Priest.MYSTIC_DOOR, 4),
                s(Priest.SHINING_RAY, 30),
                s(Priest.DOOM, 30),
                s(Priest.MYSTIC_DOOR, 20),
                s(Priest.DISPEL, 20),
                s(Priest.ELEMENTAL_RESISTANCE, 6),
                s(Priest.SUMMON_DRAGON, 15)
        );
    }

    private static List<BuildStep> bishopBuild() {
        return List.of(
                s(Bishop.GENESIS, 1),
                s(Bishop.MAPLE_WARRIOR, 9),
                s(Bishop.RESURRECTION, 1),
                s(Bishop.GENESIS, 30),
                s(Bishop.BAHAMUT, 30),
                s(Bishop.HOLY_SHIELD, 30),
                s(Bishop.HEROS_WILL, 1),
                s(Bishop.RESURRECTION, 10),
                s(Bishop.INFINITY, 30),
                s(Priest.ELEMENTAL_RESISTANCE, 20),
                s(Bishop.ANGEL_RAY, 30),
                s(Bishop.HEROS_WILL, 5),
                s(Bishop.BIG_BANG, 30),
                s(Bishop.MAPLE_WARRIOR, 10),
                s(Bishop.MANA_REFLECTION, max(Bishop.MANA_REFLECTION))
        );
    }
}
