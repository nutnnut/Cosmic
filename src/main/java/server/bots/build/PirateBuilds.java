package server.bots.build;

import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.Corsair;
import constants.skills.Gunslinger;
import constants.skills.Marauder;
import constants.skills.Outlaw;
import constants.skills.Pirate;
import java.util.List;

/**
 * SP build orders for the Pirate class — the Brawler/Marauder/Buccaneer (knuckler) line and the
 * Gunslinger/Outlaw/Corsair (gun) line. The shared 1st-job {@link Job#PIRATE} build is weapon-aware:
 * BotBuildManager passes "gun" or "knuckler" as the variant based on the bot's equipped weapon.
 */
public final class PirateBuilds {

    private PirateBuilds() {
    }

    public static List<BuildStep> getBuildOrder(Job job, String variant) {
        return switch (job) {
            case PIRATE -> "gun".equals(variant) ? pirateGunBuild() : pirateKnuckleBuild();
            case BRAWLER -> brawlerBuild();
            case MARAUDER -> marauderBuild();
            case BUCCANEER -> buccaneerBuild();
            case GUNSLINGER -> gunslingerBuild();
            case OUTLAW -> outlawBuild();
            case CORSAIR -> corsairBuild();
            default -> null;
        };
    }

    private static BuildStep s(int id, int to) {
        return new BuildStep(id, to);
    }

    private static int max(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        return skill != null ? skill.getMaxLevel() : 20;
    }

    // ---- Knuckler line (Brawler / Marauder / Buccaneer) ----

    private static List<BuildStep> pirateKnuckleBuild() {
        return List.of(
                s(Pirate.FLASH_FIST, 1),
                s(Pirate.SOMERSAULT_KICK, 20),
                s(Pirate.FLASH_FIST, 20),
                s(Pirate.BULLET_TIME, 20),
                s(Pirate.DASH, 1)
        );
    }

    private static List<BuildStep> brawlerBuild() {
        return List.of(
                s(Brawler.IMPROVE_MAX_HP, 10),
                s(Brawler.KNUCKLER_MASTERY, 1),
                s(Brawler.BACK_SPIN_BLOW, 1),
                s(Brawler.CORKSCREW_BLOW, 1),
                s(Brawler.KNUCKLER_MASTERY, 5),
                s(Brawler.KNUCKLER_BOOSTER, 6),
                s(Brawler.KNUCKLER_MASTERY, 19),
                s(Brawler.KNUCKLER_BOOSTER, 20),
                s(Brawler.BACK_SPIN_BLOW, 20),
                s(Brawler.CORKSCREW_BLOW, 20),
                s(Brawler.MP_RECOVERY, 10),
                s(Pirate.DASH, 10),
                s(Brawler.KNUCKLER_MASTERY, 20),
                s(Brawler.OAK_BARREL, 10),
                s(Brawler.DOUBLE_UPPERCUT, 2)
        );
    }

    private static List<BuildStep> marauderBuild() {
        return List.of(
                s(Marauder.TRANSFORMATION, 1),
                s(Marauder.ENERGY_CHARGE, 1),
                s(Marauder.ENERGY_BLAST, 1),
                s(Marauder.ENERGY_DRAIN, 1),
                s(Marauder.ENERGY_CHARGE, 40),
                s(Marauder.ENERGY_BLAST, 30),
                s(Marauder.ENERGY_DRAIN, 20),
                s(Marauder.SHOCKWAVE, 21),
                s(Marauder.STUN_MASTERY, 20),
                s(Marauder.TRANSFORMATION, 20)
        );
    }

    private static List<BuildStep> buccaneerBuild() {
        return List.of(
                s(Buccaneer.SUPER_TRANSFORMATION, 1),
                s(Buccaneer.DEMOLITION, 1),
                s(Buccaneer.BARRAGE, 1),
                s(Buccaneer.DRAGON_STRIKE, 1),
                s(Buccaneer.TIME_LEAP, 1),
                s(Buccaneer.SPEED_INFUSION, 11),
                s(Buccaneer.DEMOLITION, 30),
                s(Buccaneer.DRAGON_STRIKE, 30),
                s(Buccaneer.BARRAGE, 30),
                s(Buccaneer.MAPLE_WARRIOR, 9),
                s(Buccaneer.SNATCH, 30),
                s(Buccaneer.SUPER_TRANSFORMATION, 11),
                s(Buccaneer.TIME_LEAP, 30),
                s(Buccaneer.ENERGY_ORB, 30),
                s(Buccaneer.SPEED_INFUSION, 20),
                s(Buccaneer.MAPLE_WARRIOR, 10),
                s(Buccaneer.SUPER_TRANSFORMATION, max(Buccaneer.SUPER_TRANSFORMATION))
        );
    }

    // ---- Gun line (Gunslinger / Outlaw / Corsair) ----

    private static List<BuildStep> pirateGunBuild() {
        return List.of(
                s(Pirate.DOUBLE_SHOT, 1),
                s(Pirate.SOMERSAULT_KICK, 1),
                s(Pirate.DOUBLE_SHOT, 20),
                s(Pirate.SOMERSAULT_KICK, 20),
                s(Pirate.DASH, 10),
                s(Pirate.BULLET_TIME, 11)
        );
    }

    private static List<BuildStep> gunslingerBuild() {
        return List.of(
                s(Gunslinger.INVISIBLE_SHOT, 1),
                s(Gunslinger.GUN_MASTERY, 5),
                s(Gunslinger.GUN_BOOSTER, 6),
                s(Gunslinger.GUN_MASTERY, 19),
                s(Gunslinger.INVISIBLE_SHOT, 20),
                s(Gunslinger.WINGS, 5),
                s(Gunslinger.RECOIL_SHOT, 20),
                s(Gunslinger.WINGS, 10),
                s(Gunslinger.GUN_BOOSTER, 20),
                s(Gunslinger.BLANK_SHOT, 20),
                s(Pirate.BULLET_TIME, 20),
                s(Gunslinger.GUN_MASTERY, 20),
                s(Gunslinger.GRENADE, 1)
        );
    }

    private static List<BuildStep> outlawBuild() {
        return List.of(
                s(Outlaw.ICE_SPLITTER, 1),
                s(Outlaw.FLAME_THROWER, 1),
                s(Outlaw.BURST_FIRE, 20),
                s(Outlaw.ICE_SPLITTER, 26),
                s(Outlaw.FLAME_THROWER, 30),
                s(Outlaw.OCTOPUS, 30),
                s(Outlaw.HOMING_BEACON, 30),
                s(Outlaw.GAVIOTA, 15)
        );
    }

    private static List<BuildStep> corsairBuild() {
        return List.of(
                s(Corsair.RAPID_FIRE, 1),
                s(Corsair.ELEMENTAL_BOOST, 1),
                s(Corsair.AERIAL_STRIKE, 1),
                s(Corsair.WRATH_OF_THE_OCTOPI, 1),
                s(Corsair.BATTLE_SHIP, 1),
                s(Corsair.BATTLESHIP_CANNON, 30),
                s(Corsair.BULLSEYE, 20),
                s(Corsair.WRATH_OF_THE_OCTOPI, 11),
                s(Corsair.RAPID_FIRE, 30),
                s(Corsair.BATTLE_SHIP, 10),
                s(Corsair.WRATH_OF_THE_OCTOPI, 20),
                s(Corsair.MAPLE_WARRIOR, 9),
                s(Corsair.HYPNOTIZE, 1),
                s(Corsair.BATTLESHIP_TORPEDO, 30),
                s(Corsair.ELEMENTAL_BOOST, 30),
                s(Outlaw.ICE_SPLITTER, 30),
                s(Corsair.AERIAL_STRIKE, 30),
                s(Outlaw.GAVIOTA, max(Outlaw.GAVIOTA))
        );
    }
}
