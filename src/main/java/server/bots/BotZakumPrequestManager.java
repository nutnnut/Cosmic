/*
    This file is part of the OdinMS Maple Story Server.
    Bot Zakum-prequest driver (AI companion feature).
*/
package server.bots;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.NpcId;
import net.server.world.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventManager;
import server.quest.Quest;
import server.quest.actions.ExpAction;

import java.util.List;

/**
 * Long-horizon autopilot "errand" that walks a companion bot through the Zakum prequest chain so it
 * earns its own Eyes of Fire (the altar entry ticket {@code Zakum05.js} checks). Registered as a
 * {@link BotAutopilotManager.DetourErrand} between the job-change walk and the Temple questline.
 *
 * <p><b>What the server actually requires</b> (USE_ENABLE_SOLO_EXPEDITIONS is off, so nothing is
 * skipped; verified against scripts/npc/2020008-2020013.js, 2030008.js, 2032002.js, 2032003.js and
 * scripts/portal/Zakum05.js):
 * <ol>
 *   <li><b>Quest 100200</b> ("council approval", custom/script-only): one talk with the bot's class
 *       chief in the El Nath Chief's Residence (map 211000001) at level 50+.</li>
 *   <li><b>Quest 100201</b> ("the trials"), all via Adobis (2030008) at the Door to Zakum
 *       (211042300):
 *       <ul>
 *         <li>Stage 1 — Zakum PQ ({@code minPlayers=1}, solo party works): inside the instanced mine
 *             maze, break the 7 key chests (reactors 2112011/2112004, one key 4001016 each, always),
 *             drop the 7-key stack in front of the Giant Chest (reactor 2112014 on 280011005 —
 *             {@code MapleMap.searchItemReactors} consumes a single drop of EXACTLY quantity 7 inside
 *             its trigger box), loot the Fire Ore 4001018, turn in to Aura (2032002, 280010000) →
 *             Breath of Fire 4031061.</li>
 *         <li>Stage 2 — Breath of Lava: Adobis warps the bot to 280020000 (no instance, no party);
 *             one plain portal to 280020001 where Lira (2032003) grants Breath of Lava 4031062.</li>
 *         <li>Stage 3 — hand Adobis 4031061 + 4031062 + 30x Zombie's Lost Gold Tooth 4000082 (2%
 *             drop from Miner Zombies, maps 211041500-211041800) → 100201 completes, 5x Eye of Fire
 *             4001017 granted.</li>
 *       </ul></li>
 * </ol>
 *
 * <p><b>How it drives.</b> Stateless like the Temple driver: each tick {@link #resolveStep} recomputes
 * the lowest unmet requirement from quest state + inventory + current map, then executes ONE step —
 * NPC approaches via {@link BotTravelManager#tickApproachNpc}, the tooth quota via the normal grind
 * flow (map pin + crowd-defer, mirroring the Temple lane pin), and the script-only specials by
 * reproducing their NPC scripts' exact server calls. While inside the PQ instance the errand always
 * consumes the tick (the maze is an unroutable island for the normal grind flow) and
 * {@link BotManager}'s LOD abstraction stands down (see {@link #inLiveMaps}) so instance-blind
 * timed-warp travel can never fire there.
 *
 * <p><b>Opt-in / stagger.</b> Arms once the bot reaches its personal
 * {@link BotPersonality#zakumAmbitionLevel} (stable per-bot roll in [70,120]); quest ids 100200/100201
 * have no WZ data — {@link Quest#getInstance} synthesizes empty quests and
 * forceStart/forceComplete work exactly as the NPC scripts' {@code cm.startQuest}/{@code completeQuest}
 * do (same calls, same NpcId.MAPLE_ADMINISTRATOR attribution).
 */
final class BotZakumPrequestManager {

    private static final Logger log = LoggerFactory.getLogger(BotZakumPrequestManager.class);

    private BotZakumPrequestManager() {}

    // ---- personal-ambition band (opt-in / stagger) -------------------------------------------

    /** Lowest level at which any bot will start the Zakum prequest chain. */
    static final int AMBITION_MIN_LEVEL = 70;
    /** Width of the ambition band above {@link #AMBITION_MIN_LEVEL} (so the band is [70,120]). */
    static final int AMBITION_BAND = 50;

    // ---- ids (verified against scripts, wz/Map.wz placements and the live reactordrops/drop_data) --

    static final int Q_APPROVAL = 100200;      // custom quest: council approval (started, never completed)
    static final int Q_TRIALS = 100201;        // custom quest: the trials (completed by stage 3)

    private static final int CHIEF_MAP = 211000001;        // El Nath - Chief's Residence (all 5 chiefs)
    static final int DOOR_MAP = 211042300;                 // El Nath - The Door to Zakum
    private static final int ADOBIS = 2030008;             // Zakum recruiter (Door to Zakum)
    private static final int LIRA = 2032003;               // Breath of Lava grant NPC (280020001)

    // In-instance geography (maps/reactors/Aura) lives in BotZakumPqRun — the SSOT run machine
    // shared with player-led runs (BotPqHooks).
    private static final int LAVA_MAP_1 = 280020000, LAVA_MAP_2 = 280020001;

    static final int ITEM_KEY = 4001016;          // Key of the Dead Mine (7 needed, PQ-exclusive)
    static final int ITEM_FIRE_ORE = 4001018;     // Fire Ore (PQ-exclusive)
    static final int ITEM_PQ_DOCUMENT = 4001015;  // Document (optional side path; bots never collect)
    static final int ITEM_BREATH_FIRE = 4031061;  // Breath of Fire (stage-1 reward)
    static final int ITEM_BREATH_LAVA = 4031062;  // Breath of Lava (stage-2 reward)
    static final int ITEM_GOLD_TOOTH = 4000082;   // Zombie's Lost Gold Tooth (30 needed)
    static final int ITEM_EYE_OF_FIRE = 4001017;  // altar entry ticket (5 granted)
    static final int TEETH_NEEDED = 30;
    static final int KEYS_NEEDED = 7;

    private static final int LAVA_STAGE_EXP = 10_000;      // exp Lira's script grants on the hand-over

    /** Miner Zombie maps (Dead Mine "Cave of Trial" chain) — the gold-tooth grind pool. */
    static final int[] TEETH_MAPS = {211041500, 211041600, 211041700, 211041800};

    // ---- tuning ------------------------------------------------------------------------------

    private static final int TRIGGER_RADIUS_PX = BotQuestManager.NPC_TRIGGER_RADIUS_PX;
    /** No-progress deadline for an NPC approach / out-of-instance travel leg. */
    private static final long APPROACH_TIMEOUT_MS = 90_000L;
    /** No-progress deadline inside the PQ / lava course (jump legs are slow; the 30-min event timer
     *  is the hard bound). */
    private static final long INSTANCE_TIMEOUT_MS = 4 * 60_000L;
    /** Lava-course attempts per arm before a long step-aside (the course is a jump quest; a bot that
     *  can't climb it must not enter/give-up loop forever). */
    private static final int LAVA_MAX_ATTEMPTS = 3;
    private static final int LAVA_LONG_DEFER_MIN_MS = 2 * 3600_000, LAVA_LONG_DEFER_MAX_MS = 4 * 3600_000;
    /** Others already committed to a tooth map before this bot re-picks / steps aside. */
    private static final int CROWD_DEFER_THRESHOLD = 2;
    private static final int CROWD_DEFER_MIN_MS = 60_000, CROWD_DEFER_MAX_MS = 180_000;
    private static final long PIN_HOLD_MS = 30_000L;
    private static final int BACKOFF_MIN_MS = 60_000, BACKOFF_MAX_MS = 120_000;
    /** Busy-lobby defer: one PQ lobby per channel and a run takes tens of minutes, so a loser steps
     *  aside far longer than a plain approach back-off — otherwise every armed bot in the world camps
     *  the Door retrying on the short cooldown. */
    private static final int LOBBY_BUSY_DEFER_MIN_MS = 4 * 60_000, LOBBY_BUSY_DEFER_MAX_MS = 10 * 60_000;
    /** How long a crew-run leader holds at the recruiter for stragglers before stepping aside. */
    private static final long ASSEMBLE_TIMEOUT_MS = 3 * 60_000L;
    private static final int REARM_MIN_MS = 30_000, REARM_MAX_MS = 90_000;

    /** Bot chat seam (tests swap it; production routes through the owner's channel). */
    static java.util.function.BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    // ---- pure step resolver (unit-tested) ----------------------------------------------------

    enum Step { APPROVAL, PQ, LAVA, TEETH, TURNIN, DONE }

    /** The lowest unmet Zakum-prequest requirement. Pure over its inputs so it's testable WZ-free.
     *  (Where the bot physically is — inside the PQ, inside the lava course — refines HOW the step
     *  executes, not WHICH step is due.) */
    static Step resolveStep(boolean approvalStarted, boolean trialsDone,
                            boolean haveBreathFire, boolean haveBreathLava, int teethHeld) {
        if (trialsDone) {
            return Step.DONE;
        }
        if (!approvalStarted) {
            return Step.APPROVAL;
        }
        if (!haveBreathFire) {
            return Step.PQ;
        }
        if (!haveBreathLava) {
            return Step.LAVA;
        }
        if (teethHeld < TEETH_NEEDED) {
            return Step.TEETH;
        }
        return Step.TURNIN;
    }

    /** The class chief that grants 100200, by job family ({@code jobBase % 10} — Cygnus/Aran branches
     *  fold onto the same five chiefs, exactly as the scripts' job checks do). -1 = no chief (beginner;
     *  cannot happen past the ambition gate). */
    static int chiefNpcFor(int jobId) {
        return switch ((jobId / 100) % 10) {
            case 1 -> 2020008; // Tylus (warrior; also Dawn Warrior, Aran)
            case 2 -> 2020009; // Robeira (magician; also Blaze Wizard)
            case 3 -> 2020010; // Rene (bowman; also Wind Archer)
            case 4 -> 2020011; // Arec (thief; also Night Walker)
            case 5 -> 2020013; // Pedro (pirate; also Thunder Breaker)
            default -> -1;
        };
    }

    // ---- errand framework hooks --------------------------------------------------------------

    /** Re-arm hook (called every tick while disarmed): opt in once the bot reaches its personal
     *  ambition level and the trials are still unfinished. */
    static void maybeStart(BotEntry entry, Character bot) {
        if (entry.zakumErrandMapId != -1 || bot == null) {
            return; // already armed
        }
        if (!BotManager.cfg.ZAKUM_PREQUEST || !BotAutopilotManager.isActive(entry)) {
            return; // disabled, or a supervised bot (stays at the owner's side)
        }
        long now = System.currentTimeMillis();
        if (now < entry.nextZakumScanAtMs) {
            return; // re-arm cooldown
        }
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        if (bot.getLevel() < p.zakumAmbitionLevel()) {
            return; // not ambitious/high enough yet
        }
        if (BotQuestManager.gate.isCompleted(bot, Q_TRIALS)) {
            return; // trials already done — nothing left to earn
        }
        if (!crewReadyForZakum(bot)) {
            return; // crews opt in TOGETHER (see below) — a lone member never peels off for hours
        }
        entry.zakumErrandMapId = bot.getMapId(); // armed sentinel (tick overwrites with the real target)
        entry.zakumErrandNpcId = 0;
        entry.zakumErrandProgress.begin(now);
        reply.accept(entry, "gonna earn my zakum entry pass");
    }

    static boolean active(BotEntry entry) {
        return entry.zakumErrandMapId != -1;
    }

    // ---- crew coordination -------------------------------------------------------------------
    // A crew (persistent all-bot party) works the prequest chain TOGETHER — a group of players who
    // always play together would. Arming is gated on the whole crew being ready, the PQ runs as one
    // party (the script takes 1-6 members), and the teeth grind pins one shared map. A party with a
    // human in it never arms: the errand must neither drag a human into the mines nor walk out on one.

    /** Solo bots are always "crew-ready". A partied bot is ready only when the party is all-bot and
     *  every online member still needing the trials has reached its own ambition level — then all of
     *  them arm within a tick of each other and progress the chain side by side. */
    private static boolean crewReadyForZakum(Character bot) {
        if (bot.getParty() == null) {
            // A crew bot with no party yet is in the login window BEFORE its crew re-parties —
            // arming now would sidestep the crew gate (and strand it: the PQ step needs the party
            // leader). Only true soloists pass here.
            BotEntry e = BotManager.getInstance().getEntryByBotCharId(bot.getId());
            return e == null || e.crewGroupId == null;
        }
        if (!BotManager.onlinePartyMembersAllBots(bot)) {
            return false;
        }
        for (BotEntry m : BotManager.getInstance().partyBotEntries(bot)) {
            if (m.bot == null || BotQuestManager.gate.isCompleted(m.bot, Q_TRIALS)) {
                continue; // already through — grinds along, doesn't gate the rest
            }
            BotPersonality mp = m.personality != null ? m.personality : BotPersonality.defaults();
            if (m.bot.getLevel() < mp.zakumAmbitionLevel()) {
                return false;
            }
        }
        return true;
    }

    /** The game-party leader when this bot is in an all-bot party, else null (solo / human around).
     *  The PQ script only admits a team whose PARTY LEADER stands on the recruit map, so the crew
     *  run is driven by that character; everyone else gathers and waits. */
    private static Character crewPqLeader(Character bot) {
        Party party = bot.getParty();
        if (party == null || !BotManager.onlinePartyMembersAllBots(bot)) {
            return null;
        }
        for (BotEntry m : BotManager.getInstance().partyBotEntries(bot)) {
            if (m.bot != null && m.bot.getId() == party.getLeaderId()) {
                return m.bot;
            }
        }
        return null;
    }

    /** True while the crew's party leader still needs the PQ itself (armed at the PQ step), i.e. a
     *  waiting member can expect a crew run to actually start. */
    private static boolean leaderAtPqStep(Character leader) {
        BotEntry le = BotManager.getInstance().getEntryByBotCharId(leader.getId());
        return le != null && le.zakumErrandMapId != -1
                && !leader.haveItem(ITEM_BREATH_FIRE)
                && !BotQuestManager.gate.isCompleted(leader, Q_TRIALS);
    }

    /** True once every online crew member that needs the PQ (armed, no Breath of Fire yet) stands on
     *  the recruit map — the leader holds the start until the whole team is present, so nobody is
     *  left outside the instance. */
    private static boolean crewAssembledAtDoor(Character leader) {
        for (BotEntry m : BotManager.getInstance().partyBotEntries(leader)) {
            if (m.bot == null || m.zakumErrandMapId == -1) {
                continue; // not on the errand (done, or below its ambition) — grinds on, not expected
            }
            if (BotQuestManager.gate.isCompleted(m.bot, Q_TRIALS) || m.bot.haveItem(ITEM_BREATH_FIRE)) {
                continue; // past the PQ stage — not part of this run
            }
            if (m.bot.getMapId() != DOOR_MAP) {
                return false;
            }
        }
        return true;
    }

    /** True while the errand is actively driving the bot (walking to / talking with an NPC, or inside
     *  the PQ/lava course) — the states whose ticks it consumes, so the status line must say so
     *  instead of falling through to a stale grind objective. Teeth grinding and back-off windows
     *  release the tick to the normal flow, whose statuses are then accurate. */
    static boolean drivingStatus(BotEntry entry, Character bot) {
        return entry.zakumErrandMapId != -1 && (entry.zakumErrandNpcId != 0 || inLiveMaps(bot));
    }

    /** True to let a cramped-bag resupply run before this errand (keys/ore/eyes all need bag room,
     *  and the El Nath legs may buy taxi fares). Mirrors the job/Temple errands. */
    static boolean yieldForResupply(BotEntry entry, Character bot) {
        // Never yield inside the special maps: a resupply trip cannot route out of the PQ island /
        // lava course, and leaving mid-instance forfeits the run anyway.
        return !inLiveMaps(bot) && BotAutopilotManager.bagFull.bagFull(entry, bot);
    }

    /** True while the bot stands in the PQ instance or the lava course — the phases that need real
     *  ticks: LOD's instance-blind timed-warp travel and motion-plan movement must stand down there
     *  (checked by {@code BotManager.lod1MotionPlanCovered}). */
    static boolean inLiveMaps(Character bot) {
        if (bot == null) {
            return false;
        }
        int mapId = bot.getMapId();
        return BotZakumPqRun.isPqMap(mapId) || mapId == LAVA_MAP_1 || mapId == LAVA_MAP_2;
    }

    /** Sell/discard guard: quest-critical Zakum items the inventory hygiene must never unload.
     *  Teeth and the (instance-exclusive, but belt-and-braces) trial materials are critical until the
     *  trials complete; the Eyes of Fire are the altar entry ticket and are kept for good. */
    static boolean isQuestCriticalItem(Character bot, int itemId) {
        if (itemId == ITEM_EYE_OF_FIRE) {
            return true;
        }
        if (itemId != ITEM_GOLD_TOOTH && itemId != ITEM_KEY && itemId != ITEM_FIRE_ORE) {
            return false;
        }
        return bot != null && !BotQuestManager.gate.isCompleted(bot, Q_TRIALS);
    }

    // ---- main tick ---------------------------------------------------------------------------

    /**
     * Drives one tick of the armed errand. Returns true when it consumed the tick; false to let the
     * normal grind/combat flow run (the TEETH pin, and every defer). Inside the PQ instance it always
     * consumes the tick — the maze is a portal island the normal travel flow could only "escape" via
     * a return scroll, forfeiting the run.
     */
    static boolean tickErrand(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.zakumErrandMapId == -1) {
            return false;
        }
        if (!BotAutopilotManager.isActive(entry)) {
            // Supervised again (owner online): abandon the errand — but never strand the bot inside
            // the instance/course; walk it out the same way Aura/Amon's exit dialogs do.
            if (inLiveMaps(bot)) {
                BotZakumPqRun.warpToDoor(bot);
            }
            clearZakumErrand(entry);
            return false;
        }
        // Stranded on PQ maps with no live event (relog mid-run, event timeout race): the maze is
        // dead — leave first, then re-resolve. Same warp Aura's "I want to get out" performs.
        if (BotZakumPqRun.isPqMap(bot.getMapId()) && bot.getEventInstance() == null) {
            BotZakumPqRun.warpToDoor(bot);
            backOff(entry);
            return true;
        }
        // Back-off / step-aside window (busy lobby, failed approach, crowd defer): stay armed but
        // release every tick so the normal grind flow — including a pending resupply errand — runs.
        // Never inside the instance/course, where the errand must own the tick. Without this, an
        // approach step re-arms right after backOff() and the bot camps its NPC hammering a busy
        // lobby forever instead of grinding elsewhere and coming back.
        if (!inLiveMaps(bot) && System.currentTimeMillis() < entry.nextZakumScanAtMs) {
            return defer(entry);
        }
        Step step = resolveStep(
                BotQuestManager.gate.isStarted(bot, Q_APPROVAL) || BotQuestManager.gate.isCompleted(bot, Q_APPROVAL),
                BotQuestManager.gate.isCompleted(bot, Q_TRIALS),
                bot.haveItem(ITEM_BREATH_FIRE),
                bot.haveItem(ITEM_BREATH_LAVA),
                bot.getItemQuantity(ITEM_GOLD_TOOTH, false));
        // Off-step special-map recovery: holding the reward but still inside the lava course (Lira's
        // warp raced a relog), or inside the PQ maze past the PQ step (a crew warp-in caught a member
        // already through stage 1) — leave before working the next step.
        if (step != Step.LAVA && isLavaMap(bot.getMapId())) {
            BotZakumPqRun.warpToDoor(bot);
            return true;
        }
        if (step != Step.PQ && BotZakumPqRun.isPqMap(bot.getMapId())) {
            BotZakumPqRun.warpToDoor(bot);
            return true;
        }
        switch (step) {
            case DONE -> {
                finishAndDisarm(entry, bot);
                return false;
            }
            case APPROVAL -> { return tickApproval(entry, bot, runAiTick); }
            case PQ -> { return tickPq(entry, bot, runAiTick); }
            case LAVA -> { return tickLava(entry, bot, runAiTick); }
            case TEETH -> { return grindTeeth(entry, bot); }
            case TURNIN -> { return tickTurnIn(entry, bot, runAiTick); }
        }
        return false;
    }

    // ---- step: 100200 approval (one talk with the class chief) -------------------------------

    private static boolean tickApproval(BotEntry entry, Character bot, boolean runAiTick) {
        int chief = chiefNpcFor(bot.getJob().getId());
        if (chief == -1 || bot.getLevel() < 50) {
            return defer(entry); // no chief for this job / too low (ambition gate makes this moot)
        }
        return approach(entry, bot, CHIEF_MAP, chief, runAiTick, () -> {
            // Reproduce the chiefs' shared Zakum branch: startQuest(100200) at level 50+ (the
            // USE_ENABLE_SOLO_EXPEDITIONS auto-complete of 100201 is off on this server).
            Quest.getInstance(Q_APPROVAL).forceStart(bot, NpcId.MAPLE_ADMINISTRATOR);
            reply.accept(entry, "got the council's blessing to fight zakum");
        });
    }

    // ---- step: stage 1, the Zakum PQ (Breath of Fire) ----------------------------------------

    private static boolean tickPq(BotEntry entry, Character bot, boolean runAiTick) {
        if (!BotZakumPqRun.isPqMap(bot.getMapId())) {
            entry.zakumPqChestDropAtMs = 0L;
            Character crewLeader = crewPqLeader(bot);
            if (crewLeader != null && crewLeader.getId() != bot.getId()) {
                // Crew member: gather at the recruiter; the party leader starts the run and the
                // instance warp-in takes everyone standing on the recruit map.
                if (!leaderAtPqStep(crewLeader)) {
                    // Leader can't run it (already done, or not armed — e.g. this member armed solo
                    // in the login window before the crew party formed). A back-off would retry into
                    // the same wall forever; disarm instead and let maybeStart's crew gate re-arm
                    // the whole crew together once everyone (leader included) is ready.
                    clearZakumErrand(entry);
                    return false;
                }
                // Only camp the recruiter while the leader is actually engaged (present, or walking
                // its approach). An armed leader off on a detour (free market, gachapon, resupply)
                // can be gone for an hour — members grind normally and regather when it shows up.
                BotEntry leaderEntry = BotManager.getInstance().getEntryByBotCharId(crewLeader.getId());
                boolean leaderEngaged = crewLeader.getMapId() == DOOR_MAP
                        || (leaderEntry != null && leaderEntry.zakumErrandNpcId == ADOBIS);
                if (!leaderEngaged) {
                    return defer(entry); // stay armed; release the tick until the leader closes in
                }
                if (bot.getMapId() == DOOR_MAP) {
                    entry.zakumErrandNpcId = ADOBIS; // status stays "working on my trials"
                    entry.zakumErrandProgress.touch(System.currentTimeMillis());
                    BotTravelManager.clearMoveTargetPin(entry);
                    return true; // stand by for the leader's warp-in
                }
                return approach(entry, bot, DOOR_MAP, ADOBIS, runAiTick, () -> { });
            }
            if (crewLeader != null && bot.getMapId() == DOOR_MAP && !crewAssembledAtDoor(bot)) {
                // Crew leader holding for stragglers: BOUNDED wait (the approach stepper's stall
                // clock re-begins on every retry cycle, so it can never time this out itself). A
                // member that can't make it within the window back-offs the leader out of the hold.
                long now = System.currentTimeMillis();
                if (entry.zakumAssembleSinceMs == 0L) {
                    entry.zakumAssembleSinceMs = now;
                }
                if (now - entry.zakumAssembleSinceMs > ASSEMBLE_TIMEOUT_MS) {
                    entry.zakumAssembleSinceMs = 0L;
                    backOff(entry);
                    return false;
                }
                entry.zakumErrandNpcId = ADOBIS;
                entry.zakumErrandProgress.touch(now);
                BotTravelManager.clearMoveTargetPin(entry);
                return true; // stand at the recruiter until the crew closes up
            }
            entry.zakumAssembleSinceMs = 0L;
            return approach(entry, bot, DOOR_MAP, ADOBIS, runAiTick, () -> startPqInstance(entry, bot));
        }
        return tickPqInside(entry, bot, runAiTick);
    }

    /** Reproduce Adobis's stage-1 path: a party + {@code em.startInstance(party, map, 1)}. A soloist
     *  runs on a party-of-one; an all-bot crew runs as ONE team (the script admits 1-6 members on the
     *  recruit map) once everyone still needing the PQ has gathered at the Door. A party containing a
     *  human is never driven in. */
    private static void startPqInstance(BotEntry entry, Character bot) {
        Party party = bot.getParty();
        if (party != null && party.getMembers().size() > 1
                && !BotManager.onlinePartyMembersAllBots(bot)) {
            backOff(entry); // partied with a human — don't drag them in; retry once it's over
            return;
        }
        if (party != null && party.getMembers().size() > 1 && !crewAssembledAtDoor(bot)) {
            // Crew run: hold the start until the whole team stands at the recruiter. The approach
            // stepper's stall clock still runs, so an unreachable straggler back-offs this leader
            // out of the wait instead of pinning it here forever.
            return;
        }
        try {
            if (party == null) {
                if (!Party.createParty(bot, true)) {
                    backOff(entry);
                    return;
                }
                party = bot.getParty();
            }
            EventManager em = bot.getClient().getChannelServer().getEventSM().getEventManager("ZakumPQ");
            if (em == null || party == null || em.getEligibleParty(party).isEmpty()) {
                backOff(entry);
                return;
            }
            if (!em.startInstance(party, bot.getMap(), 1)) {
                backOff(entry); // single lobby per channel is taken — come back later
                entry.nextZakumScanAtMs = System.currentTimeMillis()
                        + BotManager.randMs(LOBBY_BUSY_DEFER_MIN_MS, LOBBY_BUSY_DEFER_MAX_MS);
                reply.accept(entry, "mines are busy, ill come back for the trials later");
                return;
            }
            entry.zakumPqChestDropAtMs = 0L;
            entry.zakumErrandProgress.begin(System.currentTimeMillis());
            reply.accept(entry, "heading into the dead mine");
        } catch (RuntimeException e) {
            log.warn("Bot '{}' failed to start ZakumPQ", bot.getName(), e);
            backOff(entry);
        }
    }

    /**
     * One tick inside the instance, delegating to the SSOT run machine ({@link BotZakumPqRun} — the
     * same brain player-led runs use). The bot party leader works the LEADER duties: sweep its share
     * of key rooms, assemble the couriered keys, open the Giant Chest, turn the ore in to Aura. Crew
     * members run the WORKER role. ALWAYS consumes the tick (see {@link #tickErrand}); a stall or an
     * unrecoverable run exits via the door warp + back-off so the event timer never strands the bot.
     */
    private static boolean tickPqInside(BotEntry entry, Character bot, boolean runAiTick) {
        long now = System.currentTimeMillis();
        var eim = bot.getEventInstance(); // non-null: tickErrand's stranded-maze check ran already
        Character crewLeader = crewPqLeader(bot);
        if (crewLeader != null && crewLeader.getId() != bot.getId()) {
            entry.zakumErrandProgress.touch(now); // the leader's stall clock owns the run's pacing
            if (eim != null && !eim.isEventCleared() && !BotZakumPqRun.isPqMap(crewLeader.getMapId())) {
                BotZakumPqRun.warpToDoor(bot); // leader already left (stall exit) — follow suit
                backOff(entry);
                return true;
            }
            return BotZakumPqRun.tickWorker(entry, bot, crewLeader, false, runAiTick);
        }
        if (entry.zakumErrandProgress.stalled(now, INSTANCE_TIMEOUT_MS)) {
            BotZakumPqRun.warpToDoor(bot);
            backOff(entry);
            return true;
        }
        if (bot.haveItem(ITEM_FIRE_ORE)) {
            entry.zakumPqChestDropAtMs = 0L;
            if (bot.getMapId() != BotZakumPqRun.PQ_ENTRY_MAP) {
                return BotZakumPqRun.travelInsidePq(entry, bot, BotZakumPqRun.PQ_ENTRY_MAP, runAiTick);
            }
            approach(entry, bot, BotZakumPqRun.PQ_ENTRY_MAP, BotZakumPqRun.AURA, runAiTick,
                    () -> BotZakumPqRun.leaderTurnInToAura(entry, bot, () -> backOff(entry)));
            return true; // even ARRIVED/failed approaches consume the tick inside the instance
        }
        // A pending 7-key drop keeps the bot in the chest phase even though its key COUNT fell to 0
        // when the stack hit the floor — otherwise it would walk off to "re-collect" its own drop.
        if (entry.zakumPqChestDropAtMs != 0L || bot.getItemQuantity(ITEM_KEY, false) >= KEYS_NEEDED) {
            if (bot.getMapId() != BotZakumPqRun.PQ_CHEST_MAP) {
                return BotZakumPqRun.travelInsidePq(entry, bot, BotZakumPqRun.PQ_CHEST_MAP, runAiTick);
            }
            if (!BotZakumPqRun.tickGiantChest(entry, bot, runAiTick)) {
                BotZakumPqRun.warpToDoor(bot); // chest open but the ore is gone — unrecoverable run
                backOff(entry);
            }
            return true;
        }
        if (eim != null && BotZakumPqRun.sweepRooms(entry, bot, bot, eim, runAiTick)) {
            return true; // collecting my share of the keys
        }
        // My share is swept and I hold <7 keys: couriers may still be inbound — or a key was lost.
        if (eim == null || BotZakumPqRun.totalKeysInPlay(eim, bot, bot) < KEYS_NEEDED) {
            BotZakumPqRun.warpToDoor(bot); // a key expired/vanished — this run cannot finish
            backOff(entry);
            return true;
        }
        if (bot.getMapId() != BotZakumPqRun.PQ_CHEST_MAP) {
            return BotZakumPqRun.travelInsidePq(entry, bot, BotZakumPqRun.PQ_CHEST_MAP, runAiTick);
        }
        return BotZakumPqRun.hoverNear(entry, bot, bot.getPosition(), runAiTick); // await couriers
    }

    // ---- step: stage 2, Breath of Lava -------------------------------------------------------

    private static boolean tickLava(BotEntry entry, Character bot, boolean runAiTick) {
        if (!isLavaMap(bot.getMapId())) {
            if (entry.zakumLavaAttempts >= LAVA_MAX_ATTEMPTS) {
                // Course looks unclimbable for this bot right now — long step-aside, then re-try.
                entry.zakumLavaAttempts = 0;
                entry.nextZakumScanAtMs = System.currentTimeMillis()
                        + BotManager.randMs(LAVA_LONG_DEFER_MIN_MS, LAVA_LONG_DEFER_MAX_MS);
                return defer(entry);
            }
            return approach(entry, bot, DOOR_MAP, ADOBIS, runAiTick, () -> {
                // Reproduce Adobis's stage-2 branch: gated on 4031061 held + 4031062 not, then a
                // plain warp into the (shared, non-instanced) lava course.
                if (bot.haveItem(ITEM_BREATH_FIRE) && !bot.haveItem(ITEM_BREATH_LAVA)) {
                    entry.zakumErrandProgress.begin(System.currentTimeMillis());
                    BotZakumPqRun.warpTo(bot, LAVA_MAP_1);
                    reply.accept(entry, "time to brave the lava");
                }
            });
        }
        long now = System.currentTimeMillis();
        if (entry.zakumErrandProgress.stalled(now, INSTANCE_TIMEOUT_MS)) {
            // Give up this attempt the way Amon's dialog does — warp back to the door.
            entry.zakumLavaAttempts++;
            BotZakumPqRun.warpToDoor(bot);
            backOff(entry);
            return true;
        }
        // The course is two maps joined by one plain portal; Lira waits on the second. The shared
        // NPC approach stepper handles both the portal hop and the jump-course walking.
        stepApproach(entry, bot, LAVA_MAP_2, LIRA, runAiTick, () -> {
            if (!bot.canHold(ITEM_BREATH_LAVA, 1)) {
                return;
            }
            // Reproduce Lira's script: grant the Breath of Lava + exp, then out.
            InventoryManipulator.addById(bot.getClient(), ITEM_BREATH_LAVA, (short) 1);
            ExpAction.runAction(bot, LAVA_STAGE_EXP);
            entry.zakumLavaAttempts = 0;
            BotZakumPqRun.warpToDoor(bot);
            reply.accept(entry, "got the breath of lava");
        });
        return true; // consume every tick on the course — there is no grind flow to release to here
    }

    // ---- step: the gold-tooth grind ----------------------------------------------------------

    /**
     * Pin the grind to a Miner Zombie map so teeth accrue through the normal grind/combat/loot flow.
     * Mirrors the Temple lane pin: occupancy-aware (steps aside when the spot is crowded), re-checked
     * on a seconds cadence, never running a full advisor pass on the bot tick thread.
     */
    private static boolean grindTeeth(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();
        entry.zakumErrandNpcId = 0;
        entry.zakumErrandProgress.touch(now); // no NPC to time out on while grinding
        BotTravelManager.clearMoveTargetPin(entry);
        if (now < entry.nextZakumScanAtMs) {
            return false; // in a step-aside window: normal grind runs elsewhere
        }
        int pinned = isTeethMap(entry.autopilotMapId) ? entry.autopilotMapId : -1;
        if (now >= entry.zakumCrowdCheckDueMs || pinned == -1) {
            entry.zakumCrowdCheckDueMs = now + BotManager.randMs(2_500, 4_500);
            double penalty = BotManager.cfg.CROWD_PENALTY_FACTOR;
            var extra = BotOccupancy.extraCompetitors(bot, penalty);
            int best = -1;
            double bestSurcharge = Double.MAX_VALUE;
            for (int mapId : TEETH_MAPS) {
                double surcharge = extra.applyAsDouble(mapId);
                if (surcharge < bestSurcharge) {
                    bestSurcharge = surcharge;
                    best = mapId;
                }
            }
            int competitors = penalty > 0 ? (int) Math.round(bestSurcharge / penalty) : 0;
            if (competitors >= CROWD_DEFER_THRESHOLD) {
                // Even the emptiest tooth map is packed — disperse for a while.
                entry.nextZakumScanAtMs = now + BotManager.randMs(CROWD_DEFER_MIN_MS, CROWD_DEFER_MAX_MS);
                entry.autopilotNextDecisionAtMs = 0L;
                return false;
            }
            pinned = best;
        }
        entry.zakumErrandMapId = pinned; // armed sentinel / debug view of the target
        entry.autopilotMapId = pinned;   // teeth accrue via the normal grind/combat/loot flow
        entry.autopilotNextDecisionAtMs = Math.max(entry.autopilotNextDecisionAtMs, now + PIN_HOLD_MS);
        // Crew: the plan leader mirrors its pin into the party plan, so crewmates WITHOUT an armed
        // errand (early finishers, below-ambition members) grind the same tooth map with the crew.
        BotAutopilotManager.publishLeaderPin(entry, pinned);
        return false;
    }

    // ---- step: stage 3 turn-in (100201 + the Eyes of Fire) -----------------------------------

    private static boolean tickTurnIn(BotEntry entry, Character bot, boolean runAiTick) {
        return approach(entry, bot, DOOR_MAP, ADOBIS, runAiTick, () -> {
            if (!bot.haveItem(ITEM_BREATH_FIRE) || !bot.haveItem(ITEM_BREATH_LAVA)
                    || bot.getItemQuantity(ITEM_GOLD_TOOTH, false) < TEETH_NEEDED) {
                return; // lost something — next tick re-resolves to the right step
            }
            if (!bot.canHold(ITEM_EYE_OF_FIRE, 5)) {
                backOff(entry); // ETC row needed; the resupply flow frees space during the back-off
                return;
            }
            try {
                // Reproduce Adobis's stage-3 block verbatim: complete 100201, consume the trial
                // items, grant the 5 Eyes of Fire.
                Quest.getInstance(Q_TRIALS).forceComplete(bot, NpcId.MAPLE_ADMINISTRATOR);
                InventoryManipulator.removeById(bot.getClient(), InventoryType.ETC, ITEM_BREATH_FIRE, 1, true, false);
                InventoryManipulator.removeById(bot.getClient(), InventoryType.ETC, ITEM_BREATH_LAVA, 1, true, false);
                InventoryManipulator.removeById(bot.getClient(), InventoryType.ETC, ITEM_GOLD_TOOTH, TEETH_NEEDED, true, false);
                InventoryManipulator.addById(bot.getClient(), ITEM_EYE_OF_FIRE, (short) 5);
            } catch (RuntimeException e) {
                log.warn("Bot '{}' failed the Zakum trials turn-in", bot.getName(), e);
                backOff(entry);
            }
        });
    }

    private static void finishAndDisarm(BotEntry entry, Character bot) {
        // Drop the party-of-one the PQ needed, so the bot returns to its social norm.
        Party party = bot.getParty();
        if (party != null && party.getMembers().size() <= 1 && bot.getClient() != null) {
            Party.leaveParty(party, bot.getClient());
        }
        clearZakumErrand(entry);
        reply.accept(entry, "trials done - zakum better watch out");
    }

    // ---- movement / interaction helpers ------------------------------------------------------

    /** Outcome of {@link #stepApproach}: whether the caller may release the tick. */
    private enum ApproachRun { CONSUMED, RELEASED }

    /** Walk to {@code npcId} on {@code targetMap}; on arrival, after the humanlike read-dwell, run
     *  {@code onArrive}. Returns true while the tick is consumed. Same stepper as the Temple errand
     *  (shared stall clock, dwell discipline per kb_bot_errand_dwell_clobber). */
    private static boolean approach(BotEntry entry, Character bot, int targetMap, int npcId,
                                    boolean runAiTick, Runnable onArrive) {
        return stepApproach(entry, bot, targetMap, npcId, runAiTick, onArrive) == ApproachRun.CONSUMED;
    }

    private static ApproachRun stepApproach(BotEntry entry, Character bot, int targetMap, int npcId,
                                            boolean runAiTick, Runnable onArrive) {
        long now = System.currentTimeMillis();
        if (entry.zakumErrandNpcId != npcId || entry.zakumErrandMapId != targetMap) {
            entry.zakumErrandNpcId = npcId;
            entry.zakumErrandMapId = targetMap;
            entry.zakumErrandProgress.begin(now);
            BotTravelManager.clearNpcApproach(entry);
        }
        if (!inLiveMaps(bot) && entry.zakumErrandProgress.stalled(now, APPROACH_TIMEOUT_MS)) {
            backOff(entry); // can't reach it — step aside and retry after a cooldown
            return ApproachRun.RELEASED;
        }
        BotTravelManager.ApproachStatus status = BotTravelManager.tickApproachNpc(
                entry, bot, targetMap, npcId, BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, true, TRIGGER_RADIUS_PX);
        entry.zakumErrandProgress.record(bot, status == BotTravelManager.ApproachStatus.TRAVELING, now);
        switch (status) {
            case NPC_GONE -> {
                backOff(entry);
                return ApproachRun.RELEASED;
            }
            case ARRIVED -> {
                if (!BotManager.npcDwellReady(entry, BotManager.NPC_READ_DELAY_MS, BotManager.NPC_READ_JITTER_MS)) {
                    return ApproachRun.CONSUMED; // "reading" the dialogue before acting
                }
                onArrive.run();
                entry.zakumErrandNpcId = 0; // action done — next tick re-resolves the step
                return ApproachRun.RELEASED;
            }
            case TRAVEL_YIELDED -> {
                BotManager.npcDwellReset(entry);
                return ApproachRun.RELEASED; // travel gave up this tick — grind; the errand retries
            }
            case TRAVELING -> {
                return ApproachRun.CONSUMED;
            }
            default -> {
                BotManager.npcDwellReset(entry);
                return ApproachRun.CONSUMED; // WALKING within radius on the NPC's map
            }
        }
    }

    private static boolean isLavaMap(int mapId) {
        return mapId == LAVA_MAP_1 || mapId == LAVA_MAP_2;
    }

    private static boolean isTeethMap(int mapId) {
        for (int m : TEETH_MAPS) {
            if (m == mapId) {
                return true;
            }
        }
        return false;
    }

    // ---- defer / back-off / clear ------------------------------------------------------------

    /** Yield this tick (not ready) so the normal grind flow runs; stays armed, no cooldown. */
    private static boolean defer(BotEntry entry) {
        entry.zakumErrandNpcId = 0;
        entry.zakumErrandProgress.touch(System.currentTimeMillis());
        BotTravelManager.clearMoveTargetPin(entry);
        return false;
    }

    /** Step aside after a failed approach/run: keep the errand armed but pause it for a jittered
     *  cooldown so it doesn't hammer an unreachable target while the bot grinds. */
    private static void backOff(BotEntry entry) {
        entry.zakumErrandNpcId = 0;
        entry.nextZakumScanAtMs = System.currentTimeMillis() + BotManager.randMs(BACKOFF_MIN_MS, BACKOFF_MAX_MS);
        BotManager.npcDwellReset(entry);
        BotTravelManager.clearNpcApproach(entry);
        BotTravelManager.clearMoveTargetPin(entry);
    }

    /** Reset all Zakum errand state (called on disarm/finish and from BotAutopilotManager.clear).
     *  Leaves nextZakumScanAtMs intact so it rate-limits re-arming (like nextTempleScanAtMs). */
    static void clearZakumErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.zakumErrandMapId = -1;
        entry.zakumErrandNpcId = 0;
        entry.zakumPqChestDropAtMs = 0L;
        entry.zakumLavaAttempts = 0;
        entry.zakumAssembleSinceMs = 0L;
        entry.zakumHintMask = 0;
        entry.zakumErrandProgress.clear();
        if (entry.nextZakumScanAtMs < System.currentTimeMillis()) {
            entry.nextZakumScanAtMs = System.currentTimeMillis() + BotManager.randMs(REARM_MIN_MS, REARM_MAX_MS);
        }
    }
}
