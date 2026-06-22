package server.bots;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class BotStarterKitManager {
    private static final Logger log = LoggerFactory.getLogger(BotStarterKitManager.class);

    /** Bot chat output, behind a seam so tests stay off the BotManager singleton (mirrors BotQuestManager). */
    static java.util.function.BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    record ItemGrant(int itemId, short quantity) {}

    private static final int BEGINNER_WARRIOR_SWORD = 1302077;
    private static final int BEGINNER_MAGICIAN_WAND = 1372043;
    private static final int BEGINNER_BOWMANS_BOW = 1452051;
    private static final int BEGINNER_THIEF_WRIST_GUARD = 1472061;
    private static final int BEGINNER_THIEF_SHORT_SWORD = 1332063;
    private static final int NOVA_THROWING_KNIVES = 2070015;
    private static final int GARNIER = 1492000;
    private static final int STEEL_KNUCKLER = 1482000;
    private static final int WOODEN_ARROWS = 2060000;
    private static final int BULLETS = 2330000;

    private static final Map<Job, List<ItemGrant>> FIRST_JOB_KITS = Map.of(
            Job.WARRIOR, List.of(grant(BEGINNER_WARRIOR_SWORD, 1)),
            Job.MAGICIAN, List.of(grant(BEGINNER_MAGICIAN_WAND, 1)),
            Job.BOWMAN, List.of(grant(BEGINNER_BOWMANS_BOW, 1), grant(WOODEN_ARROWS, 1000)),
            Job.THIEF, List.of(
                    grant(BEGINNER_THIEF_WRIST_GUARD, 1),
                    grant(BEGINNER_THIEF_SHORT_SWORD, 1),
                    grant(NOVA_THROWING_KNIVES, 500)
            ),
            Job.PIRATE, List.of(
                    grant(GARNIER, 1),
                    grant(STEEL_KNUCKLER, 1),
                    grant(BULLETS, 1000)
            )
    );

    static void advanceJob(BotEntry entry, Job newJob) {
        Character bot = entry.bot;
        Character owner = entry.owner;
        Job oldJob = bot.getJob();
        bot.changeJob(newJob);
        BotBuildManager.handleJobAdvance(entry, bot, oldJob, newJob);
        grantStarterKitIfEligible(bot, oldJob, newJob);
        BotEquipManager.autoEquip(bot, owner, null);
        reply.accept(entry, "advanced to " + newJob + "!");
        BotChatManager.checkBotStatus(entry, bot);
    }

    static List<ItemGrant> starterKitFor(Job job) {
        return FIRST_JOB_KITS.getOrDefault(job, List.of());
    }

    static boolean isFirstJobAdvancement(Job oldJob, Job newJob) {
        return oldJob == Job.BEGINNER && FIRST_JOB_KITS.containsKey(newJob);
    }

    // Explorer job ids run base -> 1st(X00) -> 2nd(X10/X20/X30) -> 3rd(+1) -> 4th(+1) within each
    // branch (see client.Job), so the single deterministic successor of a 2nd/3rd job is just id+1.
    // This is the autopilot counterpart to the owner-typed alias parse in BotChatManager (3rd/4th
    // job have no choice, unlike 1st/2nd), and is restricted to explorer ids so Cygnus/Aran/Evan
    // (different numbering) never match.
    private static final int EXPLORER_MIN = 100;
    private static final int EXPLORER_MAX = 600;

    /** The lone 3rd-job successor of an explorer 2nd job (e.g. FIGHTER -> CRUSADER), or null if the
     *  given job is not an explorer 2nd job. */
    static Job thirdJobOf(Job secondJob) {
        int id = secondJob == null ? 0 : secondJob.getId();
        boolean isSecond = id >= EXPLORER_MIN && id < EXPLORER_MAX && id % 100 != 0 && id % 10 == 0;
        return isSecond ? Job.getById(id + 1) : null;
    }

    /** The lone 4th-job successor of an explorer 3rd job (e.g. CRUSADER -> HERO), or null if the
     *  given job is not an explorer 3rd job. */
    static Job fourthJobOf(Job thirdJob) {
        int id = thirdJob == null ? 0 : thirdJob.getId();
        boolean isThird = id >= EXPLORER_MIN && id < EXPLORER_MAX && id % 10 == 1;
        return isThird ? Job.getById(id + 1) : null;
    }

    // ---- job-change NPC errand (autopilot only) ----------------------------------------------

    /** A job instructor: the NPC that advances a tier, and the map it sits on. */
    record JobChangeNpc(int npcId, int mapId, String townName) {}

    // SSOT for "which NPC advances each explorer branch", keyed by branch = id/100 (the shared first
    // digit of every job in a class line). One table per advancement tier. Verified vs Map.wz life data
    // + handbook/NPC.txt.

    // 1st job: the town instructor (Magician/Bowman sit in scripted hidden streets reached via
    // BotWorldGraph.SCRIPTED_ENTRANCES; the rest are plain-reachable).
    private static final Map<Integer, JobChangeNpc> FIRST_JOB_NPC = Map.of(
            1, new JobChangeNpc(1022000, 102000003, "Perion"),    // Warrior  - Dances with Balrog
            2, new JobChangeNpc(1032001, 101000003, "Ellinia"),   // Magician - Grendel the Really Old
            3, new JobChangeNpc(1012100, 100000201, "Henesys"),   // Bowman   - Athena Pierce
            4, new JobChangeNpc(1052001, 103000003, "Kerning"),   // Thief    - Dark Lord
            5, new JobChangeNpc(1090000, 120000101, "Nautilus")   // Pirate   - Kyrin
    );

    // 2nd job: the distinct field "Job Instructor" NPCs (1072xxx) — NOT the same as 1st job, and in
    // plain-reachable field maps (the old "unreachable test map" assumption was wrong). Pirate reuses
    // Kyrin. Verified reachable vs the portal graph + Map.wz life data + handbook/NPC.txt.
    private static final Map<Integer, JobChangeNpc> SECOND_JOB_NPC = Map.of(
            1, new JobChangeNpc(1072000, 102020300, "West Rocky Mountain IV"),         // Warrior Job Instructor
            2, new JobChangeNpc(1072001, 101020000, "the Forest North of Ellinia"),    // Magician Job Instructor
            3, new JobChangeNpc(1072002, 106010000, "the Road to the Dungeon"),        // Bowman Job Instructor
            4, new JobChangeNpc(1072003, 102040000, "the Construction Site N of Kerning"), // Thief Job Instructor
            5, new JobChangeNpc(1090000, 120000101, "Nautilus")                        // Pirate - Kyrin
    );

    // 3rd job: the same NPC 1061009 "Door of Dimension" sits in each branch's hidden dungeon map.
    private static final int THIRD_JOB_NPC = 1061009;
    private static final Map<Integer, JobChangeNpc> THIRD_JOB_NPC_BY_BRANCH = Map.of(
            1, new JobChangeNpc(THIRD_JOB_NPC, 105070001, "Ant Tunnel Park"),     // Warrior
            2, new JobChangeNpc(THIRD_JOB_NPC, 100040106, "Forest of Evil II"),   // Magician
            3, new JobChangeNpc(THIRD_JOB_NPC, 105040305, "Sleepy Dungeon V"),    // Bowman
            4, new JobChangeNpc(THIRD_JOB_NPC, 107000402, "Monkey Swamp II"),     // Thief
            5, new JobChangeNpc(THIRD_JOB_NPC, 105070200, "Cave of Evil Eye II")  // Pirate
    );

    // 4th job: per-branch master, all in Leafre - Forest of the Priest (240010501).
    private static final int FOURTH_JOB_MAP = 240010501;
    private static final Map<Integer, JobChangeNpc> FOURTH_JOB_NPC_BY_BRANCH = Map.of(
            1, new JobChangeNpc(2081100, FOURTH_JOB_MAP, "Leafre"),  // Warrior  - Harmonia
            2, new JobChangeNpc(2081200, FOURTH_JOB_MAP, "Leafre"),  // Magician - Gritto
            3, new JobChangeNpc(2081300, FOURTH_JOB_MAP, "Leafre"),  // Bowman   - Legor
            4, new JobChangeNpc(2081400, FOURTH_JOB_MAP, "Leafre"),  // Thief    - Hellin
            5, new JobChangeNpc(2081500, FOURTH_JOB_MAP, "Leafre")   // Pirate   - Samuel
    );

    /** The instructor NPC for an explorer advancement target, or null for Beginner / non-explorer.
     *  Ones digit of the job id = tier: 0 = 1st/2nd job, 1 = 3rd, 2 = 4th; within tier 0, id%100==0 is
     *  1st job (town instructor) and the rest are 2nd job (distinct field Job Instructor). */
    static JobChangeNpc jobChangeNpcFor(Job target) {
        if (target == null) {
            return null;
        }
        int id = target.getId();
        int branch = id / 100;
        if (id <= 0 || branch < 1 || branch > 5) {
            return null;
        }
        return switch (id % 10) {
            case 0 -> (id % 100 == 0) ? FIRST_JOB_NPC.get(branch) : SECOND_JOB_NPC.get(branch);
            case 1 -> THIRD_JOB_NPC_BY_BRANCH.get(branch);
            case 2 -> FOURTH_JOB_NPC_BY_BRANCH.get(branch);
            default -> null;
        };
    }

    /** True when advancing to {@code target} should WALK to an instructor first (every explorer
     *  1st-4th job advancement). */
    static boolean routesThroughNpc(Job target) {
        return jobChangeNpcFor(target) != null;
    }

    /** Within this many px of the instructor counts as "talked to it" — matches the quest/cab radius. */
    static final int NPC_TRIGGER_RADIUS_PX = 500;
    /** With the fallback ON, give up after this long WITHOUT PROGRESS (no map hop and not actively
     *  traveling) so it force-advances instead of wedging. It is a no-progress deadline, NOT a total
     *  trip budget — a legal cross-continent route (incl. boat waits longer than this) keeps refreshing
     *  it on every hop / travel tick, so only a genuine single-map wedge trips it. With the fallback
     *  OFF there is no give-up: the bot stays committed and keeps retrying (see {@link #tickJobErrand}). */
    static final long ERRAND_NO_PROGRESS_MS = 90_000L;
    /** Throttle for the "can't reach instructor" error log while a fallback-off bot is stuck retrying. */
    static final long ERRAND_WARN_INTERVAL_MS = 30_000L;

    /** Begin an instructor-walk errand for an autopilot bot instead of advancing instantly. */
    static void beginJobErrand(BotEntry entry, Job target) {
        JobChangeNpc instructor = jobChangeNpcFor(target);
        if (instructor == null) {
            return;
        }
        entry.jobErrandTarget = target;
        entry.jobErrandNpcId = instructor.npcId();
        entry.jobErrandMapId = instructor.mapId();
        entry.jobErrandProgress.begin(System.currentTimeMillis());
        reply.accept(entry, "heading to " + instructor.townName() + " to change job");
    }

    static void clearJobErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.jobErrandTarget = null;
        entry.jobErrandNpcId = 0;
        entry.jobErrandMapId = -1;
        entry.jobErrandProgress.clear();
        entry.jobErrandLastWarnMs = 0L;
    }

    /**
     * Drives an active job-change errand: travel to the instructor's town, walk within radius, then
     * advance on arrival. Returns true while the tick is consumed (so the caller doesn't grind), false
     * only once the errand is done or force-advanced. Called from {@link BotAutopilotManager#tick}
     * BEFORE combat so the bot does not grind (and over-level) en route. Reuses the shared
     * {@link BotTravelManager#tickApproachNpc} stepper (no reimplemented travel).
     *
     * <p>When it can't reach the instructor the behavior depends on
     * {@link BotManager.Config#JOB_CHANGE_FALLBACK_ANYWHERE}:
     * <ul>
     *   <li><b>ON</b> (legacy): a 90s timeout / missing-NPC force-advances on the spot.</li>
     *   <li><b>OFF</b> (default): the bot stays committed and keeps retrying forever — it never falls
     *       back to grinding/autopilot — and logs a throttled error WITH reachability so the block is
     *       debuggable.</li>
     * </ul>
     */
    static boolean tickJobErrand(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.jobErrandMapId == -1 || entry.jobErrandTarget == null) {
            return false;
        }
        boolean forceFallback = BotManager.cfg.JOB_CHANGE_FALLBACK_ANYWHERE;
        BotTravelManager.ApproachStatus status = BotTravelManager.tickApproachNpc(
                entry, bot, entry.jobErrandMapId, entry.jobErrandNpcId,
                BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, true, NPC_TRIGGER_RADIUS_PX); // ferry: instructor may be cross-continent
        long now = System.currentTimeMillis();
        entry.jobErrandProgress.record(bot, status == BotTravelManager.ApproachStatus.TRAVELING, now);
        boolean noProgressTooLong = forceFallback && entry.jobErrandProgress.stalled(now, ERRAND_NO_PROGRESS_MS);
        switch (status) {
            case NPC_GONE -> {
                if (forceFallback) {
                    forceAdvance(entry, bot, "instructor not on the resolved map");
                    return false;
                }
                warnJobErrandStuck(entry, bot, "instructor not on the resolved map");
                return true; // stay committed — don't drop back to grinding
            }
            case ARRIVED -> {
                if (!BotManager.npcDwellReady(entry, BotManager.NPC_READ_DELAY_MS, BotManager.NPC_READ_JITTER_MS)) {
                    return true; // standing at the instructor, "reading" before advancing
                }
                Job target = entry.jobErrandTarget;
                clearJobErrand(entry);
                advanceJob(entry, target);
                return false;
            }
            case TRAVEL_YIELDED -> {
                BotManager.npcDwellReset(entry);
                if (forceFallback) {
                    if (noProgressTooLong) {
                        forceAdvance(entry, bot, "no travel progress toward instructor");
                        return false;
                    }
                    return false; // legacy: release the tick so travel retries / grind fills the gap
                }
                warnJobErrandStuck(entry, bot, "travel gave up reaching instructor");
                return true; // keep retrying, stuck here until it gets through — never grind
            }
            default -> {
                BotManager.npcDwellReset(entry);
                if (noProgressTooLong) { // WALKING but can't reach the NPC on its own map
                    forceAdvance(entry, bot, "no progress reaching instructor on its map");
                    return false;
                }
                return true; // TRAVELING / WALKING — tick consumed
            }
        }
    }

    /** Force the advance on the spot (fallback ON), logging why for parity with the OFF-path warn. */
    private static void forceAdvance(BotEntry entry, Character bot, String reason) {
        Job target = entry.jobErrandTarget;
        log.warn("Bot '{}' force-advancing to {} ({}): JOB_CHANGE_FALLBACK_ANYWHERE is on.",
                bot != null ? bot.getName() : "?", target, reason);
        clearJobErrand(entry);
        advanceJob(entry, target);
    }

    /**
     * The bot can't reach its job instructor and the fallback is OFF, so it's staying put and retrying.
     * Logs a throttled error WITH reachability for debugging; the errand is NOT cleared (the bot remains
     * committed and never falls back to grinding). {@code route()} is null when no portal path reaches
     * the instructor's map within the hop budget (boat-gated, other continent, or genuinely no route).
     */
    private static void warnJobErrandStuck(BotEntry entry, Character bot, String reason) {
        long now = System.currentTimeMillis();
        if (now - entry.jobErrandLastWarnMs < ERRAND_WARN_INTERVAL_MS) {
            return;
        }
        entry.jobErrandLastWarnMs = now;
        // Match what the errand travel can actually do: ferry-allowed, gated by the bot's meso (a broke
        // bot that can't afford a fare genuinely can't route there), so the log doesn't falsely claim
        // unreachable for a cross-continent instructor the bot could ferry to.
        java.util.List<Integer> liveRoute = BotAutopilotManager.routeForBot(bot,
                bot.getMapId(), entry.jobErrandMapId, BotAutopilotManager.MAX_TRAVEL_HOPS,
                new BotWorldGraph.RouteOptions(false, bot.getMeso(), true));
        boolean reachable = liveRoute != null;
        // Surface WHY travel actually gave up (deadline / taxi-fare-fail / ferry-board-fail / portal-closed
        // / route-null) plus the failed hop and the bot's meso — "route-reachable=true" alone hides the
        // execution-side cause (e.g. couldn't afford/reach the cab, or a hop the executor can't walk).
        String lastGiveUp = entry.followTravelGiveUpReason == null
                ? "none"
                : entry.followTravelGiveUpReason + " [" + entry.followTravelGiveUpHop
                        + " failedMap=" + entry.followTravelGiveUpTargetMapId
                        + " agoMs=" + (now - entry.followTravelGiveUpAtMs) + "]";
        // The give-up sometimes shows a degenerate self-hop (nextHop==fromMap==current map) that the route
        // function never produces from a clean state — almost always a SECOND travel consumer (autopilot
        // grind-travel) clobbering the errand's hop on the same entry. Log the freshly-computed route from
        // here + the grind dest + the live hop so the next stuck log says which: liveRoute[0]==current ⇒
        // routing; grindDest != jobMap with a live hop toward grindDest ⇒ contention.
        String liveRouteStr = liveRoute == null ? "null"
                : (liveRoute.isEmpty() ? "[]" : "first=" + liveRoute.get(0) + " " + liveRoute);
        String hopState = "target=" + entry.followTravelTargetMapId
                + " nextHop=" + entry.followTravelNextHopMapId
                + " fromMap=" + entry.followTravelFromMapId
                + " following=" + entry.following + " followTargetId=" + entry.followTargetId
                + " transitFollow=" + entry.autopilotTransitFollow;
        log.error("Bot '{}' stuck trying to job-advance to {} ({}): instructor npc {} on map {}, bot on "
                        + "map {}, meso={}, route-reachable={}, lastGiveUp={}, liveRoute={}, grindDest={}, "
                        + "liveHop=[{}]. Staying put and retrying (set JOB_CHANGE_FALLBACK_ANYWHERE=true to "
                        + "force-advance instead).",
                bot.getName(), entry.jobErrandTarget, reason, entry.jobErrandNpcId,
                entry.jobErrandMapId, bot.getMapId(), bot.getMeso(), reachable, lastGiveUp,
                liveRouteStr, entry.autopilotMapId, hopState);
    }

    // Job-topology SSOT for the autonomous (ownerless) job picker in BotBuildManager. Unlike the
    // deterministic 3rd/4th successors above, the 1st and 2nd advancements are real choices, so
    // these mirror the option sets the owner is offered in BotBuildManager.buildJobPrompt.

    /** The five explorer 1st-job classes an ownerless Beginner can advance into at lv10. */
    static List<Job> firstJobChoices() {
        return List.of(Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.PIRATE);
    }

    /** The 2nd-job options for an explorer 1st job (lv30 choice), or empty for anything else. */
    static List<Job> secondJobChoices(Job firstJob) {
        if (firstJob == null) {
            return List.of();
        }
        return switch (firstJob) {
            case WARRIOR -> List.of(Job.FIGHTER, Job.PAGE, Job.SPEARMAN);
            case MAGICIAN -> List.of(Job.FP_WIZARD, Job.IL_WIZARD, Job.CLERIC);
            case BOWMAN -> List.of(Job.HUNTER, Job.CROSSBOWMAN);
            case THIEF -> List.of(Job.ASSASSIN, Job.BANDIT);
            case PIRATE -> List.of(Job.BRAWLER, Job.GUNSLINGER);
            default -> List.of();
        };
    }

    private static void grantStarterKitIfEligible(Character bot, Job oldJob, Job newJob) {
        if (!isFirstJobAdvancement(oldJob, newJob)) {
            return;
        }

        List<ItemGrant> starterKit = starterKitFor(newJob);
        if (starterKit.isEmpty()) {
            return;
        }
        if (!canHoldStarterKit(bot, starterKit)) {
            log.warn("Bot '{}' could not receive {} starter kit due to inventory space", bot.getName(), newJob);
            return;
        }

        for (ItemGrant grant : starterKit) {
            if (!InventoryManipulator.addById(bot.getClient(), grant.itemId(), grant.quantity())) {
                log.warn("Bot '{}' failed to receive starter item {} x{} for job {}",
                        bot.getName(), grant.itemId(), grant.quantity(), newJob);
            }
        }
    }

    private static boolean canHoldStarterKit(Character bot, List<ItemGrant> starterKit) {
        Map<InventoryType, Integer> requiredSlots = new EnumMap<>(InventoryType.class);
        for (ItemGrant grant : starterKit) {
            InventoryType inventoryType = ItemConstants.getInventoryType(grant.itemId());
            if (inventoryType == InventoryType.EQUIP) {
                requiredSlots.merge(InventoryType.EQUIP, 1, Integer::sum);
                continue;
            }
            if (!bot.canHold(grant.itemId(), grant.quantity())) {
                return false;
            }
        }

        for (Map.Entry<InventoryType, Integer> requirement : requiredSlots.entrySet()) {
            if (bot.getInventory(requirement.getKey()).getNumFreeSlot() < requirement.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static ItemGrant grant(int itemId, int quantity) {
        return new ItemGrant(itemId, (short) quantity);
    }
}
