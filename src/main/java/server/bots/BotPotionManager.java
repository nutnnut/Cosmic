package server.bots;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.keybind.KeyBinding;
import constants.skills.Crusader;
import constants.skills.DawnWarrior;
import constants.skills.Magician;
import constants.skills.Warrior;
import constants.skills.WhiteKnight;
import server.ItemInformationProvider;
import server.StatEffect;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class BotPotionManager {
    private static final List<String> GRIND_REPLIES = List.of(
            "ok", "on it", "lets get it", "farming time", "got it",
            "sure", "ok boss", "time to grind",
            "lets farm", "hunting time", "aye, killing stuff",
            "lezgo", "gonna get some kills", "on it boss",
            "time to work", "lets do this");
    private static final List<String> POT_REQUEST_HP_MSGS = List.of(
            "anyone have HP pots? running low",
            "low on HP pots, does anyone have some?",
            "need HP pots!! anyone?",
            "HP pots? who has some",
            "anyone got HP pots to spare?");
    private static final List<String> POT_REQUEST_MP_MSGS = List.of(
            "anyone have MP pots? running low",
            "low on MP pots, does anyone have some?",
            "need MP pots!! anyone?",
            "MP pots? who has some",
            "anyone got MP pots to spare?");
    private static final List<String> POT_OFFER_HP_MSGS = List.of(
            "got some HP pots, inv u",
            "yep i have HP pots, inv u",
            "sure, got spare HP pots",
            "coming, inv",
            "got you");
    private static final List<String> POT_OFFER_MP_MSGS = List.of(
            "got some MP pots, inv u",
            "yep i have MP pots, inv u",
            "sure, got spare MP pots",
            "coming, inv",
            "got you");

    // ownerCharId -> shared HP/MP 30 s request cooldown
    private static final Map<Integer, Long> potShareCooldownUntil = new ConcurrentHashMap<>();
    // ownerCharId -> category-specific 10 min failed-request backoff
    private static final Map<Integer, Long> potShareHpBackoffUntil = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> potShareMpBackoffUntil = new ConcurrentHashMap<>();

    private BotPotionManager() {
    }

    // Bots whose USE-item StatEffects have been resolved into the shared itemEffect cache, and those
    // currently being warmed. The first potion scan of a freshly spawned bot otherwise resolves ~all
    // its USE item effects from WZ inline -- a one-time ~300ms cold load that landed on the bot tick
    // ("potion-recovery-scan" stall WARN), amplified by several bots booting together. We move that
    // cold load off the tick (see potionEffectsReady); steady-state stays warm (itemEffect is a shared
    // ConcurrentHashMap, so a warm scan is just a map-get + field reads per item).
    private static final java.util.Set<Integer> recoveryEffectsWarmed =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Integer> recoveryEffectsWarming =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * True once this bot's USE-item effects are resolved into the (shared) {@link
     * BotInventoryManager#itemEffect} cache. The first call kicks off an off-thread warm and returns
     * false, so the cold WZ load never runs on the bot tick; subsequent ticks proceed once warm
     * (a couple of ticks later -- autopot setup being momentarily late at spawn is harmless). The
     * warm also populates shared potion ids for every other bot.
     */
    static boolean potionEffectsReady(Character bot) {
        if (bot == null) {
            return true; // nothing to scan; let the caller no-op normally
        }
        int id = bot.getId();
        if (recoveryEffectsWarmed.contains(id)) {
            return true;
        }
        if (recoveryEffectsWarming.add(id)) { // first observer starts the warm
            try {
                BotManager.after(1, () -> {
                    try {
                        var use = bot.getInventory(InventoryType.USE);
                        if (use != null) {
                            for (Item item : use.list()) {
                                BotInventoryManager.itemEffect(item.getItemId());
                            }
                        }
                    } finally {
                        recoveryEffectsWarmed.add(id);
                        recoveryEffectsWarming.remove(id);
                    }
                });
            } catch (RuntimeException ex) {
                // Scheduling unavailable: never block autopot forever — mark ready so the next tick
                // does a normal (possibly one-time cold) scan, i.e. the pre-fix behavior.
                recoveryEffectsWarmed.add(id);
                recoveryEffectsWarming.remove(id);
            }
        }
        return false;
    }

    /** Single source of truth: items the bot has that count as recovery pots. */
    static List<Item> recoveryPotions(Character bot) {
        long startedAt = BotPerformanceMonitor.start();
        List<Item> result = new java.util.ArrayList<>();
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item.getQuantity() <= 0) {
                continue;
            }
            if (BotInventoryManager.isRecoveryPotion(item.getItemId())) {
                result.add(item);
            }
        }
        BotPerformanceMonitor.recordSince("potion-recovery-scan", startedAt);
        return result;
    }

    static int[] countPotions(Character bot) {
        long startedAt = BotPerformanceMonitor.start();
        int hp = 0;
        int mp = 0;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item.getQuantity() <= 0) {
                continue;
            }
            StatEffect effect = BotInventoryManager.itemEffect(item.getItemId());
            if (effect == null) {
                continue;
            }
            boolean healsHp = healsHp(effect);
            boolean healsMp = healsMp(effect);
            if ((!healsHp && !healsMp) || !effect.getStatups().isEmpty()) {
                continue;
            }
            int quantity = item.getQuantity();
            if (healsHp) {
                hp += quantity;
            }
            if (healsMp) {
                mp += quantity;
            }
        }
        BotPerformanceMonitor.recordSince("potion-recovery-scan", startedAt);
        return new int[]{hp, mp};
    }

    static int[] countPotions(List<Item> items, Function<Integer, StatEffect> effectLookup) {
        long startedAt = BotPerformanceMonitor.start();
        int hp = 0;
        int mp = 0;
        for (Item item : items) {
            StatEffect effect = effectLookup.apply(item.getItemId());
            if (effect == null) {
                continue;
            }
            int quantity = item.getQuantity();
            if (healsHp(effect)) {
                hp += quantity;
            }
            if (healsMp(effect)) {
                mp += quantity;
            }
        }
        BotPerformanceMonitor.recordSince("potion-recovery-count", startedAt);
        return new int[]{hp, mp};
    }

    /** SSOT recovery-axis tests (flat or percent), shared by potion counting and the USE runway. */
    static boolean healsHp(StatEffect fx) {
        return fx != null && (fx.getHp() > 0 || fx.getHpRate() > 0);
    }

    static boolean healsMp(StatEffect fx) {
        return fx != null && (fx.getMp() > 0 || fx.getMpRate() > 0);
    }

    /**
     * Autopot selection priority, best (lowest ordinal) → worst:
     *   1. FLAT_SINGLE — e.g. 50 HP only
     *   2. FLAT_MIXED  — e.g. 50 HP + 50 MP
     *   3. RATE_SINGLE — e.g. 20% HP only
     *   4. RATE_MIXED  — e.g. 20% HP + 20% MP
     * Within the same tier, the smaller recovery value wins (burn cheap pots first;
     * preserve big pots for emergencies). Buff potions (statups present) are excluded.
     */
    enum PotionTier {
        FLAT_SINGLE,
        FLAT_MIXED,
        RATE_SINGLE,
        RATE_MIXED
    }

    /** Result of classifying an item for a slot: tier + the slot-stat magnitude used for tie-breaking. */
    record PotionRanking(PotionTier tier, double value) {
        boolean betterThan(PotionRanking other) {
            if (other == null) return true;
            int t = Integer.compare(this.tier.ordinal(), other.tier.ordinal());
            if (t != 0) return t < 0;
            return this.value < other.value; // smaller value preferred within tier
        }
    }

    static PotionRanking classifyForSlot(StatEffect fx, boolean hpSlot) {
        if (fx == null) {
            return null;
        }
        int flatPrim    = Math.max(0, hpSlot ? fx.getHp()     : fx.getMp());
        int flatOther   = Math.max(0, hpSlot ? fx.getMp()     : fx.getHp());
        double ratePrim  = Math.max(0.0, hpSlot ? fx.getHpRate() : fx.getMpRate());
        double rateOther = Math.max(0.0, hpSlot ? fx.getMpRate() : fx.getHpRate());

        if (flatPrim == 0 && ratePrim == 0.0) {
            return null; // does not restore the slot's stat
        }

        boolean hasFlat = flatPrim > 0 || flatOther > 0;
        boolean hasRate = ratePrim > 0 || rateOther > 0;

        if (hasFlat && !hasRate) {
            boolean mixed = flatPrim > 0 && flatOther > 0;
            return new PotionRanking(mixed ? PotionTier.FLAT_MIXED : PotionTier.FLAT_SINGLE, flatPrim);
        }
        if (hasRate && !hasFlat) {
            boolean mixed = ratePrim > 0 && rateOther > 0;
            return new PotionRanking(mixed ? PotionTier.RATE_MIXED : PotionTier.RATE_SINGLE, ratePrim);
        }
        // Hybrid flat+rate: rank as worst tier (mixed rate) so flat-only pots always win.
        return new PotionRanking(PotionTier.RATE_MIXED, ratePrim > 0 ? ratePrim : flatPrim);
    }

    /** Pair of best autopot picks for the HP and MP slots over the bot's recovery pots. */
    record AutopotChoice(int hpItemId, PotionRanking hpRank, int mpItemId, PotionRanking mpRank) {}

    private record PotionInventorySnapshot(int hpCount, int mpCount, AutopotChoice autopotChoice) {
        int[] counts() {
            return new int[]{hpCount, mpCount};
        }
    }

    private static PotionInventorySnapshot scanPotionInventory(Character bot) {
        long startedAt = BotPerformanceMonitor.start();
        int hp = 0;
        int mp = 0;
        int hpItemId = -1;
        int mpItemId = -1;
        PotionRanking bestHp = null;
        PotionRanking bestMp = null;

        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item.getQuantity() <= 0) {
                continue;
            }
            StatEffect effect = BotInventoryManager.itemEffect(item.getItemId());
            if (effect == null || !effect.getStatups().isEmpty()) {
                continue;
            }

            boolean healsHp = effect.getHp() > 0 || effect.getHpRate() > 0;
            boolean healsMp = effect.getMp() > 0 || effect.getMpRate() > 0;
            if (!healsHp && !healsMp) {
                continue;
            }

            int quantity = item.getQuantity();
            if (healsHp) {
                hp += quantity;
            }
            if (healsMp) {
                mp += quantity;
            }

            PotionRanking hpRank = classifyForSlot(effect, true);
            if (hpRank != null && hpRank.betterThan(bestHp)) {
                bestHp = hpRank;
                hpItemId = item.getItemId();
            }
            PotionRanking mpRank = classifyForSlot(effect, false);
            if (mpRank != null && mpRank.betterThan(bestMp)) {
                bestMp = mpRank;
                mpItemId = item.getItemId();
            }
        }

        BotPerformanceMonitor.recordSince("potion-recovery-scan", startedAt);
        return new PotionInventorySnapshot(hp, mp, new AutopotChoice(hpItemId, bestHp, mpItemId, bestMp));
    }

    /** Shared selection used by both keybind setup and the debug report. */
    static AutopotChoice computeAutopotChoice(Character bot) {
        return scanPotionInventory(bot).autopotChoice();
    }

    static void setupAutopotForBot(Character bot) {
        setupAutopotForBot(bot, computeAutopotChoice(bot));
    }

    private static void setupAutopotForBot(Character bot, AutopotChoice choice) {
        if (choice.hpItemId() > 0) {
            bot.changeKeybinding(91, new KeyBinding(7, choice.hpItemId()));
            bot.setAutopotHpAlert(BotManager.cfg.AUTOPOT_HP_THRESH);
        } else {
            bot.getKeymap().remove(91);
            bot.setAutopotHpAlert(0f);
        }

        if (choice.mpItemId() > 0) {
            bot.changeKeybinding(92, new KeyBinding(7, choice.mpItemId()));
            bot.setAutopotMpAlert(BotManager.cfg.AUTOPOT_MP_THRESH);
        } else {
            bot.getKeymap().remove(92);
            bot.setAutopotMpAlert(0f);
        }
    }

    /** Owner-facing diagnostic: counts vs. selected items for each slot. */
    static String autopotDebugReport(Character bot) {
        PotionInventorySnapshot snapshot = scanPotionInventory(bot);
        int[] cnt = snapshot.counts();
        AutopotChoice choice = snapshot.autopotChoice();
        ItemInformationProvider iip = ItemInformationProvider.getInstance();
        return "pots: " + cnt[0] + " hp / " + cnt[1] + " mp"
                + " | hp slot: " + describeChoice(iip, choice.hpItemId(), choice.hpRank())
                + " | mp slot: " + describeChoice(iip, choice.mpItemId(), choice.mpRank());
    }

    private static String describeChoice(ItemInformationProvider iip, int itemId, PotionRanking rank) {
        if (itemId <= 0 || rank == null) {
            return "none";
        }
        String name = iip.getName(itemId);
        if (name == null) name = String.valueOf(itemId);
        String value = rank.tier().name().startsWith("FLAT_")
                ? String.valueOf((int) rank.value())
                : String.format("%.0f%%", rank.value() * 100);
        return name + " (" + rank.tier().name() + "/" + value + ")";
    }

    static String grindStartMessage(Character bot) {
        int[] pots = countPotions(bot);
        int hp = pots[0];
        int mp = pots[1];
        String base = BotManager.randomReply(GRIND_REPLIES);
        if (hp >= BotManager.cfg.POT_LOW_WARN && mp >= BotManager.cfg.POT_LOW_WARN) {
            return base;
        }

        StringBuilder message = new StringBuilder(base).append(", but");
        if (hp < BotManager.cfg.POT_LOW_WARN) {
            message.append(" only ").append(hp).append(" HP pots");
        }
        if (hp < BotManager.cfg.POT_LOW_WARN && mp < BotManager.cfg.POT_LOW_WARN) {
            message.append(" and");
        }
        if (mp < BotManager.cfg.POT_LOW_WARN) {
            message.append(" only ").append(mp).append(" MP pots");
        }
        return message.append(" left").toString();
    }

    static void tickPotionCheck(BotEntry entry, Character bot) {
        if (!potionEffectsReady(bot)) {
            return; // first-time USE-effect WZ load is warming off-thread; don't cold-scan on the tick
        }
        if (entry.potCheckTimerMs > 0) {
            entry.potCheckTimerMs = BotMovementManager.tickDown(entry.potCheckTimerMs);
            return;
        }
        entry.potCheckTimerMs = BotMovementManager.delayAfterCurrentTick(BotManager.cfg.POT_CHECK_INTERVAL_MS);

        long startedAt = BotPerformanceMonitor.start();
        PotionInventorySnapshot potions = scanPotionInventory(bot);
        setupAutopotForBot(bot, potions.autopotChoice());
        BotPerformanceMonitor.recordSince("potion-autopot", startedAt);

        startedAt = BotPerformanceMonitor.start();
        BotCombatManager.tickAmmoCheck(entry, bot);
        BotPerformanceMonitor.recordSince("potion-ammo-check", startedAt);

        // Diagnostic BEFORE the grinding/following gate: an autopilot bot with a cramped bag that
        // never walks to a shop is the open bug, and the leading suspects (grinding=false, etc) would
        // make the later trigger branch silent. Log the full decision state here so the next live run
        // is conclusive. Self-throttles and self-gates on "a bag is actually cramped".
        if (BotAutopilotManager.isActive(entry)) {
            BotShopManager.logSellBlockIfCramped(entry, bot);
        }

        if (!entry.grinding && !entry.following) {
            return;
        }
        startedAt = BotPerformanceMonitor.start();
        BotAmmoManager.tickAmmoShareCheck(entry, bot);
        BotPerformanceMonitor.recordSince("potion-ammo-share", startedAt);

        startedAt = BotPerformanceMonitor.start();
        int[] pots = potions.counts();
        BotPerformanceMonitor.recordSince("potion-count", startedAt);

        startedAt = BotPerformanceMonitor.start();
        requestLowPotShare(entry, bot, pots[0], true, false);
        BotPerformanceMonitor.recordSince("potion-share-hp", startedAt);

        startedAt = BotPerformanceMonitor.start();
        requestLowPotShare(entry, bot, pots[1], false, false);
        BotPerformanceMonitor.recordSince("potion-share-mp", startedAt);

        if (!entry.grinding) {
            return;
        }
        // Autopilot bag-pressure relief that needs no town trip: turn hoarded leftover stacks into
        // Maker crystals / disassemble trash equips right here when a tab is cramped. No-ops silently
        // unless there's maker skill + a cramped tab + actual work, so it's cheap to attempt each tick.
        if (BotAutopilotManager.isActive(entry)) {
            BotMakerManager.autoCompactIfCramped(entry, bot);
        }
        startedAt = BotPerformanceMonitor.start();
        // Affordability gate: a broke bot can't buy pots, so don't send it on a futile buy trip
        // (walk to shop -> NOT_ENOUGH_MESO -> buy nothing -> walk back -> repeat). The sell-trash
        // branch below is intentionally NOT gated - selling is how a broke bot earns meso to buy.
        boolean canAffordPots = bot.getMeso() >= BotManager.cfg.RESUPPLY_MIN_MESO;
        if (canAffordPots && pots[0] < BotManager.cfg.POT_STOP
                && BotAutopilotManager.requestResupplyErrand(entry, bot)) {
            // Autopilot restocks on its own: town errand, shop, walk back. Proactive — no
            // HP gate, an independent bot shouldn't grind its last potions dry first.
        } else if (canAffordPots && pots[1] < BotManager.cfg.POT_STOP
                && BotAutopilotManager.requestResupplyErrand(entry, bot)) {
            // Mages can be combat-stopped by zero MP pots; give them the same autopilot
            // resupply path after the party/owner grace request has had a chance to land.
        } else if (pots[0] < BotManager.cfg.POT_STOP && bot.getHp() < bot.getMaxHp() * 0.4f
                && BotManager.canWalkToOwner(entry)) {
            // canWalkToOwner is false for autopilot / self-owned / owner-offline bots, so an
            // independent bot that can't resupply just holds position and keeps grinding here
            // instead of anchoring to the owner.
            BotManager.getInstance().issueFollowOwner(entry);
            BotManager.getInstance().botSay(bot, "low on pots!! walking to you");
            bot.changeFaceExpression(Emote.GLARE.getValue());
        } else if (BotAutopilotManager.isActive(entry) && BotShopManager.shouldAutoSellTrash(entry, bot)) {
            // Bags filled up mid-grind (passive loot): same errand, the visit also sells.
            BotAutopilotManager.requestResupplyErrand(entry, bot);
        }
        BotPerformanceMonitor.recordSince("potion-grind-stop", startedAt);
    }

    static void checkPotShareOnModeStart(BotEntry entry, Character bot) {
        entry.potShareRequestedHp = false;
        entry.potShareRequestedMp = false;
        BotAmmoManager.checkAmmoShareOnModeStart(entry, bot);
        requestLowPotShares(entry, bot, false);
    }

    static boolean requestLowSuppliesFromOwnerAsk(BotEntry entry, Character bot) {
        boolean requestedPots = requestLowPotShares(entry, bot, true);
        boolean requestedAmmo = BotAmmoManager.requestLowAmmoShare(entry, bot, true);
        return requestedPots || requestedAmmo;
    }

    private static boolean requestLowPotShares(BotEntry entry, Character bot, boolean bypassShareLimits) {
        return requestLowPotShares(entry, bot, countPotions(bot), bypassShareLimits);
    }

    private static boolean requestLowPotShares(BotEntry entry, Character bot, int[] pots, boolean bypassShareLimits) {
        boolean requestedHp = requestLowPotShare(entry, bot, pots[0], true, bypassShareLimits);
        boolean requestedMp = requestLowPotShare(entry, bot, pots[1], false, bypassShareLimits);
        return requestedHp || requestedMp;
    }

    private static boolean requestLowPotShare(BotEntry entry,
                                              Character bot,
                                              int count,
                                              boolean forHp,
                                              boolean bypassShareLimits) {
        if (count >= BotManager.cfg.POT_LOW_WARN) {
            if (forHp) {
                entry.potShareRequestedHp = false;
            } else {
                entry.potShareRequestedMp = false;
            }
            return false;
        }

        boolean alreadyRequested = forHp ? entry.potShareRequestedHp : entry.potShareRequestedMp;
        if ((alreadyRequested && !bypassShareLimits)
                || !requestPotShare(entry, bot, forHp, bypassShareLimits)) {
            return false;
        }
        if (forHp) {
            entry.potShareRequestedHp = true;
        } else {
            entry.potShareRequestedMp = true;
        }
        return true;
    }

    static void tickPassiveRecovery(BotEntry entry, Character bot) {
        boolean hpFull = bot.getHp() >= bot.getCurrentMaxHp();
        boolean mpFull = bot.getMp() >= bot.getCurrentMaxMp();
        if (hpFull && mpFull) {
            entry.mpRecoveryTimerMs = 0;
            return;
        }
        if (entry.mpRecoveryTimerMs > 0) {
            entry.mpRecoveryTimerMs = BotMovementManager.tickDown(entry.mpRecoveryTimerMs);
            return;
        }

        entry.mpRecoveryTimerMs = BotMovementManager.delayAfterCurrentTick(BotManager.cfg.MP_RECOVERY_INTERVAL_MS);

        int hpRecovery = hpFull ? 0 : calculatePassiveHpRecovery(entry, bot);
        int mpRecovery = mpFull ? 0 : calculatePassiveMpRecovery(entry, bot);
        if (hpRecovery <= 0 && mpRecovery <= 0) {
            return;
        }

        bot.addMPHP(hpRecovery, mpRecovery);
    }

    static boolean requestPotShare(BotEntry entry, Character bot, boolean forHp) {
        return requestPotShare(entry, bot, forHp, false);
    }

    static boolean requestPotShare(BotEntry entry, Character bot, boolean forHp, boolean bypassShareLimits) {
        long startedAt = BotPerformanceMonitor.start();
        Character owner = entry.owner;
        // owner == bot is a self-owned (@botme) bot: there is no separate owner to beg pots from,
        // so skip the share request entirely (it would target itself).
        if (owner == null || owner == bot || bot.getTrade() != null || entry.pendingTradeCategory != null) {
            BotPerformanceMonitor.recordSince("potion-request", startedAt);
            return false;
        }

        long now = System.currentTimeMillis();
        Map<Integer, Long> categoryBackoff = forHp ? potShareHpBackoffUntil : potShareMpBackoffUntil;
        if (!bypassShareLimits) {
            if (now < categoryBackoff.getOrDefault(owner.getId(), 0L)) {
                BotPerformanceMonitor.recordSince("potion-request", startedAt);
                return false;
            }
            if (now < potShareCooldownUntil.getOrDefault(owner.getId(), 0L)) {
                BotPerformanceMonitor.recordSince("potion-request", startedAt);
                return false;
            }
            potShareCooldownUntil.put(owner.getId(), now + 30_000L);
        }

        BotAutopilotManager.noteLowSupplyPartyRequest(entry);
        saySupplyRequest(bot, BotManager.randomReply(forHp ? POT_REQUEST_HP_MSGS : POT_REQUEST_MP_MSGS));

        PotDonorPlan plan = selectPotDonor(owner, bot, entry, forHp);
        if (plan == null) {
            if (!bypassShareLimits) {
                categoryBackoff.put(owner.getId(), now + 10 * 60_000L);
            }
            BotPerformanceMonitor.recordSince("potion-request", startedAt);
            return true;
        }

        if (!plan.qualifies()) {
            if (!bypassShareLimits) {
                categoryBackoff.put(owner.getId(), now + 10 * 60_000L);
            }
        } else {
            schedulePotShare(plan, bot, forHp, BotManager.randMs(2000, 3000));
        }
        BotPerformanceMonitor.recordSince("potion-request", startedAt);
        return true;
    }

    enum OwnerPotShareResult {
        OFFERED,
        NO_DONOR,
        BLOCKED
    }

    static OwnerPotShareResult offerPotShareToOwner(BotEntry entry, boolean forHp) {
        Character owner = entry.owner;
        if (owner == null || owner.getTrade() != null) {
            return OwnerPotShareResult.BLOCKED;
        }

        PotDonorPlan plan = selectPotDonor(owner, owner, null, forHp);
        if (plan == null || !plan.qualifies()) {
            return OwnerPotShareResult.NO_DONOR;
        }

        schedulePotShare(plan, owner, forHp, BotManager.randMs(900, 1400));
        return OwnerPotShareResult.OFFERED;
    }

    private static PotDonorPlan selectPotDonor(Character owner, Character recipient, BotEntry excludedEntry, boolean forHp) {
        long startedAt = BotPerformanceMonitor.start();
        BotEntry bestEntry = null;
        int bestCount = 0;
        for (BotEntry sibling : BotManager.getInstance().getBotEntries(owner.getId())) {
            if (sibling == excludedEntry || sibling.bot == null || sibling.bot.getMapId() != recipient.getMapId()) {
                continue;
            }
            int[] pots = countPotions(sibling.bot);
            int count = forHp ? pots[0] : pots[1];
            if (count > bestCount) {
                bestCount = count;
                bestEntry = sibling;
            }
        }
        BotPerformanceMonitor.recordSince("potion-donor-select", startedAt);
        return bestEntry != null ? new PotDonorPlan(bestEntry, bestCount) : null;
    }

    private static void schedulePotShare(PotDonorPlan plan, Character recipient, boolean forHp, long initialDelayMs) {
        BotEntry donorEntry = plan.entry();
        Character donorBot = donorEntry.bot;
        int maxQty = plan.donationQty();
        BotManager.after(initialDelayMs, () -> {
            if (donorBot.getTrade() != null || donorEntry.pendingTradeCategory != null || recipient.getTrade() != null) {
                return;
            }
            List<Item> items = BotInventoryManager.collectPotShareItems(donorBot, forHp, maxQty);
            if (items.isEmpty()) {
                return;
            }
            BotManager.getInstance().botSay(donorBot, BotManager.randomReply(forHp ? POT_OFFER_HP_MSGS : POT_OFFER_MP_MSGS));
            BotManager.after(BotManager.randMs(900, 1100), () ->
                    BotInventoryManager.startPotShareTransfer(items, recipient, donorEntry, donorBot, maxQty));
        });
    }

    private static void saySupplyRequest(Character bot, String message) {
        if (bot.getParty() != null) {
            BotManager.getInstance().botSayParty(bot, message);
        } else {
            BotManager.getInstance().botSay(bot, message);
        }
    }

    private record PotDonorPlan(BotEntry entry, int count) {
        boolean qualifies() {
            return count > BotManager.cfg.POT_LOW_WARN * 3;
        }

        int donationQty() {
            return count / 3;
        }
    }

    private static int calculatePassiveHpRecovery(BotEntry entry, Character bot) {
        int recovery = BotManager.cfg.BASE_HP_RECOVERY;
        if (!isStandingStillForRecovery(entry)) {
            return recovery;
        }

        recovery += getFlatHpRecoveryBonus(bot, Warrior.IMPROVED_HPREC);
        return recovery;
    }

    private static int calculatePassiveMpRecovery(BotEntry entry, Character bot) {
        int recovery = BotManager.cfg.BASE_MP_RECOVERY;
        if (!isStandingStillForRecovery(entry)) {
            return recovery;
        }

        recovery += getFlatMpRecoveryBonus(bot, Crusader.IMPROVING_MPREC);
        recovery += getFlatMpRecoveryBonus(bot, WhiteKnight.IMPROVING_MP_RECOVERY);
        recovery += getFlatMpRecoveryBonus(bot, DawnWarrior.INCREASED_MP_RECOVERY);
        recovery += getMagicianMpRecoveryBonus(bot);
        return recovery;
    }

    private static boolean isStandingStillForRecovery(BotEntry entry) {
        if (entry.inAir || entry.climbing) {
            return false;
        }
        return entry.moveDir == 0
                && BotPhysicsEngine.isStandingStance(BotPhysicsEngine.resolveStance(entry));
    }

    private static int getFlatHpRecoveryBonus(Character bot, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return 0;
        }
        int level = bot.getSkillLevel(skill);
        if (level <= 0) {
            return 0;
        }
        return skill.getEffect(level).getHp();
    }

    private static int getFlatMpRecoveryBonus(Character bot, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return 0;
        }
        int level = bot.getSkillLevel(skill);
        if (level <= 0) {
            return 0;
        }
        return skill.getEffect(level).getMp();
    }

    private static int getMagicianMpRecoveryBonus(Character bot) {
        Skill skill = SkillFactory.getSkill(Magician.IMPROVED_MP_RECOVERY);
        if (skill == null) {
            return 0;
        }
        int level = bot.getSkillLevel(skill);
        if (level <= 0) {
            return 0;
        }
        return Math.max(0, (bot.getInt() / 10) * level);
    }

}
