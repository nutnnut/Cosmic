package server.bots;

import client.BuffStat;
import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.Bishop;
import constants.skills.Bowmaster;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.ChiefBandit;
import constants.skills.Cleric;
import constants.skills.Corsair;
import constants.skills.Crossbowman;
import constants.skills.Crusader;
import constants.skills.DarkKnight;
import constants.skills.DawnWarrior;
import constants.skills.DragonKnight;
import constants.skills.Evan;
import constants.skills.Fighter;
import constants.skills.FPArchMage;
import constants.skills.FPMage;
import constants.skills.GM;
import constants.skills.Gunslinger;
import constants.skills.Hermit;
import constants.skills.Hero;
import constants.skills.Hunter;
import constants.skills.ILArchMage;
import constants.skills.ILWizard;
import constants.skills.Marksman;
import constants.skills.NightWalker;
import constants.skills.Paladin;
import constants.skills.Pirate;
import constants.skills.Priest;
import constants.skills.Rogue;
import constants.skills.Spearman;
import constants.skills.SuperGM;
import constants.skills.ThunderBreaker;
import constants.skills.WhiteKnight;
import constants.skills.WindArcher;
import io.netty.buffer.Unpooled;
import net.PacketHandler;
import net.PacketProcessor;
import net.server.PlayerBuffValueHolder;
import net.server.channel.handlers.AbstractDealDamageHandler;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import server.StatEffect;
import server.bots.combat.BotAttackDataProvider;
import server.bots.combat.BotDefenseDataProvider;
import server.bots.combat.BotMobHitboxProvider;
import server.combat.CombatFormulaProvider;
import server.life.Monster;
import server.maps.Foothold;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import tools.PacketCreator;
import tools.Pair;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

class BotCombatManager {
    private static final long UNREACHABLE_GRAPH_COST = Long.MAX_VALUE / 4;

    // Skills that bots must never cast — stealth makes them untargetable by monsters,
    // breaking combat entirely.
    static final Set<Integer> BUFF_BLACKLIST = Set.of(
            Rogue.DARK_SIGHT,
            NightWalker.DARK_SIGHT
    );
    static final Set<Integer> NON_DAMAGE_ACTIVE_SKILL_IDS = Set.of(
            Crusader.ARMOR_CRASH,
            WhiteKnight.MAGIC_CRASH,
            DragonKnight.POWER_CRASH
    );
    // Battleship cannons only fire while in Battleship form (a MONSTER_RIDING morph that disables a
    // bot's normal attacks and uses a separate HP pool), so bots skip them; Corsairs fall back to
    // Aerial Strike / basic gun (Rapid Fire is keydown — see KEYDOWN_SKILL_IDS below).
    // Meso Explosion is NOT here: it's enabled conditionally in planSkillAttack once Pickpocket has
    // dropped detonatable mesos near the target.
    static final Set<Integer> BOT_UNUSABLE_ATTACK_SKILL_IDS = Set.of(
            Corsair.BATTLESHIP_CANNON,
            Corsair.BATTLESHIP_TORPEDO,
            // Heaven's Hammer caps monsters at 1 HP (it can never kill), so a bot would spam it
            // forever without finishing anything — leave it out and let Paladins use Blast.
            Paladin.HEAVENS_HAMMER
    );
    // Keydown / charge skills (press-and-hold). A real client casts these via a skill-prepare packet
    // that the server rebroadcasts as a SKILL_EFFECT "charge" animation (SkillEffectHandler) BEFORE
    // the attack body, putting watching clients into the keydown render state. Bots have no keydown
    // cast path, so firing one as a normal one-shot attack broadcast makes observing v83 clients try
    // to render an un-charged charge skill and crash mid-grind. Excluded from attack selection; the
    // bot falls back to its non-keydown attacks / basic attack. Keep in sync with the authoritative
    // keydown set in net.server.channel.handlers.SkillEffectHandler.
    static final Set<Integer> KEYDOWN_SKILL_IDS = Set.of(
            FPMage.EXPLOSION,
            FPArchMage.BIG_BANG,
            ILArchMage.BIG_BANG,
            Bishop.BIG_BANG,
            Bowmaster.HURRICANE,
            Marksman.PIERCING_ARROW,
            ChiefBandit.CHAKRA,
            Brawler.CORKSCREW_BLOW,
            Gunslinger.GRENADE,
            Corsair.RAPID_FIRE,
            WindArcher.HURRICANE,
            NightWalker.POISON_BOMB,
            ThunderBreaker.CORKSCREW_BLOW,
            Paladin.MONSTER_MAGNET,
            DarkKnight.MONSTER_MAGNET,
            Hero.MONSTER_MAGNET,
            Evan.FIRE_BREATH,
            Evan.ICE_BREATH
    );
    private static final double MESO_EXPLOSION_RADIUS_SQ = 250.0 * 250.0;
    private static final int MESO_EXPLOSION_MAX_MESOS = 15;
    private static final int DRAGON_ROAR_MIN_TARGETS_WITHOUT_HEALER = 10;

    enum AttackRoute {
        CLOSE,
        RANGED,
        MAGIC
    }

    static final class AttackPlan {
        final int skillId;
        final int skillLevel;
        final int numDamage;
        final Rectangle hitBox;
        final List<Monster> targets;
        final AttackRoute route;
        final int display;
        final int direction;
        final int rangedDirection;
        final int stance;
        final int speed;
        final int hitDelayMs;
        final int cooldownMs;
        final WeaponType damageWeaponType;

        AttackPlan(int skillId, int skillLevel, int numDamage, Rectangle hitBox, List<Monster> targets,
                   AttackRoute route, int display, int direction, int rangedDirection, int stance, int speed,
                   int hitDelayMs, int cooldownMs, WeaponType damageWeaponType) {
            this.skillId = skillId;
            this.skillLevel = skillLevel;
            this.numDamage = numDamage;
            this.hitBox = hitBox;
            this.targets = targets;
            this.route = route;
            this.display = display;
            this.direction = direction;
            this.rangedDirection = rangedDirection;
            this.stance = stance;
            this.speed = speed;
            this.hitDelayMs = hitDelayMs;
            this.cooldownMs = cooldownMs;
            this.damageWeaponType = damageWeaponType;
        }

        boolean hasHitBox() {
            return hitBox != null;
        }

        Monster primaryTarget() {
            return targets.get(0);
        }

        boolean isCloseRangeRoute() {
            return route == AttackRoute.CLOSE;
        }
    }

    static class Config {
        // Physics (combat use only)
        // OpenStory Player::damage sets hspeed = +/-1.5 and vforce -= 3.5 on mob knockback.
        public float KNOCKBACK_HSPEED = 1.5f;
        public float KNOCKBACK_VFORCE = 3.5f;

        // Basic attack fallback when weapon data cannot produce a real normal-attack hit box.
        public int   ATTACK_RANGE_X  = 80;
        public int   ATTACK_RANGE_Y  = 50;
        public int   ATTACK_DOWN_MAX = 20;
        public int   ATTACK_JUMP_Y   = 130;
        public int   ATTACK_JUMP_X_EXTRA = 60;
        public int   RANGED_DEGENERATE_RANGE_X = 50;
        public int   RANGED_DEGENERATE_RANGE_Y = 50;
        public int   RANGED_RETREAT_THRESHOLD_X = 80;
        public int   RANGED_RETREAT_DISTANCE_X = 100;
        public int   BREAKOUT_MAX_MS = 3000; // cap on a committed surround-breakout run before re-deciding

        // Ammo
        public int   AMMO_LOW_WARN = 500;

        // Grind / AoE
        public int   GRIND_SEEK_RANGE  = 800;
        public int   GRIND_RETARGET_INTERVAL_MS = 400;
        public int   AOE_MOB_THRESHOLD = 2;
        public int   GRIND_REGION_OCCUPANCY_PENALTY = 1200;
        public int   GRIND_REGION_OCCUPANCY_PENALTY_CAP = 3600;

        // Mob damage
        public int   MOB_TOUCH_SWEEP_HEIGHT = 50;
        public int   MOB_HIT_COOLDOWN_MS = 1500;
        public long  BOT_DEAD_MS      = 30_000L;

        // Support
        public int   SUPPORT_RANGE = 400;
        public int   SUPPORT_VERTICAL_RANGE = 220;
        public int   SUPPORT_REBUFF_CD_MS = 3_000;
        // Heal until every member in range (including the cleric itself) is above this HP ratio.
        // Cadence: animation lock (attackCooldownMs) then HEAL_MOVE_WINDOW_MS walk window.
        // Heal is also gated by moveWindowMs > 0, so it cannot fire mid-attack-movement-window.
        public float SUPPORT_HEAL_TARGET_RATIO = 0.90f;
        public int   HEAL_MOVE_WINDOW_MS = 600;
        // Jump-heal: while following, if the leader is at least this many px ahead horizontally,
        // kick a diagonal jump toward them just before the heal cast so the bot keeps closing
        // distance instead of stopping to plant the heal animation. 0 disables.
        public int   JUMP_HEAL_LEADER_AHEAD_PX = 80;
    }

    static Config cfg = new Config();
    // Journey client CharStats::get_range() returns Rectangle(-projectilerange, -5, -50, 50).
    static final int CLIENT_PROJECTILE_BASE_RANGE = 400;
    private static final int CLIENT_PROJECTILE_NEAR_INSET = 5;
    private static final int CLIENT_PROJECTILE_TOP = 50;
    private static final int CLIENT_PROJECTILE_BOTTOM = 50;

    // Pierce-line projectiles do NOT use the generic ±50 px vertical band: the actual
    // client projectile sprite is much thinner (Iron Arrow) or much taller (Avenger)
    // and the launch height sits at ~30 px above the character's feet.
    //   yAbove = how far the projectile box extends above the bot's feet.
    //   yBelow = how far it extends below (can be negative when the entire box is above
    //            the feet, e.g. a thin Iron Arrow line at player.Y - 30).
    // Values measured 2026-05-18 from monitored-packets-{ironarrow,avenger}-*.log
    // (Bowgurl / admin) by binary-searching the mob-Y vs. player-feet-Y delta at which
    // the client stopped including a mob in the per-attack mob list:
    //   Iron Arrow: hit @ Δy=17, miss @ Δy=41  → reach ≈ 30 (~thin horizontal line)
    //   Avenger:    hit @ Δy=56, miss @ Δy=65  → reach ≈ 60 (~±30 around launch line)
    private record ProjectileVerticalReach(int yAbove, int yBelow) { }

    private static final Map<Integer, ProjectileVerticalReach> PIERCE_LINE_PROJECTILE_REACH = Map.of(
            Crossbowman.IRON_ARROW, new ProjectileVerticalReach(32, -28),
            Hermit.AVENGER, new ProjectileVerticalReach(60, 0),
            NightWalker.AVENGER, new ProjectileVerticalReach(60, 0)
    );
    private static final List<Integer> PASSIVE_PROJECTILE_RANGE_SKILL_IDS = List.of(
            Archer.EYE_OF_AMAZON,
            Rogue.KEEN_EYES,
            WindArcher.EYE_OF_AMAZON,
            NightWalker.KEEN_EYES
    );
    // Skills whose WZ bbox describes an explosion around the strike point (primary target),
    // not a sweep around the caster. Anchor the planner's hitBox at the target for these,
    // matching how StatEffect handles NightWalker.POISON_BOMB (see StatEffect line 1089).
    private static final Set<Integer> STRIKE_POINT_ANCHORED_AOE_SKILL_IDS = Set.of(
            Hunter.ARROW_BOMB
    );
    private static final Set<Integer> PARTY_SUPPORT_SKILL_IDS = Set.of(
            Assassin.HASTE,
            Bandit.HASTE,
            NightWalker.HASTE,
            Fighter.RAGE,
            DawnWarrior.RAGE,
            Cleric.BLESS,
            Priest.HOLY_SYMBOL,
            Spearman.HYPER_BODY,
            Buccaneer.PIRATES_RAGE,
            Buccaneer.SPEED_INFUSION,
            Corsair.SPEED_INFUSION,
            ThunderBreaker.SPEED_INFUSION,
            Bowmaster.SHARP_EYES,
            Marksman.SHARP_EYES,
            GM.HASTE,
            GM.BLESS,
            GM.HYPER_BODY,
            SuperGM.HASTE,
            SuperGM.HOLY_SYMBOL,
            SuperGM.HYPER_BODY
    );

    private static final List<String> DEATH_REPLIES = List.of(
            "oops im dead", "gg", "rip me", "oww", "i died lol",
            "welp", "ouchh", "nooo", "ok i died", "i'll be right back");
    private static final List<String> AMMO_LOW_MSGS = List.of(
            "running low on ammo",
            "ammo getting low",
            "not much ammo left",
            "gonna need more ammo soon");
    private static final List<String> AMMO_OUT_MSGS = List.of(
            "out of ammo! heading back",
            "no ammo left, coming to you",
            "need ammo!! walking back",
            "im out of ammo, heading to you");
    private static final List<String> MP_POTS_OUT_MSGS = List.of(
            "out of MP pots! heading back",
            "no MP pots left, coming to you",
            "need MP pots!! walking back",
            "im out of MP pots, heading to you");

    /** Check every alive monster on the map; if bot is inside its bounding box, apply a hit. */
    static void tickMobDamage(BotEntry entry, Character bot) {
        Point botPos = bot.getPosition();
        try {
            if (entry.mobHitCooldownMs > 0) {
                entry.mobHitCooldownMs = BotMovementManager.tickDown(entry.mobHitCooldownMs);
                return;
            }
            if (bot.getHp() <= 0) return;

            for (Monster mob : bot.getMap().getAllMonsters()) {
                if (!mob.isAlive()) continue;
                if (isMobTouchingBot(entry, bot, mob)) {
                    applyMobHit(entry, bot, mob);
                    return;
                }
            }
        } finally {
            rememberMobTouchCheck(entry, bot, botPos);
        }
    }

    /**
     * Apply one physical hit from {@code mob} to the bot.
     * Uses the bot's shared character WDEF cache instead of ignoring defense entirely.
     */
    static void applyMobHit(BotEntry entry, Character bot, Monster mob) {
        int dmg = rollPhysicalMobDamage(bot, mob);
        MobHitKnockback kb = resolveMobHitKnockback(bot.getPosition(), mob.getPosition());
        applyDamage(entry, bot, dmg, -1, mob.getId(), kb.direction(), kb.airVelX());
    }

    /**
     * Apply fall damage on landing. Distance is peak-to-landing descent in pixels
     * (BotPhysicsEngine tracks {@code entry.fallPeakPhysY} each airborne tick and
     * passes the delta here). Distance-based rather than velocity-based because
     * terminal velocity is reached after only ~112px of fall, so velocity saturates
     * immediately while real-client damage keeps scaling with drop height.
     *
     * No packet is broadcast below threshold — matches real-client behaviour for
     * small jumps (no DAMAGE_PLAYER observed in monitored-packets logs).
     *
     * Broadcast direction is hardcoded to 0 because every captured real-client fall
     * sample used direction=0. Physics knockback still derives from bot facing so
     * the recoil arc points backward along the bot's movement direction.
     */
    static void applyFallDamage(BotEntry entry, Character bot, float fallDistancePx) {
        if (bot.getHp() <= 0) return;
        if (entry.mobHitCooldownMs > 0) return; // damage invincibility window
        int dmg = fallDamageFromDistance(fallDistancePx);
        if (dmg <= 0) return;
        int dirSign = entry.facingDir >= 0 ? 1 : -1;
        int airVelX = Math.round(-dirSign * scaledOpenStoryStep(cfg.KNOCKBACK_HSPEED));
        applyDamage(entry, bot, dmg, -3, 0, 0, airVelX);
    }

    /**
     * Fall-damage curve — smooth single formula (no hard breakpoint):
     *   dmg = SAT * (1 - exp(-k*u)) + tail*u,    where u = max(0, dist - threshold)
     * Saturating exponential models the steep knee; linear tail models the slow
     * deep-fall growth (damage keeps scaling past the knee in the real client).
     *
     * Grid-search fit against real-client samples — all within ±1 dmg:
     *   916 → 8, 1094 → 27 (a=26), 1132 → 27 (a=28), 1421 → 29, 3861 → 35.
     *
     * O(1): one exp, two multiplies, one add, one round.
     */
    static final float FALL_DIST_THRESHOLD_PX = 890.0f;   // below: 0 dmg, no packet
    static final float FALL_DMG_SAT           = 28.0f;    // asymptote of the knee component
    static final float FALL_KNEE_SHARPNESS    = 0.013f;   // 1/px — larger = sharper knee
    static final float FALL_DMG_PER_PX_TAIL   = 0.0024f;  // linear tail slope (dmg/px)

    static int fallDamageFromDistance(float distancePx) {
        if (distancePx <= FALL_DIST_THRESHOLD_PX) return 0;
        double u = distancePx - FALL_DIST_THRESHOLD_PX;
        double dmg = FALL_DMG_SAT * (1.0 - Math.exp(-FALL_KNEE_SHARPNESS * u))
                + FALL_DMG_PER_PX_TAIL * u;
        return (int) Math.max(1, Math.round(dmg));
    }

    /**
     * Core damage application: HP loss, DAMAGE_PLAYER broadcast, alert pose, knockback.
     * Shared by mob-touch (damageFrom=-1) and fall (damageFrom=-3). Call via helpers
     * {@link #applyMobHit} / {@link #applyFallDamage} so magic numbers stay out of call sites.
     *
     * @param broadcastDirection direction byte in DAMAGE_PLAYER packet (0 or 1).
     *                           Mob-hit: derived from attacker side.
     *                           Fall:    always 0 (observed in real-client samples).
     * @param knockbackAirVelX   signed horizontal impulse for physics knockback (px/tick-step).
     */
    private static void applyDamage(BotEntry entry, Character bot, int dmg,
                                    int damageFrom, int monsterId,
                                    int broadcastDirection, int knockbackAirVelX) {
        Config cc = BotCombatManager.cfg;
        Point botPos = bot.getPosition();

        if (dmg <= 0) {
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.damagePlayer(damageFrom, monsterId, bot.getId(), 0, 0,
                            broadcastDirection, false, 0, false, 0, 0, 0), false);
            entry.mobHitCooldownMs = BotMovementManager.delayAfterCurrentTick(cc.MOB_HIT_COOLDOWN_MS);
            markAlerted(entry);
            return;
        }

        bot.addMPHPAndTriggerAutopot(-dmg, 0);

        bot.getMap().broadcastMessage(bot,
                PacketCreator.damagePlayer(damageFrom, monsterId, bot.getId(), dmg, 0,
                        broadcastDirection, false, 0, false, 0, 0, 0), false);

        entry.mobHitCooldownMs = BotMovementManager.delayAfterCurrentTick(cc.MOB_HIT_COOLDOWN_MS);
        markAlerted(entry);

        if (bot.getHp() <= 0) {
            enterDeadState(entry, bot, true);
            return;
        }

        if (!shouldApplyMobKnockback(entry, bot)) {
            return;
        }

        clearActionState(entry);
        if (entry.inAir) {
            BotPhysicsEngine.applyAirKnockback(entry, bot, knockbackAirVelX);
        } else {
            BotPhysicsEngine.beginKnockback(entry, bot, botPos, -scaledOpenStoryStep(cfg.KNOCKBACK_VFORCE), knockbackAirVelX);
        }
        BotMovementManager.broadcastMovement(entry);
    }

    private static int rollPhysicalMobDamage(Character bot, Monster mob) {
        return BotDefenseDataProvider.getInstance().rollPhysicalTouchDamage(bot, mob);
    }

    private static boolean shouldApplyMobKnockback(BotEntry entry, Character bot) {
        if (entry.climbing || bot.getHp() <= 0) {
            return false;
        }

        Integer stancePercent = bot.getBuffedValue(BuffStat.STANCE);
        if (stancePercent == null || stancePercent <= 0) {
            return true;
        }

        float stanceChance = Math.max(0f, Math.min(1f, stancePercent / 100f));
        return ThreadLocalRandom.current().nextFloat() > stanceChance;
    }

    private static MobHitKnockback resolveMobHitKnockback(Point botPos, Point attackOrigin) {
        boolean attackFromRight = attackOrigin.x > botPos.x;
        int direction = attackFromRight ? 0 : 1;
        int airVelX = Math.round((attackFromRight ? -1f : 1f) * scaledOpenStoryStep(cfg.KNOCKBACK_HSPEED));
        return new MobHitKnockback(direction, airVelX);
    }

    private static float scaledOpenStoryStep(float openStoryStepValue) {
        return openStoryStepValue * (BotMovementManager.cfg.TICK_MS / 8.0f);
    }

    static void enterDeadState(BotEntry entry, Character bot, boolean announceDeath) {
        clearActionState(entry);
        BotPhysicsEngine.markDead(entry, bot);
        BotMovementManager.broadcastMovement(entry);
        entry.deadUntil = System.currentTimeMillis() + cfg.BOT_DEAD_MS;
        entry.lastDeathAtMs = System.currentTimeMillis();   // feeds moodHint (grumpy after dying)
        if (announceDeath) {
            BotManager.getInstance().botSay(bot, BotManager.randomReply(DEATH_REPLIES));
        }
    }

    private static void clearActionState(BotEntry entry) {
        entry.grindTarget = null;
        entry.attackCooldownMs = 0;
        entry.moveWindowMs = 0;
        BotMovementManager.clearNavigationState(entry);
        entry.movementBroadcastValid = false;
    }

    private record MobHitKnockback(int direction, int airVelX) {
    }

    static void rebuildSkillCacheIfNeeded(BotEntry entry, Character bot) {
        int skillSignature = skillCacheSignature(bot);
        if (entry.cachedSkillJob == bot.getJob().getId()
                && entry.cachedSkillLevel == bot.getLevel()
                && entry.cachedSkillSignature == skillSignature) {
            return;
        }

        entry.cachedSkillJob = bot.getJob().getId();
        entry.cachedSkillLevel = bot.getLevel();
        entry.cachedSkillSignature = skillSignature;
        entry.attackSkillId = 0;
        entry.aoeSkillId = 0;
        entry.aoeSkillMobs = 1;
        entry.attackSkillIds.clear();
        entry.healSkillId = 0;
        entry.buffSkillIds.clear();
        entry.summonSkillIds.clear();

        int bestAtkHits = 0;
        int bestAtkPriority = Integer.MIN_VALUE;
        int bestAtkDamage = Integer.MIN_VALUE;
        long bestAoeScore = 0;
        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);

        for (Skill skill : bot.getSkills().keySet()) {
            int lvl = bot.getSkillLevel(skill);
            if (lvl <= 0) continue;

            StatEffect fx = skill.getEffect(lvl);
            int atk = effectiveHitCount(fx);
            int mobs = fx.getMobCount();

            if (isHealSkill(skill.getId())) {
                if (isActiveHealSkill(skill, fx)) {
                    entry.healSkillId = skill.getId();
                }
                continue;  // not an attack skill; offensive use against undead handled in tickSupportHealing
            }

            // Skip skills the equipped weapon can't use, so the bot doesn't pick a wrong-weapon
            // skill (e.g. a knuckle Brawler choosing the free-maxed gun-line Double Shot) as its
            // main attack. When the weapon is unknown (weaponType == null) we don't filter here —
            // the use-time gate in planSkillAttack still blocks the actual cast either way.
            if (isActiveAttackSkill(skill, fx)
                    && (weaponType == null || canUseAttackSkillWithWeapon(skill.getId(), weaponType))) {
                entry.attackSkillIds.add(skill.getId());
                if (mobs >= 2) {
                    long score = (long) Math.max(0, fx.getDamagePercent()) * Math.max(1, atk) * Math.max(1, mobs);
                    if (score > bestAoeScore) {
                        bestAoeScore = score;
                        entry.aoeSkillId = skill.getId();
                        entry.aoeSkillMobs = mobs;
                    }
                } else if (shouldUseAsBestSingleTargetSkill(bot, skill, fx, atk,
                        bestAtkHits, bestAtkPriority, bestAtkDamage, entry.attackSkillId)) {
                    bestAtkHits = atk;
                    bestAtkPriority = singleTargetSkillPriority(bot, skill);
                    bestAtkDamage = fx.getDamage();
                    entry.attackSkillId = skill.getId();
                }
                continue;
            }

            if (isSummonSkill(fx)) {
                // Own bucket, not rebuffable — see BotEntry.summonSkillIds.
                entry.summonSkillIds.add(skill.getId());
                continue;
            }

            if (!isActiveSupportSkill(skill, fx)) continue;
            if (BUFF_BLACKLIST.contains(skill.getId())) continue;
            entry.buffSkillIds.add(skill.getId());
            entry.nextBuffAt.putIfAbsent(skill.getId(), 0L);
        }

        // I/L mages lead with Thunder Bolt as their main attack, not the weak 1st-job Magic Claw
        // (which would otherwise win the single-target slot). Thunder Bolt is an AoE skill, so it's
        // already the cluster pick; this also makes it the single-target attack once learned.
        if (bot.getJob().isA(Job.IL_WIZARD) && entry.attackSkillIds.contains(ILWizard.THUNDERBOLT)) {
            entry.attackSkillId = ILWizard.THUNDERBOLT;
        }
    }

    private static int skillCacheSignature(Character bot) {
        int result = 1;
        for (Map.Entry<Skill, Character.SkillEntry> learned : bot.getSkills().entrySet()) {
            Skill skill = learned.getKey();
            if (skill == null) {
                continue;
            }
            result = 31 * result + skill.getId();
            result = 31 * result + bot.getSkillLevel(skill);
        }
        return result;
    }

    static void tickBuffs(BotEntry entry, Character bot) {
        if (entry.attackCooldownMs > 0) return;
        if (!entry.skillBuffsEnabled) {
            noteSkillBuffDecision(entry, "skill buffs disabled");
            return;
        }
        if (!entry.following && !entry.grinding) {
            noteSkillBuffDecision(entry, "idle (not following or grinding)");
            return;
        }
        if (entry.buffSkillIds.isEmpty()) {
            noteSkillBuffDecision(entry, "no buff skills in cache");
            return;
        }
        if (bot.getMap().getAllMonsters().stream().noneMatch(Monster::isAlive)) return;

        long now = System.currentTimeMillis();
        if (trySupportBuff(entry, bot, now)) {
            return;
        }

        for (int skillId : entry.buffSkillIds) {
            if (now < entry.nextBuffAt.getOrDefault(skillId, 0L)) continue;
            if (bot.skillIsCooling(skillId)) continue;

            Skill skill = SkillFactory.getSkill(skillId);
            int lvl = bot.getSkillLevel(skill);
            if (lvl <= 0) continue;

            StatEffect fx = skill.getEffect(lvl);
            if (!isActiveSupportSkill(skill, fx) || BUFF_BLACKLIST.contains(skill.getId())) {
                continue;
            }
            if (castSupportSkill(entry, bot, skill, fx, now)) {
                return;
            }
        }
        noteSkillBuffDecision(entry, "all skill buffs active or on cooldown");
    }

    private static boolean shouldUseAsBestSingleTargetSkill(Character bot, Skill skill, StatEffect effect,
                                                            int attackCount, int bestAttackCount,
                                                            int bestPriority, int bestDamage,
                                                            int currentBestSkillId) {
        int priority = singleTargetSkillPriority(bot, skill);
        if (priority != bestPriority) {
            return priority > bestPriority;
        }

        int damage = effect != null ? effect.getDamagePercent() : 0;
        int score = damage * attackCount;
        int bestScore = bestDamage * bestAttackCount;
        if (score != bestScore) {
            return score > bestScore;
        }

        return currentBestSkillId == 0 || skill.getId() < currentBestSkillId;
    }

    private static int singleTargetSkillPriority(Character bot, Skill skill) {
        if (skill == null) {
            return Integer.MIN_VALUE;
        }
        if (skill.isBeginnerSkill()) {
            return 0;
        }
        return GameConstants.isInJobTree(skill.getId(), bot.getJob().getId()) ? 2 : 1;
    }

    /**
     * Healing is the cleric bot's top priority: runs before any attack decision (see BotManager tick)
     * and casts whenever the bot itself OR any nearby party member is below
     * {@link Config#SUPPORT_HEAL_TARGET_RATIO}. There is no decision-side cooldown — the only
     * throttle is {@code entry.attackCooldownMs}, which we set from the skill's animation timing so
     * consecutive casts match what a legit client would send (~600ms between Heal packets per the
     * captured monitored-packets-cleric-heal-only.log reference).
     *
     * <p>The cast packet is broadcast even when no undead targets are in range so other clients see
     * the heal animation play (matches real player behaviour when Heal is pressed with no mob in range).
     */
    static boolean tickSupportHealing(BotEntry entry, Character bot) {
        if (entry.attackCooldownMs > 0 || (entry.moveWindowMs > 0 && !entry.inAir)) return false;
        if (!entry.supportHealsEnabled) return false;
        if (!entry.following && !entry.grinding) return false;
        if (entry.healSkillId == 0 || bot.skillIsCooling(entry.healSkillId)) return false;

        Skill skill = SkillFactory.getSkill(entry.healSkillId);
        int lvl = bot.getSkillLevel(skill);
        if (lvl <= 0) return false;
        StatEffect fx = skill.getEffect(lvl);

        // Decision range MUST match the skill's actual WZ hitbox. If we decide to heal based on a
        // looser SUPPORT_RANGE but fx.applyTo() iterates only members inside the heal bbox, a party
        // member outside the bbox but inside SUPPORT_RANGE would never receive HP and the bot would
        // re-cast every tick forever. Anchor both self- and party-checks to fx.calculateBoundingBox.
        Rectangle healBounds = fx.hasBoundingBox()
                ? fx.calculateBoundingBox(bot.getPosition(), bot.isFacingLeft())
                : null;
        boolean selfNeedsHeal = needsHeal(bot);
        boolean partyNeedsHeal = selfNeedsHeal || hasPartyMemberInBoundsNeedingHeal(bot, healBounds);
        List<Monster> undeadTargets = getUndeadMobsInHealRange(bot, fx);
        if (!partyNeedsHeal && undeadTargets.isEmpty()) return false;

        // Jump-heal: when following and the leader has pulled ahead, kick a diagonal jump toward
        // them right before the cast. The top guard already permits casts while inAir, so the
        // heal animation plays mid-flight instead of forcing a planted stand-and-cast.
        boolean jumpHealing = false;
        if (entry.following && !entry.inAir && !entry.climbing && cfg.JUMP_HEAL_LEADER_AHEAD_PX > 0) {
            Character anchor = BotManager.getInstance().resolveFollowAnchor(entry, entry.owner);
            if (anchor != null && anchor != bot && anchor.getMap() == bot.getMap()) {
                int dx = anchor.getPosition().x - bot.getPosition().x;
                if (Math.abs(dx) >= cfg.JUMP_HEAL_LEADER_AHEAD_PX) {
                    BotMovementManager.initiateJump(entry, bot, dx);
                    jumpHealing = true;
                }
            }
        }

        if (!fx.canPaySkillCost(bot) || !fx.applyTo(bot)) return false;

        long now = System.currentTimeMillis();
        BotAttackExecutionProvider.BasicAttackData fallbackAttackData =
                BotAttackExecutionProvider.buildBasicAttackData(bot, bot.getPosition());
        String action = BotAttackExecutionProvider.resolveSkillAttackAction(bot, skill, lvl,
                BotAttackExecutionProvider.getEquippedWeaponType(bot));
        BotAttackExecutionProvider.SkillAttackTiming skillTiming =
                BotAttackExecutionProvider.resolveSkillAttackTiming(skill, action, bot, fallbackAttackData);
        entry.attackCooldownMs = Math.max(entry.attackCooldownMs, skillTiming.cooldownMs());
        if (partyNeedsHeal && fx.getCooldown() > 0) {
            bot.addCooldown(entry.healSkillId, now, fx.getCooldown() * 1000L);
        }

        // Always send the attack/cast packet so the animation plays even when there are no undead
        // to hit — the packet carries an empty targets map in that case, which is what a real client
        // does when a player presses Heal with no mob in range.
        sendHealAttack(entry.healSkillId, lvl, bot, undeadTargets, fallbackAttackData, skillTiming);
        markAlerted(entry);
        entry.moveWindowMs = Math.max(entry.moveWindowMs, cfg.HEAL_MOVE_WINDOW_MS);
        if (!jumpHealing) {
            // Stop walk-in-place: broadcast STAND→ALERT immediately on the heal tick.
            // Skipped on jump-heal — initiateJump already broadcast the airborne stance and
            // zeroing moveDir here would cancel the diagonal air-steering toward the leader.
            entry.moveDir = 0;
            BotMovementManager.broadcastMovement(entry);
        }
        return true;
    }

    private static boolean needsHeal(Character chr) {
        if (chr == null || !chr.isAlive()) return false;
        int maxHp = chr.getCurrentMaxHp();
        if (maxHp <= 0) return false;
        return chr.getHp() < Math.round(maxHp * cfg.SUPPORT_HEAL_TARGET_RATIO);
    }

    /**
     * Sends a skill attack packet targeting undead mobs with Heal damage.
     * Called after fx.applyTo() has already handled the party heal and MP cost,
     * so we build the AttackInfo directly rather than going through attackMonster()
     * (which would re-check MP via canUseSkill and fail).
     */
    private static void sendHealAttack(int healSkillId, int lvl, Character bot,
            List<Monster> undeadTargets,
            BotAttackExecutionProvider.BasicAttackData fallbackAttackData,
            BotAttackExecutionProvider.SkillAttackTiming skillTiming) {
        AttackRoute route = BotAttackExecutionProvider.determineSkillRoute(bot, healSkillId);
        // N in Russt's target multiplier is caster + damaged targets. When no undead are in range
        // the damage profile is unused (numAttacked=0) but we still pass 1 to avoid a divide-by-zero
        // surprise if the profile gets reused elsewhere.
        int healTargetCount = Math.max(1, undeadTargets.size() + 1);
        CombatFormulaProvider.DamageProfile damageProfile = CombatFormulaProvider.getInstance()
                .resolveDamageProfile(bot, healSkillId, lvl, true, healTargetCount);
        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        attack.skill = healSkillId;
        attack.skilllevel = lvl;
        attack.numDamage = 1;
        attack.numAttacked = undeadTargets.size();
        attack.numAttackedAndDamage = (undeadTargets.size() << 4) | 1;
        attack.speed = fallbackAttackData.speed();
        // Real cleric Heal packet (captured in monitored-packets-cleric-heal-only.log) encodes
        // the direction byte as bodyActionId("alert2") = 41 (0x29) so other clients render the
        // caster in the magic-casting "alert2" pose rather than the idle-frame default. The
        // stance byte is the shared facing mask used by every attack-plan builder.
        boolean facingLeft = bot.isFacingLeft();
        BotAttackExecutionProvider.CloseRangePacketFields castFields =
                BotAttackExecutionProvider.mimicCloseRangePacketFields("alert2", "alert2", facingLeft);
        attack.display = castFields.display();
        attack.direction = castFields.bodyActionId();
        attack.stance = BotAttackExecutionProvider.attackPacketStance(facingLeft);
        attack.rangedirection = BotAttackExecutionProvider.attackPacketStance(facingLeft);
        attack.ranged = false;
        attack.magic = damageProfile.magicAttack();
        attack.targets = new HashMap<>();
        for (Monster target : undeadTargets) {
            attack.targets.put(target.getObjectId(),
                    CombatFormulaProvider.getInstance().makeTarget(
                            bot, target, 1, healSkillId, damageProfile, skillTiming.hitDelayMs()));
        }
        BotAttackExecutionProvider.applyAttackRoute(route, attack, bot);
    }

    private static List<Monster> getUndeadMobsInHealRange(Character bot, StatEffect fx) {
        Rectangle bounds = fx.calculateBoundingBox(bot.getPosition(), bot.isFacingLeft());
        List<MapObject> objects = bot.getMap().getMapObjectsInRect(bounds, Arrays.asList(MapObjectType.MONSTER));
        List<Monster> undead = new ArrayList<>();
        int cap = fx.getMobCount();
        for (MapObject mo : objects) {
            Monster m = (Monster) mo;
            if (m.isAlive() && m.getStats().isUndead()) {
                undead.add(m);
                if (undead.size() >= cap) break;
            }
        }
        return undead;
    }

    static Monster findGrindTarget(Character bot) {
        return findGrindTarget(null, bot);
    }

    /** Returns the most convenient reachable target (deterministic — closest/best score wins). */
    static Monster findGrindTarget(BotEntry entry, Character bot) {
        long startedAt = System.nanoTime();
        try {
            Point botPos = bot.getPosition();
            double rangeSq = (double) BotCombatManager.cfg.GRIND_SEEK_RANGE * BotCombatManager.cfg.GRIND_SEEK_RANGE;
            Foothold botFoothold = findGroundFoothold(botPos, bot);
            List<Monster> candidates = aliveMonstersInRange(bot, botPos, rangeSq);
            if (candidates.isEmpty()) return null;

            List<ScoredGrindTarget> scoredTargets = scoreGrindTargets(entry, bot, botPos, botFoothold, candidates);
            if (scoredTargets.isEmpty()) {
                return null;
            }

            scoredTargets.sort(Comparator
                    .comparingLong(ScoredGrindTarget::graphCost)
                    .thenComparingLong(ScoredGrindTarget::localScore)
                    .thenComparingDouble(ScoredGrindTarget::distanceSq));
            List<ScoredGrindTarget> reachableTargets = scoredTargets.stream()
                    .filter(target -> target.graphCost() < UNREACHABLE_GRAPH_COST)
                    .toList();
            List<ScoredGrindTarget> selectable = reachableTargets.isEmpty() ? scoredTargets : reachableTargets;
            if (selectable.getFirst().graphCost() >= UNREACHABLE_GRAPH_COST) {
                return null;
            }

            return selectable.get(0).monster();
        } finally {
            BotPerformanceMonitor.record("combat-target-search", System.nanoTime() - startedAt);
        }
    }

    /**
     * Patrol mode: find the best grind target restricted to the patrol region and its
     * immediate neighbours (1 graph hop). Tries the home region first; expands to
     * adjacent regions only when the home region has no candidates.
     */
    static Monster findPatrolTarget(BotEntry entry, Character bot) {
        long startedAt = System.nanoTime();
        try {
            if (entry == null || bot == null || entry.patrolRegionId < 0) {
                return null;
            }
            Point botPos = bot.getPosition();
            double rangeSq = (double) cfg.GRIND_SEEK_RANGE * cfg.GRIND_SEEK_RANGE;
            Foothold botFoothold = findGroundFoothold(botPos, bot);
            List<Monster> candidates = aliveMonstersInRange(bot, botPos, rangeSq);
            if (candidates.isEmpty()) {
                return null;
            }
            GrindGraphContext graphContext = GrindGraphContext.resolve(entry, bot, botPos);
            if (!graphContext.available()) {
                return null;
            }
            BotNavigationGraph graph = graphContext.graph();
            MapleMap map = graphContext.map();
            int patrolId = entry.patrolRegionId;

            // 1-hop expansion: only inter-region edges count as a hop. Self-loop edges
            // (intra-region portals where fromRegionId == toRegionId) are free traversals
            // within the patrol region itself — A* uses them to shortcut long walks but
            // they don't expose new regions, so skip them here to keep intent explicit.
            Set<Integer> adjacentIds = graph.getMutualAdjacentRegionIds(patrolId);

            // Phase 1: home region only
            List<Monster> filtered = new ArrayList<>();
            for (Monster m : candidates) {
                if (graph.findRegionId(map, m.getPosition()) == patrolId) {
                    filtered.add(m);
                }
            }
            // Phase 2: expand to adjacent if home region is empty
            if (filtered.isEmpty()) {
                for (Monster m : candidates) {
                    int mId = graph.findRegionId(map, m.getPosition());
                    if (mId == patrolId || adjacentIds.contains(mId)) {
                        filtered.add(m);
                    }
                }
            }
            if (filtered.isEmpty()) {
                return null;
            }

            List<ScoredGrindTarget> scored = scoreGrindTargets(entry, bot, botPos, botFoothold, filtered);
            if (scored.isEmpty()) {
                return null;
            }
            scored.sort(Comparator
                    .comparingLong(ScoredGrindTarget::graphCost)
                    .thenComparingLong(ScoredGrindTarget::localScore)
                    .thenComparingDouble(ScoredGrindTarget::distanceSq));
            List<ScoredGrindTarget> reachable = scored.stream()
                    .filter(t -> t.graphCost() < UNREACHABLE_GRAPH_COST)
                    .toList();
            List<ScoredGrindTarget> selectable = reachable.isEmpty() ? scored : reachable;
            if (selectable.getFirst().graphCost() >= UNREACHABLE_GRAPH_COST) {
                return null;
            }
            return selectable.get(0).monster();
        } finally {
            BotPerformanceMonitor.record("combat-target-search", System.nanoTime() - startedAt);
        }
    }

    /** Follow mode should only attack local mobs; it should not run pathfinding or chase across the map. */
    static Monster findFollowAttackTarget(BotEntry entry, Character bot) {
        long startedAt = System.nanoTime();
        try {
            Point botPos = bot.getPosition();
            double range = Math.max(CLIENT_PROJECTILE_BASE_RANGE + passiveProjectileRangeBonus(bot),
                    BotCombatManager.cfg.ATTACK_RANGE_X + BotCombatManager.cfg.ATTACK_JUMP_X_EXTRA);
            List<Monster> candidates = aliveMonstersInRange(bot, botPos, range * range);
            if (candidates.isEmpty()) {
                return null;
            }

            Foothold botFoothold = findGroundFoothold(botPos, bot);
            GrindGraphContext graphContext = GrindGraphContext.resolve(entry, bot, botPos);
            List<ScoredGrindTarget> localTargets = new ArrayList<>();
            for (Monster candidate : candidates) {
                if (!isLocalCombatTarget(graphContext, bot, botFoothold, candidate)
                        && !isImmediateProjectileTarget(entry, bot, candidate)) {
                    continue;
                }
                long localScore = grindTargetScore(bot, botPos, botFoothold, candidate)
                        - aoeClusterBonus(entry, candidate, candidates)
                    - undeadTargetBonus(entry, candidate);
                localTargets.add(new ScoredGrindTarget(candidate, localScore, localScore,
                        candidate.getPosition().distanceSq(botPos)));
            }
            return pickFromBestTargets(localTargets);
        } finally {
            BotPerformanceMonitor.record("combat-target-search", System.nanoTime() - startedAt);
        }
    }

    static boolean isReachableGrindTarget(BotEntry entry, Character bot, Monster target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (entry == null || bot == null) {
            return true;
        }

        GrindGraphContext graphContext = GrindGraphContext.resolve(entry, bot, bot.getPosition());
        if (isImmediateProjectileTarget(entry, bot, target)) {
            return true;
        }
        return !graphContext.available() || graphTargetCost(graphContext, target) < UNREACHABLE_GRAPH_COST;
    }

    /** Grind-seek range squared, scaled by the bot's aggressive/careful chase factor. */
    static double seekRangeSq(BotEntry entry) {
        double r = cfg.GRIND_SEEK_RANGE * (entry != null ? entry.chaseFactor : 1f);
        return r * r;
    }

    static AttackPlan planAttack(BotEntry entry, Character bot, Monster target) {
        long startedAt = System.nanoTime();
        try {
            List<AttackPlan> candidates = new ArrayList<>(3);

            // "melee only" / "conserve mp": skip attack skills, fall back to the basic attack.
            if (!entry.meleeOnly) {
                if (entry.forcedSkillId != 0) {
                    // "spam <skill>": use only the forced skill when it can fire right now; if it
                    // can't (range/MP/ammo/cooldown), fall through to the basic attack below.
                    AttackPlan forced = planSkillAttack(entry, bot, target, entry.forcedSkillId);
                    if (forced != null) {
                        return forced;
                    }
                } else {
                    for (int skillId : cachedAttackSkillIds(entry)) {
                        AttackPlan skillAttack = planSkillAttack(entry, bot, target, skillId);
                        if (skillAttack != null) {
                            candidates.add(skillAttack);
                        }
                    }
                }
            }

            AttackPlan basicAttack = planBasicAttack(bot, target);
            if (basicAttack != null) {
                candidates.add(basicAttack);
            }
            return selectBestAttackPlan(bot, candidates);
        } finally {
            BotPerformanceMonitor.record("combat-plan", System.nanoTime() - startedAt);
        }
    }

    private static List<Integer> cachedAttackSkillIds(BotEntry entry) {
        if (!entry.attackSkillIds.isEmpty()) {
            return entry.attackSkillIds;
        }

        List<Integer> skillIds = new ArrayList<>(2);
        if (entry.attackSkillId != 0) {
            skillIds.add(entry.attackSkillId);
        }
        if (entry.aoeSkillId != 0 && entry.aoeSkillId != entry.attackSkillId) {
            skillIds.add(entry.aoeSkillId);
        }
        return skillIds;
    }

    private static AttackPlan planBasicAttack(Character bot, Monster target) {
        BotAttackExecutionProvider.BasicAttackData basicAttackData = buildBasicAttackData(bot, target);
        Monster effective = resolveEffectivePrimary(bot, target, basicAttackData.hitBox());
        if (effective != target) {
            basicAttackData = buildBasicAttackData(bot, effective);
        }
        if (!doesHitBoxIntersectMonster(basicAttackData.hitBox(), effective)) {
            // Original facing is dry. Try the opposite facing: resolveEffectivePrimary only
            // scans the forward hemisphere, so a closer mob on the other side would have
            // been ignored. If one exists, pivot to it.
            Monster pivoted = findReachableOnOppositeFacing(bot, target);
            if (pivoted == null) {
                return null;
            }
            basicAttackData = buildBasicAttackData(bot, pivoted);
            effective = pivoted;
        }
        int numDamage = shadowPartnerHitMultiplier(bot, basicAttackData.route());
        return new AttackPlan(0, 0, numDamage, basicAttackData.hitBox(), List.of(effective), basicAttackData.route(),
                basicAttackData.display(), basicAttackData.direction(), basicAttackData.rangedDirection(), basicAttackData.stance(),
                basicAttackData.speed(), basicAttackData.hitDelayMs(), basicAttackData.cooldownMs(),
                damageWeaponTypeForAction(0, BotAttackExecutionProvider.getEquippedWeaponType(bot), basicAttackData.action()));
    }

    private static Monster findReachableOnOppositeFacing(Character bot, Monster originalTarget) {
        if (bot == null || originalTarget == null
                || bot.getPosition() == null || originalTarget.getPosition() == null) {
            return null;
        }
        Point botPos = bot.getPosition();
        Point mirroredPos = new Point(2 * botPos.x - originalTarget.getPosition().x,
                originalTarget.getPosition().y);
        BotAttackExecutionProvider.BasicAttackData oppositeData =
                BotAttackExecutionProvider.buildBasicAttackData(bot, mirroredPos);
        Rectangle oppositeHitBox = oppositeData.hitBox();
        if (oppositeHitBox == null) {
            return null;
        }
        Monster mirrored = resolveEffectivePrimary(bot, originalTarget, oppositeHitBox);
        return mirrored != originalTarget ? mirrored : null;
    }

    private static AttackPlan selectBestAttackPlan(Character bot, List<AttackPlan> candidates) {
        List<PlanScore> scores = new ArrayList<>(candidates.size());
        for (AttackPlan candidate : candidates) {
            scores.add(scoreAttackPlan(bot, candidate));
        }

        // Bias toward skills: unless the basic attack already one-shots its target, drop it from
        // contention so a usable attack skill is picked instead. The basic attack stays only when
        // it can finish the mob in a single hit (no point spending MP/ammo a free swing would
        // save) or when no skill plan is available at all (fallback). Without this, the basic
        // attack (skillId 0) wins every DPS tie and every guaranteed-kill comparison against an
        // equal-cooldown skill, so skills like Lucky Seven effectively never fire.
        boolean basicOneShots = scores.stream()
                .anyMatch(score -> score.plan.skillId == 0 && score.minimumKillsFullHpTargets);
        boolean hasSkillPlan = scores.stream().anyMatch(score -> score.plan.skillId != 0);
        if (!basicOneShots && hasSkillPlan) {
            scores.removeIf(score -> score.plan.skillId == 0);
        }

        boolean hasGuaranteedFullHpKill = scores.stream().anyMatch(score -> score.minimumKillsFullHpTargets);
        PlanScore best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (PlanScore score : scores) {
            // Keep a plan in contention if it guarantees a full-HP kill OR it's a big AoE (>=3 mobs):
            // clearing a cluster of 3+ beats one-shotting a single mob, so the kill-filter must not
            // drop a big AoE just because some single-target plan happens to one-shot one mob. The
            // useful-DPS comparison below (summed over all targets) then favors the cluster clear.
            boolean bigAoe = score.plan.targets.size() >= AOE_STRONG_PREFER_MOBS;
            if (hasGuaranteedFullHpKill && !score.minimumKillsFullHpTargets && !bigAoe) {
                continue;
            }
            double candidateScore = hasGuaranteedFullHpKill ? score.usefulDps : score.rawDps;
            if (best == null
                    || candidateScore > bestScore
                    || (Double.compare(candidateScore, bestScore) == 0 && isBetterTieBreak(score.plan, best.plan))) {
                best = score;
                bestScore = candidateScore;
            }
        }
        return best != null ? best.plan : null;
    }

    private record PlanScore(AttackPlan plan, double usefulDamage, double rawDamage, double usefulDps, double rawDps,
                             boolean minimumKillsFullHpTargets) {
    }

    private static PlanScore scoreAttackPlan(Character bot, AttackPlan attackPlan) {
        CombatFormulaProvider.DamageProfile damageProfile = CombatFormulaProvider.getInstance().resolveDamageProfile(
                bot, attackPlan.skillId, attackPlan.skillLevel,
                attackPlan.route == AttackRoute.MAGIC, attackPlan.damageWeaponType);
        double usefulDamage = 0.0d;
        double rawDamage = 0.0d;
        boolean minimumKillsFullHpTargets = !attackPlan.targets.isEmpty();
        for (Monster target : attackPlan.targets) {
            double expectedDamage = CombatFormulaProvider.getInstance().estimateExpectedDamage(bot, target, attackPlan.numDamage,
                    attackPlan.skillId, damageProfile);
            usefulDamage += capDamageByCurrentHp(expectedDamage, target);
            rawDamage += expectedDamage;

            int fullHp = target.getMaxHp();
            if (fullHp <= 0) {
                fullHp = target.getHp();
            }
            int minimumDamage = CombatFormulaProvider.getInstance().estimateMinimumDamage(bot, target, attackPlan.numDamage,
                    attackPlan.skillId, damageProfile);
            if (fullHp <= 0 || minimumDamage < fullHp) {
                minimumKillsFullHpTargets = false;
            }
        }
        double animationSeconds = Math.max(1, attackPlan.cooldownMs) / 1000.0d;
        return new PlanScore(attackPlan, usefulDamage, rawDamage,
                usefulDamage / animationSeconds, rawDamage / animationSeconds, minimumKillsFullHpTargets);
    }

    private static boolean isBetterTieBreak(AttackPlan candidate, AttackPlan currentBest) {
        if (candidate.cooldownMs != currentBest.cooldownMs) {
            return candidate.cooldownMs < currentBest.cooldownMs;
        }
        return candidate.skillId < currentBest.skillId;
    }

    private static double estimatePlanDamage(Character bot, AttackPlan attackPlan) {
        return scoreAttackPlan(bot, attackPlan).usefulDamage;
    }

    private static double capDamageByCurrentHp(double expectedDamage, Monster target) {
        if (target == null) {
            return expectedDamage;
        }
        int currentHp = target.getHp();
        if (currentHp <= 0) {
            return 0.0d;
        }
        return Math.min(expectedDamage, currentHp);
    }

    static boolean isTargetInAttackRange(AttackPlan attackPlan, Character bot, Monster target) {
        if (attackPlan == null) {
            return false;
        }
        if (attackPlan.hasHitBox()) {
            return doesHitBoxIntersectMonster(attackPlan.hitBox, target);
        }
        return isBasicAttackInRange(bot.getPosition(), target.getPosition());
    }

    static boolean canUseAttackPlanNow(BotEntry entry, WeaponType weaponType, AttackPlan attackPlan) {
        if (entry == null || attackPlan == null) {
            return false;
        }
        if (!entry.inAir) {
            return true;
        }
        return !isAirborneRangedAttackBlockedWeapon(weaponType) || attackPlan.route != AttackRoute.RANGED;
    }

    static boolean isTargetJumpable(BotMovementProfile movementProfile, boolean closeRangeRoute, Point botPos, Point targetPos) {
        if (!closeRangeRoute || botPos == null || targetPos == null) {
            return false;
        }

        int dx = Math.abs(targetPos.x - botPos.x);
        if (dx > BotCombatManager.cfg.ATTACK_RANGE_X + BotCombatManager.cfg.ATTACK_JUMP_X_EXTRA) {
            return false;
        }

        int dy = botPos.y - targetPos.y;
        int maxJumpHeight = Math.max(BotCombatManager.cfg.ATTACK_JUMP_Y,
                (int) Math.ceil(BotPhysicsEngine.calculateMaxJumpHeight(movementProfile)));
        return dy > BotCombatManager.cfg.ATTACK_RANGE_Y && dy <= maxJumpHeight;
    }

    static boolean isTargetJumpable(boolean closeRangeRoute, Point botPos, Point targetPos) {
        return isTargetJumpable(BotMovementProfile.base(), closeRangeRoute, botPos, targetPos);
    }

    static void attackMonster(BotEntry entry, Character bot, AttackPlan attackPlan) {
        if (entry.attackCooldownMs > 0) {
            return;
        }
        if (entry.noAmmo) {
            return;
        }
        if (attackPlan.skillId != 0 && !canUseSkill(bot, attackPlan.skillId, attackPlan.skillLevel)) {
            return;
        }
        if (!canUseAttackPlanNow(entry, BotAttackExecutionProvider.getEquippedWeaponType(bot), attackPlan)) {
            return;
        }

        int numAttacked = attackPlan.targets.size();
        AbstractDealDamageHandler.AttackInfo attack = new AbstractDealDamageHandler.AttackInfo();
        attack.skill = attackPlan.skillId;
        attack.skilllevel = attackPlan.skillLevel;
        attack.numDamage = attackPlan.numDamage;
        attack.numAttacked = numAttacked;
        attack.numAttackedAndDamage = (numAttacked << 4) | attackPlan.numDamage;
        attack.speed = attackPlan.speed;
        attack.stance = attackPlan.stance; // Historical server name: packet byte 3.
        attack.display = attackPlan.display;
        attack.direction = attackPlan.direction; // Historical server name: packet byte 2.
        attack.rangedirection = attackPlan.rangedDirection; // Extra ranged byte after speed.
        attack.ranged = attackPlan.route == AttackRoute.RANGED;
        CombatFormulaProvider.DamageProfile damageProfile = CombatFormulaProvider.getInstance().resolveDamageProfile(
                bot, attackPlan.skillId, attackPlan.skillLevel,
                attackPlan.route == AttackRoute.MAGIC, attackPlan.damageWeaponType);
        attack.magic = damageProfile.magicAttack();
        attack.targets = new HashMap<>();

        for (Monster target : attackPlan.targets) {
            attack.targets.put(target.getObjectId(),
                    CombatFormulaProvider.getInstance().makeTarget(bot, target, attackPlan.numDamage,
                            attackPlan.skillId, damageProfile, attackPlan.hitDelayMs));
        }
        recordCombatTelemetry(entry, attack.targets);
        if (attackPlan.skillId == ChiefBandit.MESO_EXPLOSION && !attackPlan.targets.isEmpty()) {
            // Detonate the mesos Pickpocket dropped near the target; applyAttack() removes them.
            attack.explodedMesos = detonatableMesoOids(bot, attackPlan.targets.get(0));
        }

        BotAttackExecutionProvider.applyAttackRoute(attackPlan.route, attack, bot);
        entry.attackCooldownMs = Math.max(entry.attackCooldownMs, attackPlan.cooldownMs);
        rememberAttackFacing(entry, attackPlan.stance);
        markAlerted(entry);
    }

    /** Accumulates damage/hit/miss totals for the "dps?" command (0-damage lines are misses). */
    private static void recordCombatTelemetry(BotEntry entry,
            java.util.Map<Integer, AbstractDealDamageHandler.AttackTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        if (entry.dpsWindowStartMs == 0L) {
            entry.dpsWindowStartMs = System.currentTimeMillis();
        }
        for (AbstractDealDamageHandler.AttackTarget t : targets.values()) {
            if (t == null || t.damageLines() == null) {
                continue;
            }
            for (int dmg : t.damageLines()) {
                if (dmg > 0) {
                    entry.dpsDamage += dmg;
                    entry.dpsHits++;
                } else {
                    entry.dpsMisses++;
                }
            }
        }
    }

    /** OIDs of meso drops near {@code target} that Meso Explosion can detonate (Pickpocket-dropped). */
    private static List<Integer> detonatableMesoOids(Character bot, Monster target) {
        MapleMap map = bot.getMap();
        if (map == null || target == null) {
            return List.of();
        }
        List<Integer> oids = new ArrayList<>();
        for (MapObject o : map.getMapObjectsInRange(target.getPosition(),
                MESO_EXPLOSION_RADIUS_SQ, List.of(MapObjectType.ITEM))) {
            if (o instanceof MapItem mi && mi.getMeso() > 0 && !mi.isPickedUp()) {
                oids.add(mi.getObjectId());
                if (oids.size() >= MESO_EXPLOSION_MAX_MESOS) {
                    break;
                }
            }
        }
        return oids;
    }

    /** Resolves a learned attack-skill id from a (partial, case-insensitive) name, or 0 if none. */
    static int resolveAttackSkillByName(BotEntry entry, Character bot, String query) {
        if (query == null) {
            return 0;
        }
        rebuildSkillCacheIfNeeded(entry, bot);
        String q = query.toLowerCase().trim();
        if (q.isEmpty()) {
            return 0;
        }
        int best = 0;
        String bestName = null;
        for (int id : entry.attackSkillIds) {
            String name = SkillFactory.getSkillName(id);
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase();
            if (lower.equals(q)) {
                return id; // exact match wins
            }
            if (lower.contains(q) && (bestName == null || name.length() < bestName.length())) {
                best = id;
                bestName = name;
            }
        }
        return best;
    }

    /** Display names of the bot's learned attack skills, for the "spam" command's help reply. */
    static List<String> listAttackSkillNames(BotEntry entry, Character bot) {
        rebuildSkillCacheIfNeeded(entry, bot);
        List<String> names = new ArrayList<>();
        for (int id : entry.attackSkillIds) {
            String name = SkillFactory.getSkillName(id);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    static void rememberAttackFacing(BotEntry entry, int attackPacketStance) {
        entry.facingDir = BotAttackExecutionProvider.facingDirFromAttackPacketStance(attackPacketStance);
        BotPhysicsEngine.syncCharacterState(entry);
    }

    static void tickActionLock(BotEntry entry) {
        if (entry.attackCooldownMs > 0) {
            entry.attackCooldownMs = BotMovementManager.tickDown(entry.attackCooldownMs);
        } else if (entry.moveWindowMs > 0) {
            // Movement window: animation done, bot may walk but not attack yet.
            entry.moveWindowMs = BotMovementManager.tickDown(entry.moveWindowMs);
        }
    }

    // Matches maplestory-wasm CharLook::set_alerted(5000): called on attack, skill cast, and
    // damage taken. Always an absolute reset to now+5s (never additive), mirroring TimedBool::set_for.
    private static final long ALERT_DURATION_MS = 5000L;

    static void markAlerted(BotEntry entry) {
        entry.alertedUntilMs = System.currentTimeMillis() + ALERT_DURATION_MS;
        scheduleAlertReset(entry);
    }

    // Ensures the bot broadcasts a fresh STAND packet when the alert timer expires, even if
    // it has stopped moving in the meantime (otherwise the last-sent ALERT wire stance sticks).
    // Self-reschedules if markAlerted extended the deadline while we were waiting.
    private static void scheduleAlertReset(BotEntry entry) {
        if (entry.alertResetScheduled) return;
        entry.alertResetScheduled = true;
        long delay = Math.max(50L, entry.alertedUntilMs - System.currentTimeMillis() + 100L);
        BotManager.after(delay, () -> {
            long now = System.currentTimeMillis();
            if (now < entry.alertedUntilMs) {
                entry.alertResetScheduled = false;
                scheduleAlertReset(entry);
                return;
            }
            entry.alertResetScheduled = false;
            try {
                if (entry.bot != null) entry.bot.broadcastStance();
            } catch (Throwable ignored) {}
        });
    }

    private static AttackPlan planSkillAttack(BotEntry entry, Character bot, Monster primaryTarget, int skillId) {
        if (skillId == 0 || bot.skillIsCooling(skillId)) {
            return null;
        }

        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return null;
        }
        int skillLevel = bot.getSkillLevel(skill);
        if (skillLevel <= 0) {
            return null;
        }
        // Meso Explosion only works once Pickpocket (auto-cast via the buff loop) has dropped mesos
        // near the target; otherwise skip it so the bot uses a normal attack.
        if (skillId == ChiefBandit.MESO_EXPLOSION && detonatableMesoOids(bot, primaryTarget).isEmpty()) {
            return null;
        }

        StatEffect effect = skill.getEffect(skillLevel);
        if (!effect.canPaySkillCost(bot)) {
            return null;
        }
        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        if (!canUseAttackSkillWithWeapon(skillId, weaponType)) {
            return null;
        }
        AttackRoute route = BotAttackExecutionProvider.determineSkillRoute(bot, skillId);
        // Ammo gate: ranged skills with bulletCount need that many arrows/stars/bullets in
        // the bot's USE inventory. canPaySkillCost only covers MP/HP. countAmmo returns
        // MAX_VALUE for non-ammo weapons and while Soul Arrow / Shadow Claw are active.
        // Avenger / Iron Arrow set bulletConsume (e.g. 3 for Avenger) for ammo cost without
        // changing the visible projectile count, so use the larger of the two. Shadow
        // Partner doubles the actual consume (see RangedAttackHandler.bulletConsume *= 2).
        int ammoCost = Math.max(effect.getBulletCount(), effect.getBulletConsume())
                * shadowPartnerHitMultiplier(bot, route);
        if (ammoCost > 0 && route == AttackRoute.RANGED
                && countAmmo(bot, weaponType) < ammoCost) {
            return null;
        }
        if (isStrikePointAnchoredAoeSkill(skillId)) {
            primaryTarget = resolveStrikePointPrimaryByBasicWeapon(bot, primaryTarget, route);
        }
        Rectangle hitBox = calculateSkillHitBox(effect, bot, primaryTarget, route, skillId);
        if (hitBox == null) {
            return null;
        }

        if (isStrikePointAnchoredAoeSkill(skillId)
                && !isPrimaryReachableByBasicWeapon(bot, primaryTarget, route)) {
            return null;
        }

        if (!isStrikePointAnchoredAoeSkill(skillId)) {
            primaryTarget = resolveEffectivePrimary(bot, primaryTarget, hitBox);
        }
        if (!doesHitBoxIntersectMonster(hitBox, primaryTarget)) {
            return null;
        }

        int attackCount = effectiveHitCount(effect) * shadowPartnerHitMultiplier(bot, route);
        if (!BotAttackExecutionProvider.canUseRangedAttackRoute(route, weaponType, bot.getPosition(), primaryTarget.getPosition())) {
            return null;
        }
        boolean facingLeft = primaryTarget.getPosition().x < bot.getPosition().x;
        BotAttackExecutionProvider.BasicAttackData fallbackAttackData = buildBasicAttackData(bot, primaryTarget);
        BotAttackDataProvider.AttackAnimationSpec attackSpec = BotAttackDataProvider.getInstance().getBasicAttackSpec(weaponType);
        String action = BotAttackExecutionProvider.resolveSkillAttackAction(bot, skill, skillLevel, weaponType);
        String fallbackAction = attackSpec.primaryAction();
        BotAttackExecutionProvider.CloseRangePacketFields closeRangePacketFields = route == AttackRoute.CLOSE
                ? BotAttackExecutionProvider.mimicCloseRangePacketFields(action, fallbackAction, facingLeft)
                : null;
        int direction = route == AttackRoute.CLOSE
                ? closeRangePacketFields.bodyActionId()
                : BotAttackExecutionProvider.bodyActionId(action, fallbackAction, weaponType);
        BotAttackExecutionProvider.SkillAttackTiming skillTiming =
                BotAttackExecutionProvider.resolveSkillAttackTiming(skill, action, bot, fallbackAttackData);
        List<Monster> targets = collectTargetsInHitBox(bot, primaryTarget, hitBox, Math.max(1, effect.getMobCount()));
        if (skillId == DragonKnight.DRAGON_ROAR && !canUseDragonRoarPlan(bot, targets.size())) {
            return null;
        }
        return new AttackPlan(skillId, skillLevel, attackCount, hitBox, targets,
                route, route == AttackRoute.CLOSE ? closeRangePacketFields.display() : 0,
                direction, direction,
                BotAttackExecutionProvider.attackPacketStance(facingLeft),
                fallbackAttackData.speed(), skillTiming.hitDelayMs(), skillTiming.cooldownMs(),
                damageWeaponTypeForAction(skillId, weaponType, action));
    }

    private static boolean canUseDragonRoarPlan(Character bot, int targetCount) {
        int maxHp = bot.getCurrentMaxHp();
        if (maxHp <= 0 || bot.getHp() * 2 <= maxHp) {
            return false;
        }
        return targetCount >= DRAGON_ROAR_MIN_TARGETS_WITHOUT_HEALER || hasNearbyHealSkillAlly(bot);
    }

    private static boolean hasNearbyHealSkillAlly(Character bot) {
        for (Character member : getNearbyPartyMembers(bot)) {
            if (member.getSkillLevel(Cleric.HEAL) > 0 || member.getSkillLevel(SuperGM.HEAL_PLUS_DISPEL) > 0) {
                return true;
            }
        }
        return false;
    }

    private static WeaponType damageWeaponTypeForAction(int skillId, WeaponType equippedWeaponType, String action) {
        WeaponType skillForcedWeaponType = switch (skillId) {
            case DragonKnight.SPEAR_CRUSHER -> WeaponType.SPEAR_STAB;
            case DragonKnight.POLE_ARM_CRUSHER -> WeaponType.POLE_ARM_STAB;
            case DragonKnight.SPEAR_DRAGON_FURY -> WeaponType.SPEAR_SWING;
            case DragonKnight.POLE_ARM_DRAGON_FURY -> WeaponType.POLE_ARM_SWING;
            default -> null;
        };
        if (skillForcedWeaponType != null || action == null || equippedWeaponType == null) {
            return skillForcedWeaponType;
        }

        boolean stab = action.startsWith("stab");
        boolean swing = action.startsWith("swing");
        if (!stab && !swing) {
            return null;
        }

        return switch (equippedWeaponType) {
            case SPEAR_STAB, SPEAR_SWING -> stab ? WeaponType.SPEAR_STAB : WeaponType.SPEAR_SWING;
            case POLE_ARM_SWING, POLE_ARM_STAB -> stab ? WeaponType.POLE_ARM_STAB : WeaponType.POLE_ARM_SWING;
            case GENERAL1H_SWING, GENERAL1H_STAB -> stab ? WeaponType.GENERAL1H_STAB : WeaponType.GENERAL1H_SWING;
            case GENERAL2H_SWING, GENERAL2H_STAB -> stab ? WeaponType.GENERAL2H_STAB : WeaponType.GENERAL2H_SWING;
            default -> null;
        };
    }

    static boolean canUseAttackSkillWithWeapon(int skillId, WeaponType weaponType) {
        return switch (skillId) {
            case DragonKnight.SPEAR_CRUSHER, DragonKnight.SPEAR_DRAGON_FURY -> isSpearWeapon(weaponType);
            case DragonKnight.POLE_ARM_CRUSHER, DragonKnight.POLE_ARM_DRAGON_FURY -> isPolearmWeapon(weaponType);
            // The Pirate 1st job is shared between the gun and knuckle lines, and maxPreviousJobSkills
            // free-maxes BOTH lines' skills on advancement. Firing the wrong-weapon skill routes a
            // "shoot" action into a melee broadcast (or a punch into a ranged one) and crashes watching
            // v83 clients. Gate the weapon-bound 1st-job attacks to their weapon.
            case Pirate.DOUBLE_SHOT -> weaponType == WeaponType.GUN;
            case Pirate.FLASH_FIST -> weaponType == WeaponType.KNUCKLE;
            default -> true;
        };
    }

    private static boolean isSpearWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.SPEAR_STAB || weaponType == WeaponType.SPEAR_SWING;
    }

    private static boolean isPolearmWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.POLE_ARM_SWING || weaponType == WeaponType.POLE_ARM_STAB;
    }

    private static Rectangle calculateSkillHitBox(StatEffect effect, Character bot, Monster primaryTarget, AttackRoute route, int skillId) {
        boolean facingLeft = primaryTarget.getPosition().x < bot.getPosition().x;
        if (effect.hasBoundingBox()) {
            Point anchor = isStrikePointAnchoredAoeSkill(skillId)
                    ? primaryTarget.getPosition()
                    : bot.getPosition();
            return effect.calculateBoundingBox(anchor, facingLeft);
        }

        return fallbackSkillHitBox(effect, bot, facingLeft, route, skillId);
    }

    static boolean isStrikePointAnchoredAoeSkill(int skillId) {
        return STRIKE_POINT_ANCHORED_AOE_SKILL_IDS.contains(skillId);
    }

    // Caller (fallbackSkillHitBox) already gates on route == CLOSE; we no longer require
    // the bot's basic route to be CLOSE so bow/crossbow Power Knockback (a melee swing on
    // the client) gets the proper rectangular reach instead of falling through to the
    // 400 px ranged projectile box.
    static Rectangle fallbackCloseRangeSkillHitBox(StatEffect effect, Character bot, boolean facingLeft) {
        if (effect == null || bot == null) {
            return null;
        }

        Point origin = bot.getPosition();
        int horizontalRange = Math.max(cfg.ATTACK_RANGE_X, effect.getRange());
        int top = origin.y - cfg.ATTACK_RANGE_Y;
        int height = cfg.ATTACK_RANGE_Y + cfg.ATTACK_DOWN_MAX;
        int left = facingLeft ? origin.x - horizontalRange : origin.x;
        return new Rectangle(left, top, horizontalRange, height);
    }

    static Rectangle fallbackSkillHitBox(StatEffect effect, Character bot, boolean facingLeft, AttackRoute route, int skillId) {
        if (route == AttackRoute.CLOSE) {
            return fallbackCloseRangeSkillHitBox(effect, bot, facingLeft);
        }
        if (effect == null || bot == null) {
            return null;
        }

        ProjectileVerticalReach reach = PIERCE_LINE_PROJECTILE_REACH.get(skillId);
        if (reach != null) {
            return clientProjectileHitBox(bot, facingLeft, projectileRangeScale(effect),
                    reach.yAbove(), reach.yBelow());
        }
        return clientProjectileHitBox(bot, facingLeft, projectileRangeScale(effect));
    }

    static Rectangle clientProjectileHitBox(Character bot, boolean facingLeft, float horizontalScale) {
        return clientProjectileHitBox(bot, facingLeft, horizontalScale,
                CLIENT_PROJECTILE_TOP, CLIENT_PROJECTILE_BOTTOM);
    }

    // Vertical extents are signed offsets from the character feet (origin.y):
    //   yAboveOrigin >  0 → box top is yAboveOrigin px above feet.
    //   yBelowOrigin >  0 → box bottom is yBelowOrigin px below feet.
    //   yBelowOrigin <  0 → box bottom is |yBelowOrigin| px above feet (used for thin
    //                       pierce-line projectiles fired from mid-body).
    // Empirically (see kb-pierce-line-projectile-vertical) the projectile launches from
    // approximately player.Y - 30 (mid-body) and its vertical reach is the projectile
    // sprite half-height. The bot derives this from per-skill measurements rather than
    // the client's WZ projectile asset because we do not yet parse those.
    static Rectangle clientProjectileHitBox(Character bot, boolean facingLeft, float horizontalScale,
                                            int yAboveOrigin, int yBelowOrigin) {
        if (bot == null || bot.getPosition() == null) {
            return null;
        }

        Point origin = bot.getPosition();
        int projectileRange = CLIENT_PROJECTILE_BASE_RANGE + passiveProjectileRangeBonus(bot);
        int farEdge = Math.max(CLIENT_PROJECTILE_NEAR_INSET, Math.round(projectileRange * Math.max(0f, horizontalScale)));
        int left = facingLeft ? origin.x - farEdge : origin.x + CLIENT_PROJECTILE_NEAR_INSET;
        int right = facingLeft ? origin.x - CLIENT_PROJECTILE_NEAR_INSET : origin.x + farEdge;
        int top = origin.y - yAboveOrigin;
        int height = Math.max(1, yAboveOrigin + yBelowOrigin);
        return new Rectangle(left, top, right - left, height);
    }

    static float projectileRangeScale(StatEffect effect) {
        return effect != null && effect.getRange() > 0 ? effect.getRange() / 100.0f : 1.0f;
    }

    static int passiveProjectileRangeBonus(Character bot) {
        if (bot == null) {
            return 0;
        }

        int bonus = 0;
        for (int skillId : PASSIVE_PROJECTILE_RANGE_SKILL_IDS) {
            Skill skill = resolveLearnedSkill(bot, skillId);
            if (skill == null) {
                continue;
            }

            int level = bot.getSkillLevel(skill);
            if (level <= 0) {
                continue;
            }

            bonus += Math.max(0, skill.getEffect(level).getRange());
        }
        return bonus;
    }

    private static Skill resolveLearnedSkill(Character bot, int skillId) {
        Map<Skill, Character.SkillEntry> skills = bot.getSkills();
        if (skills != null) {
            for (Skill learned : skills.keySet()) {
                if (learned != null && learned.getId() == skillId) {
                    return learned;
                }
            }
        }

        return SkillFactory.getSkill(skillId);
    }

    private static List<Monster> collectTargetsInHitBox(Character bot, Monster primaryTarget, Rectangle hitBox, int maxTargets) {
        if (!doesHitBoxIntersectMonster(hitBox, primaryTarget)) {
            return List.of();
        }

        List<Monster> targets = new ArrayList<>();
        targets.add(primaryTarget);
        if (maxTargets <= 1) {
            return targets;
        }

        List<Monster> secondaryTargets = new ArrayList<>();
        for (Monster monster : bot.getMap().getAllMonsters()) {
            if (!monster.isAlive() || monster.getObjectId() == primaryTarget.getObjectId()) {
                continue;
            }
            if (!doesHitBoxIntersectMonster(hitBox, monster)) {
                continue;
            }
            secondaryTargets.add(monster);
        }

        secondaryTargets.sort(Comparator.comparingDouble(monster -> monster.getPosition().distanceSq(primaryTarget.getPosition())));
        for (Monster monster : secondaryTargets) {
            targets.add(monster);
            if (targets.size() >= maxTargets) {
                break;
            }
        }
        return targets;
    }

    private static boolean isBasicAttackInRange(Point botPos, Point targetPos) {
        int dx = Math.abs(targetPos.x - botPos.x);
        int dy = botPos.y - targetPos.y;
        boolean inHRange = dx <= BotCombatManager.cfg.ATTACK_RANGE_X;
        boolean inVRange = dy >= -BotCombatManager.cfg.ATTACK_DOWN_MAX && dy <= BotCombatManager.cfg.ATTACK_RANGE_Y;
        return inHRange && inVRange;
    }

    /**
     * Returns the number of damage lines a skill fires per target.
     * Uses the larger of attackCount and bulletCount from skill data —
     * claw skills like Lucky Seven store their projectile count in bulletCount.
     */
    static int effectiveHitCount(StatEffect effect) {
        return Math.max(1, Math.max(effect.getAttackCount(), effect.getBulletCount()));
    }

    // Shadow Partner (Hermit / NightWalker / DualBlade book) doubles the per-mob damage
    // line count for ranged attacks: the client packs `numDamage * 2` into
    // numAttackedAndDamage and the second half of each mob's lines are rolled at half
    // damage. The server validates this in AbstractDealDamageHandler (`maxattack * 2`
    // autoban headroom) and RangedAttackHandler (`bulletConsume * 2`). Bots inherit the
    // multiplier automatically once the buff lands on them (future admin command).
    // Melee and magic routes are left untouched here — Shadow Partner's melee/magic
    // doubling for thief skills (e.g. Triple Throw is ranged-claw, so it covers itself)
    // can be enabled per-skill later if needed.
    private static int shadowPartnerHitMultiplier(Character bot, AttackRoute route) {
        if (route != AttackRoute.RANGED || bot == null) {
            return 1;
        }
        return bot.getBuffEffect(BuffStat.SHADOWPARTNER) != null ? 2 : 1;
    }

    /**
     * True iff the AoE skill's expected total damage (damage% × hits × targets) beats
     * the bot's best single-target option (best of configured attack skill or basic 100%).
     * Used to gate AoE selection when only a small cluster is in range.
     */
    private static boolean beatsSingleTargetScore(Character bot, BotEntry entry, StatEffect aoeEffect,
                                                  int aoeAttackCount, int targetCount) {
        int aoeDamage = Math.max(0, aoeEffect.getDamage());
        long aoeScore = (long) aoeDamage * Math.max(1, aoeAttackCount) * Math.max(1, targetCount);
        long singleScore = 100L; // basic attack: 100% damage × 1 line
        if (entry.attackSkillId != 0) {
            Skill skill = SkillFactory.getSkill(entry.attackSkillId);
            int level = skill == null ? 0 : bot.getSkillLevel(skill);
            if (level > 0) {
                StatEffect fx = skill.getEffect(level);
                if (fx != null) {
                    singleScore = Math.max(singleScore,
                            (long) Math.max(0, fx.getDamage()) * effectiveHitCount(fx));
                }
            }
        }
        return aoeScore > singleScore;
    }

    // "focus <mob>": restrict to candidates whose name contains the focus, but only when at least
    // one is in range — otherwise fall back to all candidates so the bot never stands idle.
    private static List<Monster> applyFocus(BotEntry entry, List<Monster> candidates) {
        String focus = entry != null ? entry.focusMobName : null;
        if (focus == null || candidates.isEmpty()) {
            return candidates;
        }
        List<Monster> matched = new ArrayList<>();
        for (Monster m : candidates) {
            String name = m.getName();
            if (name != null && name.toLowerCase().contains(focus)) {
                matched.add(m);
            }
        }
        return matched.isEmpty() ? candidates : matched;
    }

    private static List<ScoredGrindTarget> scoreGrindTargets(BotEntry entry,
                                                             Character bot,
                                                             Point botPos,
                                                             Foothold botFoothold,
                                                             List<Monster> candidates) {
        candidates = applyFocus(entry, candidates);
        GrindGraphContext graphContext = GrindGraphContext.resolve(entry, bot, botPos);
        if (!graphContext.available()) {
            return scoreLocalTargets(entry, bot, botPos, botFoothold, candidates);
        }

        return scoreTargetRegions(entry, graphContext, bot, botPos, botFoothold, candidates);
    }

    private static List<Monster> aliveMonstersInRange(Character bot, Point botPos, double rangeSq) {
        List<Monster> candidates = new ArrayList<>();
        for (Monster m : bot.getMap().getAllMonsters()) {
            if (m.isAlive() && m.getPosition().distanceSq(botPos) <= rangeSq) {
                candidates.add(m);
            }
        }
        return candidates;
    }

    private static boolean isLocalCombatTarget(GrindGraphContext context,
                                               Character bot,
                                               Foothold botFoothold,
                                               Monster target) {
        if (botFoothold != null) {
            Foothold targetFoothold = findGroundFoothold(target.getPosition(), bot);
            if (targetFoothold != null && targetFoothold.getId() == botFoothold.getId()) {
                return true;
            }
        }
        if (!context.available()) {
            return false;
        }

        int targetRegionId = BotNavigationManager.resolveTargetRegionId(
                context.graph(), context.entry(), context.map(), target.getPosition());
        return targetRegionId >= 0 && targetRegionId == context.startRegionId();
    }

    private static boolean isImmediateProjectileTarget(BotEntry entry, Character bot, Monster target) {
        if (entry == null || entry.noAmmo || bot == null || target == null || !target.isAlive()) {
            return false;
        }

        Point botPos = bot.getPosition();
        Point targetPos = target.getPosition();
        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        if (BotAttackExecutionProvider.determineBasicWeaponRoute(weaponType) == AttackRoute.RANGED
                && !BotAttackExecutionProvider.shouldDegenerateRangedAttack(weaponType, botPos, targetPos)) {
            Rectangle hitBox = clientProjectileHitBox(bot, targetPos.x < botPos.x, 1.0f);
            if (doesHitBoxIntersectMonster(hitBox, target)) {
                return true;
            }
        }

        return isImmediateProjectileSkillTarget(entry, bot, target);
    }

    private static boolean isImmediateProjectileSkillTarget(BotEntry entry, Character bot, Monster target) {
        if (entry.attackSkillId == 0 || bot.skillIsCooling(entry.attackSkillId)) {
            return false;
        }

        Skill skill = SkillFactory.getSkill(entry.attackSkillId);
        int skillLevel = skill == null ? 0 : bot.getSkillLevel(skill);
        if (skillLevel <= 0) {
            return false;
        }

        StatEffect effect = skill.getEffect(skillLevel);
        if (effect == null || !effect.canPaySkillCost(bot)) {
            return false;
        }

        AttackRoute route = BotAttackExecutionProvider.determineSkillRoute(bot, entry.attackSkillId);
        if (route != AttackRoute.RANGED && route != AttackRoute.MAGIC) {
            return false;
        }

        Rectangle hitBox = calculateSkillHitBox(effect, bot, target, route, entry.attackSkillId);
        if (hitBox == null || !doesHitBoxIntersectMonster(hitBox, target)) {
            return false;
        }

        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        return BotAttackExecutionProvider.canUseRangedAttackRoute(route, weaponType, bot.getPosition(), target.getPosition());
    }

    private static List<ScoredGrindTarget> scoreLocalTargets(BotEntry entry,
                                                             Character bot,
                                                             Point botPos,
                                                             Foothold botFoothold,
                                                             List<Monster> candidates) {
        List<ScoredGrindTarget> scoredTargets = new ArrayList<>(candidates.size());
        for (Monster candidate : candidates) {
            long localScore = grindTargetScore(bot, botPos, botFoothold, candidate)
                    - aoeClusterBonus(entry, candidate, candidates)
                    - undeadTargetBonus(entry, candidate);
            scoredTargets.add(new ScoredGrindTarget(candidate, localScore, localScore,
                    candidate.getPosition().distanceSq(botPos)));
        }
        return scoredTargets;
    }

    private static List<ScoredGrindTarget> scoreTargetRegions(BotEntry entry,
                                                              GrindGraphContext context,
                                                              Character bot,
                                                              Point botPos,
                                                              Foothold botFoothold,
                                                              List<Monster> candidates) {
        Map<Integer, GrindTargetGroup> groupsByRegionId = new HashMap<>();
        for (Monster candidate : candidates) {
            Point targetPos = candidate.getPosition();
            int targetRegionId = BotNavigationManager.resolveTargetRegionId(
                    context.graph(), context.entry(), context.map(), targetPos);
            if (targetRegionId < 0) {
                continue;
            }

            long localScore = grindTargetScore(bot, botPos, botFoothold, candidate)
                    - aoeClusterBonus(entry, candidate, candidates)
                    - undeadTargetBonus(entry, candidate);
            GrindTargetGroup group = groupsByRegionId.computeIfAbsent(targetRegionId, GrindTargetGroup::new);
            group.add(candidate, localScore, targetPos.distanceSq(botPos));
        }

        List<ScoredGrindTarget> scoredTargets = new ArrayList<>(groupsByRegionId.size());
        for (GrindTargetGroup group : groupsByRegionId.values()) {
            long pathCost = graphPathCost(context.graph(), context.map(), context.startPos(), context.startRegionId(),
                    group.bestMonster().getPosition(), group.regionId(), context.profile());
            long crowdBonus = Math.min(3_000L, (long) Math.max(0, group.mobCount() - 1) * 400L);
            long occupancyPenalty = grindRegionOccupancyPenalty(context, bot, group.regionId());
            long graphScore = pathCost >= UNREACHABLE_GRAPH_COST
                    ? UNREACHABLE_GRAPH_COST
                    : Math.max(0L, pathCost - crowdBonus) + occupancyPenalty;
            long localScore = group.bestLocalScore() + occupancyPenalty;
            scoredTargets.add(new ScoredGrindTarget(group.bestMonster(), graphScore, localScore,
                    group.bestDistanceSq()));
        }
        return scoredTargets;
    }

    private static Monster pickFromBestTargets(List<ScoredGrindTarget> scoredTargets) {
        if (scoredTargets.isEmpty()) {
            return null;
        }
        scoredTargets.sort(Comparator
                .comparingLong(ScoredGrindTarget::graphCost)
                .thenComparingLong(ScoredGrindTarget::localScore)
                .thenComparingDouble(ScoredGrindTarget::distanceSq));
        return scoredTargets.get(0).monster();
    }

    private static long graphTargetCost(GrindGraphContext context, Monster target) {
        Point targetPos = target.getPosition();
        int targetRegionId = BotNavigationManager.resolveTargetRegionId(
                context.graph(), context.entry(), context.map(), targetPos);
        if (targetRegionId < 0) {
            return UNREACHABLE_GRAPH_COST;
        }

        return graphPathCost(context.graph(), context.map(), context.startPos(), context.startRegionId(),
                targetPos, targetRegionId, context.profile());
    }

    private static long graphPathCost(BotNavigationGraph graph,
                                      MapleMap map,
                                      Point startPos,
                                      int startRegionId,
                                      Point targetPos,
                                      int targetRegionId,
                                      BotMovementProfile profile) {
        if (startPos == null || targetPos == null || startRegionId < 0 || targetRegionId < 0) {
            return UNREACHABLE_GRAPH_COST;
        }
        if (startRegionId == targetRegionId) {
            return estimateLocalTravelCostMs(startPos, targetPos, profile);
        }

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPathForTargetScore(
                graph, map, startPos, startRegionId, targetRegionId, targetPos);
        if (path.isEmpty()) {
            return UNREACHABLE_GRAPH_COST;
        }

        long cost = 0;
        for (BotNavigationGraph.Edge edge : path) {
            cost += edge.cost;
        }
        return cost;
    }

    private static long estimateLocalTravelCostMs(Point from, Point to, BotMovementProfile profile) {
        int dx = Math.abs(to.x - from.x);
        int dy = Math.abs(to.y - from.y);
        double walkVelocity = Math.max(1.0, profile.walkVelocityPxs());
        return Math.round(dx * 1000.0 / walkVelocity) + dy * 4L;
    }

    private static long grindTargetScore(Character bot, Point botPos, Foothold botFoothold, Monster target) {
        Point targetPos = target.getPosition();
        Foothold targetFoothold = findGroundFoothold(targetPos, bot);

        int dx = Math.abs(targetPos.x - botPos.x);
        int dy = Math.abs(targetPos.y - botPos.y);
        boolean sameFoothold = botFoothold != null && targetFoothold != null && botFoothold.getId() == targetFoothold.getId();
        boolean nearSameLevel = dy <= BotCombatManager.cfg.ATTACK_RANGE_Y;

        long score = dx;
        score += (long) dy * 8L;
        if (!nearSameLevel) {
            score += 600L;
        }
        if (!sameFoothold) {
            score += 1200L;
        }
        return score;
    }

    // Cluster-density bonus: when the bot has an AoE skill, bias target selection toward
    // mobs that anchor a cluster. The single-skill DPS scorer already prefers AoE plans
    // over basic ones on the same target (selectBestAttackPlan), but it never sees the
    // alternative cluster if target selection passed it over for a closer/lone mob.
    //
    // Returns a non-negative bonus that is subtracted from the candidate's localScore
    // (lower score wins). Capped by the AoE skill's mobCount-1 so a 6-mob skill on a
    // pile of 10 mobs doesn't crater scores past the natural distance/foothold penalties.
    static final int AOE_CLUSTER_RADIUS_PX = 150;
    static final long AOE_CLUSTER_BONUS_PER_MOB = 200L;
    // An AoE plan hitting at least this many mobs is preferred even over a single-target one-shot.
    static final int AOE_STRONG_PREFER_MOBS = 3;
    // Heal-capable bots (clerics) prefer undead targets — Heal is their best damage vs undead.
    static final long UNDEAD_HEAL_TARGET_BONUS = 250L;

    /** Target-selection bias: clerics favor undead so they engage them and Heal-bomb (lower = better). */
    private static long undeadTargetBonus(BotEntry entry, Monster target) {
        if (entry == null || entry.healSkillId == 0 || target == null) {
            return 0L;
        }
        return target.getStats().isUndead() ? UNDEAD_HEAL_TARGET_BONUS : 0L;
    }

    private static long aoeClusterBonus(BotEntry entry, Monster target, List<Monster> candidates) {
        if (entry == null || entry.aoeSkillId == 0 || entry.aoeSkillMobs <= 1
                || target == null || candidates == null || candidates.isEmpty()) {
            return 0L;
        }
        Point tp = target.getPosition();
        if (tp == null) {
            return 0L;
        }
        long radiusSq = (long) AOE_CLUSTER_RADIUS_PX * AOE_CLUSTER_RADIUS_PX;
        int cap = entry.aoeSkillMobs - 1;
        int neighbors = 0;
        for (Monster other : candidates) {
            if (other == target || other == null || !other.isAlive()) {
                continue;
            }
            Point op = other.getPosition();
            if (op == null) {
                continue;
            }
            long dx = (long) op.x - tp.x;
            long dy = (long) op.y - tp.y;
            if (dx * dx + dy * dy <= radiusSq) {
                neighbors++;
                if (neighbors >= cap) {
                    break;
                }
            }
        }
        return neighbors * AOE_CLUSTER_BONUS_PER_MOB;
    }

    private static long grindRegionOccupancyPenalty(GrindGraphContext context, Character bot, int targetRegionId) {
        if (!context.available() || context.entry().owner == null || bot == null || targetRegionId < 0) {
            return 0L;
        }

        int occupiedCount = 0;
        for (BotEntry sibling : BotManager.getInstance().getBotEntries(context.entry().owner.getId())) {
            if (sibling == context.entry() || sibling == null || !sibling.grinding || sibling.bot == null) {
                continue;
            }
            if (sibling.bot.getMap() != context.map() || sibling.bot.getHp() <= 0 || sibling.bot.getPosition() == null) {
                continue;
            }

            int occupiedRegionId = BotNavigationManager.resolveCurrentRegionId(
                    context.graph(), sibling, context.map(), sibling.bot.getPosition());
            if (occupiedRegionId == targetRegionId) {
                occupiedCount++;
            }
        }

        long penalty = (long) Math.max(0, occupiedCount) * Math.max(0, cfg.GRIND_REGION_OCCUPANCY_PENALTY);
        return Math.min(Math.max(0, cfg.GRIND_REGION_OCCUPANCY_PENALTY_CAP), penalty);
    }

    private static Foothold findGroundFoothold(Point position, Character bot) {
        if (position == null || bot == null || bot.getMap() == null) {
            return null;
        }

        return BotPhysicsEngine.findGroundFoothold(bot.getMap(), position);
    }

    private record ScoredGrindTarget(Monster monster, long graphCost, long localScore, double distanceSq) {
    }

    private static final class GrindTargetGroup {
        private final int regionId;
        private int mobCount;
        private Monster bestMonster;
        private long bestLocalScore = Long.MAX_VALUE;
        private double bestDistanceSq = Double.MAX_VALUE;

        private GrindTargetGroup(int regionId) {
            this.regionId = regionId;
        }

        private void add(Monster monster, long localScore, double distanceSq) {
            mobCount++;
            if (bestMonster == null
                    || localScore < bestLocalScore
                    || (localScore == bestLocalScore && distanceSq < bestDistanceSq)) {
                bestMonster = monster;
                bestLocalScore = localScore;
                bestDistanceSq = distanceSq;
            }
        }

        private int regionId() {
            return regionId;
        }

        private int mobCount() {
            return mobCount;
        }

        private Monster bestMonster() {
            return bestMonster;
        }

        private long bestLocalScore() {
            return bestLocalScore;
        }

        private double bestDistanceSq() {
            return bestDistanceSq;
        }
    }

    private record GrindGraphContext(BotEntry entry,
                                     MapleMap map,
                                     BotNavigationGraph graph,
                                     BotMovementProfile profile,
                                     Point startPos,
                                     int startRegionId) {
        static GrindGraphContext resolve(BotEntry entry, Character bot, Point botPos) {
            if (entry == null || bot == null || bot.getMap() == null || bot.getMap().getFootholds() == null) {
                return unavailable(entry, bot, botPos);
            }

            BotMovementProfile profile = entry.movementProfile == null ? BotMovementProfile.fromCharacter(bot) : entry.movementProfile;
            BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(bot.getMap(), profile);
            if (graph == null) {
                BotNavigationGraphProvider.warmGraphAsync(bot.getMap(), profile);
                graph = BotNavigationGraphProvider.peekClosestGraph(bot.getMap(), profile);
            }
            if (graph == null) {
                return unavailable(entry, bot, botPos);
            }

            int startRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, bot.getMap(), botPos);
            if (startRegionId < 0) {
                return unavailable(entry, bot, botPos);
            }
            return new GrindGraphContext(entry, bot.getMap(), graph, profile, new Point(botPos), startRegionId);
        }

        private static GrindGraphContext unavailable(BotEntry entry, Character bot, Point botPos) {
            MapleMap map = bot == null ? null : bot.getMap();
            BotMovementProfile profile = entry == null ? BotMovementProfile.base() : entry.movementProfile;
            Point startPos = botPos == null ? null : new Point(botPos);
            return new GrindGraphContext(entry, map, null, profile, startPos, -1);
        }

        boolean available() {
            return graph != null && map != null && startPos != null && startRegionId >= 0 && entry != null;
        }
    }

    static boolean isMobTouchingBot(BotEntry entry, Character bot, Monster mob) {
        Rectangle botBounds = getBotTouchBounds(entry, bot);
        Rectangle mobBounds = BotMobHitboxProvider.getInstance().getMobBounds(mob);
        if (mobBounds == null) {
            return false;
        }
        return mobBounds.intersects(botBounds);
    }

    static Rectangle getBotTouchBounds(BotEntry entry, Character bot) {
        Point currentPos = bot.getPosition();
        Point previousPos = currentPos;
        if (entry != null
                && entry.lastMobTouchCheckPos != null
                && entry.lastMobTouchMapId == bot.getMapId()) {
            previousPos = entry.lastMobTouchCheckPos;
        }

        // Mirror the client touch check: sweep the player's foot position between ticks
        // and use a fixed height above the feet instead of the full character sprite.
        int left = Math.min(previousPos.x, currentPos.x);
        int right = Math.max(previousPos.x, currentPos.x);
        int top = Math.min(previousPos.y, currentPos.y) - BotCombatManager.cfg.MOB_TOUCH_SWEEP_HEIGHT;
        int bottom = Math.max(previousPos.y, currentPos.y);
        return inclusiveRectangle(left, top, right, bottom);
    }

    private static Rectangle inclusiveRectangle(int left, int top, int right, int bottom) {
        return new Rectangle(left, top, Math.max(1, right - left + 1), Math.max(1, bottom - top + 1));
    }

    private static void rememberMobTouchCheck(BotEntry entry, Character bot, Point position) {
        if (entry == null || bot == null || position == null) {
            return;
        }

        entry.lastMobTouchCheckPos = new Point(position);
        entry.lastMobTouchMapId = bot.getMapId();
    }

    private static boolean doesHitBoxIntersectMonster(Rectangle hitBox, Monster monster) {
        if (hitBox == null || monster == null) {
            return false;
        }

        Rectangle mobBounds = BotMobHitboxProvider.getInstance().getMobBounds(monster);
        if (mobBounds != null) {
            return hitBox.intersects(mobBounds) || hitBox.contains(monster.getPosition());
        }

        return hitBox.contains(monster.getPosition());
    }

    // Strike-point-anchored skills (e.g. Arrow Bomb) center their bbox on the target, so
    // doesHitBoxIntersectMonster(hitBox, target) is trivially true and does not gate reach.
    // Use the bot's basic weapon rectangle as a separate reach check. MAGIC route is left
    // ungated for now — untested.
    private static boolean isPrimaryReachableByBasicWeapon(Character bot, Monster target, AttackRoute route) {
        if (bot == null || target == null || bot.getPosition() == null || target.getPosition() == null) {
            return false;
        }
        if (route != AttackRoute.RANGED && route != AttackRoute.CLOSE) {
            return true;
        }
        boolean facingLeft = target.getPosition().x < bot.getPosition().x;
        Rectangle basicReach = basicWeaponReachRect(bot, facingLeft, route);
        return basicReach != null && doesHitBoxIntersectMonster(basicReach, target);
    }

    private static Monster resolveStrikePointPrimaryByBasicWeapon(Character bot, Monster fallback, AttackRoute route) {
        if (bot == null || fallback == null || bot.getPosition() == null || fallback.getPosition() == null) {
            return fallback;
        }
        if (route != AttackRoute.RANGED && route != AttackRoute.CLOSE) {
            return fallback;
        }
        boolean facingLeft = fallback.getPosition().x < bot.getPosition().x;
        Rectangle basicReach = basicWeaponReachRect(bot, facingLeft, route);
        if (basicReach == null) {
            return fallback;
        }
        return resolveEffectivePrimary(bot, fallback, basicReach);
    }

    /**
     * Rect the bot's *basic* (un-skilled) weapon attack would cover for the given facing/route.
     * RANGED -> projectile reach (400 px + passive bonuses); CLOSE -> ATTACK_RANGE_X/Y/DOWN_MAX
     * around bot origin. Returns null for routes with no rect-based reach (e.g. MAGIC).
     */
    private static Rectangle basicWeaponReachRect(Character bot, boolean facingLeft, AttackRoute route) {
        if (bot == null || bot.getPosition() == null) {
            return null;
        }
        if (route == AttackRoute.RANGED) {
            return clientProjectileHitBox(bot, facingLeft, 1.0f);
        }
        if (route == AttackRoute.CLOSE) {
            Point origin = bot.getPosition();
            int left = facingLeft ? origin.x - cfg.ATTACK_RANGE_X : origin.x;
            int top = origin.y - cfg.ATTACK_RANGE_Y;
            int height = cfg.ATTACK_RANGE_Y + cfg.ATTACK_DOWN_MAX;
            return new Rectangle(left, top, cfg.ATTACK_RANGE_X, height);
        }
        return null;
    }

    private static boolean isForwardProjectileHitBox(Rectangle hitBox, Point botPos) {
        if (hitBox == null || botPos == null) {
            return false;
        }
        return botPos.x < hitBox.getMinX() || botPos.x > hitBox.getMaxX();
    }

    static Monster resolveEffectivePrimary(Character bot, Monster fallback, Rectangle hitBox) {
        Point botPos = bot.getPosition();
        if (!isForwardProjectileHitBox(hitBox, botPos)) {
            return fallback;
        }
        Monster closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (Monster m : bot.getMap().getAllMonsters()) {
            if (!m.isAlive() || !doesHitBoxIntersectMonster(hitBox, m)) {
                continue;
            }
            double distSq = m.getPosition().distanceSq(botPos);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = m;
            }
        }
        return closest != null ? closest : fallback;
    }

    static Monster findClosestAliveMonster(Character bot, double maxRangeSq) {
        Point botPos = bot.getPosition();
        Monster closest = null;
        double closestDistSq = maxRangeSq;
        for (Monster m : bot.getMap().getAllMonsters()) {
            if (!m.isAlive()) {
                continue;
            }
            double distSq = m.getPosition().distanceSq(botPos);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = m;
            }
        }
        return closest;
    }

    private static BotAttackExecutionProvider.BasicAttackData buildBasicAttackData(Character bot, Monster primaryTarget) {
        return BotAttackExecutionProvider.buildBasicAttackData(bot, primaryTarget.getPosition());
    }

    private static void noteSkillBuffDecision(BotEntry entry, String summary) {
        entry.lastSkillBuffActionAtMs = System.currentTimeMillis();
        entry.lastSkillBuffActionSummary = summary;
    }

    static List<String> getSkillBuffDebugLines(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();

        // Line 1: last decision
        String lastAction = entry.lastSkillBuffActionSummary;
        if (entry.lastSkillBuffActionAtMs > 0) {
            long ageMs = Math.max(0L, now - entry.lastSkillBuffActionAtMs);
            lastAction += " (" + formatBuffAge(ageMs) + " ago)";
        }

        // Line 2: active skill buffs
        StringJoiner activeJoiner = new StringJoiner(", ");
        for (PlayerBuffValueHolder holder : bot.getAllBuffs()) {
            StatEffect effect = holder.effect;
            if (effect == null || !effect.isSkill()) continue;
            int skillId = effect.getSourceId();
            String remaining = effect.getDuration() > 0
                    ? " " + formatBuffAge(Math.max(0, effect.getDuration() - holder.usedTime)) + " left"
                    : "";
            activeJoiner.add(skillLabel(skillId) + remaining);
        }
        String activeLine = activeJoiner.length() == 0 ? "none" : activeJoiner.toString().toLowerCase(Locale.ROOT);

        // Line 3: cached buff skills with ready/cooldown status
        StringJoiner availJoiner = new StringJoiner(", ");
        for (int skillId : entry.buffSkillIds) {
            boolean cooling = bot.skillIsCooling(skillId);
            long nextAt = entry.nextBuffAt.getOrDefault(skillId, 0L);
            String status;
            if (cooling) {
                status = "cd";
            } else if (now < nextAt) {
                status = "rebuff " + formatBuffAge(nextAt - now);
            } else {
                status = "ready";
            }
            availJoiner.add(skillLabel(skillId) + " (" + status + ")");
        }
        String availLine = availJoiner.length() == 0 ? "none cached" : availJoiner.toString().toLowerCase(Locale.ROOT);

        return List.of(
                "skill buffs: last: " + lastAction,
                "active: " + activeLine,
                "cached: " + availLine
        );
    }

    private static String formatBuffAge(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0) return seconds + "s";
        return minutes + "m" + seconds + "s";
    }

    private static String skillLabel(int skillId) {
        String name = SkillFactory.getSkillName(skillId);
        return (name != null && !name.isBlank()) ? name : "skill#" + skillId;
    }

    static String describeDebugStats(BotEntry entry, Character bot) {
        Monster target = entry.grindTarget;
        if (target == null || !target.isAlive()) {
            target = findGrindTarget(bot);
        }

        AttackPlan plan = target != null ? planAttack(entry, bot, target) : null;
        String route = plan != null ? plan.route.name().toLowerCase() : BotAttackExecutionProvider.determineBasicAttackRoute(bot).name().toLowerCase();
        int speed = plan != null ? plan.speed : BotAttackExecutionProvider.buildBasicAttackData(bot, bot.getPosition()).speed();
        double cooldownSeconds = (plan != null ? plan.cooldownMs : 0) / 1000.0;
        double remainingSeconds = entry.attackCooldownMs / 1000.0;
        String targetName = target != null ? target.getName() : "none";

        return String.format(
                "debug: route %s, atk speed %d, atk cd %.2fs, remaining %.2fs, tick %dms, ai %dms, target %s",
                route, speed, cooldownSeconds, remainingSeconds,
                BotMovementManager.cfg.TICK_MS, BotManager.cfg.AI_TICK_MS, targetName);
    }

    private static boolean trySupportBuff(BotEntry entry, Character bot, long now) {
        for (int skillId : entry.buffSkillIds) {
            if (!isPartySupportSkill(skillId)) {
                continue;
            }
            if (bot.skillIsCooling(skillId)) {
                continue;
            }
            if (now < entry.nextSupportBuffAt.getOrDefault(skillId, 0L)) {
                continue;
            }

            Skill skill = SkillFactory.getSkill(skillId);
            int lvl = bot.getSkillLevel(skill);
            if (lvl <= 0) {
                continue;
            }

            StatEffect fx = skill.getEffect(lvl);
            if (!hasNearbyPartyMemberMissingBuff(bot, fx)) {
                continue;
            }

            if (castSupportSkill(entry, bot, skill, fx, now)) {
                entry.nextSupportBuffAt.put(skillId, now + cfg.SUPPORT_REBUFF_CD_MS);
                return true;
            }
        }

        return false;
    }

    private static boolean castSupportSkill(BotEntry entry, Character bot, Skill skill, StatEffect fx, long now) {
        int skillLevel = bot.getSkillLevel(skill);
        if (skillLevel <= 0) {
            noteSkillBuffDecision(entry, "missing skill level for " + skillLabel(skill.getId()));
            return false;
        }
        if (!bot.isAlive()) {
            noteSkillBuffDecision(entry, "can't cast while dead: " + skillLabel(skill.getId()));
            return false;
        }
        if (!fx.canPaySkillCost(bot)) {
            noteSkillBuffDecision(entry, "can't pay cost for " + skillLabel(skill.getId()));
            return false;
        }
        if (!dispatchSupportSpecialMove(bot, skill, skillLevel)) {
            noteSkillBuffDecision(entry, "special move failed for " + skillLabel(skill.getId()));
            return false;
        }

        long dur = fx.getDuration();
        if (dur > 0) {
            entry.nextBuffAt.put(skill.getId(), now + (long) (dur * 0.9));
        }
        BotAttackExecutionProvider.BasicAttackData fallbackAttackData =
                BotAttackExecutionProvider.buildBasicAttackData(bot, bot.getPosition());
        String action = BotAttackExecutionProvider.resolveSkillAttackAction(bot, skill, skillLevel,
                BotAttackExecutionProvider.getEquippedWeaponType(bot));
        BotAttackExecutionProvider.SkillAttackTiming skillTiming =
                BotAttackExecutionProvider.resolveSkillAttackTiming(skill, action, bot, fallbackAttackData);
        int animMs = skill.getAnimationTime() > 0 ? skill.getAnimationTime() : 1000;
        entry.attackCooldownMs = Math.max(entry.attackCooldownMs, Math.max(skillTiming.cooldownMs(), animMs));
        markAlerted(entry);
        noteSkillBuffDecision(entry, "cast " + skillLabel(skill.getId()));
        return true;
    }

    // Real v83 self-buff SPECIAL_MOVE captures show two shapes:
    // - self-only buffs like Magic Guard / Invincible: timestamp, skillId, skillLevel, 00 00
    // - party support buffs like Bless: timestamp, skillId, skillLevel, pos(x,y), facingMask, 00 00
    // Bot buffs must mimic those client parameters and then run through the normal SpecialMoveHandler.
    static byte[] buildSupportSpecialMovePacket(Character bot, int skillId, int skillLevel, int packetTimestamp) {
        ByteBufOutPacket packet = new ByteBufOutPacket();
        packet.writeShort(RecvOpcode.SPECIAL_MOVE.getValue());
        packet.writeInt(packetTimestamp);
        packet.writeInt(skillId);
        packet.writeByte(skillLevel);
        if (isPartySupportSkill(skillId)) {
            Point position = bot.getPosition();
            packet.writePos(position != null ? position : new Point(0, 0));
            packet.writeByte(bot.isFacingLeft() ? 0x80 : 0x00);
            packet.writeShort(0);
        } else {
            packet.writeShort(0);
        }
        return packet.getBytes();
    }

    private static boolean dispatchSupportSpecialMove(Character bot, Skill skill, int skillLevel) {
        Client client = bot.getClient();
        if (client == null) {
            return false;
        }

        byte[] packetBytes = buildSupportSpecialMovePacket(
                bot,
                skill.getId(),
                skillLevel,
                net.server.Server.getInstance().getCurrentTimestamp());
        InPacket packet = new ByteBufInPacket(Unpooled.wrappedBuffer(packetBytes));
        short packetId = packet.readShort();
        PacketHandler handler = PacketProcessor.getProcessor(bot.getWorld(), client.getChannel()).getHandler(packetId);
        if (handler == null || !handler.validateState(client)) {
            return false;
        }

        handler.handlePacket(packet, client);
        return true;
    }

    private static boolean canUseSkill(Character bot, int skillId, int skillLevel) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null || skillLevel <= 0) {
            return false;
        }

        return skill.getEffect(skillLevel).canPaySkillCost(bot);
    }

    /** Returns true if the bot's weapon type requires projectile ammo. */
    static boolean isRangedAmmoWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.BOW || weaponType == WeaponType.CROSSBOW
                || weaponType == WeaponType.CLAW || weaponType == WeaponType.GUN;
    }

    static boolean isAirborneRangedAttackBlockedWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.BOW
                || weaponType == WeaponType.CROSSBOW
                || weaponType == WeaponType.GUN;
    }

    /**
     * Periodically checks ammo for ranged bots. Piggybacks on the pot-check timer.
     * Warns at AMMO_LOW_WARN, stops grinding and follows owner at 0.
     */
    static void tickAmmoCheck(BotEntry entry, Character bot) {
        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        boolean mage = weaponType == WeaponType.WAND || weaponType == WeaponType.STAFF;
        if (!mage && !isRangedAmmoWeapon(weaponType)) {
            entry.noAmmo = false;
            entry.ammoWarnSent = false;
            return;
        }

        if (mage) {
            int mpPotionCount = 0;
            for (Item item : bot.getInventory(InventoryType.USE).list()) {
                if (item.getQuantity() <= 0) {
                    continue;
                }
                StatEffect effect = BotInventoryManager.itemEffect(item.getItemId());
                if (effect == null || !effect.getStatups().isEmpty()) {
                    continue;
                }
                if (effect.getMp() > 0 || effect.getMpRate() > 0) {
                    mpPotionCount += item.getQuantity();
                    if (mpPotionCount >= BotManager.cfg.POT_LOW_WARN) {
                        break;
                    }
                }
            }
            if (mpPotionCount > 0) {
                entry.noAmmo = false;
                entry.ammoWarnSent = false;
                return;
            }
            if (!entry.noAmmo) {
                entry.noAmmo = true;
                if (entry.grinding) {
                    BotManager.getInstance().issueFollowOwner(entry);
                    BotManager.getInstance().botSay(bot, BotManager.randomReply(MP_POTS_OUT_MSGS));
                }
            }
            return;
        }

        int ammo = countAmmo(bot, weaponType);
        if (ammo >= cfg.AMMO_LOW_WARN) {
            entry.noAmmo = false;
            entry.ammoWarnSent = false;
            return;
        }

        if (ammo > 0 && !entry.ammoWarnSent) {
            entry.ammoWarnSent = true;
            BotManager.getInstance().botSay(bot, BotManager.randomReply(AMMO_LOW_MSGS));
            return;
        }

        if (ammo <= 0 && !entry.noAmmo) {
            entry.noAmmo = true;
            if (entry.grinding) {
                BotManager.getInstance().issueFollowOwner(entry);
                BotManager.getInstance().botSay(bot, BotManager.randomReply(AMMO_OUT_MSGS));
            }
        }
    }

    /** Counts total ammo in USE inventory matching the bot's equipped weapon type. */
    static int countAmmo(Character bot, WeaponType weaponType) {
        if (weaponType == null || !isRangedAmmoWeapon(weaponType)) {
            return Integer.MAX_VALUE;
        }
        boolean soulArrow = bot.getBuffedValue(BuffStat.SOULARROW) != null;
        boolean shadowClaw = bot.getBuffedValue(BuffStat.SHADOW_CLAW) != null;
        if (soulArrow || shadowClaw) {
            return Integer.MAX_VALUE;
        }
        int total = 0;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            int id = item.getItemId();
            boolean match = switch (weaponType) {
                case BOW -> ItemConstants.isArrowForBow(id);
                case CROSSBOW -> ItemConstants.isArrowForCrossBow(id);
                case CLAW -> ItemConstants.isThrowingStar(id);
                case GUN -> ItemConstants.isBullet(id);
                default -> false;
            };
            if (match) {
                total += item.getQuantity();
            }
        }
        return total;
    }

    private static boolean hasNearbyPartyMemberMissingBuff(Character bot, StatEffect fx) {
        if (fx.getStatups().isEmpty()) {
            return false;
        }

        for (Character target : getNearbyPartyMembers(bot)) {
            for (var statup : fx.getStatups()) {
                if (target.getBuffedValue(statup.getLeft()) == null) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * True when any party member inside the heal skill's WZ bounding box is below the heal
     * threshold. Using the skill bounds (not cfg.SUPPORT_RANGE) avoids the infinite-cast loop that
     * occurs when a member sits inside the looser support range but outside the actual heal hitbox:
     * applyTo() would never include them, so their HP never recovers and the decision fires again.
     *
     * <p>Falls back to the legacy SUPPORT_RANGE box if the skill has no WZ bounding box, which is
     * defensive — Cleric.HEAL always has lt/rb, but other heal-like skills added later might not.
     */
    private static boolean hasPartyMemberInBoundsNeedingHeal(Character bot, Rectangle healBounds) {
        Point botPos = bot.getPosition();
        for (Character target : bot.getPartyMembersOnSameMap()) {
            if (target == null || target.getId() == bot.getId() || !target.isAlive()) {
                continue;
            }
            Point memberPos = target.getPosition();
            if (healBounds != null) {
                if (!healBounds.contains(memberPos)) {
                    continue;
                }
            } else {
                if (Math.abs(memberPos.y - botPos.y) > cfg.SUPPORT_VERTICAL_RANGE) {
                    continue;
                }
                double rangeSq = (double) cfg.SUPPORT_RANGE * cfg.SUPPORT_RANGE;
                if (memberPos.distanceSq(botPos) > rangeSq) {
                    continue;
                }
            }
            if (needsHeal(target)) {
                return true;
            }
        }
        return false;
    }

    private static List<Character> getNearbyPartyMembers(Character bot) {
        List<Character> nearby = new ArrayList<>();
        Point botPos = bot.getPosition();
        double rangeSq = (double) cfg.SUPPORT_RANGE * cfg.SUPPORT_RANGE;
        for (Character member : bot.getPartyMembersOnSameMap()) {
            if (member == null || member.getId() == bot.getId() || !member.isAlive()) {
                continue;
            }

            Point memberPos = member.getPosition();
            if (Math.abs(memberPos.y - botPos.y) > cfg.SUPPORT_VERTICAL_RANGE) {
                continue;
            }
            if (memberPos.distanceSq(botPos) > rangeSq) {
                continue;
            }

            nearby.add(member);
        }
        return nearby;
    }

    static boolean isPartySupportSkill(int skillId) {
        return PARTY_SUPPORT_SKILL_IDS.contains(skillId);
    }

    static boolean isActiveAttackSkill(Skill skill, StatEffect effect) {
        if (skill == null || effect == null) {
            return false;
        }
        if (NON_DAMAGE_ACTIVE_SKILL_IDS.contains(skill.getId())
                || BOT_UNUSABLE_ATTACK_SKILL_IDS.contains(skill.getId())
                || KEYDOWN_SKILL_IDS.contains(skill.getId())) {
            return false;
        }
        if (effect.isOverTime() || !declaresOffense(effect)) {
            return false;
        }
        if (skill.getSkillType() == 1 || skill.getSkillType() == 3) {
            return false;
        }
        // v83 attack skills often omit a top-level action node; passive damage carriers do not
        // carry a client-paid cost. Use WZ skillType for explicit passives and cost as the fallback.
        return effect.getMpCon() > 0 || effect.getHpCon() > 0 || skill.isBeginnerSkill();
    }

    // Identifies skills the WZ source declares as offensive. Three WZ shapes cover every
    // attack skill we know of in v83; combined with isOverTime() this rejects utility skills
    // (Teleport, Magic Guard, Haste) that would otherwise pass numeric checks because the
    // StatEffect loader defaults "damage" to 100 and "mad" to 0 when those keys are absent.
    //   1. "damage" key present  → physical attack skill (Power Strike, Strafe, Iron Arrow).
    //   2. "mad" key present     → magic attack skill (Magic Claw, Cold Beam, Thunder Bolt).
    //   3. mobCount > 1 + bbox   → bomb/explosion skill that derives damage from "x" instead
    //                              of "damage" (Hunter.ARROW_BOMB and similar).
    private static boolean declaresOffense(StatEffect effect) {
        return effect.hasDamage()
                || effect.hasMatk()
                || (effect.getMobCount() > 1 && effect.hasBoundingBox());
    }

    static boolean isActiveSupportSkill(Skill skill, StatEffect effect) {
        if (skill == null || effect == null || !effect.isOverTime()) {
            return false;
        }
        // Data-driven gate for "rebuffable self/party buff" — two WZ facts replace what used to
        // need per-skill exclusion lists:
        //   1. getDuration() > 0. The rebuff loop is timer-based: castSupportSkill sets
        //      nextBuffAt = now + duration*0.9 and only recasts once that elapses. Skills with no
        //      WZ "time" key load as duration -1000 (Dispel, Resurrection, Big Bang, MP Recovery,
        //      Time Leap, Energy Drain), so they would never advance the timer and recast every
        //      tick. These are one-shot / instant / charged effects, not rebuffable buffs.
        //   2. getStatups() non-empty. A genuine buff declares at least one stat it grants the
        //      caster/party (pad/pdd/mad/acc/eva/speed/jump/SUMMON/MORPH/...). Mob-targeting
        //      debuffs (Threaten, Slow, Seal, Doom, Ninja Ambush) instead carry mobCount + a
        //      bounding box and grant the caster no statup; the bot only has a self/party
        //      SPECIAL_MOVE cast path, so firing them on a rebuff timer is wasted MP. This also
        //      subsumes the old NON_DAMAGE_ACTIVE_SKILL_IDS exclusion here (the *Crash skills
        //      carry no statup), so no skill-id list is needed on the support path.
        if (effect.getDuration() <= 0 || effect.getStatups().isEmpty()) {
            return false;
        }
        // Summons are classified separately (entry.summonSkillIds): their only statup is
        // SUMMON/PUPPET, and they cannot be cast through the rebuff loop (no spawn position).
        if (isSummonSkill(effect)) {
            return false;
        }
        return skill.getAction() || skill.getSkillType() == 2;
    }

    /**
     * Data-driven summon test: a summon's effect grants only the SUMMON (follow/circle hawk-type)
     * or PUPPET (stationary decoy) buffstat — the WZ marks the summoned creature, not a caster
     * stat boost. Detecting it from the statups avoids a per-skill id list and matches the
     * server's spawn path (StatEffect.applyTo spawns the creature only when given a position).
     */
    static boolean isSummonSkill(StatEffect effect) {
        if (effect == null) {
            return false;
        }
        for (Pair<BuffStat, Integer> statup : effect.getStatups()) {
            BuffStat stat = statup.getLeft();
            if (stat == BuffStat.SUMMON || stat == BuffStat.PUPPET) {
                return true;
            }
        }
        return false;
    }

    static boolean isActiveHealSkill(Skill skill, StatEffect effect) {
        return skill != null && effect != null && skill.getAction();
    }

    static boolean isHealSkill(int skillId) {
        return skillId == Cleric.HEAL || skillId == SuperGM.HEAL_PLUS_DISPEL;
    }
}
