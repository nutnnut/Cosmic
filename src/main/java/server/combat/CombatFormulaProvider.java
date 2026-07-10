package server.combat;

import client.BuffStat;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.skills.Bandit;
import constants.skills.Aran;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.BlazeWizard;
import constants.skills.Brawler;
import constants.skills.Cleric;
import constants.skills.Crossbowman;
import constants.skills.DragonKnight;
import constants.skills.Evan;
import constants.skills.Fighter;
import constants.skills.FPMage;
import constants.skills.Gunslinger;
import constants.skills.Hermit;
import constants.skills.Hunter;
import constants.skills.ILMage;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Page;
import constants.skills.Rogue;
import constants.skills.Shadower;
import constants.skills.Spearman;
import constants.skills.ThunderBreaker;
import constants.skills.WindArcher;
import constants.game.GameConstants;
import constants.skills.Buccaneer;
import constants.skills.Crusader;
import constants.skills.DarkKnight;
import constants.skills.DawnWarrior;
import constants.skills.Hero;
import constants.skills.Marauder;
import constants.skills.Paladin;
import constants.skills.WhiteKnight;
import net.server.PlayerBuffValueHolder;
import net.server.channel.handlers.AbstractDealDamageHandler;
import server.StatEffect;
import server.life.Element;
import server.life.ElementalEffectiveness;
import server.life.Monster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class CombatFormulaProvider {
    public record DamageProfile(int minDamage, int maxDamage, boolean magicAttack, boolean alwaysHit,
                                boolean noCrit) {
        public DamageProfile(int minDamage, int maxDamage, boolean magicAttack, boolean alwaysHit) {
            this(minDamage, maxDamage, magicAttack, alwaysHit, false);
        }
    }

    /**
     * Critical hit parameters resolved from a bot's job passives and active buffs.
     * critChance is in [0.0, 1.0]; critMultiplier is the damage scaling factor on a crit
     * (2.0 = standard +100% crit per formula doc step 6).
     */
    public record CritProfile(double critChance, double critMultiplier) {
        public static final CritProfile NONE = new CritProfile(0.0, 1.0);
    }

    private static final double MIN_HIT_CHANCE = 0.01d;
    private static final double MAX_HIT_CHANCE = 1.0d;
    private static final CombatFormulaProvider instance = new CombatFormulaProvider();

    /**
     * All passive skills whose x-field encodes crit chance % and whose presence grants +100% crit damage.
     * Scanned directly on the bot's skill map — no job-type checks needed.
     */
    private static final List<Integer> CRIT_PASSIVE_SKILL_IDS = List.of(
            Archer.CRITICAL_SHOT,
            WindArcher.CRITICAL_SHOT,
            Assassin.CRITICAL_THROW,
            NightWalker.CRITICAL_THROW,
            Aran.COMBO_CRITICAL
    );

    public static CombatFormulaProvider getInstance() {
        return instance;
    }

    /**
     * Standard spell damage MAX base.
     * Formula: ceil((Magic² / 1000 + Magic) / 30 + INT / 200)
     * All arithmetic is float; single outer ceil avoids split-ceil rounding errors.
     */
    public long magicDamageBase(int matk, int totalInt) {
        return (long) Math.ceil((matk * matk / 1000.0 + matk) / 30.0 + totalInt / 200.0);
    }

    /**
     * Standard spell damage MIN base.
     * Formula: ceil((Magic² / 1000 + Magic * Mastery * 0.9) / 30 + INT / 200)
     *
     * @param mastery mastery factor in [0.0, 1.0] — from skill data x field divided by 100
     */
    public long magicDamageBaseMin(int matk, int totalInt, double mastery) {
        return (long) Math.ceil((matk * matk / 1000.0 + matk * mastery * 0.9) / 30.0 + totalInt / 200.0);
    }

    public int getTotalAccuracy(Character bot) {
        int derivedAccuracy = (int) Math.floor(bot.getTotalDex() * 0.8d + bot.getTotalLuk() * 0.5d);
        return Math.max(0, derivedAccuracy + getFlatAccuracy(bot));
    }

    public int getTotalMagicAccuracy(Character bot) {
        // Magic accuracy = 5 × (floor(INT/10) + floor(LUK/10))  — per cat123/Eric client research
        int derivedMagicAccuracy = 5 * ((int) Math.floor(bot.getTotalInt() / 10.0)
                + (int) Math.floor(bot.getTotalLuk() / 10.0));
        return Math.max(0, derivedMagicAccuracy);
    }

    public int getTotalAvoidability(Character bot) {
        int derivedAvoidability = (int) Math.floor(bot.getTotalDex() * 0.25d + bot.getTotalLuk() * 0.5d);
        return Math.max(0, derivedAvoidability + getFlatAvoidability(bot));
    }

    public double calculateMobHitChance(Character bot, Monster monster) {
        return calculateMobHitChance(bot, monster, false);
    }

    public double calculateMobHitChance(Character bot, Monster monster, boolean magicAttack) {
        if (magicAttack) {
            return calculateMagicMobHitChance(getTotalMagicAccuracy(bot), bot.getLevel(), monster.getLevel(), monster.getAvoidability());
        }
        return calculatePhysicalMobHitChance(getTotalAccuracy(bot), bot.getLevel(), monster.getLevel(), monster.getAvoidability());
    }

    double calculateMobHitChance(int accuracy, int botLevel, int monsterLevel, int monsterAvoidability) {
        return calculatePhysicalMobHitChance(accuracy, botLevel, monsterLevel, monsterAvoidability);
    }

    public double calculatePhysicalMobHitChance(int accuracy, int botLevel, int monsterLevel, int monsterAvoidability) {
        // Source: cat123/Eric client research (RaGEZONE, Mar 2026)
        // accuracy_rate = accuracy * 100 / (levelDelta * 10 + 255)
        // hit if random(0.7, 1.3) * accuracy_rate >= avoid
        // => hitChance = (1.3 - avoid/accuracy_rate) / 0.6
        int levelDelta = Math.max(0, monsterLevel - botLevel);
        if (monsterAvoidability <= 0) return MAX_HIT_CHANCE;
        double accuracyRate = accuracy * 100.0 / (levelDelta * 10 + 255);
        double hitChance = (1.3 - monsterAvoidability / accuracyRate) / 0.6;
        return Math.max(MIN_HIT_CHANCE, Math.min(MAX_HIT_CHANCE, hitChance));
    }

    public double calculateMagicMobHitChance(int magicAccuracy, int botLevel, int monsterLevel, int monsterAvoidability) {
        // Same accuracy_rate scaling as physical; random bounds are (0.5, 1.2) for magic
        // => hitChance = (1.2 - avoid/accuracy_rate) / 0.7
        int levelDelta = Math.max(0, monsterLevel - botLevel);
        if (monsterAvoidability <= 0) return MAX_HIT_CHANCE;
        double accuracyRate = magicAccuracy * 100.0 / (levelDelta * 10 + 255);
        double hitChance = (1.2 - monsterAvoidability / accuracyRate) / 0.7;
        return Math.max(MIN_HIT_CHANCE, Math.min(MAX_HIT_CHANCE, hitChance));
    }

    public double calculateBotAvoidChance(Character bot, Monster monster) {
        return calculateBotAvoidChance(monster.getAccuracy(), monster.getLevel(), bot.getLevel(), getTotalAvoidability(bot));
    }

    public double calculateBotAvoidChance(int monsterAccuracy, int monsterLevel, int botLevel, int botAvoidability) {
        int levelDelta = Math.max(0, botLevel - monsterLevel);
        double hitChance = monsterAccuracy / (((1.84d + 0.07d * levelDelta) * botAvoidability) + 1.0d);
        hitChance = Math.max(MIN_HIT_CHANCE, hitChance);
        return Math.min(MAX_HIT_CHANCE, hitChance);
    }

    public boolean doesMobHit(Character bot, Monster monster) {
        return doesMobHit(calculateBotAvoidChance(bot, monster));
    }

    public boolean doesMobHit(double hitChance) {
        double normalizedHitChance = Math.max(0.0d, Math.min(MAX_HIT_CHANCE, hitChance));
        return ThreadLocalRandom.current().nextDouble() <= normalizedHitChance;
    }

    public List<Integer> rollDamageLines(Character bot, Monster monster, int hits, int minDamage, int maxDamage) {
        return rollDamageLines(bot, monster, hits, minDamage, maxDamage, false);
    }

    public List<Integer> rollDamageLines(Character bot, Monster monster, int hits, int minDamage, int maxDamage, boolean magicAttack) {
        return rollDamageLines(hits, minDamage, maxDamage, calculateMobHitChance(bot, monster, magicAttack));
    }

    public List<Integer> rollDamageLines(int hits, int minDamage, int maxDamage, double hitChance) {
        int normalizedMinDamage = Math.max(0, minDamage);
        int normalizedMaxDamage = Math.max(normalizedMinDamage, maxDamage);
        double normalizedHitChance = Math.max(0.0d, Math.min(MAX_HIT_CHANCE, hitChance));

        List<Integer> damageLines = new ArrayList<>(Math.max(0, hits));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < hits; i++) {
            if (random.nextDouble() > normalizedHitChance) {
                damageLines.add(0);
                continue;
            }

            damageLines.add(normalizedMinDamage < normalizedMaxDamage
                    ? random.nextInt(normalizedMinDamage, normalizedMaxDamage + 1)
                    : normalizedMaxDamage);
        }

        return damageLines;
    }

    // Default target count for Cleric Heal damage: caster + 1 target. Resolves to the 4.0× target
    // multiplier, which algebraically matches the old (INT*4.8 + LUK*4) Cosmic formula —
    // unaffected callers (non-Heal or tests) keep their existing numbers.
    private static final int DEFAULT_HEAL_TARGET_COUNT = 2;

    public DamageProfile resolveDamageProfile(Character bot, int skillId, int skillLevel, boolean magicAttack) {
        return resolveDamageProfile(bot, skillId, skillLevel, magicAttack, DEFAULT_HEAL_TARGET_COUNT);
    }

    public DamageProfile resolveDamageProfile(Character bot, int skillId, int skillLevel,
                                              boolean magicAttack, WeaponType physicalWeaponTypeOverride) {
        Skill skill = skillId != 0 ? SkillFactory.getSkill(skillId) : null;
        StatEffect effect = skill != null && skillLevel > 0 ? skill.getEffect(skillLevel) : null;
        return resolveDamageProfile(bot, skillId, effect, magicAttack, DEFAULT_HEAL_TARGET_COUNT, physicalWeaponTypeOverride);
    }

    /**
     * Variant used by Heal path: the client's damage-vs-undead formula includes a target multiplier
     * of {@code 1.5 + 5 / N} where N is the number of targets including the caster. Callers that
     * know how many undead are being hit (e.g. {@code BotCombatManager.sendHealAttack}) should pass
     * {@code undeadCount + 1} so the bot's Heal damage matches what a real client would send.
     *
     * @param healTargetCount N in the formula — caster + undead mobs. Ignored for non-Heal skills.
     */
    public DamageProfile resolveDamageProfile(Character bot, int skillId, int skillLevel,
                                              boolean magicAttack, int healTargetCount) {
        Skill skill = skillId != 0 ? SkillFactory.getSkill(skillId) : null;
        StatEffect effect = skill != null && skillLevel > 0 ? skill.getEffect(skillLevel) : null;
        return resolveDamageProfile(bot, skillId, effect, magicAttack, healTargetCount, null);
    }

    public DamageProfile resolveDamageProfile(Character bot, int skillId, StatEffect effect, boolean magicAttack) {
        return resolveDamageProfile(bot, skillId, effect, magicAttack, DEFAULT_HEAL_TARGET_COUNT);
    }

    public DamageProfile resolveDamageProfile(Character bot, int skillId, StatEffect effect,
                                              boolean magicAttack, WeaponType physicalWeaponTypeOverride) {
        return resolveDamageProfile(bot, skillId, effect, magicAttack, DEFAULT_HEAL_TARGET_COUNT, physicalWeaponTypeOverride);
    }

    public DamageProfile resolveDamageProfile(Character bot, int skillId, StatEffect effect,
                                              boolean magicAttack, int healTargetCount) {
        return resolveDamageProfile(bot, skillId, effect, magicAttack, healTargetCount, null);
    }

    private DamageProfile resolveDamageProfile(Character bot, int skillId, StatEffect effect,
                                               boolean magicAttack, int healTargetCount,
                                               WeaponType physicalWeaponTypeOverride) {
        if (effect != null && effect.getFixDamage() > 0) {
            int fixedDamage = Math.max(1, effect.getFixDamage());
            return new DamageProfile(fixedDamage, fixedDamage, magicAttack, true);
        }

        return magicAttack
                ? resolveMagicDamageProfile(bot, skillId, effect, healTargetCount)
                : resolvePhysicalDamageProfile(bot, skillId, effect, physicalWeaponTypeOverride);
    }

    public AbstractDealDamageHandler.AttackTarget makeTarget(Character bot, Monster monster, int hits,
                                                      DamageProfile damageProfile, int hitDelayMs) {
        return makeTarget(bot, monster, hits, 0, damageProfile, hitDelayMs);
    }

    public AbstractDealDamageHandler.AttackTarget makeTarget(Character bot, Monster monster, int hits,
                                                      int skillId, DamageProfile damageProfile, int hitDelayMs) {
        int normalizedHitDelay = Math.max(0, Math.min(Short.MAX_VALUE, hitDelayMs));
        if (damageProfile.alwaysHit()) {
            List<Integer> lines = rollDamageLines(hits, damageProfile.minDamage(), damageProfile.maxDamage(), 1.0d);
            return new AbstractDealDamageHandler.AttackTarget((short) normalizedHitDelay, lines);
        }
        long rawMin = applyCharacterDamageModifiers(damageProfile.minDamage(), bot, skillId);
        long rawMax = applyCharacterDamageModifiers(damageProfile.maxDamage(), bot, skillId);
        boolean elementalResetActive = bot.getBuffedValue(BuffStat.ELEMENTAL_RESET) != null;
        rawMax = applySkillElementalMultiplier(rawMax, skillId, monster, elementalResetActive);
        rawMin = applySkillElementalMultiplier(rawMin, skillId, monster, elementalResetActive);
        // parseDamage omits STRONG penalty as anti-cheat headroom; bots apply it for realism
        if (skillId != 0 && !elementalResetActive && monster != null) {
            Skill elemSkill = SkillFactory.getSkill(skillId);
            if (elemSkill != null && elemSkill.getElement() != Element.NEUTRAL
                    && monster.getElementalEffectiveness(elemSkill.getElement()) == ElementalEffectiveness.STRONG) {
                rawMax = Math.max(1, rawMax / 2);
                rawMin = Math.max(1, rawMin / 2);
            }
        }
        rawMax = applyWkChargeElementalBonus(rawMax, bot, monster);
        rawMin = applyWkChargeElementalBonus(rawMin, bot, monster);
        int modMax = (int) Math.min(Integer.MAX_VALUE, rawMax);
        int modMin = (int) Math.min(modMax, Math.max(1, (int) rawMin));
        int[] adjustedDamage = applyMonsterDefense(bot, monster, modMin, modMax, damageProfile.magicAttack());
        boolean shadowPartner = hits > 1 && bot.getBuffEffect(BuffStat.SHADOWPARTNER) != null;
        if (!damageProfile.magicAttack()) {
            CritProfile crit = damageProfile.noCrit() ? CritProfile.NONE : resolveCritProfile(bot);
            double hitChance = calculateMobHitChance(bot, monster, false);
            if (skillId == Buccaneer.BARRAGE || skillId == ThunderBreaker.BARRAGE) {
                return rollBarrageDamageLines(hits, adjustedDamage, hitChance, crit, normalizedHitDelay);
            }
            if (shadowPartner) {
                return rollWithShadowPartnerPhysical(hits, adjustedDamage, hitChance, crit, normalizedHitDelay);
            }
            CritDamageResult result = rollDamageLinesWithCrit(hits, adjustedDamage[0], adjustedDamage[1],
                    hitChance, crit.critChance(), crit.critMultiplier());
            return new AbstractDealDamageHandler.AttackTarget((short) normalizedHitDelay,
                    result.lines(), result.critIndices());
        } else {
            if (shadowPartner) {
                return rollWithShadowPartnerMagic(bot, monster, hits, adjustedDamage, normalizedHitDelay);
            }
            List<Integer> lines = rollDamageLines(bot, monster, hits, adjustedDamage[0], adjustedDamage[1], true);
            return new AbstractDealDamageHandler.AttackTarget((short) normalizedHitDelay, lines);
        }
    }

    /**
     * Deterministic counterpart to {@link #makeTarget}: estimates the total damage the bot should
     * deal to one monster after character modifiers, elemental effects, accuracy, crits, Shadow
     * Partner, and monster defense. Used by bot planning so skill choice is based on expected output
     * instead of raw skill percentages.
     */
    public double estimateExpectedDamage(Character bot, Monster monster, int hits, int skillId,
                                         DamageProfile damageProfile) {
        int normalizedHits = Math.max(0, hits);
        if (normalizedHits == 0 || damageProfile == null) {
            return 0.0d;
        }
        if (damageProfile.alwaysHit()) {
            return averageDamage(damageProfile.minDamage(), damageProfile.maxDamage()) * normalizedHits;
        }

        int[] adjustedDamage = adjustedDamageRange(bot, monster, skillId, damageProfile);
        double hitChance = calculateMobHitChance(bot, monster, damageProfile.magicAttack());
        boolean shadowPartner = normalizedHits > 1 && bot.getBuffEffect(BuffStat.SHADOWPARTNER) != null;
        if (damageProfile.magicAttack()) {
            if (shadowPartner) {
                int mainHits = normalizedHits / 2;
                int partnerHits = normalizedHits - mainHits;
                double mainAverage = averageDamage(adjustedDamage[0], adjustedDamage[1]);
                double partnerAverage = averageDamage(Math.max(1, Math.min(adjustedDamage[1] / 2, adjustedDamage[0] / 2)),
                        Math.max(1, adjustedDamage[1] / 2));
                return hitChance * (mainAverage * mainHits + partnerAverage * partnerHits);
            }
            return hitChance * averageDamage(adjustedDamage[0], adjustedDamage[1]) * normalizedHits;
        }

        CritProfile crit = damageProfile.noCrit() ? CritProfile.NONE : resolveCritProfile(bot);
        if (skillId == Buccaneer.BARRAGE || skillId == ThunderBreaker.BARRAGE) {
            double total = 0.0d;
            for (int j = 0; j < normalizedHits; j++) {
                int scaledMin = adjustedDamage[0];
                int scaledMax = adjustedDamage[1];
                if (j > 3) {
                    int factor = 1 << (j - 3);
                    scaledMax = (int) Math.min(Integer.MAX_VALUE, (long) adjustedDamage[1] * factor);
                    scaledMin = (int) Math.min(scaledMax, (long) adjustedDamage[0] * factor);
                }
                total += expectedPhysicalLineDamage(scaledMin, scaledMax, hitChance, crit);
            }
            return total;
        }
        if (shadowPartner) {
            int mainHits = normalizedHits / 2;
            int partnerHits = normalizedHits - mainHits;
            int partnerMax = Math.max(1, adjustedDamage[1] / 2);
            int partnerMin = Math.max(1, Math.min(partnerMax, adjustedDamage[0] / 2));
            return expectedPhysicalLineDamage(adjustedDamage[0], adjustedDamage[1], hitChance, crit) * mainHits
                    + expectedPhysicalLineDamage(partnerMin, partnerMax, hitChance, crit) * partnerHits;
        }
        return expectedPhysicalLineDamage(adjustedDamage[0], adjustedDamage[1], hitChance, crit) * normalizedHits;
    }

    public int estimateMinimumDamage(Character bot, Monster monster, int hits, int skillId,
                                     DamageProfile damageProfile) {
        int normalizedHits = Math.max(0, hits);
        if (normalizedHits == 0 || damageProfile == null) {
            return 0;
        }
        if (damageProfile.alwaysHit()) {
            return Math.max(0, damageProfile.minDamage()) * normalizedHits;
        }

        int[] adjustedDamage = adjustedDamageRange(bot, monster, skillId, damageProfile);
        int minLine = adjustedDamage[0];
        boolean shadowPartner = normalizedHits > 1 && bot.getBuffEffect(BuffStat.SHADOWPARTNER) != null;
        if (shadowPartner) {
            int mainHits = normalizedHits / 2;
            int partnerHits = normalizedHits - mainHits;
            int partnerMin = Math.max(1, Math.min(Math.max(1, adjustedDamage[1] / 2), minLine / 2));
            return minLine * mainHits + partnerMin * partnerHits;
        }
        return minLine * normalizedHits;
    }

    private int[] adjustedDamageRange(Character bot, Monster monster, int skillId, DamageProfile damageProfile) {
        long rawMin = applyCharacterDamageModifiers(damageProfile.minDamage(), bot, skillId);
        long rawMax = applyCharacterDamageModifiers(damageProfile.maxDamage(), bot, skillId);
        boolean elementalResetActive = bot.getBuffedValue(BuffStat.ELEMENTAL_RESET) != null;
        rawMax = applySkillElementalMultiplier(rawMax, skillId, monster, elementalResetActive);
        rawMin = applySkillElementalMultiplier(rawMin, skillId, monster, elementalResetActive);
        if (skillId != 0 && !elementalResetActive && monster != null) {
            Skill elemSkill = SkillFactory.getSkill(skillId);
            if (elemSkill != null && elemSkill.getElement() != Element.NEUTRAL
                    && monster.getElementalEffectiveness(elemSkill.getElement()) == ElementalEffectiveness.STRONG) {
                rawMax = Math.max(1, rawMax / 2);
                rawMin = Math.max(1, rawMin / 2);
            }
        }
        rawMax = applyWkChargeElementalBonus(rawMax, bot, monster);
        rawMin = applyWkChargeElementalBonus(rawMin, bot, monster);

        int modMax = (int) Math.min(Integer.MAX_VALUE, rawMax);
        int modMin = (int) Math.min(modMax, Math.max(1, (int) rawMin));
        return applyMonsterDefense(bot, monster, modMin, modMax, damageProfile.magicAttack());
    }

    private double expectedPhysicalLineDamage(int minDamage, int maxDamage, double hitChance, CritProfile crit) {
        double average = averageDamage(minDamage, maxDamage);
        double critChance = Math.max(0.0d, Math.min(1.0d, crit.critChance()));
        double critMultiplier = Math.max(1.0d, crit.critMultiplier());
        return hitChance * average * (1.0d + critChance * (critMultiplier - 1.0d));
    }

    private double averageDamage(int minDamage, int maxDamage) {
        int normalizedMin = Math.max(0, minDamage);
        int normalizedMax = Math.max(normalizedMin, maxDamage);
        return (normalizedMin + normalizedMax) / 2.0d;
    }

    // Shadow Partner damage ratio. WZ skill 4111002 `x` = 50 across all 30 levels → shadow lines
    // deal 50% of their origin line. Client truth: the shadow REPLAYS each original hit, it does not
    // re-roll — partner line i = floor(mainLine i * 0.5), inheriting that line's crit flag and its
    // miss (0 → 0). Independent re-rolls would desync crit flags and hit/miss from the original.
    private static final double SHADOW_PARTNER_RATIO = 0.5d;

    private AbstractDealDamageHandler.AttackTarget rollWithShadowPartnerPhysical(
            int hits, int[] adjustedDamage, double hitChance, CritProfile crit, int normalizedHitDelay) {
        int mainHits = hits / 2;
        int partnerHits = hits - mainHits;
        CritDamageResult main = rollDamageLinesWithCrit(mainHits, adjustedDamage[0], adjustedDamage[1],
                hitChance, crit.critChance(), crit.critMultiplier());
        List<Integer> lines = new ArrayList<>(main.lines());
        Set<Integer> critIndices = new HashSet<>(main.critIndices());
        for (int i = 0; i < partnerHits; i++) {
            int mainLine = i < main.lines().size() ? main.lines().get(i) : 0;
            lines.add((int) Math.floor(mainLine * SHADOW_PARTNER_RATIO));
            if (main.critIndices().contains(i)) {
                critIndices.add(mainHits + i);
            }
        }
        return new AbstractDealDamageHandler.AttackTarget((short) normalizedHitDelay, lines, critIndices);
    }

    private AbstractDealDamageHandler.AttackTarget rollWithShadowPartnerMagic(
            Character bot, Monster monster, int hits, int[] adjustedDamage, int normalizedHitDelay) {
        int mainHits = hits / 2;
        int partnerHits = hits - mainHits;
        List<Integer> main = rollDamageLines(bot, monster, mainHits, adjustedDamage[0], adjustedDamage[1], true);
        List<Integer> lines = new ArrayList<>(main);
        for (int i = 0; i < partnerHits; i++) {
            int mainLine = i < main.size() ? main.get(i) : 0;
            lines.add((int) Math.floor(mainLine * SHADOW_PARTNER_RATIO));
        }
        return new AbstractDealDamageHandler.AttackTarget((short) normalizedHitDelay, lines);
    }

    // Barrage hits j>3 deal 2^(j-3)x damage — matches parseDamage lines 847-851
    private AbstractDealDamageHandler.AttackTarget rollBarrageDamageLines(
            int hits, int[] adjustedDamage, double hitChance, CritProfile crit, int normalizedHitDelay) {
        List<Integer> lines = new ArrayList<>(hits);
        Set<Integer> critIndices = new HashSet<>();
        for (int j = 0; j < hits; j++) {
            int scaledMin = adjustedDamage[0];
            int scaledMax = adjustedDamage[1];
            if (j > 3) {
                int factor = 1 << (j - 3); // 2^(j-3)
                scaledMax = (int) Math.min(Integer.MAX_VALUE, (long) adjustedDamage[1] * factor);
                scaledMin = (int) Math.min(scaledMax, (long) adjustedDamage[0] * factor);
            }
            CritDamageResult hit = rollDamageLinesWithCrit(1, scaledMin, scaledMax,
                    hitChance, crit.critChance(), crit.critMultiplier());
            lines.addAll(hit.lines());
            if (!hit.critIndices().isEmpty()) {
                critIndices.add(j);
            }
        }
        return new AbstractDealDamageHandler.AttackTarget((short) normalizedHitDelay, lines, critIndices);
    }

    /** Shared with AbstractDealDamageHandler.parseDamage — keep in sync. */
    public long applySkillElementalMultiplier(long damage, int skillId, Monster monster, boolean elementalResetActive) {
        if (skillId == 0 || elementalResetActive) return damage;
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null || skill.getElement() == Element.NEUTRAL) return damage;
        if (monster == null) return (long) (damage * 1.5);
        ElementalEffectiveness eff = monster.getElementalEffectiveness(skill.getElement());
        if (eff == ElementalEffectiveness.WEAK) return (long) (damage * 1.5);
        // STRONG intentionally not penalized — matches parseDamage commented-out headroom
        return damage;
    }

    /** Shared with AbstractDealDamageHandler.parseDamage — keep in sync. */
    public long applyWkChargeElementalBonus(long damage, Character chr, Monster monster) {
        if (chr.getBuffEffect(BuffStat.WK_CHARGE) == null) return damage;
        int sourceId = chr.getBuffSource(BuffStat.WK_CHARGE);
        int level = chr.getBuffedValue(BuffStat.WK_CHARGE);
        if (monster == null) return (long) (damage * 1.5);
        Element chargeElement;
        boolean isHoly;
        if (sourceId == WhiteKnight.BW_FIRE_CHARGE || sourceId == WhiteKnight.SWORD_FIRE_CHARGE) {
            chargeElement = Element.FIRE; isHoly = false;
        } else if (sourceId == WhiteKnight.BW_ICE_CHARGE || sourceId == WhiteKnight.SWORD_ICE_CHARGE) {
            chargeElement = Element.ICE; isHoly = false;
        } else if (sourceId == WhiteKnight.BW_LIT_CHARGE || sourceId == WhiteKnight.SWORD_LIT_CHARGE) {
            chargeElement = Element.LIGHTING; isHoly = false;
        } else if (sourceId == Paladin.BW_HOLY_CHARGE || sourceId == Paladin.SWORD_HOLY_CHARGE) {
            chargeElement = Element.HOLY; isHoly = true;
        } else {
            return damage;
        }
        if (monster.getStats().getEffectiveness(chargeElement) == ElementalEffectiveness.WEAK) {
            double base = isHoly ? 1.2 : 1.05;
            return (long) (damage * (base + level * 0.015));
        }
        return damage;
    }

    /**
     * Character-level damage multipliers shared with parseDamage.
     * Intentionally matches parseDamage anti-cheat headrooms (Berserk unconditional, etc.).
     */
    public long applyCharacterDamageModifiers(long damage, Character chr, int skillId) {
        Integer comboBuff = chr.getBuffedValue(BuffStat.COMBO);
        if (comboBuff != null && comboBuff > 0) {
            int comboId = chr.isCygnus() ? DawnWarrior.COMBO : Crusader.COMBO;
            int advComboId = chr.isCygnus() ? DawnWarrior.ADVANCED_COMBO : Hero.ADVANCED_COMBO;
            if (comboBuff > 6) {
                StatEffect ceffect = SkillFactory.getSkill(advComboId).getEffect(chr.getSkillLevel(advComboId));
                damage = (long) Math.floor(damage * (ceffect.getDamage() + 50) / 100 + 0.20 + (comboBuff - 5) * 0.04);
            } else {
                int skillLv = chr.getSkillLevel(comboId);
                if (skillLv > 0) {
                    StatEffect ceffect = SkillFactory.getSkill(comboId).getEffect(skillLv);
                    damage = (long) Math.floor(damage * (ceffect.getDamage() + 50) / 100 + Math.floor((comboBuff - 1) * (skillLv / 6)) / 100);
                }
            }
            if (GameConstants.isFinisherSkill(skillId)) {
                int orbs = comboBuff - 1;
                if (orbs == 2) {
                    damage = (long) (damage * 1.2);
                } else if (orbs == 3) {
                    damage = (long) (damage * 1.54);
                } else if (orbs == 4) {
                    damage *= 2;
                } else if (orbs >= 5) {
                    damage = (long) (damage * 2.5);
                }
            }
        }
        if (chr.getEnergyBar() == 15000) {
            int energyChargeId = chr.isCygnus() ? ThunderBreaker.ENERGY_CHARGE : Marauder.ENERGY_CHARGE;
            Skill energySkill = SkillFactory.getSkill(energyChargeId);
            if (energySkill != null) {
                int lvl = chr.getSkillLevel(energySkill);
                if (lvl > 0) {
                    // Integer division matches parseDamage headroom exactly
                    damage *= (100 + energySkill.getEffect(lvl).getDamage()) / 100;
                }
            }
        }
        int bonusDmgBuff = 100;
        for (PlayerBuffValueHolder pbvh : chr.getAllBuffs()) {
            bonusDmgBuff += pbvh.effect.getDamage() - 100;
        }
        if (bonusDmgBuff != 100) {
            damage = (long) Math.ceil(damage * bonusDmgBuff / 100.0f);
        }
        int berserkLvl = chr.getSkillLevel(DarkKnight.BERSERK);
        if (berserkLvl > 0) {
            int hpThreshold = SkillFactory.getSkill(DarkKnight.BERSERK).getEffect(berserkLvl).getX();
            if (chr.getHp() * 100 / chr.getCurrentMaxHp() < hpThreshold) {
                damage *= 2;
            }
        }
        return damage;
    }

    /**
     * Resolves the critical hit profile by scanning the bot's actual leveled skills against
     * the known crit passive set. No job-type filter — works correctly regardless of job
     * advancement level or future skill additions.
     *
     * <p>Crit chance from the passive's {@code prop} field (e.g. Critical Shot lv20 prop=40 → 40%).
     * Crit multiplier from {@code damage} field (e.g. lv20 damage=200 → 2.0×), plus SE bonus additively.
     * All additive per line; magic attacks skip this entirely.
     */
    public CritProfile resolveCritProfile(Character bot) {
        double critChance = 0.0;
        double critMultiplier = 1.0;

        for (int skillId : CRIT_PASSIVE_SKILL_IDS) {
            int level = bot.getSkillLevel(skillId);
            if (level <= 0) continue;
            Skill skill = SkillFactory.getSkill(skillId);
            if (skill == null) continue;
            StatEffect effect = skill.getEffect(level);
            if (effect == null) continue;
            // prop = crit chance [0,1]; damage = total crit multiplier % (e.g. 200 → 2.0x)
            critChance = Math.min(1.0, critChance + effect.getProp());
            critMultiplier = effect.getDamage() / 100.0;
            break; // only one crit passive applies per bot
        }

        // Sharp Eyes buff value encodes: (critRate% << 8) | critDmgBonus%
        Integer sharpEyesValue = bot.getBuffedValue(BuffStat.SHARP_EYES);
        if (sharpEyesValue != null) {
            int seCritRate = (sharpEyesValue >> 8) & 0xFF;
            int seCritDmgBonus = sharpEyesValue & 0xFF;
            critChance = Math.min(1.0, critChance + seCritRate / 100.0);
            critMultiplier += seCritDmgBonus / 100.0;
        }

        return new CritProfile(Math.min(1.0, critChance), critMultiplier);
    }

    private record CritDamageResult(List<Integer> lines, Set<Integer> critIndices) {}

    /**
     * Rolls damage lines with per-hit critical evaluation. Returns both the clean damage list
     * and the set of indices that were critical (for visual encoding in the broadcast packet).
     * On crit, damage = floor(base × critMultiplier), capped at 99999.
     */
    private CritDamageResult rollDamageLinesWithCrit(int hits, int minDamage, int maxDamage,
                                                     double hitChance, double critChance, double critMultiplier) {
        int normalizedMinDamage = Math.max(0, minDamage);
        int normalizedMaxDamage = Math.max(normalizedMinDamage, maxDamage);
        double normalizedHitChance = Math.max(0.0d, Math.min(MAX_HIT_CHANCE, hitChance));

        List<Integer> damageLines = new ArrayList<>(Math.max(0, hits));
        Set<Integer> critIndices = new HashSet<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < hits; i++) {
            if (random.nextDouble() > normalizedHitChance) {
                damageLines.add(0);
                continue;
            }
            int base = normalizedMinDamage < normalizedMaxDamage
                    ? random.nextInt(normalizedMinDamage, normalizedMaxDamage + 1)
                    : normalizedMaxDamage;
            if (critChance > 0 && random.nextDouble() < critChance) {
                base = (int) Math.min(99999, Math.floor(base * critMultiplier));
                critIndices.add(i);
            }
            damageLines.add(base);
        }
        return new CritDamageResult(damageLines, critIndices);
    }

    /**
     * Degenerate melee swing with a ranged weapon: bow/crossbow swing (including Power Knockback
     * 3101003/3201003, which the client casts as a melee swing), claw punch, and gun bash.
     *
     * <p>Client truth (CalcDamage::PDamage, Angel.idb @0x0078DF87 — verified by disasm): before the
     * normal per-weapon-type formula dispatch, the non-shoot paths compute
     * <pre>
     *   bow/crossbow: (rand(DEX, m) * 3.4 + STR) * WAtk / 150   (PKB skill IDs branch here too)
     *   claw:         (rand(LUK, m) + STR + DEX) * WAtk / 150
     *   gun:          (rand(DEX, m) + STR)       * WAtk / 200
     * </pre>
     * where the mastery factor m = (5 * masteryLevel + 10) * 0.009 with masteryLevel = 0 (weapon
     * mastery never applies to mismatched attack types) = 0.09 — i.e. "Mastery is 0.1 at all
     * levels" per docs/formulas/dmg_formula_ayumilove.txt (Power Knock Back / Claw punching).
     * Crit passives (Critical Shot / Critical Throw) do not apply either, hence {@code noCrit}.
     * A skill damage% (Power Knockback) still composes on top per the doc's order of operations.
     */
    public DamageProfile resolveDegenerateDamageProfile(Character bot, WeaponType weaponType, StatEffect effect) {
        int watk = Math.max(0, bot.getTotalWatk());
        double maxBase;
        double minBase;
        switch (weaponType) {
            case BOW, CROSSBOW -> {
                maxBase = (bot.getTotalDex() * 3.4d + bot.getTotalStr()) * watk / 150.0d;
                minBase = (bot.getTotalDex() * 3.4d * DEGENERATE_MASTERY_FACTOR + bot.getTotalStr()) * watk / 150.0d;
            }
            case CLAW -> {
                maxBase = (bot.getTotalLuk() + bot.getTotalStr() + bot.getTotalDex()) * watk / 150.0d;
                minBase = (bot.getTotalLuk() * DEGENERATE_MASTERY_FACTOR + bot.getTotalStr() + bot.getTotalDex()) * watk / 150.0d;
            }
            case GUN -> {
                maxBase = (bot.getTotalDex() + bot.getTotalStr()) * watk / 200.0d;
                minBase = (bot.getTotalDex() * DEGENERATE_MASTERY_FACTOR + bot.getTotalStr()) * watk / 200.0d;
            }
            default -> {
                return resolveDamageProfile(bot, 0, effect, false);
            }
        }

        long maxDamage = (long) Math.ceil(maxBase);
        long minDamage = Math.max(1L, Math.round(minBase));
        if (effect != null) {
            int skillDamage = effect.getDamagePercent();
            if (skillDamage > 0) {
                maxDamage = maxDamage * skillDamage / 100L;
                minDamage = minDamage * skillDamage / 100L;
            }
        }

        int normalizedMaxDamage = clampDamage(maxDamage);
        int normalizedMinDamage = Math.min(normalizedMaxDamage, clampDamage(minDamage));
        return new DamageProfile(normalizedMinDamage, normalizedMaxDamage, false, false, true);
    }

    // (5 * masteryLevel + 10) * 0.009 with masteryLevel = 0 — the client's fixed 10% mastery
    // (x 0.9) for attacks the weapon mastery skill doesn't cover.
    private static final double DEGENERATE_MASTERY_FACTOR = 0.09d;

    private DamageProfile resolvePhysicalDamageProfile(Character bot, int skillId, StatEffect effect,
                                                       WeaponType physicalWeaponTypeOverride) {
        int watk = Math.max(0, bot.getTotalWatk());
        long maxDamage;
        long minDamage;

        if (skillId == Rogue.LUCKY_SEVEN
                || skillId == NightWalker.LUCKY_SEVEN
                || skillId == NightLord.TRIPLE_THROW
                || skillId == NightWalker.TRIPLE_THROW) {
//            Lucky Seven/Triple Throw (credit to HS.net / LazyBui for recent verification):
//            MAX = (LUK * 5.0) * Weapon Attack / 100
//            MIN = (LUK * 2.5) * Weapon Attack / 100
            maxDamage = (long) Math.ceil(bot.getTotalLuk() * 5L * watk / 100.0d);
            minDamage = Math.max(1L, Math.round(maxDamage * 0.5d));
        } else if (skillId == DragonKnight.DRAGON_ROAR) {
            double mastery = resolvePhysicalMastery(bot);
            maxDamage = (long) Math.ceil((bot.getTotalStr() * 4.0d + bot.getTotalDex()) * watk / 100.0d);
            minDamage = Math.max(1L, Math.round((bot.getTotalStr() * 4.0d * mastery * 0.9d + bot.getTotalDex()) * watk / 100.0d));
        } else if (skillId == NightLord.VENOMOUS_STAR || skillId == Shadower.VENOMOUS_STAB) {
            maxDamage = (long) Math.ceil((18.5d * (bot.getTotalStr() + bot.getTotalLuk()) + bot.getTotalDex() * 2.0d)
                    / 100.0d * bot.calculateMaxBaseDamage(watk));
            minDamage = Math.max(1L, Math.round(maxDamage * 0.8d));
        } else if (skillId == Hermit.SHADOW_MESO && effect != null) {
            maxDamage = (long) Math.floor(effect.getMoneyCon() * 10.0d * 1.5d);
            minDamage = maxDamage;
        } else {
            WeaponType forcedWeaponType = physicalWeaponTypeOverride != null
                    ? physicalWeaponTypeOverride
                    : forcedWeaponTypeForSkill(skillId);
            if (forcedWeaponType != null) {
                double mastery = resolvePhysicalMastery(bot);
                maxDamage = physicalMaxBaseDamage(bot, watk, forcedWeaponType);
                minDamage = physicalMinBaseDamage(bot, watk, forcedWeaponType, mastery);
            } else {
                maxDamage = bot.calculateMaxBaseDamage(watk);
                minDamage = bot.calculateMinBaseDamage(watk, resolvePhysicalMastery(bot));
            }
        }

        if (skillId != 0 && effect != null && skillId != Hermit.SHADOW_MESO) {
            int skillDamage = effect.getDamagePercent();
            if (skillDamage > 0) {
                maxDamage = maxDamage * skillDamage / 100L;
                minDamage = minDamage * skillDamage / 100L;
            }
        }

        return normalizeDamageProfile(minDamage, maxDamage, false, false);
    }

    private WeaponType forcedWeaponTypeForSkill(int skillId) {
        return switch (skillId) {
            case DragonKnight.SPEAR_CRUSHER -> WeaponType.SPEAR_STAB;
            case DragonKnight.POLE_ARM_CRUSHER -> WeaponType.POLE_ARM_STAB;
            case DragonKnight.SPEAR_DRAGON_FURY -> WeaponType.SPEAR_SWING;
            case DragonKnight.POLE_ARM_DRAGON_FURY -> WeaponType.POLE_ARM_SWING;
            default -> null;
        };
    }

    // Base damage for a skill resolved against an explicit weapon type: MUST use the same per-weapon/job
    // stat selection the equipped-weapon path uses (Character.calculateMaxBaseDamage picks LUK for
    // thief daggers/claws, DEX for bow/xbow/gun, STR otherwise). A local STR-primary formula here gimped
    // every LUK/DEX class ~2-15x — in the grind model, in skill scoring, and in the damage bots actually
    // dealt — which is why high-level bandits were farming level-30 maps.
    private long physicalMaxBaseDamage(Character bot, int watk, WeaponType weaponType) {
        return bot.calculateMaxBaseDamage(watk, weaponType);
    }

    private long physicalMinBaseDamage(Character bot, int watk, WeaponType weaponType, double mastery) {
        return Math.max(1L, bot.calculateMinBaseDamage(watk, mastery, weaponType));
    }

    private DamageProfile resolveMagicDamageProfile(Character bot, int skillId, StatEffect effect, int healTargetCount) {
        long maxDamage;
        long minDamage;
        if (skillId == Cleric.HEAL && effect != null) {
            // Client-side GMS v83 formula (credit: Russt / Devil's Sunrise via Ayumilove / SouthPerry):
            //   MAX = (INT*1.2 + LUK) * MATK / 1000 * TargetMultiplier * HealRate%
            //   MIN = (INT*0.3 + LUK) * MATK / 1000 * TargetMultiplier * HealRate%
            //   TargetMultiplier = 1.5 + 5 / N   (N = caster + undead hit)
            // The prior (INT*4.8 + LUK*4) shortcut hard-coded N=2 (4.0×), making solo clerics
            // underdamage by ~40% vs a real client that rolls 6.5× for N=1. HealRate% is the WZ
            // "hp" field on the skill effect (10→300 across Heal lv1→30).
            double targetMultiplier = 1.5d + 5.0d / Math.max(1, healTargetCount);
            double matkOverK = bot.getTotalMagic() / 1000.0d;
            double baseMax = (bot.getTotalInt() * 1.2d + bot.getTotalLuk()) * matkOverK * targetMultiplier;
            double baseMin = (bot.getTotalInt() * 0.3d + bot.getTotalLuk()) * matkOverK * targetMultiplier;
            int healRate = Math.max(0, effect.getHp());
            maxDamage = Math.round(baseMax) * healRate / 100L;
            minDamage = Math.max(1L, Math.round(baseMin) * healRate / 100L);
        } else {
            int matk = bot.getTotalMagic();
            int totalInt = bot.getTotalInt();
            // Mastery from skill data x field (integer percent, e.g. 15 → 15%).
            // Falls back to minimum mastery (10%) when no skill effect is available.
            double mastery = effect != null ? Math.max(0.1, effect.getX() / 100.0) : 0.1;

            // MAX formula — same as anti-cheat in AbstractDealDamageHandler.parseDamage
            maxDamage = magicDamageBase(matk, totalInt);
            maxDamage = applyMagicAmplification(bot, maxDamage);
            if (effect != null && effect.getMatk() > 0) {
                maxDamage *= effect.getMatk();
            }

            // MIN formula — uses actual spell mastery from skill data
            minDamage = magicDamageBaseMin(matk, totalInt, mastery);
            minDamage = applyMagicAmplification(bot, minDamage);
            if (effect != null && effect.getMatk() > 0) {
                minDamage *= effect.getMatk();
            }
            minDamage = Math.max(1L, minDamage);
        }
        return normalizeDamageProfile(minDamage, maxDamage, true, false);
    }

    private long applyMagicAmplification(Character bot, long baseDamage) {
        int amplificationSkillId = switch (bot.getJob()) {
            case IL_ARCHMAGE, IL_MAGE -> ILMage.ELEMENT_AMPLIFICATION;
            case FP_ARCHMAGE, FP_MAGE -> FPMage.ELEMENT_AMPLIFICATION;
            case BLAZEWIZARD3, BLAZEWIZARD4 -> BlazeWizard.ELEMENT_AMPLIFICATION;
            case EVAN7, EVAN8, EVAN9, EVAN10 -> Evan.MAGIC_AMPLIFICATION;
            default -> 0;
        };
        if (amplificationSkillId == 0) {
            return baseDamage;
        }

        Skill amplificationSkill = SkillFactory.getSkill(amplificationSkillId);
        if (amplificationSkill == null) {
            return baseDamage;
        }

        int amplificationLevel = bot.getSkillLevel(amplificationSkill);
        if (amplificationLevel <= 0) {
            return baseDamage;
        }

        StatEffect amplificationEffect = amplificationSkill.getEffect(amplificationLevel);
        return baseDamage * amplificationEffect.getY() / 100L;
    }

    private int[] applyMonsterDefense(Character bot, Monster target, int minDamage, int maxDamage, boolean magicAttack) {
        int levelDelta = Math.max(0, target.getLevel() - bot.getLevel());
        double adjustedMinDamage;
        double adjustedMaxDamage;
        if (magicAttack) {
            int monsterMagicDefense = target.getMdef();
            adjustedMaxDamage = maxDamage - monsterMagicDefense * 0.5 * (1.0 + 0.01 * levelDelta);
            adjustedMinDamage = minDamage - monsterMagicDefense * 0.6 * (1.0 + 0.01 * levelDelta);
        } else {
            int monsterWeaponDefense = target.getWdef();
            double levelFactor = 1.0 - 0.01 * levelDelta;
            adjustedMaxDamage = maxDamage * levelFactor - monsterWeaponDefense * 0.5;
            adjustedMinDamage = minDamage * levelFactor - monsterWeaponDefense * 0.6;
        }
        int normalizedMinDamage = Math.max(1, (int) adjustedMinDamage);
        int normalizedMaxDamage = Math.max(normalizedMinDamage, (int) adjustedMaxDamage);
        return new int[]{normalizedMinDamage, normalizedMaxDamage};
    }

    private DamageProfile normalizeDamageProfile(long minDamage, long maxDamage,
                                                 boolean magicAttack, boolean alwaysHit) {
        int normalizedMaxDamage = clampDamage(maxDamage);
        int normalizedMinDamage = Math.min(normalizedMaxDamage, clampDamage(minDamage));
        return new DamageProfile(normalizedMinDamage, normalizedMaxDamage, magicAttack, alwaysHit);
    }

    private int clampDamage(long damage) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, damage));
    }

    private int getFlatAccuracy(Character bot) {
        Integer buffedAccuracy = bot.getBuffedValue(BuffStat.ACC);
        int buffAccuracy = buffedAccuracy != null ? buffedAccuracy : 0;
        int passiveSkillAccuracy = getPassiveSkillAccuracy(bot);
        var equippedInventory = bot.getInventory(InventoryType.EQUIPPED);
        if (equippedInventory == null) {
            return buffAccuracy + passiveSkillAccuracy;
        }

        int equipAccuracy = 0;
        for (Item item : equippedInventory) {
            if (item instanceof Equip equip) {
                equipAccuracy += equip.getAcc();
            }
        }
        return buffAccuracy + passiveSkillAccuracy + equipAccuracy;
    }

    public double resolvePhysicalMastery(Character bot) {
        int masterySkillId = resolveWeaponMasterySkillId(bot);
        if (masterySkillId == 0) {
            return 0.1d;
        }

        int skillLevel = bot.getSkillLevel(masterySkillId);
        if (skillLevel <= 0) {
            return 0.1d;
        }

        Skill masterySkill = SkillFactory.getSkill(masterySkillId);
        if (masterySkill == null) {
            return 0.1d;
        }

        StatEffect effect = masterySkill.getEffect(skillLevel);
        if (effect == null) {
            return 0.1d;
        }

        return Math.max(0.1d, 0.1d + effect.getMastery() / 20.0d);
    }

    private int resolveWeaponMasterySkillId(Character bot) {
        Item weaponItem = bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        if (weaponItem == null) {
            return 0;
        }

        WeaponType weaponType = resolveWeaponType(weaponItem.getItemId());

        return switch (weaponType) {
            case SWORD1H, SWORD2H -> bot.getJob().isA(Job.DAWNWARRIOR1) ? DawnWarrior.SWORD_MASTERY
                    : bot.getJob().isA(Job.PAGE) ? Page.SWORD_MASTERY
                    : Fighter.SWORD_MASTERY;
            case GENERAL1H_SWING, GENERAL1H_STAB, GENERAL2H_SWING, GENERAL2H_STAB ->
                    bot.getJob().isA(Job.PAGE) ? Page.BW_MASTERY : Fighter.AXE_MASTERY;
            case SPEAR_STAB, SPEAR_SWING -> Spearman.SPEAR_MASTERY;
            case POLE_ARM_SWING, POLE_ARM_STAB -> bot.getJob().isA(Job.ARAN1) ? Aran.POLEARM_MASTERY : Spearman.POLEARM_MASTERY;
            case BOW -> bot.getJob().isA(Job.WINDARCHER1) ? WindArcher.BOW_MASTERY : Hunter.BOW_MASTERY;
            case CROSSBOW -> Crossbowman.CROSSBOW_MASTERY;
            case CLAW -> bot.getJob().isA(Job.NIGHTWALKER1) ? NightWalker.CLAW_MASTERY : Assassin.CLAW_MASTERY;
            case DAGGER_THIEVES -> Bandit.DAGGER_MASTERY;
            case KNUCKLE -> bot.getJob().isA(Job.THUNDERBREAKER1) ? ThunderBreaker.KNUCKLER_MASTERY : Brawler.KNUCKLER_MASTERY;
            case GUN -> Gunslinger.GUN_MASTERY;
            default -> 0;
        };
    }

    private WeaponType resolveWeaponType(int itemId) {
        return switch (itemId / 10000) {
            case 130 -> WeaponType.SWORD1H;
            case 131, 141 -> WeaponType.GENERAL1H_SWING;
            case 132, 142 -> WeaponType.GENERAL2H_SWING;
            case 133 -> WeaponType.DAGGER_THIEVES;
            case 143 -> WeaponType.SPEAR_STAB;
            case 144 -> WeaponType.POLE_ARM_SWING;
            case 145 -> WeaponType.BOW;
            case 146 -> WeaponType.CROSSBOW;
            case 147 -> WeaponType.CLAW;
            case 148 -> WeaponType.KNUCKLE;
            case 149 -> WeaponType.GUN;
            default -> WeaponType.NOT_A_WEAPON;
        };
    }

    /**
     * Weapon Mastery skill IDs. In v83 WZ, these store the per-level accuracy bonus in the
     * `x` field (and the mastery percent tier in `mastery`); the `acc` field is absent.
     * Non-weapon "mastery" skills (Shield/High/Magic/Stun) don't grant weapon accuracy and
     * are intentionally excluded.
     */
    private static final Set<Integer> WEAPON_MASTERY_SKILLS = Set.of(
            constants.skills.Fighter.SWORD_MASTERY,
            constants.skills.Fighter.AXE_MASTERY,
            constants.skills.Page.SWORD_MASTERY,
            constants.skills.Page.BW_MASTERY,
            constants.skills.Spearman.SPEAR_MASTERY,
            constants.skills.Spearman.POLEARM_MASTERY,
            constants.skills.Hunter.BOW_MASTERY,
            constants.skills.Crossbowman.CROSSBOW_MASTERY,
            constants.skills.Assassin.CLAW_MASTERY,
            constants.skills.Bandit.DAGGER_MASTERY,
            constants.skills.Brawler.KNUCKLER_MASTERY,
            constants.skills.Gunslinger.GUN_MASTERY,
            constants.skills.DawnWarrior.SWORD_MASTERY,
            constants.skills.WindArcher.BOW_MASTERY,
            constants.skills.NightWalker.CLAW_MASTERY,
            constants.skills.ThunderBreaker.KNUCKLER_MASTERY,
            constants.skills.Aran.POLEARM_MASTERY
    );

    /**
     * Sum of accuracy from leveled passive skills (mastery `x` field + any `acc` field on
     * other passives) that the client adds to its hit formula but the server otherwise
     * never sees. Active buffs contribute via {@link BuffStat#ACC} and are skipped here.
     */
    public int getPassiveSkillAccuracy(Character bot) {
        int total = 0;
        try {
            for (var entry : bot.getSkills().entrySet()) {
                int level = entry.getValue().skillevel;
                if (level <= 0) continue;
                Skill skill = entry.getKey();
                StatEffect effect = skill.getEffect(level);
                if (effect == null || effect.isOverTime()) continue;
                total += effect.getAcc();
                if (WEAPON_MASTERY_SKILLS.contains(skill.getId())) {
                    total += effect.getX();
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        return total;
    }

    private int getFlatAvoidability(Character bot) {
        Integer buffedAvoidability = bot.getBuffedValue(BuffStat.AVOID);
        int buffAvoidability = buffedAvoidability != null ? buffedAvoidability : 0;
        var equippedInventory = bot.getInventory(InventoryType.EQUIPPED);
        if (equippedInventory == null) {
            return buffAvoidability;
        }

        int equipAvoidability = 0;
        for (Item item : equippedInventory) {
            if (item instanceof Equip equip) {
                equipAvoidability += equip.getAvoid();
            }
        }
        return buffAvoidability + equipAvoidability;
    }
}
