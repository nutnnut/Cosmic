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
    private final java.util.function.BiConsumer<BotEntry, String> prevReply = BotQuestManager.reply;
    private final List<String> replies = new ArrayList<>();

    @AfterEach
    void restore() {
        BotQuestManager.gate = prevGate;
        BotQuestManager.hopCount = prevHops;
        BotQuestManager.reply = prevReply;
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
        @Override public Map<Integer, Integer> currentProgress(Character bot, int questId) { return progress; }
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

    // ---- worthwhile bar ----

    @Test
    void worthwhileRejectsTooManyHops() {
        BotQuestManager.hopCount = (from, to) -> BotQuestManager.MAX_ERRAND_HOPS + 1;
        var q = mobQuest(1019, 2005, 12100, 1000, Map.of(100100, 10), List.of());
        assertFalse(BotQuestManager.worthwhile(100040000, 100000000, q, 10));
    }

    @Test
    void worthwhileRejectsLowRewardForLevel() {
        BotQuestManager.hopCount = (from, to) -> 1;
        // reward 30 exp, bot level 20 -> floor = 20*8 = 160 > 30, rejected.
        var q = mobQuest(1019, 2005, 12100, 30, Map.of(100100, 10), List.of());
        assertFalse(BotQuestManager.worthwhile(100040000, 100000000, q, 20));
    }

    @Test
    void worthwhileAcceptsCloseAndRewarding() {
        BotQuestManager.hopCount = (from, to) -> 1;
        var q = mobQuest(1019, 2005, 12100, 70, Map.of(100100, 10), List.of());
        assertTrue(BotQuestManager.worthwhile(100040000, 100000000, q, 5)); // floor = 40 <= 70
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
