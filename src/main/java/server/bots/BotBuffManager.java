package server.bots;

import client.BuffStat;
import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.PlayerBuffValueHolder;
import net.server.channel.handlers.UseItemHandler;
import server.ItemInformationProvider;
import server.StatEffect;
import server.combat.CombatFormulaProvider;
import server.life.Monster;
import tools.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages automatic use of buff consumable items from the bot's USE inventory.
 *
 * Validity check: BotInventoryManager.isBuffConsumable (has statups) - same predicate used by
 * inv? and the trade/drop "buff" category, so all commands are consistent.
 *
 * Default: off. Configured via chat ("buff on/off", "buff cheap/max").
 */
final class BotBuffManager {

    private static final long TICK_MS = 3_000;
    private static final double ACC_HIT_THRESHOLD = 0.90;

    /** Cheap mode skips WATK/MATK buffs stronger than this; pricier high-tier pots aren't worth it. */
    static final int CHEAP_ATK_CAP = 12;

    // Shared across bot ticks, so keep the cache concurrent.
    private static final Map<Integer, StatEffect> fxCache = new ConcurrentHashMap<>();

    private record SelectedBuff(Item item, StatEffect effect, List<BuffStat> statKey, int score) {}

    private record ActiveBuff(int itemId, String name, StatEffect effect, int remainingMs) {}

    private BotBuffManager() {}

    /** Shots-to-kill at/above which an autopilot bot turns cheap buff pots on; below the disengage
     *  value it turns its own auto-enable back off (hysteresis avoids flapping on borderline maps). */
    static final double AUTO_BUFF_SHOTS_TO_KILL = 3.0;
    static final double AUTO_BUFF_DISENGAGE_SHOTS = 2.5;
    private static final long AUTO_BUFF_EVAL_MS = 5_000;

    public static void tick(BotEntry entry, Character bot) {
        autoEngageForToughMobs(entry, bot);
        if (!entry.buffConsumablesEnabled) return;

        long now = System.currentTimeMillis();
        if (now - entry.lastBuffScanMs < TICK_MS) return;
        entry.lastBuffScanMs = now;

        // Party level-gap idle-leech: parked, not fighting — buff pots are wasted items.
        if (entry.idleLeech) {
            noteDecision(entry, "idle-leech: buff pots paused");
            return;
        }

        if (bot.getMap().getAllMonsters().stream().noneMatch(Monster::isAlive)) return;

        List<SelectedBuff> selected = buildSelection(bot, entry.buffCheapMode);
        if (selected.isEmpty()) {
            noteDecision(entry, "no buff pots in bag");
            return;
        }

        for (SelectedBuff choice : selected) {
            if (!needsAnyBuffStat(entry, bot, choice.effect())) {
                continue;
            }

            Item item = choice.item();
            short beforeQty = item.getQuantity();
            String itemName = itemName(item.getItemId());

            if (!UseItemHandler.consumeUseItem(bot, item.getPosition(), item.getItemId())) {
                noteDecision(entry, "tried " + itemName + " but apply failed");
                continue;
            }

            noteDecision(entry, "used " + itemName + " " + beforeQty + "->" + item.getQuantity()
                    + " [" + formatStatList(bot, choice.effect()) + "]");
            return;
        }

        noteDecision(entry, "buff pots ready, but all matching stats are already active");
    }

    public static String getChatSummary(boolean enabled, boolean cheapMode, Character bot) {
        List<ActiveBuff> active = collectActiveItemBuffs(bot);
        List<SelectedBuff> available = buildSelection(bot, cheapMode);

        return "buff pots " + (enabled ? "on" : "off")
                + " (" + (cheapMode ? "cheap" : "max") + ")"
                + ": active " + summarizeActive(active, 2, bot)
                + "; bag " + summarizeAvailable(available, 2, bot);
    }

    public static List<String> getDebugLines(BotEntry entry, Character bot) {
        List<String> lines = new ArrayList<>(2);
        lines.add(formatDebugState(entry) + "; active: " + summarizeActive(collectActiveItemBuffs(bot), 5, bot));
        lines.add("bag: " + summarizeAvailable(buildSelection(bot, entry.buffCheapMode), 5, bot));
        return lines;
    }

    static String formatDebugState(BotEntry entry) {
        return "buff " + (entry.buffConsumablesEnabled ? "on" : "off")
                + "(" + (entry.buffCheapMode ? "cheap" : "best") + ")";
    }

    private static List<SelectedBuff> buildSelection(Character bot, boolean cheapMode) {
        Inventory use = bot.getInventory(InventoryType.USE);
        Map<List<BuffStat>, SelectedBuff> best = new LinkedHashMap<>();

        for (short slot = 1; slot <= use.getSlotLimit(); slot++) {
            Item item = use.getItem(slot);
            if (item == null || item.getQuantity() <= 0) continue;

            int itemId = item.getItemId();
            if (!BotInventoryManager.isBuffConsumable(itemId)) continue;

            StatEffect fx = fxCache.computeIfAbsent(itemId, id -> BotInventoryManager.useEffect.effect(id));
            if (fx == null || fx.getStatups().isEmpty()) continue;

            List<BuffStat> statKey = buildStatKey(bot, fx);
            if (statKey.isEmpty()) continue;

            if (cheapMode && exceedsCheapAtkCap(bot, fx)) continue;

            SelectedBuff candidate = new SelectedBuff(item, fx, statKey, scoreEffect(bot, fx));
            SelectedBuff current = best.get(statKey);
            if (current == null || isBetterChoice(candidate, current, cheapMode)) {
                best.put(statKey, candidate);
            }
        }

        List<SelectedBuff> result = new ArrayList<>(best.values());
        result.sort((left, right) -> {
            int scoreCmp = cheapMode
                    ? Integer.compare(left.score(), right.score())
                    : Integer.compare(right.score(), left.score());
            if (scoreCmp != 0) {
                return scoreCmp;
            }
            return itemName(left.item().getItemId()).compareTo(itemName(right.item().getItemId()));
        });
        return result;
    }

    /** The buff pots this bot would actually use - the best item per relevant buff stat-key under
     *  BOTH max and cheap selection (so whichever mode is active, incl. its WATK/MATK pick, is
     *  covered). BotInventoryManager protects these as the combat runway; inferior/duplicate buff
     *  stacks shelf and only sell under bag pressure. */
    static java.util.Set<Item> runwayBuffItems(Character bot) {
        java.util.Set<Item> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (SelectedBuff choice : buildSelection(bot, false)) {
            out.add(choice.item());
        }
        for (SelectedBuff choice : buildSelection(bot, true)) {
            out.add(choice.item());
        }
        return out;
    }

    /** Autopilot: turn cheap buff pots on when the map is hard enough to be worth it, off when it
     *  isn't. "Hard" = the bot's best single-target shot needs >= AUTO_BUFF_SHOTS_TO_KILL hits to
     *  drop the toughest live mob (SSOT damage benchmark estimateBestSkillHitDamage, the same the
     *  grind advisor uses). Only undoes its OWN enable (autoBuffEngaged); a manual "buff on/off"
     *  clears that flag so the owner always wins. */
    static void autoEngageForToughMobs(BotEntry entry, Character bot) {
        if (entry == null || bot == null || !BotAutopilotManager.isActive(entry)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - entry.lastAutoBuffEvalMs < AUTO_BUFF_EVAL_MS) {
            return;
        }
        entry.lastAutoBuffEvalMs = now;
        if (bot.getMap() == null) {
            return;
        }
        Monster hardest = null;
        for (Monster monster : bot.getMap().getAllMonsters()) {
            if (monster.isAlive() && (hardest == null || monster.getMaxHp() > hardest.getMaxHp())) {
                hardest = monster;
            }
        }
        if (hardest == null) {
            return;
        }
        double perShot = BotCombatManager.estimateBestSkillHitDamage(entry, bot, hardest);
        if (perShot <= 0) {
            return;
        }
        double shots = hardest.getMaxHp() / perShot;
        if (shots >= AUTO_BUFF_SHOTS_TO_KILL && !entry.buffConsumablesEnabled) {
            entry.buffConsumablesEnabled = true;
            entry.buffCheapMode = true;
            entry.autoBuffEngaged = true;
            entry.lastBuffScanMs = 0;
            noteDecision(entry, "tough map (~" + Math.round(shots) + " shots/kill) - cheap buffs on");
        } else if (shots < AUTO_BUFF_DISENGAGE_SHOTS && entry.autoBuffEngaged) {
            entry.buffConsumablesEnabled = false;
            entry.autoBuffEngaged = false;
            noteDecision(entry, "easy map (~" + Math.round(shots) + " shots/kill) - auto buffs off");
        }
    }

    private static boolean needsAnyBuffStat(BotEntry entry, Character bot, StatEffect fx) {
        for (Pair<BuffStat, Integer> statup : fx.getStatups()) {
            BuffStat stat = statup.getLeft();
            if (bot.getBuffedValue(stat) != null) {
                continue;
            }
            if (!isRelevantBuffStat(bot, stat)) {
                continue;
            }
            if (stat == BuffStat.ACC && !needsAccBuff(entry, bot)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean needsAccBuff(BotEntry entry, Character bot) {
        Monster ref = entry.grindTarget;
        if (ref == null) {
            for (Monster monster : bot.getMap().getAllMonsters()) {
                if (monster.isAlive()) {
                    ref = monster;
                    break;
                }
            }
        }
        if (ref == null) return false;
        return CombatFormulaProvider.getInstance().calculateMobHitChance(bot, ref) < ACC_HIT_THRESHOLD;
    }

    private static List<ActiveBuff> collectActiveItemBuffs(Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<ActiveBuff> active = new ArrayList<>();

        for (PlayerBuffValueHolder holder : bot.getAllBuffs()) {
            StatEffect effect = holder.effect;
            if (effect == null || effect.isSkill()) {
                continue;
            }

            int itemId = effect.getSourceId();
            if (!BotInventoryManager.isBuffConsumable(itemId)) {
                continue;
            }
            if (buildStatKey(bot, effect).isEmpty()) {
                continue;
            }

            int remainingMs = effect.getDuration() > 0
                    ? Math.max(0, effect.getDuration() - holder.usedTime)
                    : 0;
            active.add(new ActiveBuff(itemId,
                    safeName(ii.getName(itemId), itemId),
                    effect,
                    remainingMs));
        }

        active.sort(Comparator.comparing(ActiveBuff::name));
        return active;
    }

    private static List<BuffStat> buildStatKey(Character bot, StatEffect fx) {
        List<BuffStat> key = new ArrayList<>();
        for (Pair<BuffStat, Integer> statup : fx.getStatups()) {
            BuffStat stat = statup.getLeft();
            if (isRelevantBuffStat(bot, stat) && !key.contains(stat)) {
                key.add(stat);
            }
        }
        key.sort(Comparator.naturalOrder());
        return key;
    }

    private static int scoreEffect(Character bot, StatEffect fx) {
        int score = 0;
        for (Pair<BuffStat, Integer> statup : fx.getStatups()) {
            if (isRelevantBuffStat(bot, statup.getLeft())) {
                score += Math.abs(statup.getRight());
            }
        }
        return score;
    }

    /** True if a relevant WATK/MATK statup exceeds {@link #CHEAP_ATK_CAP}; cheap mode skips these. */
    static boolean exceedsCheapAtkCap(Character bot, StatEffect fx) {
        for (Pair<BuffStat, Integer> statup : fx.getStatups()) {
            BuffStat stat = statup.getLeft();
            if ((stat == BuffStat.WATK || stat == BuffStat.MATK)
                    && isRelevantBuffStat(bot, stat)
                    && statup.getRight() > CHEAP_ATK_CAP) {
                return true;
            }
        }
        return false;
    }

    static boolean isRelevantBuffStat(Character bot, BuffStat stat) {
        boolean mageJob = bot.getJobStyle() == Job.MAGICIAN;
        if (stat == BuffStat.WATK) {
            return !mageJob;
        }
        if (stat == BuffStat.MATK) {
            return mageJob;
        }
        return true;
    }

    private static boolean isBetterChoice(SelectedBuff candidate, SelectedBuff current, boolean cheapMode) {
        if (candidate.score() != current.score()) {
            return cheapMode ? candidate.score() < current.score() : candidate.score() > current.score();
        }

        if (candidate.effect().getDuration() != current.effect().getDuration()) {
            return cheapMode
                    ? candidate.effect().getDuration() < current.effect().getDuration()
                    : candidate.effect().getDuration() > current.effect().getDuration();
        }

        return cheapMode
                ? candidate.item().getItemId() < current.item().getItemId()
                : candidate.item().getItemId() > current.item().getItemId();
    }

    private static String summarizeActive(List<ActiveBuff> active, int limit, Character bot) {
        if (active.isEmpty()) {
            return "none";
        }

        StringJoiner joiner = new StringJoiner(", ");
        int count = Math.min(limit, active.size());
        for (int i = 0; i < count; i++) {
            ActiveBuff buff = active.get(i);
            String suffix = buff.remainingMs() > 0 ? " " + formatAge(buff.remainingMs()) + " left" : "";
            joiner.add(buff.name() + " [" + formatStatList(bot, buff.effect()) + suffix + "]");
        }
        if (active.size() > limit) {
            joiner.add("+" + (active.size() - limit) + " more");
        }
        return joiner.toString().toLowerCase(Locale.ROOT);
    }

    private static String summarizeAvailable(List<SelectedBuff> available, int limit, Character bot) {
        if (available.isEmpty()) {
            return "none in bag";
        }

        StringJoiner joiner = new StringJoiner(", ");
        int count = Math.min(limit, available.size());
        for (int i = 0; i < count; i++) {
            SelectedBuff buff = available.get(i);
            joiner.add(itemName(buff.item().getItemId()) + " x" + buff.item().getQuantity()
                    + " [" + formatStatList(bot, buff.effect()) + "]");
        }
        if (available.size() > limit) {
            joiner.add("+" + (available.size() - limit) + " more");
        }
        return joiner.toString().toLowerCase(Locale.ROOT);
    }

    private static String formatStatList(Character bot, StatEffect fx) {
        StringJoiner joiner = new StringJoiner("/");
        for (Pair<BuffStat, Integer> statup : fx.getStatups()) {
            if (!isRelevantBuffStat(bot, statup.getLeft())) {
                continue;
            }
            int value = statup.getRight();
            joiner.add(statup.getLeft().name() + (value >= 0 ? "+" : "") + value);
        }
        return joiner.toString();
    }

    private static String formatAge(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0) {
            return seconds + "s";
        }
        return minutes + "m" + seconds + "s";
    }

    private static void noteDecision(BotEntry entry, String summary) {
        entry.lastBuffActionAtMs = System.currentTimeMillis();
        entry.lastBuffActionSummary = summary;
    }

    private static String itemName(int itemId) {
        return safeName(ItemInformationProvider.getInstance().getName(itemId), itemId);
    }

    private static String safeName(String name, int itemId) {
        return name == null || name.isBlank() ? String.valueOf(itemId) : name;
    }
}
