package server.bots;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Equip.ScrollResult;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ModifyInventory;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.id.ItemId;
import constants.inventory.EquipSlot;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.life.LifeFactory;
import server.life.Monster;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * Bot self-scrolling (companion scope, owner-confirmed). Scans the bot's worn equips and owned
 * scrolls, asks {@link BotScrollPlanner} for the single best play, proposes it to the owner one item
 * at a time, and on confirmation applies the scroll through the same path the player
 * {@code ScrollHandler} uses ({@link ItemInformationProvider#scrollEquipWithId}).
 *
 * <p>v1 scope: only NON-destroying scrolls are auto-proposed (any scroll with a boom/{@code cursed}
 * chance is skipped), so this first build cannot destroy gear. {@link BotScrollPlanner} already
 * handles boom-capable scrolls (fallback + strongly-positive EV); wiring those in — with slot
 * fallback detection — is the next increment.
 *
 * <p>Equip value here is a transparent job-weighted offense metric (attack >> main stat > secondary),
 * a v1 stand-in for the full equip-optimizer / reproduction-cost DP in docs/bot/economy-design.md.
 */
final class BotScrollManager {

    private static final double ATT_WEIGHT = 5.0;
    /** MATK is deliberately valued like a stat point, NOT like watk — even for mages. */
    private static final double MATK_WEIGHT = 1.0;
    private static final double MAIN_STAT_WEIGHT = 1.0;
    private static final double SECONDARY_STAT_WEIGHT = 0.3;

    static final int SCROLL_ITEM_PREFIX = 204; // itemId / 10000 for scroll items

    /** Meso cost assumed for an owned scroll with no NPC-shop price (drop-only). Stub until the
     *  drop-effort→meso / economy ledger lands. */
    private static final int DEFAULT_SCROLL_COST_MESO = 1_000_000;
    /** Opportunity cost of USING an owned scroll, as a fraction of its market price: scrolls are
     *  liquid/valuable, so consuming one forgoes nearly its full sale value. Near 1.0; this is the
     *  per-apply action cost only (the value curve still uses full market price). Could later be a
     *  per-bot personality knob (more/less willing to burn scrolls). */
    private static final double SCROLL_OPPORTUNITY_FRACTION = 0.9;
    /** Per-level meso assumed to acquire a clean base — last-resort fallback only, used when an item
     *  has neither an NPC price nor any known drop source (so farming cost can't be computed). */
    private static final int CLEAN_BASE_COST_PER_LEVEL = 10_000;
    private static final int CLEAN_BASE_COST_FLOOR = 100_000;

    // ---- Farming-cost (rarity→meso) anchors. See BotFarmingCostModel. ----
    /** Effort→meso anchor: how much a second of the bot's farming is worth. The single tunable knob
     *  here (economy-design §9); a future ledger can replace it with the bot's real meso/sec. */
    private static final double FARM_MESO_PER_SECOND = 1_000.0;
    /** Flat per-kill travel/respawn-wait overhead — placeholder for the real mob-commonness term
     *  (spawns/map, #maps, respawn) until the Map-WZ spawn-density cache lands. */
    private static final double FARM_SEEK_OVERHEAD_SECONDS = 3.0;
    /** Producer attack period used as the time-to-kill floor. A coarse constant for now; per-weapon
     *  animation timing (BotEquipManager.weaponCycleMs) is the refinement. */
    private static final double FARM_ATTACK_CYCLE_SECONDS = 0.72;
    /** drop_data chance is out of this denominator (values above it ⇒ guaranteed drop). */
    private static final double DROP_CHANCE_DENOMINATOR = 1_000_000.0;

    /** Lazily-loaded best (highest-chance) dropper per item id: itemId → {mobId, chance}. */
    private static volatile Map<Integer, int[]> bestDropperByItem;

    /** Lazily-loaded cheapest NPC-shop buy price per item id (populate-once cache; all shop items). */
    private static volatile Map<Integer, Integer> shopPrices;

    private BotScrollManager() {}

    /** Owner asked the bot to look for a worthwhile scroll play (one-shot, or chained when enabled). */
    static void requestScrollPass(BotEntry entry, Character bot) {
        if (entry == null || bot == null) {
            return;
        }
        if (entry.pendingAction != null || entry.pendingTradeCategory != null) {
            BotManager.getInstance().botReply(entry, "busy rn, ask me in a sec");
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Resolved resolved = buildBestPlan(entry, bot, ii);
        if (resolved == null) {
            BotManager.getInstance().botReply(entry, explainNoPlan(bot, ii));
            return;
        }

        BotScrollPlanner.ScrollPlan plan = resolved.plan();
        Equip equip = resolved.equip();
        Item scroll = findScroll(bot, plan.scroll().scrollItemId());
        if (equip == null || scroll == null) {
            BotManager.getInstance().botReply(entry, "nvm, my inventory changed");
            return;
        }

        entry.pendingAction = "scroll_confirm";
        entry.pendingScrollEquip = equip;
        entry.pendingScrollScroll = scroll;
        BotManager.getInstance().botReply(entry,
                plan.proposal() + String.format(" (worth ~%,.0f meso to me)", plan.expectedValue()));
    }

    /** Owner replied to a pending scroll proposal. Anything that isn't a clear "yes" cancels. */
    static void handleScrollConfirm(BotEntry entry, String message) {
        String m = message == null ? "" : message.trim().toLowerCase();
        boolean yes = m.matches(".*\\b(yes|yep|yeah|yea|y|ok|okay|sure|do\\s*it|go|confirm)\\b.*");
        entry.pendingAction = null;
        if (yes) {
            Character bot = entry.bot;
            BotManager.after(BotManager.randMs(500, 700), () -> executeConfirmed(entry, bot));
        } else {
            cancelPending(entry);
            BotManager.after(BotManager.randMs(400, 600),
                    () -> BotManager.getInstance().botReply(entry, "ok, ill hold off"));
        }
    }

    /** Clears any pending scroll proposal (e.g. on "scroll off"). */
    static void cancelPending(BotEntry entry) {
        if (entry == null) {
            return;
        }
        if ("scroll_confirm".equals(entry.pendingAction)) {
            entry.pendingAction = null;
        }
        entry.pendingScrollEquip = null;
        entry.pendingScrollScroll = null;
    }

    private static void executeConfirmed(BotEntry entry, Character bot) {
        Item equipItem = entry.pendingScrollEquip;
        Item scrollItem = entry.pendingScrollScroll;
        entry.pendingScrollEquip = null;
        entry.pendingScrollScroll = null;

        if (bot == null || !(equipItem instanceof Equip equip) || scrollItem == null
                || !BotInventoryManager.hasItem(bot, equipItem)
                || !BotInventoryManager.hasItem(bot, scrollItem)
                || equip.getUpgradeSlots() < 1
                || scrollItem.getQuantity() < 1) {
            BotManager.getInstance().botReply(entry, "hmm, cant scroll that anymore");
            return;
        }

        ScrollResult result = applyScroll(bot, equip, scrollItem);
        announce(bot, result, equip);
        if (result != null) {
            BotManager.getInstance().notifyNearbyBotsOfScroll(bot, result, scrollItem.getItemId(), 3_000L);
        }
        // Confirm-each-item chaining: when armed, look for the next worthwhile play after a beat.
        if (entry.selfScrollEnabled && result != null) {
            BotManager.after(BotManager.randMs(2500, 3500), () -> requestScrollPass(entry, bot));
        }
    }

    private static void announce(Character bot, ScrollResult result, Equip equip) {
        String name = ItemInformationProvider.getInstance().getName(equip.getItemId());
        if (name == null || name.isBlank()) {
            name = "gear";
        }
        BotManager mgr = BotManager.getInstance();
        if (result == ScrollResult.SUCCESS) {
            mgr.botSay(bot, "yes! that hit, my " + name + " is better now");
        } else if (result == ScrollResult.CURSE) {
            mgr.botSay(bot, "noo it boomed...");
        } else if (result == ScrollResult.FAIL) {
            mgr.botSay(bot, "aw, failed - lost a slot");
        } else {
            mgr.botSay(bot, "couldnt scroll that, nvm");
        }
    }

    // Mirrors the essential ScrollHandler steps for a bot: scrollEquipWithId rolls AND mutates the
    // equip in place (returns the same ref, or null on a boom), then the scroll is consumed and the
    // client view refreshed. Returns the outcome, or null if the scroll vanished before applying.
    private static ScrollResult applyScroll(Character chr, Equip toScroll, Item scroll) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Client c = chr.getClient();
        int scrollId = scroll.getItemId();
        boolean equipped = toScroll.getPosition() < 0;
        Inventory equipInv = chr.getInventory(equipped ? InventoryType.EQUIPPED : InventoryType.EQUIP);
        Inventory useInv = chr.getInventory(InventoryType.USE);
        byte oldLevel = toScroll.getLevel();
        byte oldSlots = toScroll.getUpgradeSlots();

        Equip scrolled = (Equip) ii.scrollEquipWithId(toScroll, scrollId, false, 0, false);
        ScrollResult result;
        if (scrolled == null) {
            result = ScrollResult.CURSE;
        } else if (scrolled.getLevel() > oldLevel
                || (ItemConstants.isCleanSlate(scrollId) && scrolled.getUpgradeSlots() == oldSlots + 1)
                || ItemConstants.isFlagModifier(scrollId, scrolled.getFlag())) {
            result = ScrollResult.SUCCESS;
        } else {
            result = ScrollResult.FAIL;
        }

        useInv.lockInventory();
        try {
            if (scroll.getQuantity() < 1) {
                return null;
            }
            InventoryManipulator.removeFromSlot(c, InventoryType.USE, scroll.getPosition(), (short) 1, false);
        } finally {
            useInv.unlockInventory();
        }

        List<ModifyInventory> mods = new ArrayList<>();
        if (result == ScrollResult.CURSE) {
            mods.add(new ModifyInventory(3, toScroll));
            equipInv.lockInventory();
            try {
                if (equipped) {
                    chr.unequippedItem(toScroll);
                }
                equipInv.removeItem(toScroll.getPosition());
            } finally {
                equipInv.unlockInventory();
            }
        } else {
            mods.add(new ModifyInventory(3, scrolled));
            mods.add(new ModifyInventory(0, scrolled));
        }
        c.sendPacket(PacketCreator.modifyInventory(true, mods));
        chr.getMap().broadcastMessage(PacketCreator.getScrollEffect(chr.getId(), result, false, false));
        if (equipped && (result == ScrollResult.SUCCESS || result == ScrollResult.CURSE)) {
            chr.equipChanged();
        }
        return result;
    }

    /** A planner decision plus the concrete {@link Equip} it refers to. Item ids are NOT unique — a
     *  bot can own two of the same item with different stats/slots (e.g. a maxed worn copy and a fresh
     *  slotted spare) — so we keep the exact instance rather than re-resolving by id. */
    private record Resolved(BotScrollPlanner.ScrollPlan plan, Equip equip) {}

    private static Resolved buildBestPlan(BotEntry entry, Character bot, ItemInformationProvider ii) {
        IdentityHashMap<BotScrollPlanner.EquipCandidate, Equip> backing = new IdentityHashMap<>();
        ProducerCombat pc = resolveProducerCombat(entry, bot);
        List<BotScrollPlanner.EquipCandidate> candidates = collectCandidates(pc, bot, ii, backing);
        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(candidates);
        return plan == null ? null : new Resolved(plan, backing.get(plan.equip()));
    }

    /** Build a planner candidate for every scrollable equip, recording the backing {@link Equip}. */
    private static List<BotScrollPlanner.EquipCandidate> collectCandidates(ProducerCombat pc, Character bot,
            ItemInformationProvider ii, Map<BotScrollPlanner.EquipCandidate, Equip> backing) {
        List<Equip> all = collectEquips(bot, ii);
        Map<Equip, Short> slotOf = new IdentityHashMap<>();
        for (Equip e : all) {
            Short slot = primarySlot(ii, e.getItemId());
            if (slot != null) {
                slotOf.put(e, slot);
            }
        }

        List<BotScrollPlanner.EquipCandidate> candidates = new ArrayList<>();
        for (Equip eq : all) {
            Short slot = slotOf.get(eq);
            if (slot == null || eq.getUpgradeSlots() < 1) {
                continue;
            }
            List<BotScrollPlanner.ScrollOption> options = buildOptions(pc, bot, ii, eq);
            if (options.isEmpty()) {
                continue;
            }
            double value = offenseValue(bot, eq);
            // betterItemAvailable = enough same-slot items dominate this one (better as-is AND >= slots)
            // to fill the slot, so scrolling here can never make it the bot's best.
            boolean betterAvailable = isDominated(bot, eq, value, slot, all, slotOf,
                    slotCapacity(ii, eq.getItemId()));
            // Per-candidate value = the meso reproduction-cost curve (convex above the clean base):
            // how much this item is worth = cheapest expected meso to reproduce one this good. Built
            // from the item's clean-base score + total upgrade slots + the obtainable scroll set, with
            // a stubbed clean-base cost. This is what makes the DP snowball winners / abandon losers.
            DoubleUnaryOperator valueFn = BotScrollValuer.reproductionValue(
                    baseOffenseValue(bot, ii, eq.getItemId()), totalSlots(ii, eq.getItemId()),
                    reproSpecs(pc, options), cleanBaseCostMeso(pc, ii, eq.getItemId()));
            // Self-combat floor: value of the item the bot WEARS in this slot (no decay). For a worn
            // candidate this equals its own stop-now; for a bag piece it's the rival it must beat.
            Equip worn = wornInSlot(bot, ii, slot);
            double wornRivalValue = worn == null ? 0.0 : reproValueNow(pc, bot, ii, worn);
            // slotsRemaining = free upgrade slots = the DP horizon; totalSlots = catalog tuc, so
            // (totalSlots - slotsRemaining) = slots already consumed (the profit-decay exponent).
            // hasFallbackForSlot stays false (no boom scrolls fed in v1; it only gates destroy scrolls).
            BotScrollPlanner.EquipCandidate c = new BotScrollPlanner.EquipCandidate(
                    eq.getItemId(), equipName(ii, eq.getItemId()),
                    value, eq.getUpgradeSlots(), totalSlots(ii, eq.getItemId()), wornRivalValue,
                    betterAvailable, false, options, valueFn);
            candidates.add(c);
            backing.put(c, eq);
        }
        return candidates;
    }

    /**
     * Debug command ("scroll debug"): dump the full scroll decision for this bot — every candidate
     * equip with its score/slots/meso-value and applicable scrolls, the chosen (piece, scroll) play,
     * and the reproduction-cost table (target stat-score → cheapest meso + optimal first scroll) for
     * the piece the bot would scroll. Written to a file so the whole table survives (chat is tiny).
     */
    static void exportScrollDecision(BotEntry entry, Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append("=== bot scroll decision: ").append(bot.getName())
                .append(" (job ").append(jobId(bot)).append(", lvl ").append(bot.getLevel()).append(") ===\n");

        ProducerCombat pc = resolveProducerCombat(entry, bot);
        appendScrollInventory(sb, pc, bot, ii);

        IdentityHashMap<BotScrollPlanner.EquipCandidate, Equip> backing = new IdentityHashMap<>();
        List<BotScrollPlanner.EquipCandidate> candidates = collectCandidates(pc, bot, ii, backing);

        sb.append("\ncandidates (").append(candidates.size()).append("):\n");
        for (BotScrollPlanner.EquipCandidate c : candidates) {
            sb.append(String.format("  %-22s score=%.1f slots=%d value@now=%,.0f%s%n",
                    c.equipName(), c.currentStatScore(), c.slotsRemaining(),
                    c.value().applyAsDouble(c.currentStatScore()),
                    c.betterItemAvailable() ? " [out-classed -> combat pass skips; profit pass may still scroll-to-sell]" : ""));
            appendWornRival(sb, pc, bot, ii, c.equipItemId());
            for (BotScrollPlanner.ScrollOption op : c.options()) {
                sb.append(String.format("      - %-26s p=%.0f%% +%.1f score, apply-cost=%,.0f meso%n",
                        op.scrollName(), op.successRate() * 100.0, op.statGain(), op.cost()));
            }
        }

        BotScrollPlanner.ScrollPlan plan = BotScrollPlanner.planBest(candidates);
        if (plan == null) {
            sb.append("\nDECISION: nothing worth scrolling -> ").append(explainNoPlan(bot, ii)).append('\n');
        } else {
            sb.append("\nDECISION: scroll '").append(plan.equip().equipName()).append("' with '")
                    .append(plan.scroll().scrollName()).append("'\n");
            sb.append("  driver: ").append(plan.profitDriven()
                    ? "PROFIT (scroll-to-sell, market value decayed 0.9^slots-used)"
                    : "SELF-COMBAT (must beat the worn item in this slot)").append('\n');
            sb.append(String.format("  expected play value (EV at current state): ~%,.0f%n", plan.achievableValue()));
            sb.append(String.format("  value gained vs alternative: %,.0f%n", plan.expectedValue()));
            appendWornRival(sb, pc, bot, ii, plan.equip().equipItemId());
            sb.append("  proposal: ").append(plan.proposal()).append('\n');
            appendReproTable(sb, pc, bot, ii, plan.equip());
        }

        String path = writeReport(bot, sb.toString());
        BotManager.getInstance().botReply(entry,
                path != null ? "scroll debug exported -> " + path : "scroll debug: couldnt write file");
    }

    /**
     * List EVERY scroll in the bot's USE inventory (not just ones tied to a candidate equip), with the
     * raw facts the planner reads and a tag for why each is/ isn't usable. Gives full visibility into
     * the bot's scroll stock — boom-risk, slate/modifier/white, and no-gain scrolls all show here even
     * though the planner skips them.
     */
    private static void appendScrollInventory(StringBuilder sb, ProducerCombat pc, Character bot, ItemInformationProvider ii) {
        List<Item> scrolls = new ArrayList<>();
        for (Item s : bot.getInventory(InventoryType.USE).list()) {
            if (s.getItemId() / 10000 == SCROLL_ITEM_PREFIX) {
                scrolls.add(s);
            }
        }
        sb.append("\nscrolls in inventory (").append(scrolls.size()).append("):\n");
        if (scrolls.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (Item s : scrolls) {
            int sid = s.getItemId();
            Map<String, Integer> st = ii.getEquipStats(sid);
            int success = st == null ? 0 : st.getOrDefault("success", 0);
            int cursed = st == null ? 0 : st.getOrDefault("cursed", 0);
            double gain = st == null ? 0.0 : offenseValueFromStats(bot, st);
            // Show the effective success (incl. SCROLL_SUCCESS_BONUS) the bot actually plans on.
            sb.append(String.format("  %-26s x%-3d  p=%3d%%  +%4.1f score  price=%,11.0f meso  %s%n",
                    scrollName(ii, sid), s.getQuantity(), effectiveSuccessPct(success), gain,
                    scrollPriceMeso(pc, sid), scrollTag(sid, cursed, gain)));
        }
    }

    /** Short reason tag for a scroll in the inventory dump (mirrors the planner's skip rules). */
    private static String scrollTag(int sid, int cursed, double gain) {
        if (sid == ItemId.WHITE_SCROLL) {
            return "[white scroll]";
        }
        if (ItemConstants.isCleanSlate(sid)) {
            return "[clean slate]";
        }
        if (ItemConstants.isModifierScroll(sid)) {
            return "[modifier]";
        }
        if (cursed > 0) {
            return "[boom " + cursed + "% - v1 skip]";
        }
        if (gain <= 0) {
            return "[no offense gain for this bot]";
        }
        return "[usable]";
    }

    /** Append the reproduction-cost table (target → cheapest meso + optimal first scroll) for an equip. */
    private static void appendReproTable(StringBuilder sb, ProducerCombat pc, Character bot,
            ItemInformationProvider ii, BotScrollPlanner.EquipCandidate cand) {
        int itemId = cand.equipItemId();
        List<BotScrollPlanner.ScrollOption> opts = cand.options();
        double baseScore = baseOffenseValue(bot, ii, itemId);
        int tuc = totalSlots(ii, itemId);
        double baseCost = cleanBaseCostMeso(pc, ii, itemId);
        sb.append(String.format(
                "%nreproduction-cost table for %s (base score %.1f, %d total slots, clean base ~%,.0f meso):%n",
                cand.equipName(), baseScore, tuc, baseCost));
        sb.append("  target score |        meso cost | optimal first scroll\n");
        for (BotScrollValuer.CurveRow row : BotScrollValuer.explain(baseScore, tuc, reproSpecs(pc, opts), baseCost)) {
            String move = row.firstScroll() == -2 ? "(at base)"
                    : row.firstScroll() == -1 ? "(abandon + rebuy base)"
                    : opts.get(row.firstScroll()).scrollName();
            sb.append(String.format("  %12.1f | %,16.0f | %s%n", row.target(), row.cost(), move));
        }
        sb.append(String.format("  (current item is at score %.1f with %d free slots)%n",
                cand.currentStatScore(), cand.slotsRemaining()));
    }

    /**
     * Show the item the bot is ACTUALLY WEARING in this candidate's slot, with its reproduction
     * value@now. This is the rival the bot keeps if it does nothing — for a single-capacity slot the
     * scroll play only helps if the candidate's <em>achievable</em> value exceeds this. The planner
     * does NOT use this as a floor today (it measures improvement against the candidate's own current
     * value), so a bag piece can be proposed even when its ceiling stays below the worn item.
     */
    private static void appendWornRival(StringBuilder sb, ProducerCombat pc, Character bot,
            ItemInformationProvider ii, int candidateItemId) {
        Short slot = primarySlot(ii, candidateItemId);
        if (slot == null) {
            return;
        }
        Equip worn = wornInSlot(bot, ii, slot);
        if (worn == null) {
            sb.append("      worn in this slot: (none)\n");
            return;
        }
        sb.append(String.format("      worn in this slot: %-22s score=%.1f slots=%d value@now=%,.0f%s%n",
                equipName(ii, worn.getItemId()), offenseValue(bot, worn), worn.getUpgradeSlots(),
                reproValueNow(pc, bot, ii, worn),
                worn.getItemId() == candidateItemId ? " (this is the worn copy)" : ""));
    }

    /** The non-cash equip the bot currently wears in the given primary slot, or null. */
    static Equip wornInSlot(Character bot, ItemInformationProvider ii, short slot) {
        for (Item it : bot.getInventory(InventoryType.EQUIPPED).list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) {
                Short s = primarySlot(ii, e.getItemId());
                if (s != null && s == slot) {
                    return e;
                }
            }
        }
        return null;
    }

    /** Reproduction value of an equip at its current offense score, using its own value curve. */
    private static double reproValueNow(ProducerCombat pc, Character bot, ItemInformationProvider ii, Equip eq) {
        DoubleUnaryOperator vf = BotScrollValuer.reproductionValue(
                baseOffenseValue(bot, ii, eq.getItemId()), totalSlots(ii, eq.getItemId()),
                reproSpecs(pc, buildOptions(pc, bot, ii, eq)), cleanBaseCostMeso(pc, ii, eq.getItemId()));
        return vf.applyAsDouble(offenseValue(bot, eq));
    }

    private static String writeReport(Character bot, String report) {
        try {
            String safe = bot.getName() == null ? "bot" : bot.getName().replaceAll("[^A-Za-z0-9_]", "");
            java.nio.file.Path dir = java.nio.file.Path.of("logs", "bot-scroll");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path p = dir.resolve("scroll-debug-" + safe + ".txt").toAbsolutePath();
            java.nio.file.Files.writeString(p, report);
            return p.toString();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Worn + bagged equips (non-cash) the bot could plausibly scroll and keep using. Worn items are
     * included unconditionally (already equipped ⇒ wearable); bagged items must actually be wearable by
     * this bot — right job/level/stat reqs and, for weapons, a compatible weapon type — so the bot
     * never burns scrolls on gear it can't use.
     */
    private static List<Equip> collectEquips(Character bot, ItemInformationProvider ii) {
        List<Equip> out = new ArrayList<>();
        for (Item it : bot.getInventory(InventoryType.EQUIPPED).list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) {
                out.add(e);
            }
        }
        for (Item it : bot.getInventory(InventoryType.EQUIP).list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId()) && wearable(bot, ii, e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Non-mutating "can this bot equip it" check (job/level/stat reqs + weapon-type compatibility). */
    static boolean wearable(Character bot, ItemInformationProvider ii, Equip e) {
        return levelsUntilWearable(bot, ii, e, 0) == 0;
    }

    /**
     * How long until the bot can wear this equip: 0 = now, n &gt; 0 = only the LEVEL requirement
     * is pending and within {@code maxLevelsAhead}, -1 = not foreseeable. Stat/job/fame/weapon
     * requirements must be met with TODAY'S stats — deliberately never projected, because AP
     * builds park the secondary stat at a fixed target (see BotBuildManager), so a low-secondary
     * build never grows into secondary-gated gear and guessing otherwise would chase
     * impossible upgrades. (Primary stat far outruns own-job gear requirements anyway.)
     */
    static int levelsUntilWearable(Character bot, ItemInformationProvider ii, Equip e, int maxLevelsAhead) {
        int id = e.getItemId();
        Short slot = primarySlot(ii, id);
        if (slot != null && slot == (short) -11
                && !BotEquipManager.isWeaponCompatible(bot, ii.getWeaponType(id))) {
            return -1;
        }
        if (ii.meetsEquipRequirements(e, bot.getJob(), bot.getLevel(),
                bot.getTotalStr(), bot.getTotalDex(), bot.getTotalInt(), bot.getTotalLuk(), bot.getFame())) {
            return 0;
        }
        int gap = ii.getEquipLevelReq(id) - bot.getLevel();
        if (gap <= 0 || gap > maxLevelsAhead) {
            return -1;
        }
        // Re-check at the required level: passes iff level was the only blocker.
        return ii.meetsEquipRequirements(e, bot.getJob(), bot.getLevel() + gap,
                bot.getTotalStr(), bot.getTotalDex(), bot.getTotalInt(), bot.getTotalLuk(), bot.getFame())
                ? gap : -1;
    }

    /** Canonical equipment slot for an item id (works for unworn bag items too); null if not equippable. */
    static Short primarySlot(ItemInformationProvider ii, int itemId) {
        EquipSlot eslot = EquipSlot.getFromTextSlot(ii.getEquipmentSlot(itemId));
        if (eslot == null || eslot == EquipSlot.PET_EQUIP) {
            return null;
        }
        short p = (short) eslot.getPrimarySlot();
        return p == 0 ? null : p;
    }

    /**
     * True when this equip is out-classed for its slot: at least {@code capacity} other same-slot
     * equips each strictly dominate it (better as-is value AND ≥ upgrade slots), where capacity is how
     * many can be worn at once (4 for rings, 1 for most). A dominator can always stay ahead no matter
     * how this one is scrolled, so once enough exist to fill the slot(s) this one is benched and
     * scrolling it is wasted. A weaker base with MORE slots is deliberately NOT dominated — its scroll
     * potential can still surpass a maxed-out rival, which is exactly the case we want to chase.
     */
    private static boolean isDominated(Character bot, Equip eq, double value, Short slot,
                                       List<Equip> all, Map<Equip, Short> slotOf, int capacity) {
        int better = 0;
        for (Equip other : all) {
            if (other == eq || !slot.equals(slotOf.get(other))) {
                continue;
            }
            if (offenseValue(bot, other) > value && other.getUpgradeSlots() >= eq.getUpgradeSlots()) {
                if (++better >= capacity) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How many of this slot can be worn simultaneously (4 for rings, 1 for most). */
    private static int slotCapacity(ItemInformationProvider ii, int itemId) {
        return Math.max(1, EquipSlot.getFromTextSlot(ii.getEquipmentSlot(itemId)).getSlotCount());
    }

    /**
     * Cheap "why nothing?" explanation for when {@link #buildBestPlan} yields no play. Re-walks the
     * same gear/scroll scan at a coarse level (only runs on the no-op path, so cost is irrelevant) and
     * picks the most specific reason: no slots, no fitting scrolls, only boom scrolls (v1 skips those),
     * useless stats, gear too low to be worth it, or simply unfavorable odds.
     */
    private static String explainNoPlan(Character bot, ItemInformationProvider ii) {
        List<Equip> all = collectEquips(bot, ii);
        Map<Equip, Short> slotOf = new IdentityHashMap<>();
        for (Equip e : all) {
            Short slot = primarySlot(ii, e.getItemId());
            if (slot != null) {
                slotOf.put(e, slot);
            }
        }

        boolean anySlotted = false;          // an equip (worn or bagged) with a free upgrade slot
        boolean anyApplicableScroll = false; // a scroll that fits some slotted equip
        boolean anyBoomSkipped = false;      // a fitting scroll skipped only for boom risk (v1)
        boolean anyUsableOption = false;     // fitting + non-boom + positive stat gain
        boolean anyUndominatedUsable = false; // a usable option on gear not already out-classed

        for (Equip eq : all) {
            Short slot = slotOf.get(eq);
            if (slot == null || eq.getUpgradeSlots() < 1) {
                continue;
            }
            anySlotted = true;
            boolean dominated = isDominated(bot, eq, offenseValue(bot, eq), slot, all, slotOf,
                    slotCapacity(ii, eq.getItemId()));
            for (Item s : bot.getInventory(InventoryType.USE).list()) {
                int sid = s.getItemId();
                if (sid / 10000 != SCROLL_ITEM_PREFIX) {
                    continue;
                }
                if (ItemConstants.isCleanSlate(sid) || ItemConstants.isModifierScroll(sid) || sid == ItemId.WHITE_SCROLL) {
                    continue;
                }
                if (!applicable(ii, sid, eq.getItemId())) {
                    continue;
                }
                anyApplicableScroll = true;
                Map<String, Integer> st = ii.getEquipStats(sid);
                if (st == null) {
                    continue;
                }
                int success = st.getOrDefault("success", 0);
                int cursed = st.getOrDefault("cursed", 0);
                if (cursed > 0) {
                    anyBoomSkipped = true;
                    continue;
                }
                if (success <= 0 || offenseValueFromStats(bot, st) <= 0) {
                    continue;
                }
                anyUsableOption = true;
                if (!dominated) {
                    anyUndominatedUsable = true;
                }
            }
        }

        if (!anySlotted) {
            return "nothing to scroll - my gear's out of upgrade slots";
        }
        if (!anyApplicableScroll) {
            return "i dont have any scrolls that fit my gear";
        }
        if (!anyUsableOption) {
            return anyBoomSkipped
                    ? "i only have boom-risk scrolls, ill skip those for now"
                    : "those scrolls wouldnt add anything useful for me";
        }
        if (!anyUndominatedUsable) {
            return "i already have better gear for those slots, saving the scrolls";
        }
        return "not worth it - the odds dont pay off, ill save the scrolls";
    }

    private static List<BotScrollPlanner.ScrollOption> buildOptions(ProducerCombat pc, Character bot, ItemInformationProvider ii, Equip eq) {
        List<BotScrollPlanner.ScrollOption> options = new ArrayList<>();
        for (Item s : bot.getInventory(InventoryType.USE).list()) {
            int sid = s.getItemId();
            if (sid / 10000 != SCROLL_ITEM_PREFIX) {
                continue;
            }
            if (ItemConstants.isCleanSlate(sid) || ItemConstants.isModifierScroll(sid) || sid == ItemId.WHITE_SCROLL) {
                continue;
            }
            if (!applicable(ii, sid, eq.getItemId())) {
                continue;
            }
            Map<String, Integer> st = ii.getEquipStats(sid);
            if (st == null) {
                continue;
            }
            int success = st.getOrDefault("success", 0);
            int cursed = st.getOrDefault("cursed", 0);
            if (success <= 0 || cursed > 0) {
                continue; // v1: skip destroy-capable scrolls entirely
            }
            double gain = offenseValueFromStats(bot, st);
            if (gain <= 0) {
                continue;
            }
            // Use the EFFECTIVE success rate the server will actually roll (raw WZ success +
            // SCROLL_SUCCESS_BONUS, capped at 100) so the DP odds match reality, not the catalog.
            // ScrollOption.cost = the per-apply opportunity cost (fraction of market price). The
            // reproduction value curve separately uses the FULL market price (see reproSpecs).
            options.add(new BotScrollPlanner.ScrollOption(sid, scrollName(ii, sid),
                    effectiveSuccessPct(success) / 100.0,
                    0.0, gain, SCROLL_OPPORTUNITY_FRACTION * scrollPriceMeso(pc, sid)));
        }
        return options;
    }

    static boolean applicable(ItemInformationProvider ii, int scrollId, int equipId) {
        List<Integer> reqs = ii.getScrollReqs(scrollId);
        if (reqs != null && !reqs.isEmpty()) {
            return reqs.contains(equipId);
        }
        return (scrollId / 100) % 100 == (equipId / 10000) % 100;
    }

    private static Item findScroll(Character bot, int itemId) {
        for (Item s : bot.getInventory(InventoryType.USE).list()) {
            if (s.getItemId() == itemId && s.getQuantity() >= 1) {
                return s;
            }
        }
        return null;
    }

    // ---- Transparent job-weighted offense value (v1 stand-in for the equip optimizer) ----

    static double offenseValue(Character bot, Equip eq) {
        boolean[] mage = new boolean[1];
        char[] ms = mainSecondary(jobId(bot), mage);
        double att = mage[0] ? MATK_WEIGHT * eq.getMatk() : ATT_WEIGHT * eq.getWatk();
        return att
                + MAIN_STAT_WEIGHT * statOfEquip(eq, ms[0])
                + SECONDARY_STAT_WEIGHT * statOfEquip(eq, ms[1]);
    }

    static double offenseValueFromStats(Character bot, Map<String, Integer> st) {
        boolean[] mage = new boolean[1];
        char[] ms = mainSecondary(jobId(bot), mage);
        double att = mage[0] ? MATK_WEIGHT * st.getOrDefault("MAD", 0)
                : ATT_WEIGHT * st.getOrDefault("PAD", 0);
        return att
                + MAIN_STAT_WEIGHT * st.getOrDefault(statKey(ms[0]), 0)
                + SECONDARY_STAT_WEIGHT * st.getOrDefault(statKey(ms[1]), 0);
    }

    // ---- Scroll headroom: what an open upgrade slot is worth (used by grind planning) ----

    /** Fraction of the best per-slot scroll EV counted as an open slot's value. Below 1 on
     *  purpose: scrolls still have to be obtained and survive their success roll over time,
     *  so headroom should tilt close calls (a slotted-but-weaker drop can beat a maxed-out
     *  better one) without sending bots to re-farm gear every few levels for marginal
     *  slot count alone. */
    static final double SCROLL_HEADROOM_FRACTION = 0.5;

    /** Usable catalog scrolls per equip category ((equipId/10000)%100), filtered by the same
     *  rules {@link #buildOptions} applies to owned scrolls: no meta scrolls (clean slate /
     *  modifier / white), no boom risk, positive success. Built once from the item catalog. */
    private static volatile Map<Integer, List<Integer>> scrollsByCategory;

    private static Map<Integer, List<Integer>> scrollsByCategory(ItemInformationProvider ii) {
        Map<Integer, List<Integer>> cached = scrollsByCategory;
        if (cached != null) {
            return cached;
        }
        Map<Integer, List<Integer>> m = new HashMap<>();
        for (Pair<Integer, String> item : ii.getAllItems()) {
            int sid = item.getLeft();
            if (sid / 10000 != SCROLL_ITEM_PREFIX) {
                continue;
            }
            if (ItemConstants.isCleanSlate(sid) || ItemConstants.isModifierScroll(sid) || sid == ItemId.WHITE_SCROLL) {
                continue;
            }
            Map<String, Integer> st = ii.getEquipStats(sid);
            if (st == null || st.getOrDefault("success", 0) <= 0 || st.getOrDefault("cursed", 0) > 0) {
                continue;
            }
            List<Integer> reqs = ii.getScrollReqs(sid);
            if (reqs != null && !reqs.isEmpty()) {
                Set<Integer> cats = new HashSet<>();
                for (int equipId : reqs) {
                    cats.add((equipId / 10000) % 100);
                }
                for (int cat : cats) {
                    m.computeIfAbsent(cat, k -> new ArrayList<>()).add(sid);
                }
            } else {
                m.computeIfAbsent((sid / 100) % 100, k -> new ArrayList<>()).add(sid);
            }
        }
        scrollsByCategory = m;
        return m;
    }

    /** Best expected offense gain ONE upgrade slot can yield on this equip, from the full
     *  scroll catalog through the self-scrolling rules ({@link #applicable}, effective success,
     *  boom/meta skipped). Equip-type based by construction: glove slots price att scrolls,
     *  most other pieces only stat scrolls; 0 when no usable scroll exists for the type. */
    static double bestScrollEvPerSlot(Character bot, ItemInformationProvider ii, int equipId) {
        double best = 0.0;
        for (int sid : scrollsByCategory(ii).getOrDefault((equipId / 10000) % 100, List.of())) {
            if (!applicable(ii, sid, equipId)) {
                continue;
            }
            Map<String, Integer> st = ii.getEquipStats(sid);
            if (st == null) {
                continue;
            }
            double ev = effectiveSuccessPct(st.getOrDefault("success", 0)) / 100.0
                    * offenseValueFromStats(bot, st);
            best = Math.max(best, ev);
        }
        return best;
    }

    /** An equip's worth INCLUDING its remaining upgrade slots — what drop-vs-worn comparisons
     *  should use, so a maxed-out item can lose to a weaker one that still scrolls higher. */
    static double potentialValue(Character bot, ItemInformationProvider ii, Equip eq) {
        return offenseValue(bot, eq)
                + scrollHeadroom(eq.getUpgradeSlots(), bestScrollEvPerSlot(bot, ii, eq.getItemId()));
    }

    /** Pure headroom core: discounted value of open upgrade slots at a per-slot scroll EV. */
    static double scrollHeadroom(int upgradeSlots, double bestEvPerSlot) {
        return SCROLL_HEADROOM_FRACTION * Math.max(0, upgradeSlots) * Math.max(0.0, bestEvPerSlot);
    }

    // ---- Reproduction-cost valuation inputs (v1: shopitems prices + stubbed clean-base cost) ----

    /**
     * Offense value of the CLEAN base (catalog) stats — the reproduction curve's floor. Deliberately
     * NOT godly-uplifted: the floor is the cheapest clean base, and the cheapest source (often an NPC)
     * yields clean catalog stats. Better-than-clean (godly) bases are higher-score products valued by
     * their drop source, a separate acquisition path up the curve — see docs/bot/economy-design.md.
     */
    private static double baseOffenseValue(Character bot, ItemInformationProvider ii, int itemId) {
        Map<String, Integer> st = ii.getEquipStats(itemId);
        return st == null ? 0.0 : offenseValueFromStats(bot, st);
    }

    /** Effective scroll success %, mirroring {@code scrollEquipWithId}: the server's flat
     *  SCROLL_SUCCESS_BONUS is added (capped at 100) when enabled. */
    static int effectiveSuccessPct(int rawSuccess) {
        if (YamlConfig.config.server.SCROLL_SUCCESS_BONUS_ENABLED) {
            return Math.min(rawSuccess + YamlConfig.config.server.SCROLL_SUCCESS_BONUS, 100);
        }
        return rawSuccess;
    }

    /** Total upgrade slots a fresh copy of this item ships with (WZ "tuc"). */
    private static int totalSlots(ItemInformationProvider ii, int itemId) {
        Map<String, Integer> st = ii.getEquipStats(itemId);
        return st == null ? 0 : st.getOrDefault("tuc", 0);
    }

    /** Translate the owned scroll options into reproduction specs. Uses the FULL market price (an item
     *  is worth what it costs to remake), not the discounted per-apply opportunity cost in op.cost(). */
    private static List<BotScrollValuer.ScrollSpec> reproSpecs(ProducerCombat pc, List<BotScrollPlanner.ScrollOption> options) {
        List<BotScrollValuer.ScrollSpec> specs = new ArrayList<>(options.size());
        for (BotScrollPlanner.ScrollOption op : options) {
            specs.add(new BotScrollValuer.ScrollSpec(
                    op.successRate(), op.statGain(), scrollPriceMeso(pc, op.scrollItemId())));
        }
        return specs;
    }

    /**
     * Meso cost to acquire a clean base = the MIN over all sources (cheapest source wins; pricier
     * sources are irrelevant): the NPC shop price if shop-sold, and the drop-farm cost (rarity→meso)
     * if any mob drops it. A rare drop an NPC sells cheaply is therefore priced at the NPC price. If
     * neither source exists (not shop-sold, not dropped), falls back to the reqLevel-scaled placeholder.
     */
    private static double cleanBaseCostMeso(ProducerCombat pc, ItemInformationProvider ii, int itemId) {
        double best = Double.POSITIVE_INFINITY;
        Integer npcPrice = shopPrices().get(itemId);
        if (npcPrice != null) {
            best = npcPrice;
        }
        double farm = farmingCostMeso(pc, itemId);
        if (farm < best) {
            best = farm;
        }
        if (Double.isInfinite(best)) {
            Map<String, Integer> st = ii.getEquipStats(itemId);
            int reqLevel = st == null ? 0 : st.getOrDefault("reqLevel", 0);
            best = Math.max(CLEAN_BASE_COST_FLOOR, reqLevel * CLEAN_BASE_COST_PER_LEVEL);
        }
        return best;
    }

    /**
     * The asking bot's farming combat context, resolved once per pass: its {@link BotEntry} (for the
     * chosen attack skills), the bot itself, and the attack cycle (DPS denominator). Per-mob damage is
     * computed in {@link #farmingCostMeso} via the combat SSOT so it stays magic/physical-correct.
     */
    private record ProducerCombat(BotEntry entry, Character bot, double attackCycleSeconds) {}

    private static ProducerCombat resolveProducerCombat(BotEntry entry, Character bot) {
        return new ProducerCombat(entry, bot, FARM_ATTACK_CYCLE_SECONDS);
    }

    /**
     * Drop-effort → meso (rarity) for an item the <em>asking bot</em> would farm: expected kills (from
     * the item's best drop rate) × realistic capped time-to-kill × the meso/sec anchor. Returns
     * {@code +∞} when no mob drops it or the bot can't damage the dropper, so callers fall back to
     * other sources. Producer per-attack damage uses the combat SSOT
     * ({@link BotCombatManager#estimateBestSkillHitDamage}) — magic vs physical, skill %, lines and mob
     * defense all handled there — falling back to a basic physical hit only when the bot has no skill.
     */
    private static double farmingCostMeso(ProducerCombat pc, int itemId) {
        int[] dropper = bestDropperByItem().get(itemId); // {mobId, chance}
        if (dropper == null) {
            return Double.POSITIVE_INFINITY;
        }
        Monster mob = LifeFactory.getMonster(dropper[0]);
        if (mob == null) {
            return Double.POSITIVE_INFINITY;
        }
        int mobHp = Math.max(1, mob.getMaxHp());
        double perAttack = BotCombatManager.estimateBestSkillHitDamage(pc.entry(), pc.bot(), mob);
        if (perAttack <= 0.0) {
            // No usable attack skill: fall back to a basic physical hit after the mob's defense.
            int mobWdef = mob.getStats() != null ? mob.getStats().getPDDamage() : 0;
            perAttack = BotEquipManager.expectedDamageAfterDef(pc.bot().calculateMaxBaseDamage(pc.bot().getTotalWatk()), mobWdef);
        }
        double dps = perAttack / pc.attackCycleSeconds();
        BotFarmingCostModel.FarmInput in = new BotFarmingCostModel.FarmInput(
                dropper[1] / DROP_CHANCE_DENOMINATOR, mobHp, dps,
                pc.attackCycleSeconds(), FARM_SEEK_OVERHEAD_SECONDS, FARM_MESO_PER_SECOND);
        return BotFarmingCostModel.rarityMeso(in);
    }

    /** Best (highest) {@code drop_data} chance for the item across all droppers (out of 1,000,000),
     *  or 0 when nothing drops it. */
    static int bestDropChance(int itemId) {
        int[] dropper = bestDropperByItem().get(itemId);
        return dropper != null ? dropper[1] : 0;
    }

    /** Lazily-loaded best (highest drop chance) dropper mob per item id, from {@code drop_data}. */
    private static Map<Integer, int[]> bestDropperByItem() {
        Map<Integer, int[]> cached = bestDropperByItem;
        if (cached != null) {
            return cached;
        }
        Map<Integer, int[]> m = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT itemid, dropperid, chance FROM drop_data WHERE chance > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int item = rs.getInt("itemid");
                int chance = rs.getInt("chance");
                int[] cur = m.get(item);
                if (cur == null || chance > cur[1]) {
                    m.put(item, new int[]{rs.getInt("dropperid"), chance});
                }
            }
        } catch (SQLException e) {
            // Leave whatever loaded; farmingCostMeso treats a missing entry as un-farmable.
        }
        bestDropperByItem = m;
        return m;
    }

    /** Meso price of a scroll = MIN over sources: cheapest NPC-shop price, else its drop-farm cost
     *  (rarity→meso). Falls back to a flat default only when it is neither shop-sold nor dropped. */
    private static double scrollPriceMeso(ProducerCombat pc, int scrollId) {
        Integer price = shopPrices().get(scrollId);
        if (price != null) {
            return price;
        }
        double farm = farmingCostMeso(pc, scrollId);
        return Double.isFinite(farm) ? farm : DEFAULT_SCROLL_COST_MESO;
    }

    /**
     * Lazily-loaded cheapest <em>legitimate</em> NPC-shop buy price per item id — all shop items
     * (scrolls AND bases). GM/junk shop listings are excluded: a real shop never sells an item below
     * its NPC sell-back value (that would be free arbitrage), so any listing with
     * {@code buyPrice <= sellBack} (e.g. the 1-meso GM shops) is dropped before taking the min.
     */
    private static Map<Integer, Integer> shopPrices() {
        Map<Integer, Integer> cached = shopPrices;
        if (cached != null) {
            return cached;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<Integer, Integer> prices = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT itemid, price FROM shopitems WHERE price > 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int itemId = rs.getInt("itemid");
                int buy = rs.getInt("price");
                int sellBack = ii.getWholePrice(itemId); // WZ price = NPC sell-back; -1 if unpriced
                if (sellBack > 0 && buy <= sellBack) {
                    continue; // GM/junk listing selling at or below intrinsic value
                }
                Integer prev = prices.get(itemId);
                if (prev == null || buy < prev) {
                    prices.put(itemId, buy); // min over surviving (legitimate) listings
                }
            }
        } catch (SQLException e) {
            // Leave whatever loaded; scrollPriceMeso falls back to DEFAULT_SCROLL_COST_MESO.
        }
        shopPrices = prices;
        return prices;
    }

    private static int jobId(Character bot) {
        return bot.getJob() == null ? 0 : bot.getJob().getId();
    }

    // [main, secondary] stat codes; mageOut[0] set true for magician branches.
    private static char[] mainSecondary(int jobId, boolean[] mageOut) {
        boolean mage = (jobId >= 200 && jobId < 300) || (jobId >= 1200 && jobId < 1300)
                || jobId == 2001 || (jobId >= 2200 && jobId < 2300);
        mageOut[0] = mage;
        if (mage) return new char[]{'i', 'l'};
        if ((jobId >= 300 && jobId < 400) || (jobId >= 1300 && jobId < 1400)) return new char[]{'d', 's'};
        if ((jobId >= 400 && jobId < 500) || (jobId >= 1400 && jobId < 1500)) return new char[]{'l', 'd'};
        if (jobId >= 520 && jobId < 530) return new char[]{'d', 's'};
        if ((jobId >= 510 && jobId < 520) || (jobId >= 1500 && jobId < 1600)) return new char[]{'s', 'd'};
        return new char[]{'s', 'd'};
    }

    private static int statOfEquip(Equip eq, char code) {
        return switch (code) {
            case 's' -> eq.getStr();
            case 'd' -> eq.getDex();
            case 'i' -> eq.getInt();
            case 'l' -> eq.getLuk();
            default -> 0;
        };
    }

    private static String statKey(char code) {
        return switch (code) {
            case 's' -> "STR";
            case 'd' -> "DEX";
            case 'i' -> "INT";
            case 'l' -> "LUK";
            default -> "";
        };
    }

    private static String equipName(ItemInformationProvider ii, int itemId) {
        String name = ii.getName(itemId);
        return name == null || name.isBlank() ? "gear" : name;
    }

    private static String scrollName(ItemInformationProvider ii, int itemId) {
        String name = ii.getName(itemId);
        return name == null || name.isBlank() ? "scroll" : name;
    }
}
