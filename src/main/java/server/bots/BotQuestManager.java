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

    // Give up on an errand that can't reach its NPC within this long (travel hop cap covers most
    // unreachability; this catches mid-travel disruptions so the errand state can't wedge forever).
    static final long ERRAND_TIMEOUT_MS = 90_000L;

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
        /** True when the bot has already COMPLETED this quest (Feature B stale-item check). */
        boolean isCompleted(Character bot, int questId);
        /** Quest progress: required mob id -> current kills (0 when not started). */
        Map<Integer, Integer> currentProgress(Character bot, int questId);
    }

    static QuestGate gate = new QuestGate() {
        @Override public boolean canStart(Character bot, int questId, int npc) {
            return Quest.getInstance(questId).canStart(bot, npc);
        }
        @Override public boolean canComplete(Character bot, int questId, int npc) {
            // A quest whose completion is gated ONLY by an NPC end-script (e.g. 29400 "Veteran
            // Hunter": its real 1,000,000-kill count lives in q29400e.js) can't be legally finished
            // by the bot. ScriptRequirement.check() returns true unconditionally, so the generic
            // canComplete would say "yes" and the bot would falsely complete it and loop. Defer those
            // to the NPC dialogue. Quests with a real item/mob requirement (e.g. 1021) stay handled.
            return !Quest.getInstance(questId).completeGatedOnlyByScript()
                    && Quest.getInstance(questId).canComplete(bot, npc);
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
        @Override public boolean isCompleted(Character bot, int questId) {
            return bot.getQuest(Quest.getInstance(questId)).getStatus() == QuestStatus.Status.COMPLETED;
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

    /** Mob ids that drop a quest fetch-item; seam over {@link server.life.MonsterInformationProvider}.
     *  Empty = not mob-droppable (bought/crafted/gathered) -> the bot won't take that fetch quest. */
    interface ItemDroppers {
        List<Integer> droppersOf(int itemId);
    }

    static ItemDroppers itemDroppers =
            itemId -> server.life.MonsterInformationProvider.getInstance().retrieveItemDroppers(itemId);

    /** How many of an item the bot currently holds; seam over {@link Character#getItemQuantity}. Used
     *  to tell when a fetch quest's delivery items are collected (turn-in readiness). */
    interface ItemQuantity {
        int held(Character bot, int itemId);
    }

    static ItemQuantity itemQuantity = (bot, itemId) -> bot.getItemQuantity(itemId, false);

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

    /** The bot's grind exp/min on its current map — the baseline the scorer measures a quest
     *  against. CHEAP / current-map only: this runs on the bot tick thread (auto-suggest path),
     *  so it must NOT trigger the heavy world-wide {@link BotGrindAdvisor} pass. Seam over
     *  {@link BotGrindAdvisor#currentMapExpPerMinute}. */
    interface GrindExpBaseline {
        double expPerMinute(BotEntry entry, Character bot);
    }

    static GrindExpBaseline grindExpBaseline = BotGrindAdvisor::currentMapExpPerMinute;

    /** Best grind rate the bot could ACHIEVE (base exp/min), the opportunity-cost baseline when it
     *  is asked off its grind map (in town/transit) where {@link #grindExpBaseline} reads 0 -
     *  otherwise leaving to quest looks free and trivial quests score huge. HEAVY (full grind pass);
     *  used only by the off-thread "recommend quest" path, never the bot tick. Seam over
     *  {@link BotGrindAdvisor#bestGrindExpPerMinute}. */
    static GrindExpBaseline bestGrindExpBaseline = BotGrindAdvisor::bestGrindExpPerMinute;

    /** Per-mob base exp; seam over {@link server.life.LifeFactory} (un-rated, the same raw exp
     *  units {@link BotGrindAdvisor#currentMapExpPerMinute} blends, so the rate cancels in the
     *  scorer's value/cost ratio). */
    static BotQuestScorer.MobExp mobExp = mobId -> {
        try {
            server.life.Monster mob = server.life.LifeFactory.getMonster(mobId);
            return mob == null || mob.getStats() == null ? 0 : mob.getStats().getExp();
        } catch (RuntimeException e) {
            return 0;
        }
    };

    /** Round-trip travel seconds between two maps; seam over {@link BotTravelCost}. A one-way
     *  flood is doubled for the return leg. Large finite fallback when unreachable. */
    interface TravelSeconds {
        double seconds(int fromMapId, int toMapId);
    }

    static TravelSeconds travelSeconds = (from, to) -> {
        if (from == to) {
            return 0.0;
        }
        Map<Integer, Double> flood = BotTravelCost.floodSeconds(from, MAX_ERRAND_HOPS,
                BotWorldGraph.RouteOptions.PORTALS_ONLY, ms -> ms);
        Double oneWay = flood.get(to);
        return oneWay == null ? BotTravelCost.HORIZON_SECONDS : oneWay * 2.0;
    };

    /** NPC display name from id; seam over {@link server.life.LifeFactory}. Cached forever
     *  (static WZ data; the provider is synchronized and a recommend pass asks for several). */
    interface NameLookup {
        String name(int id);
    }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> npcNameCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    static NameLookup npcName = npcId -> npcNameCache.computeIfAbsent(npcId, id -> {
        try {
            String n = server.life.LifeFactory.getNPC(id).getName();
            return n == null || n.isEmpty() ? ("npc " + id) : n;
        } catch (RuntimeException e) {
            return "npc " + id;
        }
    });

    static NameLookup mapName = mapId -> {
        try {
            String n = server.maps.MapFactory.loadPlaceName(mapId);
            return n == null || n.isEmpty() ? ("map " + mapId) : n;
        } catch (RuntimeException e) {
            return "map " + mapId;
        }
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
        if (entry.buggedQuestIds.contains(questId)) {
            return; // proven un-completable upstream — don't loop the complete()+announce again
        }
        if (gate.canStart(bot, questId, 0)) {
            gate.start(bot, questId, 0);
        }
        if (gate.canComplete(bot, questId, 0)) {
            gate.complete(bot, questId, 0);
            // Verify it actually registered. A bugged quest whose complete() is a no-op leaves
            // canComplete true forever, so without this the bot re-completes + re-announces "done"
            // every scan (the 29400 loop). Suppress it instead.
            if (gate.isCompleted(bot, questId)) {
                announceDone(entry, questId);
            } else {
                markQuestBugged(entry, questId, "auto-complete did not register");
            }
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

        if (!BotManager.cfg.QUEST_PIGGYBACK) {
            return;
        }
        if (!BotAutopilotManager.isActive(entry)) {
            // Supervised (owner online, bot at their side): never wander off questing - only
            // SUGGEST a standout nearby quest, occasionally and rate-limited. This is the
            // supervised counterpart to autopilot's piggyback (which does quests itself).
            maybeAutoSuggest(entry, bot);
            return;
        }
        // Opportunistic free grab: start any indexed quest whose NPC the bot is already standing
        // next to (no detour, no overlap/worthwhile gate - it's free). Then keep the quest-mob cache
        // fresh so combat prefers what we accepted. Both run even mid-errand (grabbing is free).
        tickOpportunisticGrab(entry, bot);
        refreshActiveQuestMobs(entry, bot);
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
        BotQuestIndex.QuestMeta start = pickStartable(entry, bot);
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
        // Fetch quests are ready once the delivery items are in the bag (the passive-loot tick collects
        // them while grinding; isStaleQuestItem protects them from the auto-sell while the quest runs).
        for (Map.Entry<Integer, Integer> need : q.items().entrySet()) {
            if (itemQuantity.held(bot, need.getKey()) < need.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ---- opportunistic grab + quest commitment (so the bot does what it accepted) ----------------

    /** Free quest grab: start any startable INDEXED quest whose start NPC is on the bot's CURRENT map
     *  within talk radius. No detour, no overlap/worthwhile gate - the bot is already standing there,
     *  so taking it costs nothing. The commitment bias ({@link #questMapScoreBias} /
     *  {@link #activeQuestMobIds}) then steers grinding to actually finish it. Indexed-only: the bot
     *  can only meaningfully complete quests it knows how to (talk + the overlapping mob quests). */
    static void tickOpportunisticGrab(BotEntry entry, Character bot) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        Point botPos = bot.getPosition();
        if (botPos == null || entry.inAir || entry.climbing) {
            return;
        }
        boolean grabbed = false;
        for (BotQuestIndex.QuestMeta q : BotQuestIndex.get().byId().values()) {
            if (gate.isStarted(bot, q.id()) || gate.isCompleted(bot, q.id())) {
                continue;
            }
            NPC npc = map.getNPCById(q.startNpc());
            if (npc == null || npc.getPosition() == null) {
                continue; // NPC not on this map - not "passing" it
            }
            if (manhattan(botPos, npc.getPosition()) > NPC_TRIGGER_RADIUS_PX) {
                continue; // on the map but not next to it
            }
            if (!gate.canStart(bot, q.id(), q.startNpc())) {
                continue; // level/job/prereq not met
            }
            gate.start(bot, q.id(), q.startNpc());
            grantScriptedStartItem(bot, q.id());
            grabbed = true;
        }
        if (grabbed) {
            refreshActiveQuestMobs(entry, bot);
            reply.accept(entry, "grabbed a quest while i'm here");
        }
    }

    /** Mob ids the bot still needs to kill for any STARTED indexed quest (counts not yet met). The
     *  SSOT for "what mobs is this bot committed to" - used by both the grind map-bias and the combat
     *  target-bias. Cheap (a handful of indexed quests). */
    static java.util.Set<Integer> activeQuestMobIds(Character bot) {
        if (bot == null) {
            return java.util.Set.of();
        }
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (BotQuestIndex.QuestMeta q : BotQuestIndex.get().byId().values()) {
            if (!gate.isStarted(bot, q.id()) || (q.mobs().isEmpty() && q.items().isEmpty())) {
                continue;
            }
            Map<Integer, Integer> progress = gate.currentProgress(bot, q.id());
            for (Map.Entry<Integer, Integer> need : q.mobs().entrySet()) {
                if (progress.getOrDefault(need.getKey(), 0) < need.getValue()) {
                    out.add(need.getKey());
                }
            }
            // Fetch quests: steer toward whatever drops the still-missing delivery item (same map-bias
            // + combat-target bias as kill quests). Mob-droppable scope: droppersOf is empty for
            // bought/crafted items, so those add nothing and the bot won't chase them.
            for (Map.Entry<Integer, Integer> need : q.items().entrySet()) {
                if (itemQuantity.held(bot, need.getKey()) < need.getValue()) {
                    out.addAll(itemDroppers.droppersOf(need.getKey()));
                }
            }
        }
        return out;
    }

    /** Refresh the bot's cached still-needed quest-mob set (read O(1) by combat target selection). */
    static void refreshActiveQuestMobs(BotEntry entry, Character bot) {
        if (entry != null) {
            entry.activeQuestMobIds = activeQuestMobIds(bot);
        }
    }

    /** Map-score multiplier that favors maps spawning a still-needed started-quest mob, so an
     *  autopilot bot commits to the quest it accepted instead of drifting to a richer grind. 1.0 when
     *  the bot has no active quest mobs or this map has none of them. Applied by the autopilot
     *  travel-weight closure ({@link BotAutopilotManager}); the {@code neededMobs} set is computed
     *  once per decide pass via {@link #activeQuestMobIds}. */
    static final double QUEST_GRIND_MAP_BIAS = 1.6;

    static double questMapScoreBias(int mapId, java.util.Set<Integer> neededMobs) {
        if (neededMobs == null || neededMobs.isEmpty()) {
            return 1.0;
        }
        Map<Integer, Integer> here = mapMobs.mobsOn(mapId);
        for (int mobId : neededMobs) {
            if (here.containsKey(mobId)) {
                return QUEST_GRIND_MAP_BIAS;
            }
        }
        return 1.0;
    }

    /** Best startable quest worth a detour from the current grind: a MOB quest whose kills overlap
     *  the current map, or a TALK quest (no kills needed). Null when nothing here clears the bar. */
    private static BotQuestIndex.QuestMeta pickStartable(BotEntry entry, Character bot) {
        int mapId = bot.getMapId();
        java.util.Set<Integer> hereMobs = mapMobs.mobsOn(mapId).keySet();
        // The autopilot's chosen grind destination (when staging in town / in transit toward it). A
        // quest whose targets overlap the DESTINATION can be grabbed BEFORE departing, instead of
        // flying out, discovering the overlap on arrival, then flying BACK to the town NPC to start it
        // (the round-trip the bot was doing). Empty once already on the grind map (dest == current).
        java.util.Set<Integer> destMobs = java.util.Set.of();
        if (BotAutopilotManager.isActive(entry) && entry.autopilotMapId > 0
                && entry.autopilotMapId != mapId) {
            destMobs = mapMobs.mobsOn(entry.autopilotMapId).keySet();
        }
        BotQuestIndex.QuestMeta best = null;
        double bestScore = -1;
        for (BotQuestIndex.QuestMeta q : BotQuestIndex.get().byId().values()) {
            if (gate.isStarted(bot, q.id()) || entry.buggedQuestIds.contains(q.id())) {
                continue; // already underway, or proven un-completable/unreachable — don't re-queue it
            }
            // Mob/fetch quests are only worth a detour when their targets overlap what the bot will
            // farm (free exp / free drops). Talk quests have no targets, so the gate is skipped.
            // effectiveTargetMobs folds in the mobs that drop a fetch item; empty for a fetch quest whose
            // item isn't mob-droppable, which then fails the overlap gate (mob-droppable scope).
            java.util.Set<Integer> targets = effectiveTargetMobs(q);
            boolean overlapsHere = overlaps(targets, hereMobs);
            boolean overlapsDest = !overlapsHere && overlaps(targets, destMobs);
            if (!q.talk() && !overlapsHere && !overlapsDest) {
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
            if (npcMap == -1) {
                continue;
            }
            // Pre-departure freebie: a quest matched ONLY via the destination is taken solely when its
            // NPC is already on the map the bot is standing on — start it now, no extra hop. Skips the
            // "0 grind baseline in town makes any quest look free" trap and any mid-transit backtrack;
            // current-map-overlap quests keep the normal worthwhile-detour scoring below.
            if (overlapsDest && npcMap != mapId) {
                continue;
            }
            if (!worthwhile(entry, mapId, npcMap, q, bot)) {
                continue;
            }
            // Rank by the slice-2 score (autopilot benefits from the upgraded model), not raw exp.
            double score = scoreQuest(entry, bot, mapId, npcMap, q);
            if (score > bestScore) {
                bestScore = score;
                best = q;
            }
        }
        return best;
    }

    /** The mobs whose kills make progress on this quest: its kill targets, plus (for a fetch quest) the
     *  mobs that drop a required delivery item. Empty for a fetch quest whose item isn't mob-droppable
     *  — those are out of the bot's reach and get filtered by the overlap gate. Memoized DB lookups, so
     *  the per-scan cost is a map hit after warm-up. */
    static java.util.Set<Integer> effectiveTargetMobs(BotQuestIndex.QuestMeta q) {
        if (q.items().isEmpty()) {
            return q.mobs().keySet();
        }
        java.util.Set<Integer> out = new java.util.HashSet<>(q.mobs().keySet());
        for (int itemId : q.items().keySet()) {
            out.addAll(itemDroppers.droppersOf(itemId));
        }
        return out;
    }

    private static boolean overlaps(java.util.Set<Integer> a, java.util.Set<Integer> b) {
        for (int x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    /** Slice-2 worthwhile test: the NPC's map is within {@link #MAX_ERRAND_HOPS}, and the
     *  {@link BotQuestScorer} value/cost score (reward exp + overlapping-mob exp vs travel + off-map
     *  kill time, against the bot's grind baseline) clears {@link BotQuestScorer#RECOMMEND_MIN_SCORE}.
     *  Drives the autopilot piggyback pick — autopilot benefits from the same upgraded scoring. */
    static boolean worthwhile(BotEntry entry, int grindMapId, int npcMapId,
                              BotQuestIndex.QuestMeta q, Character bot) {
        if (hopCount.hops(grindMapId, npcMapId) > MAX_ERRAND_HOPS) {
            return false;
        }
        return scoreQuest(entry, bot, grindMapId, npcMapId, q) >= BotQuestScorer.RECOMMEND_MIN_SCORE;
    }

    /** The shared slice-2 advisor score for a quest: how much better than grinding it is, in
     *  multiples of the bot's current-map exp/min baseline. Used by the recommend command, the
     *  auto-suggest gate, and the autopilot piggyback pick — one model everywhere. Pure once the
     *  seams ({@link #mobExp}/{@link #travelSeconds}/{@link #grindExpBaseline}/{@link #mapMobs})
     *  are resolved, so the math is unit-tested directly in {@link BotQuestScorer}. */
    static double scoreQuest(BotEntry entry, Character bot, int grindMapId, int npcMapId,
                             BotQuestIndex.QuestMeta q) {
        return scoreQuest(entry, bot, grindMapId, npcMapId, q, grindExpBaseline.expPerMinute(entry, bot));
    }

    /** As {@link #scoreQuest(BotEntry, Character, int, int, BotQuestIndex.QuestMeta)} but with an
     *  explicit opportunity-cost baseline (base exp/min) - lets the off-thread recommend path pass
     *  a best-achievable-grind fallback when the current map reads 0 (bot in town/transit), so a
     *  trivial reward is not scored against a near-zero cost. */
    static double scoreQuest(BotEntry entry, Character bot, int grindMapId, int npcMapId,
                             BotQuestIndex.QuestMeta q, double baseline) {
        // Talk quests (no kills, no fetched items) score on a flat completion worth + reward vs the
        // NPC round-trip cost - there are no mobs to overlap or kill.
        if (q.talk()) {
            double travelT = travelSeconds.seconds(grindMapId, npcMapId);
            double uniqueT = uniqueRewardExpEquivalent(bot, q);
            return BotQuestScorer.scoreTalk(q.rewardExp(), uniqueT, travelT, baseline);
        }
        // Overlap = required mobs the bot already kills on its current grind map (free exp).
        java.util.Set<Integer> here = mapMobs.mobsOn(grindMapId).keySet();
        java.util.Set<Integer> overlap = new java.util.HashSet<>();
        for (int mobId : q.mobs().keySet()) {
            if (here.contains(mobId)) {
                overlap.add(mobId);
            }
        }
        // One blended kill time for the bot's current grind (proxy for non-overlap mob kill cost).
        double killSecondsPerMob = grindKillSeconds(entry, bot);
        // Round trip: grind map -> NPC map -> back. resolveNpcMap already found npcMapId.
        double travel = travelSeconds.seconds(grindMapId, npcMapId);
        double uniqueBonus = uniqueRewardExpEquivalent(bot, q);
        return BotQuestScorer.score(q.mobs(), q.rewardExp(), uniqueBonus, overlap, mobExp,
                killSecondsPerMob, travel, baseline);
    }

    /** Blended kill-seconds proxy for off-grind-map mob kills: a quest mob the bot doesn't farm
     *  here still costs roughly what one of its current mobs costs to kill. Derived from the
     *  baseline (exp/min) and a typical mob exp would be circular, so use a flat estimate that the
     *  scorer treats as cost — kept simple and visible. */
    static double grindKillSeconds(BotEntry entry, Character bot) {
        return DEFAULT_OFFMAP_KILL_SECONDS;
    }

    /** Seconds assumed to kill one off-grind-map required quest mob (pure cost). Deliberately a
     *  visible constant; non-overlapping kill quests are rare among piggyback candidates. */
    static final double DEFAULT_OFFMAP_KILL_SECONDS = 4.0;

    /** Exp-equivalent of a quest's unique equip reward, valued through the equip-value SSOT
     *  ({@link BotScrollManager#offenseValue}). Seam so tests stay WZ-free. 0 when no equip
     *  reward or it is worthless to this bot. */
    static java.util.function.ToDoubleBiFunction<Character, BotQuestIndex.QuestMeta>
            uniqueRewardValue = BotQuestManager::computeUniqueRewardValue;

    static double uniqueRewardExpEquivalent(Character bot, BotQuestIndex.QuestMeta q) {
        return uniqueRewardValue.applyAsDouble(bot, q);
    }

    /** Production unique-reward valuation: take the best offense value among the quest's equip
     *  rewards (a fresh roll), scaled to exp-equivalent by {@link #UNIQUE_REWARD_EXP_PER_OFFENSE}.
     *  Equip rewards are rare among the indexed mob quests, so this is usually 0. */
    private static double computeUniqueRewardValue(Character bot, BotQuestIndex.QuestMeta q) {
        if (q.rewardItems().isEmpty()) {
            return 0.0;
        }
        server.ItemInformationProvider ii = server.ItemInformationProvider.getInstance();
        double best = 0.0;
        for (int itemId : q.rewardItems()) {
            if (!constants.inventory.ItemConstants.isEquipment(itemId)) {
                continue;
            }
            try {
                client.inventory.Item it = ii.getEquipById(itemId);
                if (it instanceof client.inventory.Equip eq) {
                    best = Math.max(best, BotScrollManager.offenseValue(bot, eq));
                }
            } catch (RuntimeException ignored) {
                // unresolvable reward — value it at 0 (conservative).
            }
        }
        return best * UNIQUE_REWARD_EXP_PER_OFFENSE;
    }

    /** Exp-equivalent weight of one point of equip offense value for a unique quest reward. A
     *  visible knob: a strong reward equip should feel worth a few minutes of grind. */
    static final double UNIQUE_REWARD_EXP_PER_OFFENSE = 50.0;

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
        // Don't commit an errand to an unreachable NPC map. The npc->map index can hand back a bogus or
        // foreign map (observed: map 2) the bot can never walk to — it would announce "lemme turn in
        // this quest", fail to arrive, time out "couldn't get to that quest", re-queue, and loop, while
        // its travel give-up poisoned the shared state. Suppress and bail instead.
        if (hopCount.hops(bot.getMapId(), entry.questErrandMapId) > MAX_ERRAND_HOPS) {
            markQuestBugged(entry, q.id(), "NPC on unreachable map " + entry.questErrandMapId);
            clearQuestErrand(entry);
            return;
        }
        entry.questErrandStartedAtMs = System.currentTimeMillis();
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
        // Beyond the current/return map: consult the world NPC->map index so the errand can target a
        // quest NPC a few hops away (worthwhile's MAX_ERRAND_HOPS check then gates whether it's close
        // enough). Without this, talk quests only ever fire when the bot grinds right next to the NPC.
        return npcMapLookup.mapOf(npcId);
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
        // Abort an errand that can't reach the NPC in time (portal closed, death-respawn elsewhere,
        // route gone). Without this the "one errand at a time" scan guard would block all future
        // piggyback for this bot forever. Mirrors autopilot's unreachable-errand giveup.
        if (System.currentTimeMillis() - entry.questErrandStartedAtMs > ERRAND_TIMEOUT_MS) {
            finishErrand(entry, bot, "couldn't get to that quest, dropping it");
            return false;
        }
        // Travel to the NPC's map, then walk within the interaction radius (shared SSOT stepper).
        BotTravelManager.ApproachStatus status = BotTravelManager.tickApproachNpc(
                entry, bot, entry.questErrandMapId, entry.questErrandNpcId,
                BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, NPC_TRIGGER_RADIUS_PX);
        switch (status) {
            case NPC_GONE -> {
                finishErrand(entry, bot, "huh, npc's gone, never mind");
                return false;
            }
            case ARRIVED -> {
                if (!BotManager.npcDwellReady(entry, BotManager.NPC_READ_DELAY_MS, BotManager.NPC_READ_JITTER_MS)) {
                    return true; // standing at the NPC, "reading" before accept/turn-in
                }
                interactAndFinish(entry, bot);
                return false; // grind resumes on the return map next ticks
            }
            case TRAVEL_YIELDED -> {
                BotManager.npcDwellReset(entry);
                return false; // travel gave up this tick — let the bot grind, errand retries/timeouts
            }
            case TRAVELING -> {
                // A hop is actively underway (tickTravel made legal progress — it has its own
                // give-up windows, so sustained TRAVELING means the route is advancing). Reset the
                // give-up clock so a long but legitimately-progressing multi-hop journey isn't
                // dropped mid-route with "couldn't get to that quest". Genuine unreachability still
                // times out: WALKING (on the NPC's map, can't close the last gap) does NOT reset.
                entry.questErrandStartedAtMs = System.currentTimeMillis();
                BotManager.npcDwellReset(entry);
                return true;
            }
            default -> {
                BotManager.npcDwellReset(entry);
                return true; // WALKING — on the NPC's map, walking within radius (tick consumed)
            }
        }
    }

    private static void interactAndFinish(BotEntry entry, Character bot) {
        int questId = entry.questErrandQuestId;
        int npc = entry.questErrandNpcId;
        if (entry.questErrandPhase == Phase.START) {
            if (gate.canStart(bot, questId, npc)) {
                gate.start(bot, questId, npc);
                grantScriptedStartItem(bot, questId);
                refreshActiveQuestMobs(entry, bot); // commit: combat now prefers this quest's mobs
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
                refreshActiveQuestMobs(entry, bot); // done: drop its mobs from the combat bias
                if (gate.isCompleted(bot, questId)) {
                    announceDone(entry, questId);
                } else {
                    // complete() didn't take (bugged quest): suppress so the scan doesn't re-queue this
                    // turn-in errand forever ("lemme turn in this quest" -> can't -> retry loop).
                    markQuestBugged(entry, questId, "turn-in did not register");
                }
                finishErrand(entry, bot, null);
            } else {
                finishErrand(entry, bot, "hm, can't turn that in yet");
            }
        }
    }

    /** Record a quest the bot can't finish — complete() didn't register, or its NPC map is unreachable —
     *  so every scan skips it instead of looping the same doomed start/turn-in/complete + announcement.
     *  Tells the owner once (autopilot bots with no owner online just suppress silently). */
    static void markQuestBugged(BotEntry entry, int questId, String why) {
        if (entry.buggedQuestIds.add(questId)) {
            reply.accept(entry, "quest " + questId + " seems bugged (" + why + "), skipping it");
        }
    }

    /** Per-quest item the start-NPC script would GIVE, for the rare scripted talk quest where the bot
     *  must reproduce that gift. EMPTY for now: the one candidate, q1021 Roger's Apple (2010007), must
     *  NOT be granted — its COMPLETE requirement is "item 2010007, countNeeded 0", and
     *  {@link server.quest.requirements.ItemRequirement#check} fails when {@code countNeeded <= 0 &&
     *  count > 0}. I.e. the player EATS the apple and turns in holding ZERO; a bot that self-grants it
     *  could never complete the quest. So the bot starts q1021 with no apple and turns it in clean.
     *  Only add an id here if the script's item is genuinely ADDITIVE (needed at turn-in, not consumed). */
    static final Map<Integer, Integer> SCRIPTED_START_ITEM = Map.of();

    /** Item grant seam (production: the legal {@code InventoryManipulator.addById}); tests stub it. */
    static java.util.function.ObjIntConsumer<Character> grantItem = (bot, itemId) -> {
        try {
            if (client.inventory.manipulator.InventoryManipulator.checkSpace(
                    bot.getClient(), itemId, 1, "")) {
                client.inventory.manipulator.InventoryManipulator.addById(
                        bot.getClient(), itemId, (short) 1);
            }
        } catch (RuntimeException ignored) {
            // a failed grant is non-fatal.
        }
    };

    /** Reproduce a start-NPC script's ADDITIVE item gift, for any quest listed in
     *  {@link #SCRIPTED_START_ITEM} (currently none). No-op otherwise. */
    static void grantScriptedStartItem(Character bot, int questId) {
        Integer itemId = SCRIPTED_START_ITEM.get(questId);
        if (itemId != null) {
            grantItem.accept(bot, itemId);
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
        entry.questErrandStartedAtMs = 0L;
    }

    // ---- quest recommendations (Feature A) ---------------------------------------------------

    /** Mob display name from id; seam over {@link server.life.MonsterInformationProvider} so the
     *  recommend objective lines ("kill 50 Zombie Mushroom") stay WZ-free in tests. */
    static NameLookup mobName = mobId -> {
        try {
            String n = server.life.MonsterInformationProvider.getInstance().getMobNameFromId(mobId);
            return n == null || n.isEmpty() ? ("mob " + mobId) : n;
        } catch (RuntimeException e) {
            return "mob " + mobId;
        }
    };

    /** One ranked recommendation: the quest, its score, and the resolved NPC map (for the line). */
    record Recommendation(BotQuestIndex.QuestMeta quest, double score, int startNpcMap) {}

    /**
     * "recommend quest": the top startable quests for the bot's CURRENT situation, ranked by the
     * slice-2 score. SENSITIVE — the owner asked, so the bar is low ({@link
     * BotQuestScorer#RECOMMEND_MIN_SCORE}); anything net-positive over grinding is shown. Returns
     * up to {@code limit} {@link Recommendation}s, best first. Does NOT touch the bot or move it;
     * the caller renders the lines and replies. Heavy enough (per-quest canStart) to run
     * off-thread — the chat handler dispatches it on a pool, like grind advice.
     */
    static List<Recommendation> recommendQuests(BotEntry entry, Character bot, int limit) {
        if (bot == null) {
            return List.of();
        }
        int grindMap = bot.getMapId();
        // Opportunity-cost baseline: the bot's current-map grind rate, or - when asked off its grind
        // map (town/transit, rate reads 0) - its best ACHIEVABLE grind rate, so a trivial-exp quest
        // is not measured against a near-zero cost and spuriously recommended (lv64 30-pig/1300exp).
        double baseline = grindExpBaseline.expPerMinute(entry, bot);
        if (baseline <= 0.0) {
            baseline = bestGrindExpBaseline.expPerMinute(entry, bot);
        }
        List<Recommendation> out = new java.util.ArrayList<>();
        for (BotQuestIndex.QuestMeta q : BotQuestIndex.get().byId().values()) {
            if (gate.isStarted(bot, q.id()) || gate.isCompleted(bot, q.id())) {
                continue;
            }
            if (!gate.canStart(bot, q.id(), q.startNpc())) {
                continue;
            }
            int npcMap = resolveStartNpcMap(bot, q.startNpc());
            if (npcMap == -1 || hopCount.hops(grindMap, npcMap) > MAX_ERRAND_HOPS) {
                continue;
            }
            double score = scoreQuest(entry, bot, grindMap, npcMap, q, baseline);
            if (score >= BotQuestScorer.RECOMMEND_MIN_SCORE) {
                out.add(new Recommendation(q, score, npcMap));
            }
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Render one recommendation as a single ASCII chat line:
     *  "<name> @ <start NPC> in <map>: <objective> -> <reward>". */
    static String describeRecommendation(Recommendation rec) {
        BotQuestIndex.QuestMeta q = rec.quest();
        StringBuilder sb = new StringBuilder();
        sb.append("q").append(q.id())
          .append(" @ ").append(npcName.name(q.startNpc()))
          .append(" in ").append(mapName.name(rec.startNpcMap()))
          .append(": ").append(objectiveSummary(q))
          .append(" -> ").append(rewardSummary(q));
        return sb.toString();
    }

    /** "kill 50 Zombie Mushroom, 20 Stump" — the required-mob turn-in objective, ASCII. A talk quest
     *  has no kills: "talk to <end NPC>". */
    static String objectiveSummary(BotQuestIndex.QuestMeta q) {
        if (q.talk()) {
            return "talk to " + npcName.name(q.endNpc());
        }
        StringBuilder sb = new StringBuilder("kill ");
        boolean first = true;
        for (Map.Entry<Integer, Integer> need : q.mobs().entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(need.getValue()).append(" ").append(mobName.name(need.getKey()));
            first = false;
        }
        return sb.toString();
    }

    /** "<exp> exp" plus a unique item note when the quest awards one. */
    static String rewardSummary(BotQuestIndex.QuestMeta q) {
        StringBuilder sb = new StringBuilder();
        sb.append(q.rewardExp()).append(" exp");
        if (!q.rewardItems().isEmpty()) {
            sb.append(" + ").append(itemName(q.rewardItems().get(0)));
        }
        return sb.toString();
    }

    /** Item display name; seam over {@link server.ItemInformationProvider}. */
    static NameLookup itemNameLookup = itemId -> {
        try {
            String n = server.ItemInformationProvider.getInstance().getName(itemId);
            return n == null || n.isEmpty() ? ("item " + itemId) : n;
        } catch (RuntimeException e) {
            return "item " + itemId;
        }
    };

    private static String itemName(int itemId) {
        return itemNameLookup.name(itemId);
    }

    /** Where the start NPC actually is. {@link #resolveNpcMap} now itself consults the NPC->map
     *  index (current/return map first, then the index), so this is a thin alias kept for the
     *  recommend path's call site. */
    private static int resolveStartNpcMap(Character bot, int npcId) {
        return resolveNpcMap(bot, npcId);
    }

    /** NPC id -> home map id; seam over the world's NPC placement. Production consults the
     *  {@link BotSpawnIndex} NPC->map table (built from Map.wz life nodes); -1 when the NPC isn't
     *  placed on any indexed field. Tests stub it for cross-region cases. */
    interface NpcMapLookup {
        int mapOf(int npcId);
    }

    static NpcMapLookup npcMapLookup = npcId -> {
        java.util.List<Integer> maps = BotSpawnIndex.mapsWithNpc(npcId);
        return maps.isEmpty() ? -1 : maps.get(0);
    };

    // ---- auto-suggest (Feature A, supervised only) -------------------------------------------

    /** Multi-minute cooldown between unprompted quest suggestions (per bot). */
    static final long AUTO_SUGGEST_COOLDOWN_MS = 5L * 60_000L;
    /** How long a suggested (or implicitly declined) quest id stays suppressed before it could be
     *  surfaced again. The owner ignored it once; don't nag. */
    static final long SUGGESTED_QUEST_TTL_MS = 30L * 60_000L;

    /** True when the bot is in SUPERVISED mode: owner online and the bot is following them (not
     *  off on autopilot). Mirrors the owner-perks rule - a supervised bot stays at the owner's
     *  side, so it only suggests quests, never runs off to do them. */
    static boolean isSupervised(BotEntry entry) {
        return entry != null
                && !BotAutopilotManager.isActive(entry)
                && entry.owner != null
                && entry.owner.isLoggedinWorld()
                && entry.isFollowing();
    }

    /**
     * Unprompted, LOW-frequency quest suggestion for a supervised bot: fire at most once per
     * {@link #AUTO_SUGGEST_COOLDOWN_MS}, at most once per map, and ONLY when a quest is clearly
     * worth it ({@link BotQuestScorer#AUTO_SUGGEST_MIN_SCORE} multiple of the grind baseline) AND
     * close by ({@link BotQuestScorer#AUTO_SUGGEST_MAX_HOPS} hops). Never repeats a suggested or
     * stale-tracked id. One ASCII line through the rate-limited reply path.
     */
    static void maybeAutoSuggest(BotEntry entry, Character bot) {
        if (bot == null || !isSupervised(entry)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < entry.nextQuestSuggestAtMs) {
            return;
        }
        int mapId = bot.getMapId();
        if (mapId == entry.lastQuestSuggestMapId) {
            return; // already considered this map (per-map rate limit)
        }
        // Only suggest while genuinely grinding: a baseline of ~0 (standing in a town with the
        // owner) would floor the scorer's denominator and make every quest look great. Auto-suggest
        // is for "you're already killing these mobs, this quest piggybacks" - not town idling.
        if (grindExpBaseline.expPerMinute(entry, bot) <= 0.0) {
            return;
        }
        purgeStaleSuggestions(entry, now);

        BotQuestIndex.QuestMeta pick = pickAutoSuggest(entry, bot, mapId, now);
        if (pick == null) {
            // Mark the map as considered so we don't re-scan it every cadence; the cooldown still
            // governs cross-map suggestions.
            entry.lastQuestSuggestMapId = mapId;
            return;
        }
        entry.suggestedQuestExpiry.put(pick.id(), now + SUGGESTED_QUEST_TTL_MS);
        entry.lastQuestSuggestMapId = mapId;
        entry.nextQuestSuggestAtMs = now + AUTO_SUGGEST_COOLDOWN_MS;

        int npcMap = resolveStartNpcMap(bot, pick.startNpc());
        reply.accept(entry, "btw there's a good quest nearby: "
                + describeRecommendation(new Recommendation(pick, 0, npcMap)));
    }

    /** Best quest that clears the HIGH auto-suggest bar (strong score multiple, <=2 hops) and is
     *  not already suggested/stale. Null when nothing stands out. */
    private static BotQuestIndex.QuestMeta pickAutoSuggest(BotEntry entry, Character bot,
                                                           int grindMap, long now) {
        BotQuestIndex.QuestMeta best = null;
        double bestScore = BotQuestScorer.AUTO_SUGGEST_MIN_SCORE;
        for (BotQuestIndex.QuestMeta q : BotQuestIndex.get().byId().values()) {
            Long expiry = entry.suggestedQuestExpiry.get(q.id());
            if (expiry != null && expiry > now) {
                continue; // already suggested / declined and still suppressed
            }
            if (gate.isStarted(bot, q.id()) || gate.isCompleted(bot, q.id())) {
                continue;
            }
            if (!gate.canStart(bot, q.id(), q.startNpc())) {
                continue;
            }
            int npcMap = resolveStartNpcMap(bot, q.startNpc());
            if (npcMap == -1 || hopCount.hops(grindMap, npcMap) > BotQuestScorer.AUTO_SUGGEST_MAX_HOPS) {
                continue;
            }
            double score = scoreQuest(entry, bot, grindMap, npcMap, q);
            if (score > bestScore) {
                bestScore = score;
                best = q;
            }
        }
        return best;
    }

    private static void purgeStaleSuggestions(BotEntry entry, long now) {
        entry.suggestedQuestExpiry.entrySet().removeIf(e -> e.getValue() <= now);
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
