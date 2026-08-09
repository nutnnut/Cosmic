/*
    This file is part of the OdinMS Maple Story Server.
    Bot Temple-of-Time questline driver (AI companion feature).
*/
package server.bots;

import client.BuffStat;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.StatEffect;
import server.quest.Quest;
import server.quest.actions.ExpAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Long-horizon autopilot "errand" that drives a companion bot through the Temple of Time mainline
 * questline (3500 -> 3521) so it unlocks the corridor to Pink Bean's map on its own. Registered as a
 * {@link BotAutopilotManager.DetourErrand}; it PROGRESSES QUESTS only — reachability updates itself
 * because {@link BotAutopilotManager#unlockedTempleGates} threads each completed gate quest into every
 * route query, so the next corridor map becomes routable the moment its gate quest completes.
 *
 * <p><b>How it drives.</b> Each tick it recomputes {@code Q} = the lowest incomplete quest in the strict
 * {@link #MAINLINE_CHAIN}, then resolves ONE step for it:
 * <ul>
 *   <li><b>START</b> — walk to the start NPC ({@link BotTravelManager#tickApproachNpc}) and {@code gate.start}.</li>
 *   <li><b>GRIND_LANE</b> — pin the bot's grind destination to the quest's lane map so its x999 kill
 *       quota accrues through the NORMAL grind/combat flow (a bot is a Character; kills advance its own
 *       started quests). If the lane is already crowded (via {@link BotOccupancy#extraCompetitors}) the
 *       bot DISPERSES: it steps aside this cycle, kicks a re-decide so the advisor grinds elsewhere, and
 *       waits out a jittered cooldown before retrying — emergent staggering, not a swarm.</li>
 *   <li><b>TURNIN</b> — walk to the complete NPC and {@code gate.complete}.</li>
 *   <li>Three specials — {@link #handle3507} (class sub-quest unlock), {@link #handle3514} (buy + drink
 *       the Sorcerer's Potion), {@link #handle3521} (6-item Force Field turn-in).</li>
 * </ul>
 *
 * <p><b>Opt-in / stagger.</b> The errand only arms once the bot reaches its personal
 * {@link BotPersonality#templeAmbitionLevel} (a stable per-bot roll in [105,160]) so bots trickle into
 * the questline at different levels instead of all at once. Per-quest {@code lvmin} gating then defers
 * higher quests until the bot has leveled into them (it grinds normally meanwhile).
 *
 * <p>Shares player code throughout: quest state/actions go through {@link BotQuestManager#gate} (the same
 * SSOT the piggyback errand uses), NPC approach through {@link BotTravelManager#tickApproachNpc}, and the
 * two script-only specials reproduce their quest scripts' exact server calls (see {@link #handle3514}).
 */
final class BotTempleProgressionManager {

    private static final Logger log = LoggerFactory.getLogger(BotTempleProgressionManager.class);

    private BotTempleProgressionManager() {}

    // ---- personal-ambition band (opt-in / stagger) -------------------------------------------

    /** Lowest level at which any bot will start the Temple questline. */
    static final int AMBITION_MIN_LEVEL = 105;
    /** Width of the ambition band above {@link #AMBITION_MIN_LEVEL} (so the band is [105,160]). */
    static final int AMBITION_BAND = 55;

    // ---- the mainline chain (strict linear prereq) -------------------------------------------

    /** Ordered mainline quests; each strictly requires the previous. The driver always works the
     *  lowest incomplete one. 3505/3512/3519 are the Dodo/Lilynouch/Lyka lane-5 minibosses. */
    static final int[] MAINLINE_CHAIN = {
            3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510,
            3511, 3512, 3513, 3514, 3515, 3516, 3517, 3518, 3519, 3520, 3521
    };

    // Temple hub / branch NPCs (verified against Quest.wz Check.img + Map.wz NPC placement).
    private static final int TEMPLE_KEEPER = 2140000, HUB_MAP = 270000000;      // lane kill-quest turn-in hub
    private static final int MEMORY_KEEPER = 2140001, MEMORY_MAP = 270010111;   // 3506/3507
    private static final int SORCERER = 2140002, SORCERER_MAP = 270020211;      // 3513/3514
    private static final int RECORD_KEEPER = 2140003, RECORD_MAP = 270030411;   // 3520

    // Special-quest ids handled by dedicated branches.
    private static final int Q_MEMORY_KEEPER = 3507; // needs a class sub-quest done first
    private static final int Q_SORCERER = 3514;      // buy + DRINK the Sorcerer's Potion
    private static final int Q_FORCE_FIELD = 3521;   // 6-item turn-in

    private static final int SORCERER_POTION = 2022337;   // 3514's emotion potion
    private static final int POTION_COST = 1_000_000;      // meso the sorcerer charges for it
    private static final int Q3514_EXP = 891_500;          // exp q3514e grants by script (Act.img is empty)
    private static final int Q3507_INFO = 7081;            // 3507 completion infoNumber the sub-quest sets

    /** One mainline quest's placement. {@code laneMap == LANE_NONE} = talk/special (no x999 grind). */
    private record TQ(int id, int lvmin, int startNpc, int startMap, int completeNpc, int completeMap, int laneMap) {}

    private static final int LANE_NONE = -1;

    private static final Map<Integer, TQ> QUESTS = new LinkedHashMap<>();

    private static void put(int id, int lvmin, int startNpc, int startMap,
                            int completeNpc, int completeMap, int laneMap) {
        QUESTS.put(id, new TQ(id, lvmin, startNpc, startMap, completeNpc, completeMap, laneMap));
    }

    static {
        // id,   lvmin, startNpc,      startMap,   completeNpc,   completeMap, laneMap
        put(3500,  90, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   LANE_NONE);
        put(3501,  91, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270010100);
        put(3502,  94, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270010200);
        put(3503,  98, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270010300);
        put(3504, 101, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270010400);
        put(3505, 103, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270010500); // Dodo x1
        put(3506, 103, TEMPLE_KEEPER, HUB_MAP,   MEMORY_KEEPER, MEMORY_MAP, LANE_NONE);
        put(3507, 103, MEMORY_KEEPER, MEMORY_MAP, MEMORY_KEEPER, MEMORY_MAP, LANE_NONE); // special
        put(3508, 106, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270020100);
        put(3509, 109, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270020200);
        put(3510, 113, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270020300);
        put(3511, 116, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270020400);
        put(3512, 120, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270020500); // Lilynouch x1
        put(3513, 120, TEMPLE_KEEPER, HUB_MAP,   SORCERER,      SORCERER_MAP, LANE_NONE);
        put(3514, 120, SORCERER,      SORCERER_MAP, SORCERER,   SORCERER_MAP, LANE_NONE); // special
        put(3515, 121, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270030100);
        put(3516, 124, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270030200);
        put(3517, 128, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270030300);
        put(3518, 131, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270030400);
        put(3519, 135, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   270030500); // Lyka x1
        put(3520, 135, TEMPLE_KEEPER, HUB_MAP,   RECORD_KEEPER, RECORD_MAP, LANE_NONE);
        put(3521, 135, TEMPLE_KEEPER, HUB_MAP,   TEMPLE_KEEPER, HUB_MAP,   LANE_NONE); // special (6-item turn-in)
    }

    /** One class-branch sub-quest that unlocks 3507 (each a single yes/no talk at a home-town instructor). */
    private record SubQuest(int id, int npc, int map) {}

    // Verified against Quest.wz Check.img (npc/job) + Map.wz NPC placement.
    private static final SubQuest SUB_WARRIOR  = new SubQuest(3523, 1022000, 102000003); // Perion
    private static final SubQuest SUB_MAGICIAN = new SubQuest(3524, 1032001, 101000003); // Ellinia
    private static final SubQuest SUB_BOWMAN   = new SubQuest(3525, 1012100, 100000201); // Henesys
    private static final SubQuest SUB_THIEF    = new SubQuest(3526, 1052001, 103000003); // Kerning City
    private static final SubQuest SUB_PIRATE   = new SubQuest(3527, 1090000, 120000101); // Nautilus
    private static final SubQuest SUB_CYGNUS   = new SubQuest(3529, 1101002, 130000000); // Ereve
    private static final SubQuest SUB_ARAN     = new SubQuest(3539, 1201000, 140000000); // Rien

    // ---- tuning ------------------------------------------------------------------------------

    /** Interaction radius (matches the cab/shop/quest standard). */
    private static final int TRIGGER_RADIUS_PX = BotQuestManager.NPC_TRIGGER_RADIUS_PX;
    /** No-progress deadline for an NPC approach — refreshed on every hop, so a long legal route is fine. */
    private static final long APPROACH_TIMEOUT_MS = 90_000L;
    /** Others already committed to a lane before this bot steps aside to disperse. */
    private static final int CROWD_DEFER_THRESHOLD = 2;
    /** Jittered step-aside window when a lane is crowded (bot grinds elsewhere meanwhile). */
    private static final int CROWD_DEFER_MIN_MS = 60_000, CROWD_DEFER_MAX_MS = 180_000;
    /** How long a lane pin suppresses the advisor's re-decide so it doesn't fight the commitment. */
    private static final long PIN_HOLD_MS = 30_000L;
    /** Back-off after an approach stalls / the NPC is missing, before retrying the errand. */
    private static final int BACKOFF_MIN_MS = 60_000, BACKOFF_MAX_MS = 120_000;
    /** Re-arm spacing after the errand disarms (mirrors nextQuestScanAtMs surviving a clear). */
    private static final int REARM_MIN_MS = 30_000, REARM_MAX_MS = 90_000;

    /** Bot chat seam (tests swap it; production routes through the owner's channel). */
    static java.util.function.BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    // ---- pure step resolver (unit-tested) ----------------------------------------------------

    /** The lowest quest in {@link #MAINLINE_CHAIN} the bot has NOT completed, or -1 when the whole
     *  chain is done. Pure over the {@code isCompleted} predicate so the resolver is testable WZ-free. */
    static int nextIncompleteMainline(IntPredicate isCompleted) {
        for (int q : MAINLINE_CHAIN) {
            if (!isCompleted.test(q)) {
                return q;
            }
        }
        return -1;
    }

    // ---- errand framework hooks --------------------------------------------------------------

    /** Re-arm hook (called every tick while disarmed): opt in once the bot reaches its personal
     *  ambition level and the chain still has work, then let {@link #tickErrand} drive it. */
    static void maybeStart(BotEntry entry, Character bot) {
        if (entry.templeErrandMapId != -1 || bot == null) {
            return; // already armed
        }
        if (!BotManager.cfg.TEMPLE_PROGRESSION || !BotAutopilotManager.isActive(entry)) {
            return; // disabled, or a supervised bot (stays at the owner's side)
        }
        long now = System.currentTimeMillis();
        if (now < entry.nextTempleScanAtMs) {
            return; // re-arm cooldown
        }
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        if (bot.getLevel() < p.templeAmbitionLevel()) {
            return; // not ambitious/high enough yet
        }
        if (nextIncompleteMainline(q -> BotQuestManager.gate.isCompleted(bot, q)) == -1) {
            return; // nothing left to do
        }
        entry.templeErrandMapId = bot.getMapId(); // armed sentinel (tick overwrites with the real target)
        entry.templeErrandNpcId = 0;
        entry.templeErrandProgress.begin(now);
        reply.accept(entry, "gonna work through the temple of time questline");
    }

    static boolean active(BotEntry entry) {
        return entry.templeErrandMapId != -1;
    }

    /** True to let a cramped-bag resupply run before this errand (a cross-continent sub-quest leg buys
     *  a taxi/ferry fare it needs bag room for). Mirrors the job/FM errands. */
    static boolean yieldForResupply(BotEntry entry, Character bot) {
        return BotAutopilotManager.bagFull.bagFull(entry, bot);
    }

    // ---- main tick ---------------------------------------------------------------------------

    /**
     * Drives one tick of the armed Temple errand. Returns true when it consumed the tick (traveling to
     * or walking to an NPC); false to let the normal grind/combat flow run (GRIND_LANE, and every defer).
     */
    static boolean tickErrand(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.templeErrandMapId == -1) {
            return false;
        }
        if (!BotAutopilotManager.isActive(entry)) {
            clearTempleErrand(entry); // supervised again (owner online): drop the errand
            return false;
        }
        int qid = nextIncompleteMainline(q -> BotQuestManager.gate.isCompleted(bot, q));
        if (qid == -1) {
            clearTempleErrand(entry);
            reply.accept(entry, "done with the temple of time questline");
            return false;
        }
        TQ tq = QUESTS.get(qid);
        if (tq == null) {
            return defer(entry); // shouldn't happen (chain is fully mapped)
        }
        switch (qid) {
            case Q_MEMORY_KEEPER -> { return handle3507(entry, bot, runAiTick, tq); }
            case Q_SORCERER -> { return handle3514(entry, bot, runAiTick, tq); }
            case Q_FORCE_FIELD -> { return handle3521(entry, bot, runAiTick, tq); }
            default -> { return handleStandard(entry, bot, runAiTick, tq); }
        }
    }

    // ---- standard lane / talk quests ---------------------------------------------------------

    private static boolean handleStandard(BotEntry entry, Character bot, boolean runAiTick, TQ tq) {
        if (!BotQuestManager.gate.isStarted(bot, tq.id())) {
            if (bot.getLevel() < tq.lvmin()) {
                return defer(entry); // grind normally until high enough to start it
            }
            return approach(entry, bot, tq.startMap(), tq.startNpc(), runAiTick, () -> doStart(entry, bot, tq));
        }
        // Started: a talk quest (no lane) turns in immediately; a lane quest grinds until its quota is met.
        if (tq.laneMap() != LANE_NONE
                && !BotQuestManager.gate.canComplete(bot, tq.id(), tq.completeNpc())) {
            return grindLane(entry, bot, tq.laneMap());
        }
        return approach(entry, bot, tq.completeMap(), tq.completeNpc(), runAiTick, () -> doComplete(entry, bot, tq));
    }

    private static void doStart(BotEntry entry, Character bot, TQ tq) {
        if (BotQuestManager.gate.canStart(bot, tq.id(), tq.startNpc())) {
            BotQuestManager.gate.start(bot, tq.id(), tq.startNpc());
            reply.accept(entry, "picked up " + BotQuestManager.questName.name(tq.id()));
        } else {
            // A start edge beyond level (unexpected for this data) isn't met: without a pause the
            // next tick re-arrives and re-tries in a hot loop at the NPC — step aside instead.
            backOff(entry);
        }
    }

    private static void doComplete(BotEntry entry, Character bot, TQ tq) {
        if (BotQuestManager.gate.canComplete(bot, tq.id(), tq.completeNpc())) {
            BotQuestManager.gate.complete(bot, tq.id(), tq.completeNpc());
            if (BotQuestManager.gate.isCompleted(bot, tq.id())) {
                reply.accept(entry, "finished " + BotQuestManager.questName.name(tq.id()));
            } else {
                backOff(entry); // didn't register (unexpected for this data) — don't hammer it
            }
        }
    }

    // ---- GRIND_LANE (pin + crowd-defer) ------------------------------------------------------

    /**
     * Commit the bot's grind destination to a Temple lane so its started quest's kills accrue through the
     * normal grind/combat flow. Disperses when the lane is already busy: steps aside and kicks a re-decide
     * so the advisor (which applies the same crowd surcharge) sends it elsewhere for a jittered while.
     *
     * <p>Uses the crowd SIGNAL ({@link BotOccupancy#extraCompetitors}) directly rather than a full
     * {@link BotGrindAdvisor} pass — a world-wide advisor pass must never run on the bot tick thread
     * (see BotQuestManager.grindExpBaseline), and occupancy is exactly the crowd input that pass consumes.
     */
    private static boolean grindLane(BotEntry entry, Character bot, int laneMap) {
        long now = System.currentTimeMillis();
        entry.templeErrandNpcId = 0;           // not mid-approach
        entry.templeErrandMapId = laneMap;      // armed sentinel / debug view of the target
        entry.templeErrandProgress.touch(now);  // no NPC to time out on while grinding
        BotTravelManager.clearMoveTargetPin(entry);
        if (now < entry.nextTempleScanAtMs) {
            return false; // in a step-aside window: normal grind runs elsewhere
        }
        // Crowd re-check on a seconds cadence, not per tick: BotOccupancy walks every online
        // character, so a per-tick scan from every lane-pinned bot is O(bots^2) at population scale.
        // The pin bookkeeping below still runs every tick.
        if (now >= entry.templeCrowdCheckDueMs) {
            entry.templeCrowdCheckDueMs = now + BotManager.randMs(2_500, 4_500);
            double penalty = BotManager.cfg.CROWD_PENALTY_FACTOR;
            double surcharge = BotOccupancy.extraCompetitors(bot, penalty).applyAsDouble(laneMap);
            int competitors = penalty > 0 ? (int) Math.round(surcharge / penalty) : 0;
            if (competitors >= CROWD_DEFER_THRESHOLD) {
                entry.nextTempleScanAtMs = now + BotManager.randMs(CROWD_DEFER_MIN_MS, CROWD_DEFER_MAX_MS);
                entry.autopilotNextDecisionAtMs = 0L; // re-pick now, honoring the crowd surcharge -> disperse
                return false;
            }
        }
        entry.autopilotMapId = laneMap; // pin the lane; kills accrue via the normal grind/combat flow
        entry.autopilotNextDecisionAtMs = Math.max(entry.autopilotNextDecisionAtMs, now + PIN_HOLD_MS);
        return false;
    }

    // ---- special: 3507 (class sub-quest unlock) ----------------------------------------------

    private static boolean handle3507(BotEntry entry, Character bot, boolean runAiTick, TQ tq) {
        if (!BotQuestManager.gate.isStarted(bot, tq.id())) {
            return approach(entry, bot, tq.startMap(), tq.startNpc(), runAiTick,
                    () -> doStart(entry, bot, tq));
        }
        if (BotQuestManager.gate.canComplete(bot, tq.id(), tq.completeNpc())) {
            return approach(entry, bot, tq.completeMap(), tq.completeNpc(), runAiTick,
                    () -> doComplete(entry, bot, tq));
        }
        // Need a class sub-quest done first (it sets 3507's infoNumber). Detour to the home-town instructor.
        SubQuest sub = subQuestFor(bot);
        return approach(entry, bot, sub.map(), sub.npc(), runAiTick, () -> doSubQuest(entry, bot, sub));
    }

    /**
     * Reproduce the class sub-quest's exact server calls (e.g. q3523s: {@code startQuest();
     * setQuestProgress(3507,7081,1); completeQuest();}). The 3507-progress set is the load-bearing effect
     * — 3507 cannot complete without infoNumber 7081, and the sub-quest's own WZ Act is empty, so
     * {@code gate.complete} on it would NOT set it. {@link Quest#forceStart}/{@link Quest#forceComplete}
     * ARE the same calls the NPC dialog runs (these sub-quests are script-only, no data path).
     */
    private static void doSubQuest(BotEntry entry, Character bot, SubQuest sub) {
        try {
            Quest q = Quest.getInstance(sub.id());
            q.forceStart(bot, sub.npc());
            bot.setQuestProgress(Q_MEMORY_KEEPER, Q3507_INFO, "1");
            q.forceComplete(bot, sub.npc());
            reply.accept(entry, "found a teacher who remembers me");
        } catch (RuntimeException e) {
            log.warn("Bot '{}' sub-quest {} for Temple 3507 failed", bot.getName(), sub.id(), e);
            backOff(entry);
        }
    }

    /** The class-branch sub-quest that unlocks 3507 for this bot's job. */
    private static SubQuest subQuestFor(Character bot) {
        int j = bot.getJob().getId();
        if (j >= 2000) return SUB_ARAN;     // Aran
        if (j >= 1000) return SUB_CYGNUS;   // Knights of Cygnus
        if (j >= 500) return SUB_PIRATE;
        if (j >= 400) return SUB_THIEF;
        if (j >= 300) return SUB_BOWMAN;
        if (j >= 200) return SUB_MAGICIAN;
        return SUB_WARRIOR;                 // 100-199 (a Temple bot is never a beginner)
    }

    // ---- special: 3514 (buy + DRINK the Sorcerer's Potion) -----------------------------------

    /**
     * 3514 is fully script-driven (empty WZ Act, start+end scripts), so this reproduces q3514s/q3514e
     * rather than using the generic gate: the start dialog charges 1,000,000 meso for potion 2022337, and
     * the end dialog only completes once the potion has been DRUNK (buff HPREC sourced from 2022337) and
     * grants the exp itself. Deferred (bot grinds for meso) until it can afford the potion.
     */
    private static boolean handle3514(BotEntry entry, Character bot, boolean runAiTick, TQ tq) {
        if (!BotQuestManager.gate.isStarted(bot, tq.id())) {
            if (bot.getLevel() < tq.lvmin() || bot.getMeso() < POTION_COST) {
                return defer(entry); // grind to level / earn the potion's price first
            }
            return approach(entry, bot, tq.startMap(), tq.startNpc(), runAiTick, () -> buyPotionAndStart(entry, bot));
        }
        return approach(entry, bot, tq.completeMap(), tq.completeNpc(), runAiTick, () -> drinkAndComplete(entry, bot));
    }

    /** Reproduce q3514s: charge the meso, hand over the potion, start the quest. */
    private static void buyPotionAndStart(BotEntry entry, Character bot) {
        if (bot.getMeso() < POTION_COST || !bot.canHold(SORCERER_POTION, 1)) {
            return; // lost the meso or no USE room right now — retry next visit
        }
        try {
            InventoryManipulator.addById(bot.getClient(), SORCERER_POTION, (short) 1);
            bot.gainMeso(-POTION_COST);
            Quest.getInstance(Q_SORCERER).forceStart(bot, SORCERER);
            reply.accept(entry, "bought the emotion potion");
        } catch (RuntimeException e) {
            log.warn("Bot '{}' failed to buy Temple potion", bot.getName(), e);
            backOff(entry);
        }
    }

    /** Reproduce q3514e: drink the potion (if not yet), then complete once the buff is registered. */
    private static void drinkAndComplete(BotEntry entry, Character bot) {
        if (!usedPotion(bot)) {
            if (!bot.haveItem(SORCERER_POTION) && bot.canHold(SORCERER_POTION, 1)) {
                InventoryManipulator.addById(bot.getClient(), SORCERER_POTION, (short) 1); // end-dialog re-grant
            }
            drinkPotion(bot);
        }
        if (usedPotion(bot)) {
            ExpAction.runAction(bot, Q3514_EXP);
            Quest.getInstance(Q_SORCERER).forceComplete(bot, SORCERER);
            reply.accept(entry, "the emotions are thawed, moving on");
        } else {
            // Couldn't drink (potion lost + USE tab full blocks the re-grant): pause instead of
            // re-arriving in a hot loop; the resupply flow frees space during the back-off.
            backOff(entry);
        }
    }

    /** True once the potion's buff is active on the bot — the same check q3514e's usedPotion() makes. */
    private static boolean usedPotion(Character bot) {
        return bot.getBuffSource(BuffStat.HPREC) == SORCERER_POTION;
    }

    /** Drink potion 2022337: apply its effect, then remove one from the USE bag (the ScrollHandler /
     *  tryUseReturnScroll item-use shape — a fourth copy for one item id, not a new primitive). */
    private static void drinkPotion(Character bot) {
        var use = bot.getInventory(InventoryType.USE);
        if (use == null) {
            return;
        }
        for (Item it : use.list()) {
            if (it == null || it.getItemId() != SORCERER_POTION || it.getQuantity() <= 0) {
                continue;
            }
            StatEffect effect;
            try {
                effect = ItemInformationProvider.getInstance().getItemEffect(SORCERER_POTION);
            } catch (RuntimeException e) {
                return;
            }
            if (effect != null && effect.applyTo(bot)) {
                InventoryManipulator.removeFromSlot(bot.getClient(), InventoryType.USE, it.getPosition(), (short) 1, false);
            }
            return;
        }
    }

    // ---- special: 3521 (6-item Force Field turn-in) ------------------------------------------

    /** One of the 3 lane-5 miniboss helmets 3521 needs, and the map to re-farm when it's missing.
     *  (The other 3 items, masks 4000446/451/456, drop 1% off ordinary pack mobs and so accrue
     *  passively through the mainline grind — only the helmets need targeted re-farming.) Verified
     *  against drop_data: Dodo 8220004, Lilynouch 8220005, Lyka 8220006, each 60% per kill. */
    private record Helmet(int itemId, int laneMap) {}

    private static final List<Helmet> HELMETS = List.of(
            new Helmet(4000460, 270010500), // Dodo
            new Helmet(4000461, 270020500), // Lilynouch
            new Helmet(4000462, 270030500)  // Lyka
    );

    /** Sell/discard guard: the six Force Field turn-in materials must survive inventory hygiene until
     *  3521 is done. They carry no WZ {@code info/quest} flag, so the generic quest-item guard cannot
     *  see them — without this, a cramped-ETC sell trip NPCs the masks/helmets mid-questline. */
    static boolean isQuestCriticalItem(Character bot, int itemId) {
        boolean forceFieldItem = switch (itemId) {
            case 4000446, 4000451, 4000456, 4000460, 4000461, 4000462 -> true;
            default -> false;
        };
        return forceFieldItem && bot != null && !BotQuestManager.gate.isCompleted(bot, Q_FORCE_FIELD);
    }

    private static boolean handle3521(BotEntry entry, Character bot, boolean runAiTick, TQ tq) {
        if (!BotQuestManager.gate.isStarted(bot, tq.id())) {
            if (bot.getLevel() < tq.lvmin()) {
                return defer(entry);
            }
            return approach(entry, bot, tq.startMap(), tq.startNpc(), runAiTick, () -> doStart(entry, bot, tq));
        }
        // Turn in once everything's held (data-driven Act consume/reward). Otherwise, re-farm the lane-5
        // map of the first missing helmet (60% drop from a single miniboss kill stalls most bots forever
        // if left to accrue passively); once all 3 helmets are held the masks have always caught up too.
        if (BotQuestManager.gate.canComplete(bot, tq.id(), tq.completeNpc())) {
            return approach(entry, bot, tq.completeMap(), tq.completeNpc(), runAiTick, () -> doComplete(entry, bot, tq));
        }
        for (Helmet helmet : HELMETS) {
            if (!bot.haveItem(helmet.itemId())) {
                return grindLane(entry, bot, helmet.laneMap());
            }
        }
        return defer(entry);
    }

    // ---- shared NPC approach stepper ---------------------------------------------------------

    /** Walk to {@code npcId} on {@code targetMap} (SSOT {@link BotTravelManager#tickApproachNpc}); on
     *  arrival, after the humanlike read-dwell, run {@code onArrive}. Returns true while traveling/walking
     *  (tick consumed). Re-begins the shared stall clock whenever the target NPC changes. */
    private static boolean approach(BotEntry entry, Character bot, int targetMap, int npcId,
                                    boolean runAiTick, Runnable onArrive) {
        long now = System.currentTimeMillis();
        if (entry.templeErrandNpcId != npcId || entry.templeErrandMapId != targetMap) {
            entry.templeErrandNpcId = npcId;
            entry.templeErrandMapId = targetMap;
            entry.templeErrandProgress.begin(now);
            BotTravelManager.clearNpcApproach(entry);
        }
        if (entry.templeErrandProgress.stalled(now, APPROACH_TIMEOUT_MS)) {
            backOff(entry); // can't reach it — step aside and retry after a cooldown
            return false;
        }
        BotTravelManager.ApproachStatus status = BotTravelManager.tickApproachNpc(
                entry, bot, targetMap, npcId, BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, true, TRIGGER_RADIUS_PX);
        entry.templeErrandProgress.record(bot, status == BotTravelManager.ApproachStatus.TRAVELING, now);
        switch (status) {
            case NPC_GONE -> {
                backOff(entry);
                return false;
            }
            case ARRIVED -> {
                if (!BotManager.npcDwellReady(entry, BotManager.NPC_READ_DELAY_MS, BotManager.NPC_READ_JITTER_MS)) {
                    return true; // "reading" the dialogue before acting
                }
                onArrive.run();
                entry.templeErrandNpcId = 0; // action done — next tick re-resolves the step
                return false;
            }
            case TRAVEL_YIELDED -> {
                BotManager.npcDwellReset(entry);
                return false; // travel gave up this tick — let the bot grind; the errand retries/times out
            }
            case TRAVELING -> {
                return true; // a hop is underway (record() refreshed the deadline)
            }
            default -> {
                BotManager.npcDwellReset(entry);
                return true; // WALKING within radius on the NPC's map
            }
        }
    }

    // ---- defer / back-off / clear ------------------------------------------------------------

    /** Yield this tick (level/meso/item not ready) so the normal grind flow levels the bot / earns meso;
     *  stays armed, no cooldown, no chat. */
    private static boolean defer(BotEntry entry) {
        entry.templeErrandNpcId = 0;
        entry.templeErrandProgress.touch(System.currentTimeMillis());
        BotTravelManager.clearMoveTargetPin(entry);
        return false;
    }

    /** Step aside after an approach couldn't reach its NPC: keep the errand armed but pause it for a
     *  jittered cooldown so it doesn't hammer an unreachable target while the bot grinds. */
    private static void backOff(BotEntry entry) {
        entry.templeErrandNpcId = 0;
        entry.nextTempleScanAtMs = System.currentTimeMillis() + BotManager.randMs(BACKOFF_MIN_MS, BACKOFF_MAX_MS);
        BotManager.npcDwellReset(entry);
        BotTravelManager.clearNpcApproach(entry);
        BotTravelManager.clearMoveTargetPin(entry);
    }

    /** Reset all Temple errand state (called on disarm/complete and from BotAutopilotManager.clear).
     *  Leaves nextTempleScanAtMs intact so it rate-limits re-arming (like nextQuestScanAtMs). */
    static void clearTempleErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.templeErrandMapId = -1;
        entry.templeErrandNpcId = 0;
        entry.templeErrandProgress.clear();
        if (entry.nextTempleScanAtMs < System.currentTimeMillis()) {
            entry.nextTempleScanAtMs = System.currentTimeMillis() + BotManager.randMs(REARM_MIN_MS, REARM_MAX_MS);
        }
    }
}
