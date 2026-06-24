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
 * Pirate SP builds. Like the thief tree, the class splits at the 1st job into a KNUCKLE path
 * (Flash Fist/Somersault Kick -> Brawler/Marauder/Buccaneer) and a GUN path (Double Shot ->
 * Gunslinger/Outlaw/Corsair). The variant is chosen at Pirate advancement and drives the weapon
 * gate ({@code BotEquipManager.isWeaponCompatible} keys off the trained 1st-job attack skill:
 * Flash Fist/Somersault Kick -> knuckle-only, Double Shot -> gun-only), so it must commit before
 * any SP lands.
 *
 * <p>Build orders adapted from the pre-Big-Bang (v83-accurate) skill guides:
 * Buccaneer/Brawler/Marauder - https://www.digitaltq.com/maplestory-pirate-brawler-marauder-buccaneer-pre-big-bang-skill-build-guide
 * (cross-checked against Donn1e's MapleRoyals Buccaneer guide: https://royals.ms/forum/threads/donn1es-buccaneer-guide.188641/);
 * Corsair/Gunslinger/Outlaw - https://www.digitaltq.com/maplestory-pirate-gunslinger-outlaw-corsair-pre-big-bang-skill-build-guide
 * (cross-checked against Nemo's MapleRoyals Corsair guide: https://royals.ms/forum/threads/a-comprehensive-corsair-guide.122934/).
 * The bot deviation from the human guides (mobbing-first ordering for a grinding bot) is the same
 * one the other build files make; noted inline where it applies.
 */
public final class PirateBuilds {

    private PirateBuilds() {
    }

    public static List<BuildStep> getBuildOrder(Job job, String variant) {
        boolean gun = "gun".equals(variant);
        return switch (job) {
            case PIRATE -> gun ? gunPirateBuild() : knucklePirateBuild();
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
        return skill != null ? skill.getMaxLevel() : 30;
    }

    // ---- KNUCKLE path (Flash Fist/Somersault Kick -> Brawler) ----

    /** Pirate, knuckle variant: Flash Fist 1 commits the knuckle weapon gate immediately, then
     *  Somersault Kick is the main attack carried all the way to 4th job (per the guide). */
    private static List<BuildStep> knucklePirateBuild() {
        return List.of(
                s(Pirate.FLASH_FIST, 1),        // commit knuckle gate at once
                s(Pirate.SOMERSAULT_KICK, 20),  // main mobbing attack through to 4th job
                s(Pirate.DASH, max(Pirate.DASH)),
                s(Pirate.BULLET_TIME, max(Pirate.BULLET_TIME)),
                s(Pirate.FLASH_FIST, max(Pirate.FLASH_FIST))
        );
    }

    private static List<BuildStep> brawlerBuild() {
        return List.of(
                s(Brawler.KNUCKLER_MASTERY, 5),    // unlocks Booster
                s(Brawler.KNUCKLER_BOOSTER, 11),   // attack speed
                s(Brawler.CORKSCREW_BLOW, 30),     // strong single-target main
                s(Brawler.KNUCKLER_MASTERY, 20),
                s(Brawler.KNUCKLER_BOOSTER, 20),
                s(Brawler.IMPROVE_MAX_HP, max(Brawler.IMPROVE_MAX_HP)),
                s(Brawler.BACK_SPIN_BLOW, 20),
                s(Brawler.DOUBLE_UPPERCUT, 20)
        );
    }

    private static List<BuildStep> marauderBuild() {
        return List.of(
                s(Marauder.ENERGY_CHARGE, 30),   // core: fills the energy meter, gates the rest
                s(Marauder.ENERGY_BLAST, 30),    // main charged AoE attack
                s(Marauder.STUN_MASTERY, 20),
                s(Marauder.ENERGY_DRAIN, 20),    // HP-drain attack
                s(Marauder.TRANSFORMATION, 30),
                s(Marauder.SHOCKWAVE, max(Marauder.SHOCKWAVE))  // transformed AoE
        );
    }

    /** Buccaneer. 4th-job skills are book-gated (canLevelSkill caps each at its master level), so the
     *  max() targets just spend whatever SP the unlocked levels allow, in priority order. */
    private static List<BuildStep> buccaneerBuild() {
        return List.of(
                s(Buccaneer.SPEED_INFUSION, 1),  // party speed buff, useful even at 1
                s(Buccaneer.DRAGON_STRIKE, max(Buccaneer.DRAGON_STRIKE)),  // main mobbing AoE
                s(Buccaneer.MAPLE_WARRIOR, 20),
                s(Buccaneer.DEMOLITION, max(Buccaneer.DEMOLITION)),
                s(Buccaneer.BARRAGE, max(Buccaneer.BARRAGE)),  // single-target/boss finisher
                s(Buccaneer.SNATCH, max(Buccaneer.SNATCH)),
                s(Buccaneer.ENERGY_ORB, max(Buccaneer.ENERGY_ORB)),
                s(Buccaneer.SUPER_TRANSFORMATION, max(Buccaneer.SUPER_TRANSFORMATION)),
                s(Buccaneer.SPEED_INFUSION, max(Buccaneer.SPEED_INFUSION)),
                s(Buccaneer.TIME_LEAP, max(Buccaneer.TIME_LEAP)),
                s(Buccaneer.PIRATES_RAGE, max(Buccaneer.PIRATES_RAGE)),
                s(Buccaneer.MAPLE_WARRIOR, max(Buccaneer.MAPLE_WARRIOR))
        );
    }

    // ---- GUN path (Double Shot -> Gunslinger) ----

    /** Pirate, gun variant: Double Shot commits the gun weapon gate and is the main attack maxed
     *  first; the rest follows the gun (Corsair-bound) guide. */
    private static List<BuildStep> gunPirateBuild() {
        return List.of(
                s(Pirate.DOUBLE_SHOT, max(Pirate.DOUBLE_SHOT)),  // commit gun gate + main attack
                s(Pirate.DASH, max(Pirate.DASH)),
                s(Pirate.BULLET_TIME, max(Pirate.BULLET_TIME))
        );
    }

    private static List<BuildStep> gunslingerBuild() {
        return List.of(
                s(Gunslinger.INVISIBLE_SHOT, 1),  // main attack early
                s(Gunslinger.GUN_MASTERY, 5),     // unlocks Booster
                s(Gunslinger.GUN_BOOSTER, 11),    // attack speed
                s(Gunslinger.GUN_MASTERY, 20),
                s(Gunslinger.INVISIBLE_SHOT, 20), // main attack maxed
                s(Gunslinger.GUN_BOOSTER, 20),
                s(Gunslinger.RECOIL_SHOT, max(Gunslinger.RECOIL_SHOT)),
                s(Gunslinger.WINGS, max(Gunslinger.WINGS)),
                s(Gunslinger.BLANK_SHOT, max(Gunslinger.BLANK_SHOT))
        );
    }

    private static List<BuildStep> outlawBuild() {
        return List.of(
                s(Outlaw.BURST_FIRE, 20),    // main multi-hit attack
                s(Outlaw.ICE_SPLITTER, max(Outlaw.ICE_SPLITTER)),   // AoE
                s(Outlaw.FLAME_THROWER, max(Outlaw.FLAME_THROWER)), // DoT AoE
                s(Outlaw.OCTOPUS, max(Outlaw.OCTOPUS)),             // summon
                s(Outlaw.GAVIOTA, max(Outlaw.GAVIOTA)),
                s(Outlaw.HOMING_BEACON, max(Outlaw.HOMING_BEACON))  // marks target for Corsair Rapid Fire
        );
    }

    /** Corsair. 4th-job skills are book-gated (see {@link #buccaneerBuild}). Battleship is unlocked
     *  first so the bot can mount; Rapid Fire is the main DPS used on the ship. */
    private static List<BuildStep> corsairBuild() {
        return List.of(
                s(Corsair.BATTLE_SHIP, 1),       // unlock the mount
                s(Corsair.RAPID_FIRE, max(Corsair.RAPID_FIRE)),         // main single-target DPS
                s(Corsair.BATTLESHIP_CANNON, max(Corsair.BATTLESHIP_CANNON)), // AoE mob on ship
                s(Corsair.MAPLE_WARRIOR, 20),
                s(Corsair.BULLSEYE, max(Corsair.BULLSEYE)),
                s(Corsair.BATTLE_SHIP, max(Corsair.BATTLE_SHIP)),       // more ship HP
                s(Corsair.AERIAL_STRIKE, max(Corsair.AERIAL_STRIKE)),
                s(Corsair.WRATH_OF_THE_OCTOPI, max(Corsair.WRATH_OF_THE_OCTOPI)),
                s(Corsair.BATTLESHIP_TORPEDO, max(Corsair.BATTLESHIP_TORPEDO)),
                s(Corsair.ELEMENTAL_BOOST, max(Corsair.ELEMENTAL_BOOST)),
                s(Corsair.HYPNOTIZE, max(Corsair.HYPNOTIZE)),
                s(Corsair.SPEED_INFUSION, max(Corsair.SPEED_INFUSION)),
                s(Corsair.MAPLE_WARRIOR, max(Corsair.MAPLE_WARRIOR))
        );
    }
}
