package server.bots;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.processor.stat.SkillBookProcessor;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.StatEffect;
import tools.Pair;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Autonomous fourth-job skill/mastery-book demand and use. */
final class BotSkillBookManager {
    @FunctionalInterface
    interface WantedNowLookup {
        boolean wanted(BotEntry entry, Character bot, int itemId);
    }

    @FunctionalInterface
    interface BookUse {
        SkillBookProcessor.Result use(Character bot, short slot, int itemId);
    }

    static WantedNowLookup wantedNow = BotSkillBookManager::canUseWantedBookNow;
    static BookUse bookUse = SkillBookProcessor::useSkillBook;
    static java.util.function.BiConsumer<BotEntry, Character> assignSp = BotBuildManager::autoAssignSp;

    private BotSkillBookManager() {
    }

    static boolean isSkillBook(int itemId) {
        return ItemConstants.isSkillBook(itemId);
    }

    /** A book is wanted when it raises a skill in this bot's chosen build, even before its
     * reqSkillLevel is reached. This keeps future Mastery 30 books from being sold or given away. */
    static boolean wantsBook(BotEntry entry, Character bot, int itemId) {
        if (entry == null || bot == null || !isSkillBook(itemId)) {
            return false;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<String, Integer> stats = ii.getSkillStats(itemId, bot.getJob().getId());
        if (stats == null) {
            return false;
        }
        int skillId = stats.getOrDefault("skillid", 0);
        client.Skill skill = client.SkillFactory.getSkill(skillId);
        if (skillId <= 0 || skill == null) {
            return false;
        }
        int plannedTarget = BotBuildManager.plannedSkillTarget(entry, bot, skillId);
        int currentCap = bot.getMasterLevel(skill);
        int bookCap = stats.getOrDefault("masterLevel", 0);
        return raisesPlannedCap(skillId, Math.min(plannedTarget, bookCap), currentCap);
    }

    static boolean canUseWantedBookNow(BotEntry entry, Character bot, int itemId) {
        return wantsBook(entry, bot, itemId)
                && ItemInformationProvider.getInstance().canUseSkillBook(bot, itemId);
    }

    static boolean needsBookAcquisition(BotEntry entry, Character bot, int itemId) {
        return wantsBook(entry, bot, itemId)
                && bot.getInventory(InventoryType.USE).countById(itemId) <= 0;
    }

    static boolean isMissingAnyWantedBook(BotEntry entry, Character bot) {
        for (int id = ItemConstants.SKILL_BOOK_FIRST; id <= ItemConstants.SKILL_BOOK_LAST; id++) {
            if (needsBookAcquisition(entry, bot, id)) {
                return true;
            }
        }
        for (int id = ItemConstants.MASTERY_BOOK_FIRST; id <= ItemConstants.MASTERY_BOOK_LAST; id++) {
            if (needsBookAcquisition(entry, bot, id)) {
                return true;
            }
        }
        return false;
    }

    static boolean raisesPlannedCap(int skillId, int plannedTarget, int currentCap) {
        return skillId > 0 && plannedTarget > currentCap;
    }

    // Statup -> the equip-stat key BotScrollManager's scorer reads, so a skill's grant is priced by the
    // SAME function that prices an equip's. ACC is deliberately absent: equipValueFromStats has no
    // accuracy term either (the grind path prices accuracy separately, via accuracyHitFactor), so
    // including it here would double-count against gear.
    private static final Map<BuffStat, String> STATUP_EQUIP_STAT = Map.of(
            BuffStat.WATK, "PAD", BuffStat.MATK, "MAD",
            BuffStat.WDEF, "PDD", BuffStat.MDEF, "MDD",
            BuffStat.AVOID, "EVA", BuffStat.SPEED, "Speed", BuffStat.JUMP, "Jump");

    /**
     * Expected gain from unlocking this book's skill cap, as a FRACTION of the bot's current worn
     * offense — the same unit {@link BotGrindAdvisor} gives an equip drop, so the grind planner ranks
     * books and gear on one scale with no book-specific term.
     *
     * <p>Two components, summed (a skill normally has only one):
     * <ul>
     *   <li><b>stat grants</b> (Maple Warrior, Sharp Eyes, Hyper Body, and any flat WATK/WDEF/…): the
     *       statup delta between the two caps, translated to the equip-stat vocabulary and priced by
     *       {@link BotScrollManager#equipValueFromStats} — the scorer equips and scrolls already use;
     *   <li><b>attack power</b> (Genesis, Dragon Roar, …): damage-per-attack at the new cap versus the
     *       bot's best current attack, which is a fractional DPS gain by construction.
     * </ul>
     * Scaled by the book's own success chance. Returns 0 when the book raises nothing the offense model
     * can see (pure mitigation passives such as Achilles) — those simply aren't farmed for.
     */
    static double capGainFraction(BotEntry entry, Character bot, int itemId, double totalWornOffense) {
        if (!needsBookAcquisition(entry, bot, itemId)) {
            return 0.0;
        }
        Map<String, Integer> stats = ItemInformationProvider.getInstance()
                .getSkillStats(itemId, bot.getJob().getId());
        int skillId = stats.getOrDefault("skillid", 0);
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return 0.0;
        }
        double success = Math.clamp(stats.getOrDefault("success", 0) / 100.0, 0.0, 1.0);
        if (success <= 0.0) {
            return 0.0;
        }
        int currentCap = bot.getMasterLevel(skill);
        int newCap = Math.min(BotBuildManager.plannedSkillTarget(entry, bot, skillId),
                stats.getOrDefault("masterLevel", 0));
        if (newCap <= currentCap) {
            return 0.0;
        }
        return success * (statGainFraction(bot, skill, currentCap, newCap, totalWornOffense)
                + attackGainFraction(entry, bot, skill, newCap));
    }

    /** Stat-grant half: both caps priced by the shared equip scorer, differenced. Scored as whole maps
     *  rather than a delta map because the scorer's best-role term is not linear in its inputs. */
    private static double statGainFraction(Character bot, Skill skill, int currentCap, int newCap,
                                           double totalWornOffense) {
        double gain = BotScrollManager.equipValueFromStats(bot, statGrantAt(bot, skill, newCap))
                - BotScrollManager.equipValueFromStats(bot, statGrantAt(bot, skill, currentCap));
        return Math.max(0.0, gain) / Math.max(1.0, totalWornOffense);
    }

    /** The skill's stat grant at {@code level}, in the equip-stat vocabulary. Percentage buffs resolve
     *  against the bot's own base stats exactly as {@code Character.recalcLocalStats} applies them. */
    static Map<String, Integer> statGrantAt(Character bot, Skill skill, int level) {
        Map<String, Integer> out = new HashMap<>();
        StatEffect effect = level > 0 ? skill.getEffect(level) : null;
        if (effect == null) {
            return out;
        }
        for (Pair<BuffStat, Integer> up : effect.getStatups()) {
            String equipStat = STATUP_EQUIP_STAT.get(up.getLeft());
            if (equipStat != null) {
                out.merge(equipStat, up.getRight(), Integer::sum);
                continue;
            }
            int pct = up.getRight();
            switch (up.getLeft()) {
                case MAPLE_WARRIOR -> {
                    out.merge("STR", bot.getStr() * pct / 100, Integer::sum);
                    out.merge("DEX", bot.getDex() * pct / 100, Integer::sum);
                    out.merge("INT", bot.getInt() * pct / 100, Integer::sum);
                    out.merge("LUK", bot.getLuk() * pct / 100, Integer::sum);
                }
                case HYPERBODYHP -> out.merge("MHP", (int) (bot.getCurrentMaxHp() * (long) pct / 100), Integer::sum);
                case HYPERBODYMP -> out.merge("MMP", (int) (bot.getCurrentMaxMp() * (long) pct / 100), Integer::sum);
                default -> { } // no equip-stat equivalent (Sharp Eyes' packed crit, utility buffs)
            }
        }
        return out;
    }

    /** Attack half: what the skill would hit for at the new cap, over the bot's best attack today.
     *  Zero unless the raised skill actually overtakes what the bot already swings. */
    private static double attackGainFraction(BotEntry entry, Character bot, Skill skill, int newCap) {
        double raised = attackPower(skill, newCap);
        double best = 0.0;
        for (int skillId : BotCombatManager.cachedAttackSkillIds(entry)) {
            Skill owned = SkillFactory.getSkill(skillId);
            if (owned != null) {
                best = Math.max(best, attackPower(owned, bot.getSkillLevel(owned)));
            }
        }
        return best > 0.0 ? Math.max(0.0, raised - best) / best : 0.0;
    }

    /** Per-attack offense of a skill at a level: damage% across its lines — the same product
     *  {@code BotCombatManager} ranks single-target skills by. */
    private static double attackPower(Skill skill, int level) {
        StatEffect effect = level > 0 ? skill.getEffect(level) : null;
        if (effect == null || effect.getDamagePercent() <= 0) {
            return 0.0;
        }
        return (double) effect.getDamagePercent() * BotCombatManager.effectiveHitCount(effect);
    }

    static void tick(BotEntry entry, Character bot, long nowMs) {
        if (entry == null || bot == null || nowMs < entry.nextSkillBookUseAtMs
                || bot.getTrade() != null || entry.marketBusy) {
            return;
        }
        Item book = null;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (wantedNow.wanted(entry, bot, item.getItemId())) {
                book = item;
                break;
            }
        }
        if (book == null) {
            entry.nextSkillBookUseAtMs = nowMs + 15_000L;
            return;
        }

        // One book at a time with a small human-like pause before another attempt.
        entry.nextSkillBookUseAtMs = nowMs + ThreadLocalRandom.current().nextLong(2_500L, 6_501L);
        SkillBookProcessor.Result result = bookUse.use(
                bot, book.getPosition(), book.getItemId());
        if (result != null && result.success()) {
            assignSp.accept(entry, bot);
        }
    }
}
