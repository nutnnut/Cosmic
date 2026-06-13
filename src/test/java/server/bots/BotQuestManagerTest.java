package server.bots;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Loop logic over the {@link BotQuestManager} seams (no WZ/DB): auto-quest start/complete gating,
 * the rough worthwhile math, mob-overlap turn-in readiness, and the inventory-space precheck.
 */
class BotQuestManagerTest {

    private final BotQuestManager.QuestGate prevGate = BotQuestManager.gate;
    private final BotQuestManager.HopCount prevHops = BotQuestManager.hopCount;
    private final BotQuestManager.MapMobsLookup prevMobs = BotQuestManager.mapMobs;
    private final BotQuestManager.GrindExpBaseline prevBaseline = BotQuestManager.grindExpBaseline;
    private final BotQuestScorer.MobExp prevMobExp = BotQuestManager.mobExp;
    private final BotQuestManager.TravelSeconds prevTravel = BotQuestManager.travelSeconds;
    private final java.util.function.ToDoubleBiFunction<Character, BotQuestIndex.QuestMeta>
            prevUnique = BotQuestManager.uniqueRewardValue;
    private final BotQuestManager.NameLookup prevNpcName = BotQuestManager.npcName;
    private final BotQuestManager.NameLookup prevMapName = BotQuestManager.mapName;
    private final BotQuestManager.NameLookup prevMobName = BotQuestManager.mobName;
    private final BotQuestManager.NameLookup prevItemName = BotQuestManager.itemNameLookup;
    private final java.util.function.BiConsumer<BotEntry, String> prevReply = BotQuestManager.reply;
    private final List<String> replies = new ArrayList<>();

    {
        // Name seams hit WZ providers; stub them globally so describeRecommendation is WZ-free.
        BotQuestManager.npcName = id -> "NPC" + id;
        BotQuestManager.mapName = id -> "Map" + id;
        BotQuestManager.mobName = id -> "Mob" + id;
        BotQuestManager.itemNameLookup = id -> "Item" + id;
    }

    @AfterEach
    void restore() {
        BotQuestManager.gate = prevGate;
        BotQuestManager.hopCount = prevHops;
        BotQuestManager.mapMobs = prevMobs;
        BotQuestManager.grindExpBaseline = prevBaseline;
        BotQuestManager.mobExp = prevMobExp;
        BotQuestManager.travelSeconds = prevTravel;
        BotQuestManager.uniqueRewardValue = prevUnique;
        BotQuestManager.npcName = prevNpcName;
        BotQuestManager.mapName = prevMapName;
        BotQuestManager.mobName = prevMobName;
        BotQuestManager.itemNameLookup = prevItemName;
        BotQuestManager.reply = prevReply;
    }

    /** Stub the scorer's WZ-backed seams to deterministic values so worthwhile/score is pure. */
    private void stubScoringSeams(double baselineExpPerMin, int perMobExp, double travelSeconds) {
        BotQuestManager.grindExpBaseline = (e, b) -> baselineExpPerMin;
        BotQuestManager.mobExp = mobId -> perMobExp;
        BotQuestManager.travelSeconds = (from, to) -> travelSeconds;
        BotQuestManager.uniqueRewardValue = (b, q) -> 0.0;
    }

    /** Recording gate: tracks which (questId) had start/complete called, gated by canStart/canComplete. */
    private static final class RecordingGate implements BotQuestManager.QuestGate {
        boolean canStart, canComplete, started;
        Map<Integer, Integer> progress = Map.of();
        final List<Integer> startsCalled = new ArrayList<>();
        final List<Integer> completesCalled = new ArrayList<>();

        @Override public boolean canStart(Character bot, int questId, int npc) { return canStart; }
        @Override public boolean canComplete(Character bot, int questId, int npc) { return canComplete; }
        @Override public void start(Character bot, int questId, int npc) { startsCalled.add(questId); }
        @Override public void complete(Character bot, int questId, int npc) { completesCalled.add(questId); }
        @Override public boolean isStarted(Character bot, int questId) { return started; }
        @Override public boolean isCompleted(Character bot, int questId) { return completed; }
        @Override public Map<Integer, Integer> currentProgress(Character bot, int questId) { return progress; }

        boolean completed;
    }

    /** Gate that only lets ONE quest id pass canStart (others are not startable) - isolates
     *  index-wide auto-suggest/recommend tests to a single deterministic candidate. */
    private static final class SingleStartableGate implements BotQuestManager.QuestGate {
        private final int allowedId;
        java.util.Set<Integer> completedIds = java.util.Set.of();
        SingleStartableGate(int allowedId) { this.allowedId = allowedId; }
        @Override public boolean canStart(Character bot, int questId, int npc) { return questId == allowedId; }
        @Override public boolean canComplete(Character bot, int questId, int npc) { return false; }
        @Override public void start(Character bot, int questId, int npc) {}
        @Override public void complete(Character bot, int questId, int npc) {}
        @Override public boolean isStarted(Character bot, int questId) { return false; }
        @Override public boolean isCompleted(Character bot, int questId) { return completedIds.contains(questId); }
        @Override public Map<Integer, Integer> currentProgress(Character bot, int questId) { return Map.of(); }
    }

    private BotEntry entry() {
        Character bot = mock(Character.class);
        BotQuestManager.reply = (e, s) -> replies.add(s);
        return new BotEntry(bot, null, null);
    }

    private static BotQuestIndex.QuestMeta mobQuest(int id, int startNpc, int endNpc, int rewardExp,
                                                    Map<Integer, Integer> mobs, List<Integer> rewardItems) {
        return new BotQuestIndex.QuestMeta(id, startNpc, endNpc, 0, mobs, rewardExp, rewardItems,
                false, false, false, List.of("npc", "mob"));
    }

    // ---- auto quests: drive start/complete only when the gates pass ----

    @Test
    void autoQuestStartsOnlyWhenCanStart() {
        BotEntry e = entry();
        RecordingGate g = new RecordingGate();
        g.canStart = false;
        g.canComplete = false;
        BotQuestManager.gate = g;

        BotQuestManager.runAutoQuest(e, e.bot, 9800);

        assertTrue(g.startsCalled.isEmpty(), "must not start when canStart is false");
        assertTrue(g.completesCalled.isEmpty(), "must not complete when canComplete is false");
    }

    @Test
    void autoQuestStartsAndCompletesWhenGatesPass() {
        BotEntry e = entry();
        RecordingGate g = new RecordingGate();
        g.canStart = true;
        g.canComplete = true;
        BotQuestManager.gate = g;

        BotQuestManager.runAutoQuest(e, e.bot, 9800);

        assertEquals(List.of(9800), g.startsCalled);
        assertEquals(List.of(9800), g.completesCalled);
        assertTrue(replies.stream().anyMatch(s -> s.contains("quest done")), "should announce completion");
    }

    // ---- worthwhile bar (slice-2 scorer: value vs grind-exp cost) ----

    @Test
    void worthwhileRejectsTooManyHops() {
        // Even a rich reward is rejected when the NPC is beyond the errand hop cap.
        BotQuestManager.hopCount = (from, to) -> BotQuestManager.MAX_ERRAND_HOPS + 1;
        BotQuestManager.mapMobs = mapId -> Map.of(100100, 10);
        stubScoringSeams(50.0, 100, 30.0);
        var q = mobQuest(1019, 2005, 12100, 100000, Map.of(100100, 10), List.of());
        assertFalse(BotQuestManager.worthwhile(entry(), 100040000, 100000000, q, mock(Character.class)));
    }

    @Test
    void worthwhileRejectsWhenGrindBeatsTheQuest() {
        // High grind baseline, far travel, tiny reward, NO mob overlap -> cost dwarfs value.
        BotQuestManager.hopCount = (from, to) -> 1;
        BotQuestManager.mapMobs = mapId -> Map.of(999999, 1); // bot grinds a different mob
        stubScoringSeams(10000.0 /*exp/min*/, 5, 300.0 /*5 min round trip*/);
        var q = mobQuest(1019, 2005, 12100, 30, Map.of(100100, 10), List.of());
        BotEntry e = entry();
        assertFalse(BotQuestManager.worthwhile(e, 100040000, 100000000, q, e.bot));
    }

    @Test
    void worthwhileAcceptsCloseRewardingWithOverlap() {
        // Cheap travel, the bot already kills the required mob (overlap = free exp), decent reward.
        BotQuestManager.hopCount = (from, to) -> 1;
        BotQuestManager.mapMobs = mapId -> Map.of(100100, 10); // overlaps the quest mob
        stubScoringSeams(50.0 /*exp/min*/, 30 /*per-mob exp*/, 20.0 /*short trip*/);
        var q = mobQuest(1019, 2005, 12100, 700, Map.of(100100, 10), List.of());
        BotEntry e = entry();
        // value = 700 + 10*30 (overlap) = 1000; cost = (20/60)*50 ~= 16.7 exp; score ~60 >> 1.
        assertTrue(BotQuestManager.worthwhile(e, 100040000, 100000000, q, e.bot));
    }

    // ---- auto-suggest (supervised only, high bar, no-repeat) ----

    /** A supervised entry: owner online, bot following, not autopiloting. */
    private BotEntry supervisedEntry(Character bot, int mapId) {
        Character owner = mock(Character.class);
        when(owner.isLoggedinWorld()).thenReturn(true);
        when(bot.getMapId()).thenReturn(mapId);
        BotEntry e = new BotEntry(bot, owner, null);
        e.following = true;
        e.autopilotMapId = -1; // not autopiloting => supervised
        BotQuestManager.reply = (en, s) -> replies.add(s);
        return e;
    }

    @Test
    void autoSuggestFiresForStandoutNearbyQuestWhenSupervised() {
        BotQuestIndex.QuestMeta q1019 = BotQuestIndex.get().byId().get(1019);
        org.junit.jupiter.api.Assertions.assertNotNull(q1019);
        Character bot = mock(Character.class);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        server.life.NPC npc = mock(server.life.NPC.class);
        when(bot.getMap()).thenReturn(map);
        when(map.getNPCById(2005)).thenReturn(npc);
        BotEntry e = supervisedEntry(bot, 104040000);

        BotQuestManager.mapMobs = mapId -> Map.of(100100, 10);  // overlap (free exp)
        BotQuestManager.hopCount = (from, to) -> 1;              // within 2-hop cap
        stubScoringSeams(20.0, 50, 10.0);                        // strong score (>> 3x baseline)
        BotQuestManager.gate = new SingleStartableGate(1019);    // only 1019 is startable

        BotQuestManager.maybeAutoSuggest(e, bot);

        assertTrue(replies.stream().anyMatch(s -> s.contains("good quest")),
                "a standout nearby quest should be suggested to a supervised bot");
        assertTrue(e.suggestedQuestExpiry.containsKey(1019), "suggested id must be tracked");
    }

    @Test
    void autoSuggestSilentForAutopilotBot() {
        Character bot = mock(Character.class);
        BotEntry e = supervisedEntry(bot, 104040000);
        e.autopilotMapId = 104040000; // autopiloting => NOT supervised, does quests itself
        BotQuestManager.mapMobs = mapId -> Map.of(100100, 10);
        BotQuestManager.hopCount = (from, to) -> 1;
        stubScoringSeams(20.0, 50, 10.0);
        BotQuestManager.gate = new SingleStartableGate(1019);

        BotQuestManager.maybeAutoSuggest(e, bot);

        assertTrue(replies.isEmpty(), "an autopilot bot must not auto-suggest (it errands itself)");
    }

    @Test
    void autoSuggestSilentWhenScoreBelowHighBar() {
        Character bot = mock(Character.class);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        server.life.NPC npc = mock(server.life.NPC.class);
        when(bot.getMap()).thenReturn(map);
        when(map.getNPCById(2005)).thenReturn(npc);
        BotEntry e = supervisedEntry(bot, 104040000);
        BotQuestManager.mapMobs = mapId -> Map.of(999999, 1); // no overlap
        BotQuestManager.hopCount = (from, to) -> 1;
        // High baseline + far travel + tiny reward => score below the 3x auto-suggest bar.
        stubScoringSeams(100000.0, 5, 200.0);
        BotQuestManager.gate = new SingleStartableGate(1019);

        BotQuestManager.maybeAutoSuggest(e, bot);

        assertTrue(replies.isEmpty(), "a merely-ok quest must not clear the auto-suggest bar");
    }

    @Test
    void autoSuggestDoesNotRepeatTrackedId() {
        BotQuestIndex.QuestMeta q1019 = BotQuestIndex.get().byId().get(1019);
        org.junit.jupiter.api.Assertions.assertNotNull(q1019);
        Character bot = mock(Character.class);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        server.life.NPC npc = mock(server.life.NPC.class);
        when(bot.getMap()).thenReturn(map);
        when(map.getNPCById(2005)).thenReturn(npc);
        BotEntry e = supervisedEntry(bot, 104040000);
        // 1019 already suggested and still suppressed.
        e.suggestedQuestExpiry.put(1019, System.currentTimeMillis() + BotQuestManager.SUGGESTED_QUEST_TTL_MS);
        BotQuestManager.mapMobs = mapId -> Map.of(100100, 10);
        BotQuestManager.hopCount = (from, to) -> 1;
        stubScoringSeams(20.0, 50, 10.0);
        BotQuestManager.gate = new SingleStartableGate(1019);

        BotQuestManager.maybeAutoSuggest(e, bot);

        assertTrue(replies.stream().noneMatch(s -> s.contains("good quest")),
                "a suppressed (already-suggested) quest id must not be re-suggested");
    }

    // ---- recommend command (low bar, ranked) ----

    @Test
    void recommendReturnsRankedStartableQuests() {
        Character bot = mock(Character.class);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        server.life.NPC npc = mock(server.life.NPC.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(104040000);
        when(map.getNPCById(2005)).thenReturn(npc);

        BotQuestManager.mapMobs = mapId -> Map.of(100100, 10);
        BotQuestManager.hopCount = (from, to) -> 1;
        stubScoringSeams(50.0, 30, 10.0); // net-positive => clears the low recommend bar
        BotQuestManager.gate = new SingleStartableGate(1019);

        BotEntry e = new BotEntry(bot, null, null);
        List<BotQuestManager.Recommendation> recs = BotQuestManager.recommendQuests(e, bot, 3);

        assertFalse(recs.isEmpty(), "a net-positive startable quest should be recommended");
        assertEquals(1019, recs.get(0).quest().id());
        // ranked descending by score
        for (int i = 1; i < recs.size(); i++) {
            assertTrue(recs.get(i - 1).score() >= recs.get(i).score(), "must be ranked by score");
        }
    }

    @Test
    void recommendSkipsCompletedQuests() {
        Character bot = mock(Character.class);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        server.life.NPC npc = mock(server.life.NPC.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(104040000);
        when(map.getNPCById(2005)).thenReturn(npc);
        BotQuestManager.mapMobs = mapId -> Map.of(100100, 10);
        BotQuestManager.hopCount = (from, to) -> 1;
        stubScoringSeams(50.0, 30, 10.0);
        SingleStartableGate g = new SingleStartableGate(1019);
        g.completedIds = java.util.Set.of(1019); // already done
        BotQuestManager.gate = g;

        BotEntry e = new BotEntry(bot, null, null);
        List<BotQuestManager.Recommendation> recs = BotQuestManager.recommendQuests(e, bot, 3);
        assertTrue(recs.stream().noneMatch(r -> r.quest().id() == 1019),
                "a completed quest must not be recommended");
    }

    // ---- turn-in readiness (counts met) ----

    @Test
    void countsMetTrueWhenAllMobKillsReached() {
        BotEntry e = entry();
        RecordingGate g = new RecordingGate();
        g.progress = Map.of(100100, 10, 100101, 5);
        BotQuestManager.gate = g;
        var q = mobQuest(1016, 12100, 12100, 70, Map.of(100100, 5, 100101, 5), List.of());
        assertTrue(BotQuestManager.countsMet(e.bot, q));
    }

    @Test
    void countsMetFalseWhenAnyMobShort() {
        BotEntry e = entry();
        RecordingGate g = new RecordingGate();
        g.progress = Map.of(100100, 3, 100101, 5);
        BotQuestManager.gate = g;
        var q = mobQuest(1016, 12100, 12100, 70, Map.of(100100, 5, 100101, 5), List.of());
        assertFalse(BotQuestManager.countsMet(e.bot, q));
    }

    // ---- supervised bots don't errand: piggyback only runs under active autopilot ----

    @Test
    void scanDoesNotErrandWhenNotAutopiloting() {
        boolean prevAuto = BotManager.cfg.AUTO_QUESTS;
        boolean prevPiggy = BotManager.cfg.QUEST_PIGGYBACK;
        BotManager.cfg.AUTO_QUESTS = false;      // skip the WZ-loading auto path
        BotManager.cfg.QUEST_PIGGYBACK = true;
        try {
            BotEntry e = entry();
            e.autopilotMapId = -1;               // not autopiloting => supervised
            e.nextQuestScanAtMs = 0L;            // due now
            BotQuestManager.tickScan(e, e.bot);
            assertEquals(-1, e.questErrandMapId, "a supervised bot must not queue a quest errand");
        } finally {
            BotManager.cfg.AUTO_QUESTS = prevAuto;
            BotManager.cfg.QUEST_PIGGYBACK = prevPiggy;
        }
    }

    // ---- piggyback trigger: mob overlap under active autopilot queues a START errand ----

    @Test
    void piggybackQueuesStartErrandWhenMobsOverlap() {
        boolean prevAuto = BotManager.cfg.AUTO_QUESTS;
        boolean prevPiggy = BotManager.cfg.QUEST_PIGGYBACK;
        BotManager.cfg.AUTO_QUESTS = false; // isolate the piggyback path (skip WZ auto loop)
        BotManager.cfg.QUEST_PIGGYBACK = true;
        try {
            // A real indexed quest to target: 1019 needs Green Snail (100100) and starts at NPC 2005.
            BotQuestIndex.QuestMeta q1019 = BotQuestIndex.get().byId().get(1019);
            org.junit.jupiter.api.Assertions.assertNotNull(q1019, "index must contain 1019");

            Character bot = mock(Character.class);
            server.maps.MapleMap map = mock(server.maps.MapleMap.class);
            server.life.NPC npc = mock(server.life.NPC.class);
            when(bot.getMap()).thenReturn(map);
            when(bot.getMapId()).thenReturn(104040000); // Henesys Hunting Ground (Green Snail spawns)
            when(bot.getLevel()).thenReturn(5);
            when(map.getNPCById(2005)).thenReturn(npc);  // start NPC is on this map
            BotQuestManager.reply = (e, s) -> replies.add(s);

            BotQuestManager.mapMobs = mapId -> Map.of(100100, 10); // bot is killing Green Snail here
            BotQuestManager.hopCount = (from, to) -> 0;            // NPC on the grind map
            stubScoringSeams(50.0, 30, 10.0); // cheap trip + overlap => clearly worthwhile
            RecordingGate g = new RecordingGate();
            g.canStart = true;
            g.started = false;
            BotQuestManager.gate = g;

            BotEntry e = new BotEntry(bot, null, null);
            e.autopilotMapId = 104040000; // active autopilot => independent, may errand
            e.nextQuestScanAtMs = 0L;

            BotQuestManager.tickScan(e, bot);

            assertEquals(104040000, e.questErrandMapId, "overlap + worthwhile must queue a START errand");
            assertEquals(BotQuestManager.Phase.START, e.questErrandPhase);
            assertEquals(2005, e.questErrandNpcId);
        } finally {
            BotManager.cfg.AUTO_QUESTS = prevAuto;
            BotManager.cfg.QUEST_PIGGYBACK = prevPiggy;
            BotQuestManager.mapMobs = prevMobs;
        }
    }

    // ---- errand arrival: within radius of the NPC, start is called and the errand clears ----

    @Test
    void errandArrivalStartsQuestAndClears() {
        Character bot = mock(Character.class);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        server.life.NPC npc = mock(server.life.NPC.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(104040000);
        when(bot.getPosition()).thenReturn(new java.awt.Point(100, 200));
        when(npc.getPosition()).thenReturn(new java.awt.Point(120, 200)); // within 500px
        when(map.getNPCById(2005)).thenReturn(npc);
        BotQuestManager.reply = (e, s) -> replies.add(s);

        RecordingGate g = new RecordingGate();
        g.canStart = true;
        BotQuestManager.gate = g;

        BotEntry e = new BotEntry(bot, null, null);
        e.questErrandMapId = 104040000;
        e.questErrandNpcId = 2005;
        e.questErrandQuestId = 1019;
        e.questErrandPhase = BotQuestManager.Phase.START;
        e.questErrandStartedAtMs = System.currentTimeMillis();

        boolean consumed = BotQuestManager.tickErrand(e, bot, false);

        assertFalse(consumed, "arrival tick is not consumed - grind resumes");
        assertEquals(List.of(1019), g.startsCalled, "start must be called at the NPC");
        assertEquals(-1, e.questErrandMapId, "errand clears after starting");
    }

    @Test
    void errandTimesOutAndClears() {
        Character bot = mock(Character.class);
        BotQuestManager.reply = (e, s) -> replies.add(s);
        BotEntry e = new BotEntry(bot, null, null);
        e.questErrandMapId = 999999999; // unreachable
        e.questErrandNpcId = 2005;
        e.questErrandPhase = BotQuestManager.Phase.START;
        e.questErrandStartedAtMs = System.currentTimeMillis() - BotQuestManager.ERRAND_TIMEOUT_MS - 1;

        boolean consumed = BotQuestManager.tickErrand(e, bot, false);

        assertFalse(consumed);
        assertEquals(-1, e.questErrandMapId, "a stale errand must clear so future piggyback isn't wedged");
    }

    // ---- inventory-space precheck for item rewards ----

    @Test
    void roomForRewardsTrueWhenNoItems() {
        Character bot = mock(Character.class);
        var q = mobQuest(1019, 2005, 12100, 30, Map.of(100100, 10), List.of());
        assertTrue(BotQuestManager.hasRoomForRewards(bot, q));
    }

    @Test
    void roomForRewardsFalseWhenBagFull() {
        Character bot = mock(Character.class);
        when(bot.canHold(anyInt(), anyInt())).thenReturn(false);
        var q = mobQuest(1018, 2004, 2002, 30, Map.of(9300018, 1), List.of(4000142));
        assertFalse(BotQuestManager.hasRoomForRewards(bot, q));
    }

    @Test
    void roomForRewardsTrueWhenBagHasSpace() {
        Character bot = mock(Character.class);
        when(bot.canHold(anyInt(), anyInt())).thenReturn(true);
        var q = mobQuest(1018, 2004, 2002, 30, Map.of(9300018, 1), List.of(4000142));
        assertTrue(BotQuestManager.hasRoomForRewards(bot, q));
    }
}
