package server.bots;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Equip.ScrollResult;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;

/**
 * Bot self-scrolling (companion scope, owner-confirmed). Scans the bot's worn equips and owned
 * scrolls, asks {@link BotScrollPlanner} for the single best play, proposes it to the owner one item
 * at a time, and on confirmation applies the scroll through the same path the player
 * {@code ScrollHandler} uses ({@link ItemInformationProvider#scrollEquipWithId}).
 *
 * <p>Destroy-capable scrolls carry their boom chance into {@link BotScrollPlanner}. They are only
 * proposed when the candidate has fallback gear for the slot and the expected value still clears
 * stopping after pricing the destroyed branch at zero.
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

    // Survivability / utility weights: small per-point worth so DEFENSIVE & utility gear (capes,
    // shields, accessories — Old Raggedy Cape's +10 avoid, a +HP shield) isn't valued at 0. Kept well
    // below ATT/main-stat on purpose: a point of weapon attack still dwarfs a point of WDEF, but a
    // defensive piece is no longer worthless. ACC is intentionally NOT here — accuracy is owned by the
    // aspirational-grind effective-DPS model (see project_bot_accuracy_aspirational_target), valuing it
    // again per-item would double-count. ponytail: flat weights, tune if bots over/under-value tanky gear.
    private static final double WDEF_WEIGHT = 0.05;
    private static final double MDEF_WEIGHT = 0.04;
    private static final double HP_WEIGHT = 0.02;
    private static final double MP_WEIGHT = 0.01;
    private static final double AVOID_WEIGHT = 0.2;
    private static final double MOVE_WEIGHT = 0.1; // per point of speed or jump

    static final int SCROLL_ITEM_PREFIX = 204; // itemId / 10000 for scroll items

    /** Meso cost assumed for an owned scroll with no NPC-shop price (drop-only). Stub until the
     *  drop-effort→meso / economy ledger lands. */
    private static final int DEFAULT_SCROLL_COST_MESO = 1_000_000;
    /** Meso per unit of expected best-buyer combat value (successRate × stat worth) for the scroll
     *  combat-demand ceiling. Single anchor: Attack 60% (ev = 0.6 × 5×2 = 6) → 4.5M, matching the live
     *  attack-scroll market. ponytail: one knob, retune here; same min(obtainCost, ceiling) shape as ammo. */
    private static final double SCROLL_CEILING_PER_EV = 750_000.0;
    /** Opportunity cost of USING an owned scroll, as a fraction of its market price: scrolls are
     *  liquid/valuable, so consuming one forgoes nearly its full sale value. Near 1.0; this is the
     *  per-apply action cost only (the value curve still uses full market price). Could later be a
     *  per-bot personality knob (more/less willing to burn scrolls). */
    private static final double SCROLL_OPPORTUNITY_FRACTION = 0.9;
    /** Farmable-base hold (item 08, layer 3): when the autopilot is actively steering toward a
     *  clearly-better base for a slot, don't burn scrolls on the inferior base it wears there now.
     *  The clean farmable base's full scrolled potential must beat the current scroll play's
     *  achievable value by this factor to suppress (must be CLEARLY better, not marginal). */
    static double FARMABLE_SAVE_FACTOR = 1.5;
    /** ...and it must be realistically obtainable: expected kills to drop one (1/chancePerKill) must
     *  be at or under this, else the bot would hold scrolls forever chasing a near-phantom drop. */
    static double FARMABLE_MAX_EXPECTED_KILLS = 3_000.0;
    /** Per-level meso assumed to acquire a clean base — last-resort fallback only, used when an item
     *  has neither an NPC price nor any known drop source (so farming cost can't be computed). */
    private static final int CLEAN_BASE_COST_PER_LEVEL = 10_000;
    private static final int CLEAN_BASE_COST_FLOOR = 100_000;

    // ---- Farming-cost (rarity→meso) anchors. See BotFarmingCostModel. ----
    /** Effort→meso anchor: how much a second of the bot's farming is worth. The single tunable knob
     *  here (economy-design §9); a future ledger can replace it with the bot's real meso/sec.
     *  Package-visible: BotFreeMarketManager prices a stall slot's bother off the same anchor,
     *  so the P3 retirement (own observed meso/hr) swaps both call sites at once. */
    static final double FARM_MESO_PER_SECOND = 1_000.0;
    /** FALLBACK per-kill travel/respawn-wait overhead — used only when the spawn index has no
     *  data for the dropper; otherwise {@link #seekOverheadSeconds} supplies real density. */
    private static final double FARM_SEEK_OVERHEAD_SECONDS = 3.0;
    /** FALLBACK producer attack period (time-to-kill floor) — used only when the bot has no
     *  weapon or WZ timing is unavailable; otherwise {@link #producerAttackCycleSeconds}. */
    private static final double FARM_ATTACK_CYCLE_SECONDS = 0.72;
    /** drop_data chance is out of this denominator (values above it ⇒ guaranteed drop). */
    private static final double DROP_CHANCE_DENOMINATOR = 1_000_000.0;

    /** Lazily-loaded best (highest-chance) dropper per item id: itemId → {mobId, chance}. */
    private static volatile Map<Integer, int[]> bestDropperByItem;

    /** Lazily-loaded cheapest NPC-shop buy price per item id (populate-once cache; all shop items). */
    private static volatile Map<Integer, Integer> shopPrices;

    private static final int REPRO_CURVE_CACHE_MAX = 4096;
    private static final Map<ReproCurveKey, DoubleUnaryOperator> reproCurveCache = new ConcurrentHashMap<>();

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
        // Heavy valuation runs off the calling thread (owner "scroll now" command + the post-scroll
        // chain re-scan, both previously inline). Apply/announce back on the scheduler thread.
        scheduleScrollPlan(entry, bot, BotScrollManager::applyRequestedScrollPlan);
    }

    /** Apply an owner-requested (or chained) scroll plan on the scheduler thread, prompting for
     *  confirmation. Unlike the auto path, a null plan explains why nothing is worthwhile. */
    private static void applyRequestedScrollPlan(BotEntry entry, Resolved resolved) {
        Character bot = entry.bot;
        if (bot == null || entry.pendingAction != null || entry.pendingTradeCategory != null) {
            return; // state changed while the plan computed off-thread
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
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
        String proposal = plan.proposal() + String.format(" (worth ~%,.0f meso to me)", plan.expectedValue());
        BotManager.getInstance().botReply(entry, proposal);
        BotPrompt.showOptions(entry, proposal, java.util.List.of("yes", "no", "let me see"));
    }

    /** Owner replied to a pending scroll proposal. Anything that isn't a clear "yes" cancels. */
    static void handleScrollConfirm(BotEntry entry, String message) {
        String m = message == null ? "" : message.trim().toLowerCase();
        // "Let me see" (checked before yes/no, since "ok let me see" also matches yes): hand the gear +
        // scroll to the owner in a trade so they can inspect/decide or scroll it themselves.
        boolean review = m.matches(".*\\b(let\\s*me\\s*(see|look|check|do\\s*it|scroll\\s*it)|lemme\\s*(see|look)"
                + "|show\\s*me|i(?:'?)ll\\s*(do|scroll)\\s*it|trade\\s*it(?:\\s*(?:to\\s*)?me)?)\\b.*");
        if (review) {
            Item equip = entry.pendingScrollEquip;
            Item scroll = entry.pendingScrollScroll;
            entry.pendingAction = null;
            entry.pendingScrollEquip = null;
            entry.pendingScrollScroll = null;
            entry.nextSelfScrollScanAtMs = System.currentTimeMillis() + AUTO_SCAN_DECLINED_BACKOFF_MS;
            BotManager.after(BotManager.randMs(400, 600),
                    () -> BotInventoryManager.startScrollReviewTrade(entry, entry.bot, equip, scroll));
            return;
        }
        boolean yes = m.matches(".*\\b(yes|yep|yeah|yea|y|ok|okay|sure|do\\s*it|go|confirm)\\b.*");
        entry.pendingAction = null;
        if (yes) {
            Character bot = entry.bot;
            BotManager.after(BotManager.randMs(500, 700), () -> executeConfirmed(entry, bot));
        } else {
            cancelPending(entry);
            // Don't re-pitch the same idea on the next scan; "scroll now" overrides anytime.
            entry.nextSelfScrollScanAtMs = System.currentTimeMillis() + AUTO_SCAN_DECLINED_BACKOFF_MS;
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

        boolean useWhite = shouldUseWhiteScroll(entry, bot, equip, scrollItem);
        ScrollResult result = applyScroll(bot, equip, scrollItem, useWhite);
        if (useWhite && result == ScrollResult.FAIL) {
            BotManager.getInstance().botSay(bot, "failed, but the white scroll saved my slot");
        } else {
            announce(bot, result, equip);
        }
        if (result != null) {
            BotManager.getInstance().notifyNearbyBotsOfScroll(bot, result, scrollItem.getItemId(), 3_000L);
        }
        // Confirm-each-item chaining: when armed, look for the next worthwhile play after a beat.
        if (entry.selfScrollEnabled && result != null) {
            if (entry.owner == bot) {
                // Self-owned (takeover): no owner to re-confirm — the auto-scan follows up fast
                // so a success can snowball without spamming the map every few seconds.
                entry.nextSelfScrollScanAtMs = System.currentTimeMillis()
                        + BotManager.randMs(AUTO_SCAN_CHAIN_MIN_MS, AUTO_SCAN_CHAIN_MAX_MS);
            } else {
                BotManager.after(BotManager.randMs(2500, 3500), () -> requestScrollPass(entry, bot));
            }
        }
    }

    // ---- Armed periodic rescan ----
    /** Jittered cadence for the armed auto-scan. */
    private static final int AUTO_SCAN_MIN_MS = 300_000;
    private static final int AUTO_SCAN_MAX_MS = 600_000;
    /** A declined proposal backs the next look off — the owner can always say "scroll now". */
    private static final int AUTO_SCAN_DECLINED_BACKOFF_MS = 900_000;
    /** Quick follow-up after a self-confirmed scroll (snowball-the-winner pacing). */
    private static final int AUTO_SCAN_CHAIN_MIN_MS = 8_000;
    private static final int AUTO_SCAN_CHAIN_MAX_MS = 15_000;

    /**
     * Armed bots look for a worthwhile play on their own instead of only chaining after a
     * confirmed one. Self-owned bots (@botme takeover: owner == bot) are their own owner and
     * confirm themselves after a short beat; companions raise the normal owner proposal and
     * wait. Quiet when nothing qualifies — chat replies stay reserved for explicit asks.
     */
    static void tickAutoScroll(BotEntry entry, Character bot, long nowMs) {
        if (!entry.selfScrollEnabled || bot == null) {
            return;
        }
        if (entry.nextSelfScrollScanAtMs == 0L) { // just armed/loaded: schedule, don't fire now
            entry.nextSelfScrollScanAtMs = nowMs + BotManager.randMs(AUTO_SCAN_MIN_MS, AUTO_SCAN_MAX_MS);
            return;
        }
        if (nowMs < entry.nextSelfScrollScanAtMs
                || entry.pendingAction != null || entry.pendingTradeCategory != null) {
            return;
        }
        // Managed/self-owned bots only self-scroll while resting on a town-break — never mid-grind
        // (companions with an online owner keep proposing anytime, since the owner confirms). The scan
        // stays "due" (timer not re-armed) until the next town-break, then fires and re-arms below.
        boolean managed = entry.owner == null || entry.owner == bot
                || (entry.owner != null && !entry.owner.isLoggedin());
        if (managed && !(BotBreakManager.onBreak(entry, nowMs)
                && bot.getMap() != null && bot.getMap().isTown())) {
            return;
        }
        entry.nextSelfScrollScanAtMs = nowMs + BotManager.randMs(AUTO_SCAN_MIN_MS, AUTO_SCAN_MAX_MS);

        // The plan valuation walks the whole inventory with WZ lookups — far too heavy for the bot tick
        // thread (was maxing cores / stuttering with a party of self-scroll bots). Compute it off-thread
        // on the shared decision pool (serialized to ~one core for ALL bots), then apply the light
        // result back on the scheduler thread where executeConfirmed already runs.
        scheduleScrollPlan(entry, bot, BotScrollManager::applyAutoScrollPlan);
    }

    /** Run {@link #buildBestPlan} off the bot tick thread (the shared {@link BotGrindAdvisor#DECIDE_POOL})
     *  and hand the result to {@code apply} on the scheduler thread. The plan is re-validated at apply
     *  time ({@link #executeConfirmed}/{@link BotInventoryManager#hasItem}), so a stale off-thread read
     *  just yields a no-op and the next scan retries. */
    static void scheduleScrollPlan(BotEntry entry, Character bot, java.util.function.BiConsumer<BotEntry, Resolved> apply) {
        if (entry == null || bot == null || apply == null) {
            return;
        }
        if (!markScrollPlanQueued(entry)) {
            return;
        }
        BotGrindAdvisor.DECIDE_POOL.execute(() -> {
            Resolved resolved;
            // The plan walks the whole inventory with WZ lookups and shares the single DECIDE_POOL with
            // grind/party decides; time it under "scroll-scan" so the perf monitor can show its cost and
            // how often it fires (now gated to town-breaks, so far rarer than the old 90-180s cadence).
            long t0 = BotPerformanceMonitor.start();
            try {
                resolved = buildBestPlan(entry, bot, ItemInformationProvider.getInstance());
            } catch (RuntimeException e) {
                return; // WZ/inventory hiccup off-thread — skip this scan, the timer re-arms
            } finally {
                entry.scrollPlanQueued = false;
                BotPerformanceMonitor.recordSince("scroll-scan", t0);
            }
            final Resolved r = resolved; // may be null (no worthwhile play) — apply decides what to do
            BotManager.after(0, () -> apply.accept(entry, r));
        });
    }

    private static boolean markScrollPlanQueued(BotEntry entry) {
        synchronized (entry) {
            if (entry.scrollPlanQueued) {
                return false;
            }
            entry.scrollPlanQueued = true;
            return true;
        }
    }

    private static boolean markChaosPlanQueued(BotEntry entry) {
        synchronized (entry) {
            if (entry.chaosPlanQueued) {
                return false;
            }
            entry.chaosPlanQueued = true;
            return true;
        }
    }

    /** Apply an auto-scan plan on the scheduler thread. Re-checks the gating state (it may have changed
     *  while the plan computed off-thread) before committing. */
    private static void applyAutoScrollPlan(BotEntry entry, Resolved resolved) {
        Character bot = entry.bot;
        if (bot == null || !entry.selfScrollEnabled
                || entry.pendingAction != null || entry.pendingTradeCategory != null) {
            return;
        }
        if (resolved == null) {
            maybeChaosPlay(entry, bot); // no regular play: maybe gamble a chaos reroll instead
            return;
        }
        Equip equip = resolved.equip();
        Item scroll = findScroll(bot, resolved.plan().scroll().scrollItemId());
        if (equip == null || scroll == null) {
            return;
        }
        entry.pendingScrollEquip = equip;
        entry.pendingScrollScroll = scroll;
        if (entry.owner == bot) {
            BotManager.getInstance().botSay(bot, resolved.plan().proposal() + " ... going for it");
            BotManager.after(BotManager.randMs(1500, 2500), () -> executeConfirmed(entry, bot));
        } else {
            entry.pendingAction = "scroll_confirm";
            BotManager.getInstance().botReply(entry, resolved.plan().proposal()
                    + String.format(" (worth ~%,.0f meso to me)", resolved.plan().expectedValue()));
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
        return applyScroll(chr, toScroll, scroll, false);
    }

    /** {@code useWhite}: protect the slot on a fail, consuming an owned White Scroll — the same
     *  {@code ws} flag/consume flow ScrollHandler runs for players. Silently degrades to an
     *  unprotected apply when no White Scroll is actually in the bag. */
    private static ScrollResult applyScroll(Character chr, Equip toScroll, Item scroll, boolean useWhite) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Client c = chr.getClient();
        int scrollId = scroll.getItemId();
        boolean equipped = toScroll.getPosition() < 0;
        Inventory equipInv = chr.getInventory(equipped ? InventoryType.EQUIPPED : InventoryType.EQUIP);
        Inventory useInv = chr.getInventory(InventoryType.USE);
        byte oldLevel = toScroll.getLevel();
        byte oldSlots = toScroll.getUpgradeSlots();

        Item wscroll = null;
        if (useWhite) {
            wscroll = useInv.findById(ItemId.WHITE_SCROLL);
            if (wscroll == null) {
                useWhite = false;
            }
        }

        Equip scrolled = (Equip) ii.scrollEquipWithId(toScroll, scrollId, useWhite, 0, false);
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
            if (useWhite && !ItemConstants.isCleanSlate(scrollId)) {
                if (wscroll.getQuantity() < 1) {
                    return null;
                }
                InventoryManipulator.removeFromSlot(c, InventoryType.USE, wscroll.getPosition(),
                        (short) 1, false, false);
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
        if (chr.getMap().isObservedByPlayer()) {
            chr.getMap().broadcastMessage(PacketCreator.getScrollEffect(chr.getId(), result, false, false));
        }
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
        if (plan == null) {
            return null;
        }
        if (shouldHoldForFarmableBase(entry, bot, ii, pc, plan)) {
            return null; // hold scrolls: the autopilot is steering to a clearly-better base for this slot
        }
        return new Resolved(plan, backing.get(plan.equip()));
    }

    /**
     * Layer 3 (item 08): suppress scrolling the inferior base the bot wears in a slot when the
     * autopilot is actively farming a clearly-better, realistically-obtainable base for that SAME
     * slot. Compares the clean farmable base's full scrolled potential against the current play's
     * achievable value — both in meso reproduction units (same SSOT) — and holds the scrolls when
     * the farmable base wins by {@link #FARMABLE_SAVE_FACTOR}. The active steering itself is the
     * autopilot's existing gearFocused bias (it already routes the bot to that map); here we only
     * keep the scrolls for the better base instead of wasting them. Inert when not gear-steering.
     */
    private static boolean shouldHoldForFarmableBase(BotEntry entry, Character bot,
            ItemInformationProvider ii, ProducerCombat pc, BotScrollPlanner.ScrollPlan plan) {
        int wantId = entry.wantedGearItemId;
        if (wantId == 0) {
            return false;
        }
        Short wantSlot = primarySlot(ii, wantId);
        Short planSlot = primarySlot(ii, plan.equip().equipItemId());
        if (wantSlot == null || planSlot == null || !wantSlot.equals(planSlot)) {
            return false; // the play isn't on the slot we're farming a better base for
        }
        double chance = entry.wantedGearChancePerKill;
        if (chance <= 0.0 || 1.0 / chance > FARMABLE_MAX_EXPECTED_KILLS) {
            return false; // a near-phantom drop: don't hold scrolls forever, scroll what we have
        }
        double farmablePotential = farmableScrolledEv(pc, bot, ii, wantId);
        double currentPlayPotential = plan.achievableValue();
        return farmablePotential > currentPlayPotential * FARMABLE_SAVE_FACTOR;
    }

    /** Realistic scrolled value (meso reproduction units) of a CLEAN copy of {@code itemId} with all
     *  its upgrade slots — the value the bot could reach by farming this base and scrolling it. Runs
     *  the SAME planner DP as the live candidates (rule #6) so it's directly comparable to a live
     *  play's {@code achievableValue} (both stochastic EVs, not all-success ceilings). Falls back to
     *  the clean base's as-is value when no positive play exists. */
    private static double farmableScrolledEv(ProducerCombat pc, Character bot,
            ItemInformationProvider ii, int itemId) {
        if (!(ii.getEquipById(itemId) instanceof Equip clean)) {
            return 0.0;
        }
        int tuc = totalSlots(ii, itemId);
        if (tuc <= 0) {
            return 0.0;
        }
        List<BotScrollPlanner.ScrollOption> opts = buildOptions(pc, bot, ii, clean);
        if (opts.isEmpty()) {
            return 0.0;
        }
        double base = baseOffenseValue(bot, ii, itemId);
        DoubleUnaryOperator vf = cachedReproductionValue(
                base, tuc, reproSpecs(pc, opts), cleanBaseCostMeso(pc, ii, itemId));
        // wornRivalValue 0 / not-dominated / no fallback: a fresh clean base valued on its own.
        BotScrollPlanner.EquipCandidate c = new BotScrollPlanner.EquipCandidate(
                itemId, equipName(ii, itemId), base, tuc, tuc, 0.0, false, false, false, opts, vf);
        BotScrollPlanner.ScrollPlan p = BotScrollPlanner.planBest(List.of(c));
        return p == null ? vf.applyAsDouble(base) : p.achievableValue();
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
            DoubleUnaryOperator valueFn = cachedReproductionValue(
                    baseOffenseValue(bot, ii, eq.getItemId()), totalSlots(ii, eq.getItemId()),
                    reproSpecs(pc, options), cleanBaseCostMeso(pc, ii, eq.getItemId()));
            // Self-combat floor: value of the item the bot WEARS in this slot (no decay). For a worn
            // candidate this equals its own stop-now; for a bag piece it's the rival it must beat.
            Equip worn = wornInSlot(bot, ii, slot);
            double wornRivalValue = worn == null ? 0.0 : reproValueNow(pc, bot, ii, worn);
            // dominatedByWorn: this is a BAG spare whose full COMBAT ceiling (current offense + the best
            // owned per-slot scroll gain * free slots) still can't reach what the worn copy scores NOW —
            // so scrolling it can never make it the better piece to fight with; never worth scrolling.
            // Compared on combat score, NOT reproduction-meso value: a rarer/pricier base inflates the
            // meso curve but is irrelevant to which piece the bot should actually wear. (Was a slot-count
            // proxy: `worn.slots >= eq.slots`, which passed a weaker base purely for having one more slot
            // — that let a 60-score Hall Staff out-rank the worn 87-score Maple Wisdom Staff. See
            // scroll-debug-Mage.txt; the extra slot is worthless when the base is too weak to catch up.)
            double maxScrollGain = 0.0;
            for (BotScrollPlanner.ScrollOption op : options) {
                maxScrollGain = Math.max(maxScrollGain, op.statGain());
            }
            double scrollCeiling = value + maxScrollGain * eq.getUpgradeSlots();
            boolean dominatedByWorn = worn != null && worn != eq
                    && offenseValue(bot, worn) >= scrollCeiling;
            // slotsRemaining = free upgrade slots = the DP horizon; totalSlots = catalog tuc, so
            // (totalSlots - slotsRemaining) = slots already consumed (the profit-decay exponent).
            BotScrollPlanner.EquipCandidate c = new BotScrollPlanner.EquipCandidate(
                    eq.getItemId(), equipName(ii, eq.getItemId()),
                    value, eq.getUpgradeSlots(), totalSlots(ii, eq.getItemId()), wornRivalValue,
                    betterAvailable, dominatedByWorn, hasFallbackForSlot(all, slotOf, eq, slot), options, valueFn);
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
     * the bot's scroll stock — boom-risk, slate/modifier/white, and no-gain scrolls all show here.
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
            return "[boom " + cursed + "%]";
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
        DoubleUnaryOperator vf = cachedReproductionValue(
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
                && !BotEquipManager.isPreferredWeapon(bot, ii.getWeaponType(id), e)) {
            // Gate farm/scroll on the job's PREFERRED weapon, not mere equip-ability: a
            // fallback weapon the optimizer might equip in a pinch (isWeaponCompatible) is
            // never worth farming or scrolling for - the bot would drop it for its real weapon.
            return -1;
        }
        if (slot != null && slot == (short) -10) {
            // Shields can't join a two-handed build (the optimizer's 2H<->shield exclusivity):
            // a bowman's weapons are ALL 2H, so a shield is never wearable for it in practice.
            Equip weapon = wornInSlot(bot, ii, (short) -11);
            if (weapon != null && ii.isTwoHanded(weapon.getItemId())) {
                return -1;
            }
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
     * picks the most specific reason: no slots, no fitting scrolls, useless stats, gear too low to be
     * worth it, boom scrolls without backup gear, or simply unfavorable odds.
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
        boolean anyBoomWithoutFallback = false; // a fitting boom scroll, but no backup gear for the slot
        boolean anyUsableOption = false;     // fitting + positive stat gain
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
                if (success <= 0 || offenseValueFromStats(bot, st) <= 0) {
                    continue;
                }
                anyUsableOption = true;
                if (cursed > 0 && !hasFallbackForSlot(all, slotOf, eq, slot)) {
                    anyBoomWithoutFallback = true;
                }
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
            return "those scrolls wouldnt add anything useful for me";
        }
        if (!anyUndominatedUsable) {
            return "i already have better gear for those slots, saving the scrolls";
        }
        if (anyBoomWithoutFallback) {
            return "those boom-risk scrolls need backup gear first";
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
            if (success <= 0) {
                continue;
            }
            double gain = offenseValueFromStats(bot, st);
            if (gain <= 0) {
                continue;
            }
            // Use the EFFECTIVE success rate the server will actually roll (raw WZ success +
            // SCROLL_SUCCESS_BONUS, capped at 100) so the DP odds match reality, not the catalog.
            // ScrollOption.cost = the per-apply opportunity cost (fraction of market price). The
            // reproduction value curve separately uses the FULL market price (see reproSpecs).
            // Destroy-capable (cursed) scrolls carry their boom rate; the planner only allows
            // them when the candidate has a fallback equip AND the EV still wins (applyScroll
            // already handles the CURSE outcome: item removed + unequipped).
            options.add(new BotScrollPlanner.ScrollOption(sid, scrollName(ii, sid),
                    effectiveSuccessPct(success) / 100.0,
                    cursed / 100.0, gain, SCROLL_OPPORTUNITY_FRACTION * scrollPriceMeso(pc, sid)));
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

    /** The one offense weighting shared by the bot's own keep/upgrade decisions AND market pricing:
     *  the class camp's attack stat (matk for mages else watk) + main·{@link #MAIN_STAT_WEIGHT} +
     *  secondary·{@link #SECONDARY_STAT_WEIGHT}. The role ([main, secondary], mage flag) is the only
     *  input that differs — the bot's job for self-valuation, the item's implied job for the market. */
    private static double offenseCore(boolean mage, int watk, int matk, int mainStat, int secondaryStat) {
        return (mage ? MATK_WEIGHT * matk : ATT_WEIGHT * watk)
                + MAIN_STAT_WEIGHT * mainStat
                + SECONDARY_STAT_WEIGHT * secondaryStat;
    }

    static double offenseValue(Character bot, Equip eq) {
        boolean[] mage = new boolean[1];
        char[] ms = mainSecondary(jobId(bot), mage);
        return offenseCore(mage[0], eq.getWatk(), eq.getMatk(),
                statOfEquip(eq, ms[0]), statOfEquip(eq, ms[1]));
    }

    static double offenseValueFromStats(Character bot, Map<String, Integer> st) {
        boolean[] mage = new boolean[1];
        char[] ms = mainSecondary(jobId(bot), mage);
        return offenseCore(mage[0], st.getOrDefault("PAD", 0), st.getOrDefault("MAD", 0),
                st.getOrDefault(statKey(ms[0]), 0), st.getOrDefault(statKey(ms[1]), 0));
    }

    /** Job-agnostic best-buyer worth of the stats a scroll grants, for market/trade pricing: attack at
     *  ATT_WEIGHT, magic attack at MATK_WEIGHT, every base stat at its main weight (so an INT scroll keeps
     *  a mage's value even when a warrior bot holds it), plus the small survival terms. SSOT weights, no
     *  job lookup — the counterpart to {@link #offenseValueFromStats} for a tradeable economy. */
    static double marketStatValue(Map<String, Integer> st) {
        double offense = ATT_WEIGHT * st.getOrDefault("PAD", 0)
                + MATK_WEIGHT * st.getOrDefault("MAD", 0)
                + MAIN_STAT_WEIGHT * (st.getOrDefault("STR", 0) + st.getOrDefault("DEX", 0)
                        + st.getOrDefault("INT", 0) + st.getOrDefault("LUK", 0));
        return offense + survivalValueFromStats(st);
    }

    /** Survivability/utility worth of an equip: WDEF/MDEF/HP/MP/avoid/move, each small-weighted so a
     *  defensive piece registers without rivaling attack gear. The counterpart to {@link #offenseValue}
     *  for the stats it ignores. */
    static double survivalValue(Equip eq) {
        return WDEF_WEIGHT * eq.getWdef() + MDEF_WEIGHT * eq.getMdef()
                + HP_WEIGHT * eq.getHp() + MP_WEIGHT * eq.getMp()
                + AVOID_WEIGHT * eq.getAvoid() + MOVE_WEIGHT * (eq.getSpeed() + eq.getJump());
    }

    static double survivalValueFromStats(Map<String, Integer> st) {
        return WDEF_WEIGHT * st.getOrDefault("PDD", 0) + MDEF_WEIGHT * st.getOrDefault("MDD", 0)
                + HP_WEIGHT * st.getOrDefault("MHP", 0) + MP_WEIGHT * st.getOrDefault("MMP", 0)
                + AVOID_WEIGHT * st.getOrDefault("EVA", 0)
                + MOVE_WEIGHT * (st.getOrDefault("Speed", 0) + st.getOrDefault("Jump", 0));
    }

    /** Total worth of an equip to this bot: offense + survivability. The SSOT for "how good is this
     *  piece" in keep/sell/drop-vs-worn/quest-reward decisions (offense alone undervalued capes,
     *  shields, accessories). {@link #offenseValue} stays the pure-DPS sub-metric for scroll EV. */
    static double equipValue(Character bot, Equip eq) {
        return offenseValue(bot, eq) + survivalValue(eq);
    }

    static double equipValueFromStats(Character bot, Map<String, Integer> st) {
        return offenseValueFromStats(bot, st) + survivalValueFromStats(st);
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

    /** Boot warm hook (BotGrindAdvisor.warmGrindData): build the catalog-scroll index off-thread.
     *  This is the dominant one-time cold cost of the first grind gear scan — it parses every
     *  catalog scroll's stats/reqs — so priming it here makes the first decision fast. SSOT: it
     *  just calls the same {@link #scrollsByCategory} builder the scan uses, no parallel logic. */
    static void warmScrollCatalog(ItemInformationProvider ii) {
        scrollsByCategory(ii);
    }

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

    /** Best possible offense gain one upgrade slot can add, ignoring success odds. */
    static double maxScrollOffenseGainPerSlot(Character bot, ItemInformationProvider ii, int equipId) {
        double best = 0.0;
        for (int sid : scrollsByCategory(ii).getOrDefault((equipId / 10000) % 100, List.of())) {
            if (!applicable(ii, sid, equipId)) {
                continue;
            }
            Map<String, Integer> st = ii.getEquipStats(sid);
            if (st == null || st.getOrDefault("success", 0) <= 0 || st.getOrDefault("cursed", 0) > 0) {
                continue;
            }
            best = Math.max(best, offenseValueFromStats(bot, st));
        }
        return best;
    }

    /** An equip's worth INCLUDING its remaining upgrade slots — what drop-vs-worn comparisons
     *  should use, so a maxed-out item can lose to a weaker one that still scrolls higher. */
    static double potentialValue(Character bot, ItemInformationProvider ii, Equip eq) {
        return equipValue(bot, eq)
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
     *  is worth what it costs to remake), not the discounted per-apply opportunity cost in op.cost().
     *  Boom scrolls are excluded: the reproduction DP models slot burn, not destruction, so feeding
     *  them in would overstate the curve — they participate only in the online scroll/stop decision. */
    private static List<BotScrollValuer.ScrollSpec> reproSpecs(ProducerCombat pc, List<BotScrollPlanner.ScrollOption> options) {
        List<BotScrollValuer.ScrollSpec> specs = new ArrayList<>(options.size());
        for (BotScrollPlanner.ScrollOption op : options) {
            if (op.boomRate() > 0.0) {
                continue;
            }
            specs.add(new BotScrollValuer.ScrollSpec(
                    op.successRate(), op.statGain(), scrollPriceMeso(pc, op.scrollItemId())));
        }
        return specs;
    }

    private record ReproSpecKey(long successRateBits, long statGainBits, long mesoCostBits) {}

    private record ReproCurveKey(long baseScoreBits, int tuc, long cleanCostBits, List<ReproSpecKey> specs) {}

    private static DoubleUnaryOperator cachedReproductionValue(double baseScore, int tuc,
            List<BotScrollValuer.ScrollSpec> scrolls, double baseCostMeso) {
        if (tuc <= 0 || scrolls == null || scrolls.isEmpty()) {
            return BotScrollValuer.reproductionValue(baseScore, tuc, scrolls, baseCostMeso);
        }
        List<BotScrollValuer.ScrollSpec> normalized = new ArrayList<>(scrolls.size());
        List<ReproSpecKey> specs = new ArrayList<>(scrolls.size());
        for (BotScrollValuer.ScrollSpec sc : scrolls) {
            if (sc == null) {
                continue;
            }
            normalized.add(sc);
            specs.add(new ReproSpecKey(
                    Double.doubleToLongBits(sc.successRate()),
                    Double.doubleToLongBits(sc.statGain()),
                    Double.doubleToLongBits(sc.mesoCost())));
        }
        if (specs.isEmpty()) {
            return BotScrollValuer.reproductionValue(baseScore, tuc, scrolls, baseCostMeso);
        }
        ReproCurveKey key = new ReproCurveKey(
                Double.doubleToLongBits(baseScore),
                tuc,
                Double.doubleToLongBits(Math.max(0.0, baseCostMeso)),
                List.copyOf(specs));
        if (reproCurveCache.size() > REPRO_CURVE_CACHE_MAX) {
            reproCurveCache.clear();
        }
        return reproCurveCache.computeIfAbsent(key, ignored -> {
            long t0 = BotPerformanceMonitor.start();
            try {
                return BotScrollValuer.reproductionValue(baseScore, tuc, List.copyOf(normalized), baseCostMeso);
            } finally {
                BotPerformanceMonitor.recordSince("scroll-curve-build", t0);
            }
        });
    }

    /** A boom is survivable when ANOTHER usable equip exists for the same slot (worn or bagged) —
     *  the destroy-scroll gate the planner enforces via {@code hasFallbackForSlot}. */
    private static boolean hasFallbackForSlot(List<Equip> all, Map<Equip, Short> slotOf, Equip eq, Short slot) {
        for (Equip other : all) {
            if (other != eq && slot.equals(slotOf.get(other))) {
                return true;
            }
        }
        return false;
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
        return new ProducerCombat(entry, bot, producerAttackCycleSeconds(bot));
    }

    /** The producer's REAL attack period from its worn weapon's WZ animation x speed tier —
     *  the same number the equip optimizer and grind advisor benchmark with
     *  ({@link BotEquipManager#weaponCycleMs}). Falls back to the flat anchor when the bot
     *  has no weapon or WZ timing is unavailable (unit tests). */
    private static double producerAttackCycleSeconds(Character bot) {
        try {
            Item weapon = bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
            if (weapon != null) {
                int cycleMs = BotEquipManager.weaponCycleMs(weapon.getItemId());
                if (cycleMs > 0) {
                    return cycleMs / 1000.0;
                }
            }
        } catch (RuntimeException e) {
            // mocked/partial Character — fall through to the flat anchor
        }
        return FARM_ATTACK_CYCLE_SECONDS;
    }

    /** Per-kill seek overhead from the dropper's REAL spawn density: its best (densest) farmable
     *  map via the spawn index, scored with the grind planner's seek model — replaces the flat
     *  placeholder whenever spawn data exists for the mob. */
    private static double seekOverheadSeconds(int mobId) {
        double best = Double.NaN;
        try {
            BotSpawnIndex.Index index = BotSpawnIndex.get();
            for (BotSpawnIndex.SpawnSite site : BotSpawnIndex.spawnSites(mobId)) {
                BotSpawnIndex.MapSpawns map = index.byMap().get(site.mapId());
                if (map == null || map.town()) {
                    continue;
                }
                double seek = BotGrindPlanner.seekSeconds(map.areaPx(), site.spawnPoints());
                if (Double.isNaN(best) || seek < best) {
                    best = seek;
                }
            }
        } catch (RuntimeException e) {
            // spawn index unavailable (unit tests) — keep the flat placeholder
        }
        return Double.isNaN(best) ? FARM_SEEK_OVERHEAD_SECONDS : best;
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
                pc.attackCycleSeconds(), seekOverheadSeconds(dropper[0]), FARM_MESO_PER_SECOND);
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
        // Chaos/White now have real consumption (chaos gambles, white protection), so the live
        // market consensus prices them; the old 10M stopgap survives only as a cold-market floor
        // until the tape has clearings.
        if (ItemConstants.isChaosScroll(scrollId) || scrollId == ItemId.WHITE_SCROLL) {
            return Math.max(10_000_000.0,
                    BotMarketConsensus.getInstance().consensus(BotMarketMath.priceKey(scrollId, 0)));
        }
        Integer price = shopPrices().get(scrollId);
        if (price != null) {
            return price;
        }
        double farm = farmingCostMeso(pc, scrollId);
        return Double.isFinite(farm) ? farm : DEFAULT_SCROLL_COST_MESO;
    }

    /** USE-bag shelf valuation hook: kept scrolls should be protected by the same market/farm value
     *  self-scrolling uses, not by their usually-low NPC sell-back price. */
    static double scrollMarketValueMeso(Character bot, int scrollId) {
        BotEntry entry = bot == null ? null : BotManager.getInstance().getEntryByBotCharId(bot.getId());
        return scrollPriceMeso(resolveProducerCombat(entry, bot), scrollId);
    }

    /** Combat-demand ceiling for an equip scroll, in meso: the most a best-buyer pays for the combat
     *  value it injects = {@link #SCROLL_CEILING_PER_EV} × successRate × {@link #marketStatValue},
     *  scaled by the target slot's {@link #slotDurabilityFactor} — the same +2 ATT is worth far more
     *  on a glove (stays best-in-slot near-forever) than on a soon-outgrown weapon. The USE-shelf
     *  keeper caps a scroll's worth at min(obtainCost, this) — a flooded/shop-cheap scroll stays at
     *  its obtain cost, an overpriced-but-weak one is pulled down to its real combat value. Because
     *  the planner's per-apply cost is a fraction of this price, the durability premium also makes
     *  bots proportionally more reluctant to burn flat-slot scrolls on marginal gains — behavior and
     *  price calibrate together. Returns 0 for stat-less scrolls (clean slate, chaos, enhancement)
     *  so they keep obtain-cost worth. */
    static double scrollCombatCeilingMeso(int scrollId) {
        Map<String, Integer> st = ItemInformationProvider.getInstance().getEquipStats(scrollId);
        if (st == null) {
            return 0.0;
        }
        int success = st.getOrDefault("success", 0);
        double statWorth = marketStatValue(st);
        return success <= 0 || statWorth <= 0 ? 0.0
                : SCROLL_CEILING_PER_EV * (success / 100.0) * statWorth
                        * slotDurabilityFactor(scrollId / 100 % 100);
    }

    /** Levels of onward progression a scroll investment is judged against when asking how fast a
     *  slot outgrows its gear — a data-shape constant for the durability curve, not a price knob. */
    private static final double REPLACEMENT_HORIZON_LEVELS = 30.0;
    /** Worth (in {@link #marketStatValue} units) of a canonical completed scroll job — five
     *  successful +2 ATT applies. The yardstick a slot's base-stat growth is measured against:
     *  the investment dies when levelling has out-grown roughly this much added worth. */
    private static final double SCROLL_JOB_WORTH = 5 * 2 * ATT_WEIGHT;
    /** Category needs at least this many stat-bearing equips for a trustworthy growth fit. */
    private static final int DURABILITY_MIN_SAMPLES = 8;
    private static volatile Map<Integer, Double> slotDurability;

    /**
     * Investment durability of scrolling equip category {@code cat} (the {@code (id/10000)%100}
     * slot code shared by {@link #applicable}): how long a scrolled piece stays best-in-slot,
     * derived from the WZ equip catalog. Within each category we fit the growth of base
     * {@link #marketStatValue} per required level: weapons grow steeply (a scrolled level-40
     * sword is landfill twenty levels later — short investment life), gloves/shoes/capes are
     * nearly flat (a well-scrolled glove serves forever). Normalized so the WEAPON-category
     * average is 1.0: {@link #SCROLL_CEILING_PER_EV} keeps its live-market Attack-60% anchor and
     * flat-slot scrolls scale UP relative to it — matching the real economy, where armor-ATT
     * scrolls trade far above same-tier weapon scrolls. 1.0 for unknown/sparse categories and on
     * WZ-less test runs (getAllItems empty), so nothing shifts without data.
     */
    static double slotDurabilityFactor(int equipCategory) {
        Map<Integer, Double> cached = slotDurability;
        if (cached == null) {
            cached = buildSlotDurability();
            slotDurability = cached;
        }
        return cached.getOrDefault(equipCategory, 1.0);
    }

    private static Map<Integer, Double> buildSlotDurability() {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<Integer, List<double[]>> samples = new HashMap<>();
        try {
            for (Pair<Integer, String> item : ii.getAllItems()) {
                int id = item.getLeft();
                if (id < 1000000 || id >= 2000000) {
                    continue;
                }
                Map<String, Integer> st = ii.getEquipStats(id);
                if (st == null) {
                    continue;
                }
                double worth = marketStatValue(st);
                if (worth <= 0) {
                    continue;
                }
                samples.computeIfAbsent(id / 10000 % 100, k -> new ArrayList<>())
                        .add(new double[]{st.getOrDefault("reqLevel", 0), worth});
            }
        } catch (RuntimeException e) {
            // WZ unavailable (tests): empty map -> every factor 1.0
        }
        Map<Integer, Double> raw = new HashMap<>();
        double weaponSum = 0;
        int weaponN = 0;
        for (Map.Entry<Integer, List<double[]>> e : samples.entrySet()) {
            List<double[]> pts = e.getValue();
            if (pts.size() < DURABILITY_MIN_SAMPLES) {
                continue;
            }
            double meanX = 0, meanY = 0;
            for (double[] p : pts) {
                meanX += p[0];
                meanY += p[1];
            }
            meanX /= pts.size();
            meanY /= pts.size();
            double cov = 0, var = 0;
            for (double[] p : pts) {
                cov += (p[0] - meanX) * (p[1] - meanY);
                var += (p[0] - meanX) * (p[0] - meanX);
            }
            double slope = var > 0 ? Math.max(0, cov / var) : 0; // absolute worth gain per level
            // A scroll job is outgrown once the slot's base-stat growth overtakes the worth the
            // scrolls added. ABSOLUTE slope vs a fixed payload — NOT slope/meanY: gloves' tiny
            // base worth made their negligible drift look like fast relative growth (0.79 factor
            // when the real economy says ~2x weapons).
            double durability = SCROLL_JOB_WORTH
                    / (SCROLL_JOB_WORTH + slope * REPLACEMENT_HORIZON_LEVELS);
            raw.put(e.getKey(), durability);
            if (e.getKey() >= 30 && e.getKey() <= 49) {
                weaponSum += durability;
                weaponN++;
            }
        }
        double weaponAvg = weaponN > 0 ? weaponSum / weaponN : 1.0;
        Map<Integer, Double> out = new HashMap<>();
        StringBuilder dbg = new StringBuilder();
        for (Map.Entry<Integer, Double> e : raw.entrySet()) {
            double factor = Math.clamp(e.getValue() / weaponAvg, 0.5, 4.0);
            out.put(e.getKey(), factor);
            dbg.append(e.getKey()).append('=').append(String.format(Locale.US, "%.2f", factor)).append(' ');
        }
        if (!out.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(BotScrollManager.class)
                    .info("scroll slot-durability factors (weapon avg = 1.0): {}", dbg.toString().trim());
        }
        return out;
    }

    /** Market (acquisition) value of any shop-bought item — the cheapest legitimate NPC buy price.
     *  Same SSOT map scrolls use, exposed so the USE-shelf ranker can value ammo by what it costs to
     *  re-acquire instead of its near-zero NPC sell-back. 0 when nothing legit sells the item. */
    static int marketBuyPriceMeso(int itemId) {
        Integer buy = shopPrices().get(itemId);
        return buy == null ? 0 : buy;
    }

    // ---- equip market quote (S4): band + reproduction-curve price for one rolled piece ---------

    /** Secondhand discount on scrolled pieces (SoloMapling parity): a buyer pays at most 60% of
     *  what reproducing the piece would cost — below that, crafting your own dominates. The CLEAN
     *  band is never discounted; a clean base is fully fungible with a shop/drop copy. */
    static final double SECONDHAND_DISCOUNT = 0.6;

    /** Representative job for a weapon type so market pricing reuses the {@link #mainSecondary} role
     *  SSOT — a staff prices as a magician's, a bow as a bowman's, etc. */
    private static int weaponRoleJob(WeaponType wt) {
        return switch (wt) {
            case WAND, STAFF -> 200;         // magician:   INT / LUK
            case BOW, CROSSBOW -> 300;       // bowman:     DEX / STR
            case CLAW, DAGGER_OTHER -> 400;  // thief:      LUK / DEX
            case KNUCKLE -> 510;             // brawler:    STR / DEX
            case GUN -> 520;                 // gunslinger: DEX / STR
            default -> 100;                  // warrior:    STR / DEX
        };
    }

    private static int statByCode(char code, int str, int dex, int intel, int luk) {
        return switch (code) {
            case 's' -> str;
            case 'd' -> dex;
            case 'i' -> intel;
            case 'l' -> luk;
            default -> 0;
        };
    }

    /** The four distinct class camps a wearable item could belong to (representative jobs feeding
     *  {@link #mainSecondary}): warrior STR/DEX, mage INT/LUK, bowman DEX/STR, thief LUK/DEX
     *  (gunslinger/brawler collapse onto bowman/warrior stat-wise). */
    private static final int[] MARKET_ROLE_JOBS = {100, 200, 300, 400};

    /** Best-use offense worth of raw stats across every class that could wear the item — the market
     *  values a multi-job piece at the class it serves best, never a blend. E.g. +10STR/+12DEX/+10INT/
     *  +10LUK scores as a bowman (12·1 + 10·0.3); +1watk/+4matk scores as a physical job (1·5 > 4·1),
     *  since a warrior would wear it over a mage. */
    static double bestRoleWorth(int watk, int matk, int str, int dex, int intel, int luk) {
        double best = 0;
        for (int job : MARKET_ROLE_JOBS) {
            boolean[] mage = new boolean[1];
            char[] ms = mainSecondary(job, mage);
            best = Math.max(best, offenseCore(mage[0], watk, matk,
                    statByCode(ms[0], str, dex, intel, luk),
                    statByCode(ms[1], str, dex, intel, luk)));
        }
        return best;
    }

    /** Market worth of an equip's stats via the SAME {@link #offenseCore} + {@link #mainSecondary}
     *  role table the bot uses for itself — only the role source differs. A WEAPON is camp-locked, so
     *  it is scored for the class its type implies (a staff by matk + INT + LUK·secondary, a sword by
     *  watk + STR + DEX). Armor/accessory is worn by every class, so it takes the best-use MAX over
     *  all class camps ({@link #bestRoleWorth}). Either way survival is added on top. This is why a
     *  piece is never priced up by a stat its actual buyers can't use. */
    private static double equipMarketWorth(int itemId, int watk, int matk,
            int str, int dex, int intel, int luk, double survival) {
        WeaponType wt = ItemInformationProvider.getInstance().getWeaponType(itemId);
        if (wt == WeaponType.NOT_A_WEAPON) {
            return bestRoleWorth(watk, matk, str, dex, intel, luk) + survival;
        }
        boolean[] mage = new boolean[1];
        char[] ms = mainSecondary(weaponRoleJob(wt), mage);
        return offenseCore(mage[0], watk, matk,
                statByCode(ms[0], str, dex, intel, luk),
                statByCode(ms[1], str, dex, intel, luk)) + survival;
    }

    /** Market worth of an equip's ACTUAL rolled stats — the {@link Equip}-getter counterpart to
     *  {@link #marketStatValue}, for pricing a specific rolled piece (see {@link #equipMarketWorth}). */
    static double marketStatValueOf(Equip eq) {
        return equipMarketWorth(eq.getItemId(), eq.getWatk(), eq.getMatk(),
                eq.getStr(), eq.getDex(), eq.getInt(), eq.getLuk(), survivalValue(eq));
    }

    /** Same worth as {@link #marketStatValueOf} for an equip's CLEAN catalog stats — used as the band
     *  baseline so the surplus (rolled − clean) is measured on the same weapon-aware axis. */
    static double marketStatValueOfClean(int itemId, Map<String, Integer> st) {
        return equipMarketWorth(itemId,
                st.getOrDefault("PAD", 0), st.getOrDefault("MAD", 0),
                st.getOrDefault("STR", 0), st.getOrDefault("DEX", 0),
                st.getOrDefault("INT", 0), st.getOrDefault("LUK", 0),
                survivalValueFromStats(st));
    }

    /**
     * Market quote for one rolled equip. {@code band} is the shared price-key quality dimension
     * ({@link BotMarketMath#priceKey}): how many common-scroll-successes the piece sits above its
     * clean base — provenance-blind on purpose, a godly clean roll prices like a scrolled one.
     * {@code bandCurve} maps any band to meso along the reproduction-cost curve
     * ({@link BotScrollValuer#reproductionValue} over the catalog-wide scroll set at full market
     * prices), discounted {@link #SECONDHAND_DISCOUNT} above clean; hand it to
     * {@link BotMarketMath#curveCalibration} with the item's traded bands to pin the whole line to
     * live evidence. {@code curveQuoteMeso} is the curve read at this piece's own band. v1 ignores
     * consumed upgrade slots (bands don't encode them). Null when WZ knows no clean stats.
     */
    record EquipQuote(int itemId, int band, long curveQuoteMeso,
                      java.util.function.DoubleUnaryOperator bandCurve,
                      double baseScore, double bandUnit) {}

    static EquipQuote equipMarketQuote(BotEntry entry, Character bot, Equip eq) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<String, Integer> clean = ii.getEquipStats(eq.getItemId());
        if (clean == null) {
            return null;
        }
        ProducerCombat pc = resolveProducerCombat(entry, bot);
        double baseScore = marketStatValueOfClean(eq.getItemId(), clean);
        int tuc = clean.getOrDefault("tuc", 0);
        double[] gains = catalogGains(ii, eq.getItemId());
        double unit = bandUnit(gains);
        int maxBand = maxBand(gains, unit, tuc);
        int band = equipQualityBand(ii, eq);
        double cleanCost = cleanBaseCostMeso(pc, ii, eq.getItemId());
        java.util.function.DoubleUnaryOperator vf = cachedReproductionValue(
                baseScore, tuc, marketReproSpecs(pc, ii, eq.getItemId()), cleanCost);
        java.util.function.DoubleUnaryOperator bandCurve = b -> {
            if (b <= 0 || unit <= 0) {
                return cleanCost;
            }
            // Cap at the slot budget's reachable ceiling: past it the restart DP never terminates
            // in success and its estimate is meaningless (a wild clean roll prices AT the ceiling).
            return SECONDHAND_DISCOUNT * vf.applyAsDouble(baseScore + Math.min(b, maxBand) * unit);
        };
        return new EquipQuote(eq.getItemId(), band, Math.round(bandCurve.applyAsDouble(band)),
                bandCurve, baseScore, unit);
    }

    /** What a best-buyer bot pays for a rolled stall equip: its combat upgrade gain over the
     *  bot's worn piece (potentialValue diff; 0 when unwearable or no upgrade) at the same
     *  meso-per-EV anchor scroll demand uses ({@link #SCROLL_CEILING_PER_EV}), scaled by the
     *  slot's investment durability — a glove upgrade outlives a weapon upgrade for buyers
     *  exactly as it does for scrollers. */
    static long equipBuyCeilingMeso(Character bot, Equip candidate) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Short slot = primarySlot(ii, candidate.getItemId());
        if (slot == null || !ii.canWearEquipment(bot, candidate, slot)) {
            return 0;
        }
        Equip worn = wornInSlot(bot, ii, slot);
        double gain = potentialValue(bot, ii, candidate)
                - (worn == null ? 0.0 : potentialValue(bot, ii, worn));
        if (gain <= 0) {
            return 0;
        }
        return Math.round(SCROLL_CEILING_PER_EV * gain
                * slotDurabilityFactor(candidate.getItemId() / 10000 % 100));
    }

    /** Quality band of a rolled equip — the price-key dimension both sides of a trade must agree
     *  on, so it is deterministic from the WZ catalog alone (no producer/price context): the
     *  piece's job-neutral stat surplus over its clean base, in units of the slot's median
     *  catalog-scroll gain, capped at the slot budget's reachable ceiling. 0 for clean/unknown. */
    static int equipQualityBand(ItemInformationProvider ii, Equip eq) {
        Map<String, Integer> clean = ii.getEquipStats(eq.getItemId());
        if (clean == null) {
            return 0;
        }
        double[] gains = catalogGains(ii, eq.getItemId());
        double unit = bandUnit(gains);
        int band = BotMarketMath.qualityBand(marketStatValueOf(eq) - marketStatValueOfClean(eq.getItemId(), clean), unit);
        return Math.min(band, maxBand(gains, unit, clean.getOrDefault("tuc", 0)));
    }

    /** Catalog-wide reproduction specs for {@code equipId}: the slot's obtainable stat scrolls
     *  ({@link #scrollsByCategory} — meta/boom/zero-success already excluded), gain valued
     *  job-neutrally at FULL market price. The market counterpart to {@link #reproSpecs},
     *  which only sees owned scrolls. */
    private static List<BotScrollValuer.ScrollSpec> marketReproSpecs(ProducerCombat pc,
            ItemInformationProvider ii, int equipId) {
        List<BotScrollValuer.ScrollSpec> specs = new ArrayList<>();
        for (int sid : scrollsByCategory(ii).getOrDefault((equipId / 10000) % 100, List.of())) {
            if (!applicable(ii, sid, equipId)) {
                continue;
            }
            Map<String, Integer> st = ii.getEquipStats(sid);
            double gain = st == null ? 0.0 : marketStatValue(st);
            if (gain > 0) {
                specs.add(new BotScrollValuer.ScrollSpec(
                        effectiveSuccessPct(st.getOrDefault("success", 0)) / 100.0,
                        gain, scrollPriceMeso(pc, sid)));
            }
        }
        return specs;
    }

    /** Sorted positive job-neutral gains of the slot's applicable catalog scrolls. */
    private static double[] catalogGains(ItemInformationProvider ii, int equipId) {
        List<Double> gains = new ArrayList<>();
        for (int sid : scrollsByCategory(ii).getOrDefault((equipId / 10000) % 100, List.of())) {
            if (!applicable(ii, sid, equipId)) {
                continue;
            }
            Map<String, Integer> st = ii.getEquipStats(sid);
            double gain = st == null ? 0.0 : marketStatValue(st);
            if (gain > 0) {
                gains.add(gain);
            }
        }
        double[] out = new double[gains.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = gains.get(i);
        }
        java.util.Arrays.sort(out);
        return out;
    }

    /** The band unit — "one average scroll success" — as the median catalog-scroll gain. */
    private static double bandUnit(double[] sortedGains) {
        return sortedGains.length == 0 ? 0.0 : sortedGains[sortedGains.length / 2];
    }

    private static int maxBand(double[] sortedGains, double unit, int tuc) {
        return unit <= 0 || sortedGains.length == 0 ? 0
                : (int) Math.floor(tuc * sortedGains[sortedGains.length - 1] / unit);
    }

    // ---- chaos & white scroll consumption (S4, owner-specced) ----------------------------------

    /** A piece must sit at least this many bands above clean before a chaos gamble is considered —
     *  the convexity that makes the reroll EV-positive only exists on an already-scrolled piece. */
    private static final int CHAOS_MIN_BAND = 2;

    record ChaosPlay(Equip target, Item scroll, double evMeso, String targetName) {}

    /**
     * Best chaos gamble across the bot's owned chaos scrolls × scrollable pieces, or null. The
     * reroll walks every positive stat ±range symmetrically, but the piece's market value curve
     * is CONVEX in stat score — so on a well-scrolled piece the expected post-reroll VALUE beats
     * the current value (upside bands are worth more than downside bands lose). EV is judged in
     * meso against the chaos scroll's own market cost, with a deterministic per-bot gambler
     * appetite: a gambler plays slightly negative EV for the thrill, a cautious bot wants edge.
     */
    static ChaosPlay bestChaosPlay(BotEntry entry, Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item chaos = null;
        for (Item s : bot.getInventory(InventoryType.USE).list()) {
            if (ItemConstants.isChaosScroll(s.getItemId()) && s.getQuantity() > 0) {
                chaos = s;
                break;
            }
        }
        if (chaos == null) {
            return null;
        }
        Map<String, Integer> st = ii.getEquipStats(chaos.getItemId());
        double p = effectiveSuccessPct(st == null ? 60 : st.getOrDefault("success", 60)) / 100.0;
        ProducerCombat pc = resolveProducerCombat(entry, bot);
        double cost = SCROLL_OPPORTUNITY_FRACTION * scrollPriceMeso(pc, chaos.getItemId());
        int range = YamlConfig.config.server.CHSCROLL_STAT_RANGE;
        double appetite = (bot.getId() * 2654435761L >>> 24 & 0xFF) / 255.0;
        double required = cost * (0.3 - 0.6 * appetite);
        ChaosPlay best = null;
        for (Equip eq : collectEquips(bot, ii)) {
            if (eq.getUpgradeSlots() < 1) {
                continue; // server quirk: a chaos apply still needs (and consumes) a slot
            }
            EquipQuote q = equipMarketQuote(entry, bot, eq);
            if (q == null || q.band() < CHAOS_MIN_BAND || q.bandUnit() <= 0) {
                continue;
            }
            double vNow = q.bandCurve().applyAsDouble(q.band());
            double ev = p * (chaosOutcomeMeanValue(eq, q, range) - vNow) - cost;
            if (ev > required && (best == null || ev > best.evMeso())) {
                best = new ChaosPlay(eq, chaos, ev, equipName(ii, eq.getItemId()));
            }
        }
        return best;
    }

    /** Mean post-chaos market value of {@code eq}: exact expectation over the server's actual reroll
     *  semantics (every positive stat moves uniform +/-range, floored at 0), read off the piece's own
     *  convex band curve. This deliberately keeps rare high-ATT tails visible instead of relying on
     *  random samples to hit them. */
    static double chaosOutcomeMeanValue(Equip eq, EquipQuote q, int range) {
        Map<Long, Double> dist = new HashMap<>();
        dist.put(0L, 1.0);
        dist = convolveChaosStat(dist, eq.getWatk(), ATT_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getMatk(), MATK_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getStr(), MAIN_STAT_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getDex(), MAIN_STAT_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getInt(), MAIN_STAT_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getLuk(), MAIN_STAT_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getWdef(), WDEF_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getMdef(), MDEF_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getHp(), HP_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getMp(), MP_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getAvoid(), AVOID_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getSpeed(), MOVE_WEIGHT, range);
        dist = convolveChaosStat(dist, eq.getJump(), MOVE_WEIGHT, range);

        double sum = 0.0;
        for (Map.Entry<Long, Double> outcome : dist.entrySet()) {
            double score = outcome.getKey() / 1000.0;
            double band = Math.max(0.0, (score - q.baseScore()) / q.bandUnit());
            sum += outcome.getValue() * q.bandCurve().applyAsDouble(band);
        }
        return sum;
    }

    private static Map<Long, Double> convolveChaosStat(Map<Long, Double> dist, short cur, double weight, int range) {
        if (cur <= 0 || weight <= 0.0) {
            return dist;
        }
        if (range <= 0) {
            long score = Math.round(weight * cur * 1000.0);
            Map<Long, Double> next = new HashMap<>(dist.size());
            for (Map.Entry<Long, Double> base : dist.entrySet()) {
                next.merge(base.getKey() + score, base.getValue(), Double::sum);
            }
            return next;
        }
        int outcomes = 2 * range + 1;
        double p = 1.0 / outcomes;
        Map<Long, Double> next = new HashMap<>(dist.size() * Math.min(outcomes, 8));
        for (Map.Entry<Long, Double> base : dist.entrySet()) {
            for (int delta = -range; delta <= range; delta++) {
                long score = Math.round(weight * Math.max(0, cur + delta) * 1000.0);
                next.merge(base.getKey() + score, base.getValue() * p, Double::sum);
            }
        }
        return next;
    }

    /** No regular scroll play existed this scan: consider gambling a chaos reroll instead. The
     *  scan is as heavy as a plan build (quotes + MC per piece), so it runs on the decide pool
     *  and applies through the same pending/confirm flow as any scroll. */
    private static void maybeChaosPlay(BotEntry entry, Character bot) {
        if (entry == null || bot == null || !markChaosPlanQueued(entry)) {
            return;
        }
        BotGrindAdvisor.DECIDE_POOL.execute(() -> {
            ChaosPlay play;
            long t0 = BotPerformanceMonitor.start();
            try {
                play = bestChaosPlay(entry, bot);
            } catch (RuntimeException e) {
                return; // WZ/inventory hiccup off-thread — next scan retries
            } finally {
                entry.chaosPlanQueued = false;
                BotPerformanceMonitor.recordSince("chaos-scan", t0);
            }
            if (play == null) {
                return;
            }
            BotManager.after(0, () -> {
                if (!entry.selfScrollEnabled || entry.pendingAction != null
                        || entry.pendingTradeCategory != null) {
                    return;
                }
                entry.pendingScrollEquip = play.target();
                entry.pendingScrollScroll = play.scroll();
                String pitch = "feeling lucky - gonna chaos my " + play.targetName()
                        + ", could go big or brick it";
                if (entry.owner == bot) {
                    BotManager.getInstance().botSay(bot, pitch);
                    BotManager.after(BotManager.randMs(1500, 2500), () -> executeConfirmed(entry, bot));
                } else {
                    entry.pendingAction = "scroll_confirm";
                    BotManager.getInstance().botReply(entry, pitch
                            + String.format(" (ev ~%,.0f meso for me)", play.evMeso()));
                }
            });
        });
    }

    /** White-scroll gate (owner-specced): protect an apply when failRate × the slot's option
     *  value — the next success this slot could still deliver on the piece's own convex curve —
     *  exceeds the White Scroll's market cost. On a nearly-done piece the marginal band is worth
     *  a fortune, so the last slots protect themselves; early slots never do. */
    static boolean shouldUseWhiteScroll(BotEntry entry, Character bot, Equip equip, Item scroll) {
        int sid = scroll.getItemId();
        if (ItemConstants.isCleanSlate(sid) || ItemConstants.isModifierScroll(sid)) {
            return false; // nothing at stake
        }
        if (bot.getInventory(InventoryType.USE).findById(ItemId.WHITE_SCROLL) == null) {
            return false;
        }
        Map<String, Integer> st = ItemInformationProvider.getInstance().getEquipStats(sid);
        double p = st == null ? 0 : effectiveSuccessPct(st.getOrDefault("success", 0)) / 100.0;
        if (p <= 0 || p >= 1) {
            return false; // can't fail (or can't succeed): protection buys nothing
        }
        EquipQuote q = equipMarketQuote(entry, bot, equip);
        if (q == null || q.bandUnit() <= 0) {
            return false;
        }
        double marginal = q.bandCurve().applyAsDouble(q.band() + 1)
                - q.bandCurve().applyAsDouble(q.band());
        ProducerCombat pc = resolveProducerCombat(entry, bot);
        return (1.0 - p) * p * Math.max(0, marginal)
                > SCROLL_OPPORTUNITY_FRACTION * scrollPriceMeso(pc, ItemId.WHITE_SCROLL);
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
    // Package-private: also the job->stat SSOT for BotBuildManager.resolveApBuild (autonomous AP).
    static char[] mainSecondary(int jobId, boolean[] mageOut) {
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
