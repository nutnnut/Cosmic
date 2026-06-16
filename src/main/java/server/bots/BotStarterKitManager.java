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

    /** A class instructor: the town NPC that handles 1st+2nd job advancement, and the map it sits on. */
    record JobChangeNpc(int npcId, int mapId, String townName) {}

    // SSOT for "which instructor advances each explorer branch". Keyed by branch = id/100 (the
    // shared first digit of every job in a class line). Each branch's TOWN instructor handles BOTH
    // 1st and 2nd job (the 1072xxx 2nd-job test-map instructors are unreachable, so abstracted away).
    // Verified vs Map.wz life data + handbook/NPC.txt.
    private static final Map<Integer, JobChangeNpc> JOB_CHANGE_NPC = Map.of(
            1, new JobChangeNpc(1022000, 102000003, "Perion"),    // Warrior  - Dances with Balrog
            2, new JobChangeNpc(1032001, 101000003, "Ellinia"),   // Magician - Grendel the Really Old
            3, new JobChangeNpc(1012100, 100000201, "Henesys"),   // Bowman   - Athena Pierce
            4, new JobChangeNpc(1052001, 103000003, "Kerning"),   // Thief    - Dark Lord
            5, new JobChangeNpc(1090000, 120000101, "Nautilus")   // Pirate   - Kyrin
    );

    /** The instructor NPC for a 1st/2nd-job advancement target, or null when the target is not a
     *  routed advancement: 3rd-job ids end in 1, 4th in 2 (their El Nath/Leafre NPCs are
     *  unverified/unreachable, so those advance instantly), and any non-explorer branch is absent. */
    static JobChangeNpc jobChangeNpcFor(Job target) {
        if (!routesThroughNpc(target)) {
            return null;
        }
        return JOB_CHANGE_NPC.get(target.getId() / 100);
    }

    /** True when advancing to {@code target} should WALK to an instructor first: explorer 1st/2nd
     *  job only (ids ending in 0). 3rd (…1) and 4th (…2) advance instantly. */
    static boolean routesThroughNpc(Job target) {
        if (target == null) {
            return false;
        }
        int id = target.getId();
        return id > 0 && id % 10 == 0 && JOB_CHANGE_NPC.containsKey(id / 100);
    }

    /** Within this many px of the instructor counts as "talked to it" — matches the quest/cab radius. */
    static final int NPC_TRIGGER_RADIUS_PX = 500;
    /** Give up an instructor walk that can't arrive in time so the errand state can't wedge. */
    static final long ERRAND_TIMEOUT_MS = 90_000L;

    /** Begin an instructor-walk errand for an autopilot bot instead of advancing instantly. */
    static void beginJobErrand(BotEntry entry, Job target) {
        JobChangeNpc instructor = jobChangeNpcFor(target);
        if (instructor == null) {
            return;
        }
        entry.jobErrandTarget = target;
        entry.jobErrandNpcId = instructor.npcId();
        entry.jobErrandMapId = instructor.mapId();
        entry.jobErrandStartedAtMs = System.currentTimeMillis();
        reply.accept(entry, "heading to " + instructor.townName() + " to change job");
    }

    static void clearJobErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.jobErrandTarget = null;
        entry.jobErrandNpcId = 0;
        entry.jobErrandMapId = -1;
        entry.jobErrandStartedAtMs = 0L;
    }

    /**
     * Drives an active job-change errand: travel to the instructor's town, walk within radius, then
     * advance on arrival. Returns true while the tick is consumed (traveling/walking), false once
     * the errand is done or dropped. Called from {@link BotAutopilotManager#tick} BEFORE combat so
     * the bot does not grind (and over-level) en route. Reuses the shared
     * {@link BotTravelManager#tickApproachNpc} stepper (no reimplemented travel).
     */
    static boolean tickJobErrand(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.jobErrandMapId == -1 || entry.jobErrandTarget == null) {
            return false;
        }
        if (System.currentTimeMillis() - entry.jobErrandStartedAtMs > ERRAND_TIMEOUT_MS) {
            // Couldn't get there — advance on the spot rather than wedge or stay under-leveled.
            Job target = entry.jobErrandTarget;
            clearJobErrand(entry);
            advanceJob(entry, target);
            return false;
        }
        BotTravelManager.ApproachStatus status = BotTravelManager.tickApproachNpc(
                entry, bot, entry.jobErrandMapId, entry.jobErrandNpcId,
                BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, NPC_TRIGGER_RADIUS_PX);
        switch (status) {
            case NPC_GONE -> {
                // Instructor not on the map (shouldn't happen for town NPCs) — advance anyway.
                Job target = entry.jobErrandTarget;
                clearJobErrand(entry);
                advanceJob(entry, target);
                return false;
            }
            case ARRIVED -> {
                Job target = entry.jobErrandTarget;
                clearJobErrand(entry);
                advanceJob(entry, target);
                return false;
            }
            default -> {
                return true; // TRAVELING / WALKING — tick consumed
            }
        }
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
