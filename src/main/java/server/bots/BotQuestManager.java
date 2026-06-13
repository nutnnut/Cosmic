/*
    This file is part of the OdinMS Maple Story Server.
    Bot quest loop (AI companion feature) - slice 1: the smallest LEGAL quest loop.
*/
package server.bots;

import client.Character;
import client.QuestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.NPC;
import server.maps.MapleMap;
import server.quest.Quest;

import java.awt.Point;
import java.util.List;
import java.util.Map;

/**
 * The smallest legal quest loop a companion can run, piggybacked on grinding. Two paths:
 *
 * <ul>
 *   <li><b>Auto quests</b> ({@link #tickAutoQuests}): {@code autoStart}+{@code autoComplete}
 *       quests advance with no NPC at all — on a cheap jittered cadence, {@code canStart}-&gt;
 *       {@code start}, then {@code canComplete}-&gt;{@code complete}. Allowed in any mode.</li>
 *   <li><b>Mob-quest piggyback</b> ({@link #tickPiggyback}): while a bot autopilots a grind map,
 *       look for startable mob quests whose required kills OVERLAP what the bot already farms here
 *       ({@link BotSpawnIndex}). If a quest clears a rough worthwhile bar (NPC round-trip within a
 *       few hops, reward exp worth the detour) the bot detours to the start NPC, walks within an
 *       interaction radius, calls {@code quest.start}, returns to grinding, and — once the kill
 *       counts are met — detours to the end NPC and calls {@code quest.complete}.</li>
 * </ul>
 *
 * <p><b>Legality.</b> Everything goes through {@link Quest#start}/{@link Quest#complete}, the exact
 * self-gating API real players hit via {@code QuestActionHandler} cases 1/2 (never the force-start
 * or owner-mirror variants). The server does not enforce NPC proximity, so legality here is
 * BEHAVIOR: the bot physically walks within {@link #NPC_TRIGGER_RADIUS_PX} of the quest NPC before
 * calling the API - the same standard as {@link BotTravelManager} cab NPCs and
 * {@link BotShopManager} shopkeepers. Kill progress flows free: a bot is a Character, so grinding
 * advances its own started quests with no extra code.
 *
 * <p><b>Supervised bots do not errand.</b> The piggyback detour only runs in autopilot
 * ({@link BotAutopilotManager#isActive}) - i.e. owner-ordered independent play. A bot in follow
 * mode with an online owner stays at the owner's side (owner-perks rule). Auto quests #1 are fine
 * anywhere (no travel).
 */
final class BotQuestManager {

    private static final Logger log = LoggerFactory.getLogger(BotQuestManager.class);

    /** Within this many px of the quest NPC counts as "talked to it" — matches the cab/shop radius. */
    static final int NPC_TRIGGER_RADIUS_PX = 500;

    // Jittered scan cadence so a party of bots doesn't run quest passes in lock-step.
    private static final long SCAN_MIN_MS = 30_000L;
    private static final long SCAN_MAX_MS = 60_000L;

    // Worthwhile bar (deliberately rough — slice 2 adds the real advisor scoring). A piggyback
    // quest is worth a detour when the NPC's map is within a few world-graph hops of the grind map
    // and the reward exp clears a small floor scaled to the bot's level (so a 30-exp reward stops
    // being worth a town trip once the bot out-levels it). Constants are visible on purpose.
    static final int MAX_ERRAND_HOPS = 3;
    static final int REWARD_EXP_FLOOR_PER_LEVEL = 8; // reward exp must be >= level * this

    enum Phase { NONE, START, TURNIN }

    /** Bot chat output, behind a seam so tests capture replies without the BotManager singleton. */
    static java.util.function.BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    private BotQuestManager() {}

    // ---- seams (the real quest API needs WZ; tests swap these) -------------------------------

    /** Quest-eligibility + action calls, behind a seam so unit tests can assert the loop drives
     *  start/complete only when canStart/canComplete pass, without loading WZ. */
    interface QuestGate {
        boolean canStart(Character bot, int questId, int npc);
        boolean canComplete(Character bot, int questId, int npc);
        void start(Character bot, int questId, int npc);
        void complete(Character bot, int questId, int npc);
        boolean isStarted(Character bot, int questId);
        /** Quest progress: required mob id -> current kills (0 when not started). */
        Map<Integer, Integer> currentProgress(Character bot, int questId);
    }

    static QuestGate gate = new QuestGate() {
        @Override public boolean canStart(Character bot, int questId, int npc) {
            return Quest.getInstance(questId).canStart(bot, npc);
        }
        @Override public boolean canComplete(Character bot, int questId, int npc) {
            return Quest.getInstance(questId).canComplete(bot, npc);
        }
        @Override public void start(Character bot, int questId, int npc) {
            Quest.getInstance(questId).start(bot, npc);
        }
        @Override public void complete(Character bot, int questId, int npc) {
            // null selection = the no-choice completion path real players hit (QuestActionHandler
            // case 2 without a selection short). NOT -1, which would mis-index a reward action.
            Quest.getInstance(questId).complete(bot, npc, null);
        }
        @Override public boolean isStarted(Character bot, int questId) {
            return bot.getQuest(Quest.getInstance(questId)).getStatus() == QuestStatus.Status.STARTED;
        }
        @Override public Map<Integer, Integer> currentProgress(Character bot, int questId) {
            QuestStatus qs = bot.getQuest(Quest.getInstance(questId));
            java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
            for (Map.Entry<Integer, String> e : qs.getProgress().entrySet()) {
                int v;
                try {
                    v = Integer.parseInt(e.getValue());
                } catch (NumberFormatException ex) {
                    v = 0;
                }
                out.put(e.getKey(), v);
            }
            return out;
        }
    };

    /** Mobs spawning on a map (mob id -> spawn points); seam over {@link BotSpawnIndex}. */
    interface MapMobsLookup {
        Map<Integer, Integer> mobsOn(int mapId);
    }

    static MapMobsLookup mapMobs = mapId -> {
        BotSpawnIndex.MapSpawns m = BotSpawnIndex.get().byMap().get(mapId);
        return m == null ? Map.of() : m.mobCounts();
    };

    /** World-graph hop count between two maps; seam over {@link BotWorldGraph}. Integer.MAX_VALUE
     *  when unreachable within the bound. */
    interface HopCount {
        int hops(int fromMapId, int toMapId);
    }

    static HopCount hopCount = (from, to) -> {
        if (from == to) {
            return 0;
        }
        List<Integer> route = BotWorldGraph.route(from, to, MAX_ERRAND_HOPS);
        return route == null || route.isEmpty() ? Integer.MAX_VALUE : route.size() - 1;
    };

    // ---- auto quests (#2 in the build) -------------------------------------------------------

    /** Run autoStart+autoComplete quests that pass canStart (then canComplete). No travel. Cheap
     *  and jittered — shares the {@link BotEntry#nextQuestScanAtMs} timer with the piggyback scan. */
    static void tickAutoQuests(BotEntry entry, Character bot) {
        if (!BotManager.cfg.AUTO_QUESTS || bot == null) {
            return;
        }
        for (int questId : BotQuestIndex.get().autoBoth()) {
            runAutoQuest(entry, bot, questId);
        }
    }

    /** One auto quest's start-then-complete attempt. npc 0: auto quests need no NPC (the requirement
     *  is absent or auto). canStart/canComplete self-gate everything else (level/job/prereq), so a
     *  not-yet-eligible quest is a no-op. Pure over the {@link #gate} seam (test entry point). */
    static void runAutoQuest(BotEntry entry, Character bot, int questId) {
        if (gate.canStart(bot, questId, 0)) {
            gate.start(bot, questId, 0);
        }
        if (gate.canComplete(bot, questId, 0)) {
            gate.complete(bot, questId, 0);
            announceDone(entry, questId);
        }
    }

    // ---- piggyback scan + worthwhile check (#3) ----------------------------------------------

    /** Cheap jittered scan, driven from the common tick. Auto quests always; the mob-quest detour
     *  only when autopiloting (supervised bots stay put). Sets {@link BotEntry} errand state for
     *  {@link #tickErrand} to drive; never moves the bot itself. */
    static void tickScan(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();
        if (now < entry.nextQuestScanAtMs) {
            return;
        }
        entry.nextQuestScanAtMs = now + BotManager.randMs((int) SCAN_MIN_MS, (int) SCAN_MAX_MS);

        tickAutoQuests(entry, bot);

        if (!BotManager.cfg.QUEST_PIGGYBACK || !BotAutopilotManager.isActive(entry)) {
            return;
        }
        if (entry.questErrandMapId != -1) {
            return; // one errand at a time
        }
        // Already-started quest whose counts are met -> queue the turn-in errand.
        BotQuestIndex.QuestMeta turnin = readyToTurnIn(bot);
        if (turnin != null) {
            beginErrand(entry, bot, turnin, Phase.TURNIN, turnin.endNpc());
            return;
        }
        // Otherwise look for a worthwhile new quest to start.
        BotQuestIndex.QuestMeta start = pickStartable(bot);
        if (start != null) {
            beginErrand(entry, bot, start, Phase.START, start.startNpc());
        }
    }

    /** A started, indexed quest whose every required mob count is met — ready to turn in. */
    private static BotQuestIndex.QuestMeta readyToTurnIn(Character bot) {
        for (BotQuestIndex.QuestMeta q : BotQuestIndex.get().byId().values()) {
            if (!gate.isStarted(bot, q.id())) {
                continue;
            }
            if (countsMet(bot, q)) {
                return q;
            }
        }
        return null;
    }

    static boolean countsMet(Character bot, BotQuestIndex.QuestMeta q) {
        Map<Integer, Integer> progress = gate.currentProgress(bot, q.id());
        for (Map.Entry<Integer, Integer> need : q.mobs().entrySet()) {
            if (progress.getOrDefault(need.getKey(), 0) < need.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Best startable mob quest whose kills overlap the current grind map and that clears the
     *  worthwhile bar. Null when nothing here is worth a detour. */
    private static BotQuestIndex.QuestMeta pickStartable(Character bot) {
        int mapId = bot.getMapId();
        Map<Integer, Integer> here = mapMobs.mobsOn(mapId);
        if (here.isEmpty()) {
            return null;
        }
        BotQuestIndex.QuestMeta best = null;
        int bestExp = -1;
        for (BotQuestIndex.QuestMeta q : BotQuestIndex.get().byId().values()) {
            if (gate.isStarted(bot, q.id())) {
                continue;
            }
            if (!overlaps(q.mobs().keySet(), here.keySet())) {
                continue;
            }
            // Pre-check eligibility with the quest's OWN start npc id (legality is the walk, not the
            // id): skips quests a level/job/prereq start-req would reject on arrival anyway.
            if (!gate.canStart(bot, q.id(), q.startNpc())) {
                continue;
            }
            // Reachability: the NPC's map must be on the current map or a few hops away. resolveNpcMap
            // finds where the NPC actually is (current map or its return-map town).
            int npcMap = resolveNpcMap(bot, q.startNpc());
            if (npcMap == -1 || !worthwhile(mapId, npcMap, q, bot.getLevel())) {
                continue;
            }
            if (q.rewardExp() > bestExp) {
                bestExp = q.rewardExp();
                best = q;
            }
        }
        return best;
    }

    private static boolean overlaps(java.util.Set<Integer> a, java.util.Set<Integer> b) {
        for (int x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    /** Rough worthwhile test (visible constants; slice 2 does real scoring): the quest NPC's map is
     *  within {@link #MAX_ERRAND_HOPS} of the grind map, and the reward exp clears a level-scaled
     *  floor so a once-good quest stops being worth a town trip as the bot out-levels it. */
    static boolean worthwhile(int grindMapId, int npcMapId, BotQuestIndex.QuestMeta q, int botLevel) {
        if (hopCount.hops(grindMapId, npcMapId) > MAX_ERRAND_HOPS) {
            return false;
        }
        return q.rewardExp() >= (long) botLevel * REWARD_EXP_FLOOR_PER_LEVEL;
    }

    // ---- errand state + travel/interaction tick (#4) -----------------------------------------

    private static void beginErrand(BotEntry entry, Character bot, BotQuestIndex.QuestMeta q,
                                    Phase phase, int npcId) {
        entry.questErrandQuestId = q.id();
        entry.questErrandNpcId = npcId;
        entry.questErrandPhase = phase;
        entry.questErrandReturnMapId = bot.getMapId();
        // The NPC's map is resolved on arrival by walking; for travel we target the NPC's home map.
        entry.questErrandMapId = resolveNpcMap(bot, npcId);
        if (entry.questErrandMapId == -1) {
            clearQuestErrand(entry);
            return;
        }
        reply.accept(entry,
                phase == Phase.START ? "gonna grab a quest real quick" : "lemme turn in this quest");
    }

    /** Find the map the NPC is on. The bot is grinding the map the quest mobs spawn on, and quest
     *  NPCs for low-level kill quests sit in the adjacent town — so check the current map first,
     *  then the return-map town. -1 when neither has the NPC (errand skipped). */
    private static int resolveNpcMap(Character bot, int npcId) {
        if (bot.getMap() != null && bot.getMap().getNPCById(npcId) != null) {
            return bot.getMapId();
        }
        MapleMap returnMap = bot.getMap() != null ? bot.getMap().getReturnMap() : null;
        if (returnMap != null && returnMap.getNPCById(npcId) != null) {
            return returnMap.getId();
        }
        return -1;
    }

    /**
     * Drives an active quest errand. Returns true when this tick is consumed (traveling toward the
     * NPC map, or walking to the NPC). Called from {@link BotAutopilotManager#tick} BEFORE the grind
     * destination is computed, so the errand takes precedence; on completion it clears its state and
     * lets autopilot resume grinding the return map.
     */
    static boolean tickErrand(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.questErrandMapId == -1) {
            return false;
        }
        if (bot.getMapId() != entry.questErrandMapId) {
            // Travel toward the NPC's map, reusing the autopilot travel driver and its hop cap.
            return BotTravelManager.tickTravel(entry, bot, entry.questErrandMapId,
                    BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, false);
        }
        // On the NPC's map: walk within the interaction radius, then act.
        NPC npc = bot.getMap().getNPCById(entry.questErrandNpcId);
        if (npc == null) {
            finishErrand(entry, bot, "huh, npc's gone, never mind");
            return false;
        }
        Point npcPos = npc.getPosition();
        Point botPos = bot.getPosition();
        if (!entry.inAir && !entry.climbing && manhattan(botPos, npcPos) <= NPC_TRIGGER_RADIUS_PX) {
            BotTravelManager.clearMoveTargetPin(entry);
            interactAndFinish(entry, bot);
            return false; // grind resumes on the return map next ticks
        }
        BotTravelManager.pinMoveTarget(entry, npcPos);
        BotTravelManager.movementStep.step(entry, npcPos, runAiTick);
        return true;
    }

    private static void interactAndFinish(BotEntry entry, Character bot) {
        int questId = entry.questErrandQuestId;
        int npc = entry.questErrandNpcId;
        if (entry.questErrandPhase == Phase.START) {
            if (gate.canStart(bot, questId, npc)) {
                gate.start(bot, questId, npc);
                finishErrand(entry, bot, "got it, back to farming");
            } else {
                finishErrand(entry, bot, "couldn't take that quest, oh well");
            }
        } else {
            BotQuestIndex.QuestMeta q = BotQuestIndex.get().byId().get(questId);
            if (q != null && !hasRoomForRewards(bot, q)) {
                // Mirror the player full-bag failure: don't complete into a full inventory.
                finishErrand(entry, bot, "bag's full, can't claim the reward yet");
                return;
            }
            if (gate.canComplete(bot, questId, npc)) {
                gate.complete(bot, questId, npc);
                announceDone(entry, questId);
                finishErrand(entry, bot, null);
            } else {
                finishErrand(entry, bot, "hm, can't turn that in yet");
            }
        }
    }

    /** Pre-check inventory space for the quest's item rewards the same way the player path does
     *  ({@code Character.canHold}). True when there are no item rewards or all fit. */
    static boolean hasRoomForRewards(Character bot, BotQuestIndex.QuestMeta q) {
        for (int itemId : q.rewardItems()) {
            if (!bot.canHold(itemId, 1)) {
                return false;
            }
        }
        return true;
    }

    private static void finishErrand(BotEntry entry, Character bot, String say) {
        clearQuestErrand(entry);
        if (say != null) {
            reply.accept(entry, say);
        }
    }

    static void clearQuestErrand(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.questErrandMapId = -1;
        entry.questErrandNpcId = 0;
        entry.questErrandQuestId = 0;
        entry.questErrandPhase = Phase.NONE;
        entry.questErrandReturnMapId = -1;
    }

    // ---- chat status (#5) --------------------------------------------------------------------

    /** "quests" status: active started quests + progress, ASCII, rate-limited by the caller. */
    static String questStatus(Character bot) {
        if (bot == null) {
            return "no quests";
        }
        List<QuestStatus> started = bot.getStartedQuests();
        if (started.isEmpty()) {
            return "no quests started rn";
        }
        StringBuilder sb = new StringBuilder("quests: ");
        int shown = 0;
        for (QuestStatus qs : started) {
            if (shown >= 4) {
                sb.append("...");
                break;
            }
            BotQuestIndex.QuestMeta meta = BotQuestIndex.get().byId().get((int) qs.getQuestID());
            if (meta == null || meta.mobs().isEmpty()) {
                continue; // only the bot-runnable mob quests have meaningful progress to show
            }
            if (shown > 0) {
                sb.append("; ");
            }
            sb.append(qs.getQuestID()).append(" ");
            boolean first = true;
            Map<Integer, Integer> progress = gate.currentProgress(bot, qs.getQuestID());
            for (Map.Entry<Integer, Integer> need : meta.mobs().entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                sb.append(progress.getOrDefault(need.getKey(), 0)).append("/").append(need.getValue());
                first = false;
            }
            shown++;
        }
        return shown == 0 ? "no mob quests in progress" : sb.toString();
    }

    private static void announceDone(BotEntry entry, int questId) {
        reply.accept(entry, "quest done: quest " + questId);
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
