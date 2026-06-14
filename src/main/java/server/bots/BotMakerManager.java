package server.bots;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.processor.action.MakerProcessor;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles the bot Maker batch commands: "make monster crystals" (convert monster-leftover
 * etc stacks) and "disassemble trash" (break down trash equips). Both run through the shared
 * {@link MakerProcessor} player path, one operation per roughly {@link #STEP_INTERVAL_MIN_MS} ms, and
 * self-interrupt when the player issues a new directive (follow/stop/move/...): see
 * {@link BotEntry#activityEpoch}.
 */
final class BotMakerManager {
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();
    private static final int LEFTOVERS_PER_CRYSTAL = 100;   // Maker type-3 recipe req count
    private static final long STEP_INTERVAL_MIN_MS = 5000L; // 5 seconds per operation, plus humanlike jitter
    private static final int STEP_INTERVAL_JITTER_MAX_MS = 500;
    private static final int LONG_BATCH_THRESHOLD = 10;     // "will take a while" past this many ops
    private static final int NO_MORE = Integer.MIN_VALUE;   // batch step sentinel: nothing left to do
    private static final Set<Integer> ACTIVE = ConcurrentHashMap.newKeySet();

    private static final String[] CRYSTAL_VERBS = {"creating", "making", "crafting"};
    private static final String[] DISASSEMBLE_VERBS = {"disassembling", "breaking down", "scrapping"};

    /** One Maker operation under a held client lock. Returns 0 on success, {@link #NO_MORE}
     *  when nothing remains, otherwise a {@link MakerProcessor} failure status. */
    @FunctionalInterface
    private interface BatchStep {
        int run(Client c);
    }

    private BotMakerManager() {
    }

    static void handleMakeCrystals(BotEntry entry) {
        Character bot = entry.bot;
        if (bot == null || !guardStart(entry, bot)) {
            return;
        }

        Queue<Integer> leftovers = collectLeftoverQueue(bot);   // one entry per craft, holding the leftover itemid
        if (leftovers.isEmpty()) {
            BotManager.getInstance().botReply(entry,
                    "I don't have any leftovers I can turn into monster crystals (need 100+ of a drop)");
            return;
        }

        startBatch(entry, leftovers.size(), CRYSTAL_VERBS, "crystal",
                c -> {
                    Integer leftover = leftovers.poll();
                    return leftover == null ? NO_MORE : MakerProcessor.makeLeftoverCrystal(c, leftover);
                });
    }

    static void handleDisassembleTrash(BotEntry entry) {
        Character bot = entry.bot;
        if (bot == null || !guardStart(entry, bot)) {
            return;
        }

        int total = collectDisassemblableTrash(entry, bot).size();
        if (total == 0) {
            BotManager.getInstance().botReply(entry, "no trash equips I can disassemble");
            return;
        }

        // Re-scan each step rather than caching slots: a freed EQUIP slot can be refilled by
        // looted gear during the 5s gaps, so always disassemble a currently-trash equip.
        startBatch(entry, total, DISASSEMBLE_VERBS, "trash equip",
                c -> {
                    List<Equip> trash = collectDisassemblableTrash(entry, bot);
                    return trash.isEmpty() ? NO_MORE : MakerProcessor.disassembleEquip(c, trash.get(0).getPosition());
                });
    }

    /**
     * Autopilot bag-pressure relief: when a bag tab is cramped, run the same Maker batches a player
     * could trigger by hand — but only the ones that actually free slots right now, and silently
     * (no "I can't / nothing to do" chatter when there's no work). Crystal-making consumes the
     * leftover stacks (>=100) that {@code collectSellTrashEtcItems} deliberately KEEPS (the
     * crystal-leftover-keep gate), turning otherwise-hoarded clutter into useful Maker reagents
     * without any town trip. Disassembly clears trash equips into crystals. Both reuse the player
     * {@link MakerProcessor} path via the existing batch machinery; the ACTIVE guard stops overlap
     * and a batch self-interrupts on the next player command.
     *
     * <p>No-op (and no message) unless the bot has the Maker skill, a tab is cramped, and that tab
     * actually holds convertible/disassemblable items — so it is safe to call every grind tick.
     */
    static void autoCompactIfCramped(BotEntry entry, Character bot) {
        if (bot == null || ACTIVE.contains(bot.getId()) || MakerProcessor.getMakerSkillLevel(bot) < 1) {
            return;
        }
        if (BotShopManager.isCramped(bot, InventoryType.ETC) && !collectLeftoverQueue(bot).isEmpty()) {
            handleMakeCrystals(entry);
            return;
        }
        if (BotShopManager.isCramped(bot, InventoryType.EQUIP)
                && !collectDisassemblableTrash(entry, bot).isEmpty()) {
            handleDisassembleTrash(entry);
        }
    }

    // ---- Autocraft: Maker gear progression, SUPERVISED only (real owner online) + per-craft permission ----
    private static final int CRAFT_SCAN_MIN_MS = 120_000;
    private static final int CRAFT_SCAN_MAX_MS = 240_000;
    private static final long CRAFT_MESO_FLOOR = 1_000_000L; // never spend the bot below this
    private static final double CRAFT_MIN_GAIN = 5.0;        // skip trivial upgrades

    /** Autocraft only runs while a REAL owner is online to approve it: not self-owned
     *  (@botme/@botparty, owner==bot) and not offline. Mirrors {@link BotManager#canWalkToOwner}. */
    private static boolean craftSupervised(BotEntry entry) {
        Character owner = entry.owner;
        return owner != null && owner != entry.bot && owner.isLoggedinWorld();
    }

    /** Armed periodic proposal: when owner-supervised and a worthwhile craft exists, ask permission.
     *  The heavy ranking runs off the tick thread. */
    static void tickAutoCraft(BotEntry entry, Character bot, long nowMs) {
        if (entry == null || bot == null || !entry.craftEnabled || !craftSupervised(entry)) {
            return;
        }
        if (entry.nextCraftScanAtMs == 0L) { // just armed: schedule, don't fire now
            entry.nextCraftScanAtMs = nowMs + BotManager.randMs(CRAFT_SCAN_MIN_MS, CRAFT_SCAN_MAX_MS);
            return;
        }
        if (nowMs < entry.nextCraftScanAtMs || entry.pendingAction != null
                || entry.pendingTradeCategory != null || ACTIVE.contains(bot.getId())) {
            return;
        }
        entry.nextCraftScanAtMs = nowMs + BotManager.randMs(CRAFT_SCAN_MIN_MS, CRAFT_SCAN_MAX_MS);
        BotManager.after(BotManager.randMs(300, 600), () -> proposeCraft(entry, bot, false));
    }

    /** Command-triggered single proposal ("craft now"). */
    static void requestCraftPass(BotEntry entry) {
        Character bot = entry.bot;
        if (bot == null) {
            return;
        }
        if (!craftSupervised(entry)) {
            BotManager.getInstance().botReply(entry, "i only craft when you're online to ok it");
            return;
        }
        if (entry.pendingAction != null || ACTIVE.contains(bot.getId())) {
            BotManager.getInstance().botReply(entry, "hang on, im busy");
            return;
        }
        BotManager.after(BotManager.randMs(300, 600), () -> proposeCraft(entry, bot, true));
    }

    /** Rank craftable upgrades and ask permission for the best one within the meso floor.
     *  {@code announceNone} = say so when nothing qualifies (command path), else stay quiet (auto). */
    private static void proposeCraft(BotEntry entry, Character bot, boolean announceNone) {
        if (entry.pendingAction != null || !craftSupervised(entry)
                || MakerProcessor.getMakerSkillLevel(bot) < 1) {
            return;
        }
        for (BotMakerPlanner.CraftPlan plan : BotMakerPlanner.rankUpgrades(bot)) {
            if (plan.expectedGain() < CRAFT_MIN_GAIN) {
                break; // ranked best-first; nothing better remains
            }
            if (bot.getMeso() - plan.mesoCost() < CRAFT_MESO_FLOOR) {
                continue; // keep the meso floor
            }
            entry.pendingAction = "craft_confirm";
            entry.pendingCraftPlan = plan;
            BotManager.getInstance().botReply(entry, String.format(
                    "wanna craft %s? +%.0f dps, ~%,d mesos + materials (%s) - ok?",
                    plan.name(), plan.expectedGain(), plan.mesoCost(), plan.reagentDesc()));
            return;
        }
        if (announceNone) {
            BotManager.getInstance().botReply(entry, "nothing worth crafting for an upgrade right now");
        }
    }

    /** Owner replied to a craft proposal (anything that isn't a clear yes cancels). */
    static void handleCraftConfirm(BotEntry entry, String message) {
        String m = message == null ? "" : message.trim().toLowerCase();
        boolean yes = m.matches(".*\\b(yes|yep|yeah|yea|y|ok|okay|sure|do\\s*it|go|craft\\s*it|confirm)\\b.*");
        BotMakerPlanner.CraftPlan plan = entry.pendingCraftPlan;
        entry.pendingAction = null;
        entry.pendingCraftPlan = null;
        if (yes && plan != null) {
            Character bot = entry.bot;
            BotManager.after(BotManager.randMs(500, 700), () -> executeCraft(entry, bot, plan));
        } else {
            BotManager.after(BotManager.randMs(400, 600),
                    () -> BotManager.getInstance().botReply(entry, "ok, skipping it"));
        }
    }

    private static void executeCraft(BotEntry entry, Character bot, BotMakerPlanner.CraftPlan plan) {
        if (bot == null || !bot.isLoggedin() || plan == null) {
            return;
        }
        // Re-check supervision + floor at exec time: the owner may have logged off / mesos changed.
        if (!craftSupervised(entry) || bot.getMeso() - plan.mesoCost() < CRAFT_MESO_FLOOR) {
            BotManager.getInstance().botReply(entry, "cant craft it now (you're offline or im low on mesos)");
            return;
        }
        Client c = bot.getClient();
        if (c == null) {
            return;
        }
        if (!c.tryacquireClient()) {
            BotManager.after(BotManager.randMs(600, 900), () -> executeCraft(entry, bot, plan));
            return;
        }
        short status;
        try {
            status = MakerProcessor.makeItem(c, plan.itemId(), plan.stimulantId() != -1,
                    List.copyOf(plan.reagentIds().keySet()));
        } finally {
            c.releaseClient();
        }
        if (status == 0) {
            BotManager.getInstance().botSay(bot, "crafted " + plan.name() + "!");
            BotEquipManager.autoEquip(bot, entry.owner, null); // wear it if it beats current gear
            if (entry.craftEnabled) { // chain: look for the next worthwhile craft (asks again)
                BotManager.after(BotManager.randMs(2500, 3500), () -> requestCraftPass(entry));
            }
        } else {
            BotManager.getInstance().botReply(entry, craftAbortReason(status));
        }
    }

    private static String craftAbortReason(short status) {
        return switch (status) {
            case 2 -> "not enough mesos to craft";
            case 3 -> "im not high enough level to craft that";
            case -2 -> "cant put that gem on this gear";
            default -> "craft didnt take, nvm";
        };
    }

    /** Trash equips (SSOT: {@link BotInventoryManager#collectSellTrashEquips}) that actually
     *  have a Maker disassembly recipe — others would just abort the batch. */
    private static List<Equip> collectDisassemblableTrash(BotEntry entry, Character bot) {
        List<Equip> out = new ArrayList<>();
        for (Item item : BotInventoryManager.collectSellTrashEquips(entry, bot)) {
            if (item instanceof Equip equip && MakerProcessor.canDisassemble(equip.getItemId())) {
                out.add(equip);
            }
        }
        return out;
    }

    private static boolean guardStart(BotEntry entry, Character bot) {
        if (ACTIVE.contains(bot.getId())) {
            BotManager.getInstance().botReply(entry, "still working on the last batch, hang on");
            return false;
        }
        if (MakerProcessor.getMakerSkillLevel(bot) < 1) {
            BotManager.getInstance().botReply(entry, "I can't - I don't have the Maker skill");
            return false;
        }
        return true;
    }

    private static Queue<Integer> collectLeftoverQueue(Character bot) {
        Queue<Integer> queue = new LinkedList<>();
        Inventory etc = bot.getInventory(InventoryType.ETC);
        etc.lockInventory();
        try {
            Set<Integer> seen = new HashSet<>();
            for (Item item : etc.list()) {
                int itemId = item.getItemId();
                if (!seen.add(itemId)) {
                    continue;   // count each distinct leftover once; countById sums all stacks
                }
                int crafts = etc.countById(itemId) / LEFTOVERS_PER_CRYSTAL;
                if (crafts <= 0 || ii.getMakerCrystalFromLeftover(itemId) == -1) {
                    continue;
                }
                for (int i = 0; i < crafts; i++) {
                    queue.add(itemId);
                }
            }
        } finally {
            etc.unlockInventory();
        }
        return queue;
    }

    private static void startBatch(BotEntry entry, int total, String[] verbs, String noun, BatchStep step) {
        Character bot = entry.bot;
        String verb = verbs[ThreadLocalRandom.current().nextInt(verbs.length)];
        String msg = "ok " + verb + " " + total + " " + plural(noun, total);
        if (total > LONG_BATCH_THRESHOLD) {
            msg += ", will take a while";
        }
        BotManager.getInstance().botReply(entry, msg);

        ACTIVE.add(bot.getId());
        int epoch = entry.activityEpoch;
        BotManager.after(BotManager.randMs(900, 1100), () -> runStep(entry, step, noun, epoch, 0));
    }

    private static void runStep(BotEntry entry, BatchStep step, String noun, int epoch, int done) {
        Character bot = entry.bot;
        if (bot == null || !bot.isLoggedin()) {
            if (bot != null) {
                ACTIVE.remove(bot.getId());
            }
            return;
        }

        if (entry.activityEpoch != epoch) {   // player issued a new command — disrupt
            ACTIVE.remove(bot.getId());
            BotManager.getInstance().botReply(entry, "ok, stopping - " + done + " " + plural(noun, done) + " done");
            return;
        }

        Client c = bot.getClient();
        if (c == null) {
            ACTIVE.remove(bot.getId());
            return;
        }
        if (!c.tryacquireClient()) {
            // transient contention (a trade/packet in flight) — retry without consuming the step.
            // The bot keeps doing whatever it's doing; crafting just slots into the next free moment.
            BotManager.after(BotManager.randMs(600, 900), () -> runStep(entry, step, noun, epoch, done));
            return;
        }
        int status;
        try {
            status = step.run(c);
        } finally {
            c.releaseClient();
        }

        if (status == NO_MORE) {
            ACTIVE.remove(bot.getId());
            BotManager.getInstance().botReply(entry, "done - " + done + " " + plural(noun, done));
            return;
        }
        if (status != 0) {
            ACTIVE.remove(bot.getId());
            BotManager.getInstance().botReply(entry, abortReason((short) status, noun, done));
            return;
        }

        BotManager.after(nextStepDelayMs(), () -> runStep(entry, step, noun, epoch, done + 1));
    }

    private static long nextStepDelayMs() {
        return STEP_INTERVAL_MIN_MS + BotManager.randMs(0, STEP_INTERVAL_JITTER_MAX_MS + 1);
    }

    private static String abortReason(short status, String noun, int done) {
        String reason = switch (status) {
            case 1 -> "ran out of materials";
            case 2 -> "ran out of mesos";
            case 5 -> "my inventory's full";
            default -> "hit a snag";
        };
        return reason + ", stopping - " + done + " " + plural(noun, done) + " done";
    }

    private static String plural(String noun, int count) {
        return count == 1 ? noun : noun + "s";
    }
}
