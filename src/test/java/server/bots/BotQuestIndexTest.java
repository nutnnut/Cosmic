package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure filter rules over synthetic {@link BotQuestIndex.QuestMeta} (WZ can't load in unit tests).
 * Mirrors the real Quest.wz shapes seen in Check.img: 1019 (mob-only complete, qualifies),
 * 1018 (item in complete reqs, rejected), 2001 (endscript, rejected).
 */
class BotQuestIndexTest {

    private static BotQuestIndex.QuestMeta meta(int startNpc, int endNpc,
                                                Map<Integer, Integer> mobs, boolean scripted,
                                                List<String> completeKeys) {
        return new BotQuestIndex.QuestMeta(9999, startNpc, endNpc, 0, mobs, 30, List.of(),
                false, false, scripted, completeKeys);
    }

    @Test
    void shouldQualifyMobOnlyCompleteWithBothNpcs() {
        // Quest 1019: complete = npc + mob(100100 x10).
        var q = meta(2005, 12100, Map.of(100100, 10), false, List.of("npc", "mob"));
        assertTrue(BotQuestIndex.qualifies(q));
    }

    @Test
    void shouldRejectScriptedQuest() {
        // Quest 2001: endscript present.
        var q = meta(1020000, 1020000, Map.of(100100, 1), true, List.of("npc", "mob"));
        assertFalse(BotQuestIndex.qualifies(q));
    }

    @Test
    void shouldRejectItemInCompleteReqs() {
        // Quest 1018: complete reqs include an item to hand in.
        var q = meta(2004, 2002, Map.of(9300018, 1), false, List.of("npc", "item", "mob"));
        assertFalse(BotQuestIndex.qualifies(q));
    }

    @Test
    void shouldRejectMoneyOrFameOrPetCompleteReqs() {
        assertFalse(BotQuestIndex.qualifies(
                meta(100, 200, Map.of(1, 1), false, List.of("npc", "mob", "money"))));
        assertFalse(BotQuestIndex.qualifies(
                meta(100, 200, Map.of(1, 1), false, List.of("npc", "mob", "pop"))));
        assertFalse(BotQuestIndex.qualifies(
                meta(100, 200, Map.of(1, 1), false, List.of("npc", "mob", "pet"))));
    }

    @Test
    void shouldAllowRuntimeGatesInCompleteReqs() {
        // lvmin/job/quest gates are re-checked by canComplete, so they don't disqualify.
        var q = meta(100, 200, Map.of(1, 5), false, List.of("npc", "mob", "lvmin", "job", "quest"));
        assertTrue(BotQuestIndex.qualifies(q));
    }

    @Test
    void shouldRejectMissingStartOrEndNpc() {
        assertFalse(BotQuestIndex.qualifies(meta(0, 200, Map.of(1, 1), false, List.of("mob"))));
        assertFalse(BotQuestIndex.qualifies(meta(100, 0, Map.of(1, 1), false, List.of("mob"))));
    }

    @Test
    void shouldRejectNoMobRequirementAsMobQuest() {
        // A no-mob quest is not a MOB quest; qualifiesMob must reject it.
        var q = meta(100, 200, Map.of(), false, List.of("npc"));
        assertFalse(BotQuestIndex.qualifiesMob(q));
    }

    // ---- talk quests (talk NPC A -> talk NPC B, no kills, no fetched items) ----

    private static BotQuestIndex.QuestMeta talkMeta(int startNpc, int endNpc, boolean scripted) {
        return new BotQuestIndex.QuestMeta(9999, startNpc, endNpc, 0, Map.of(), 5, List.of(),
                false, false, scripted, List.of("npc"), true /*talk*/);
    }

    // ---- fetch quests (obtain item -> deliver to NPC; usually a mob drop) ----

    private static BotQuestIndex.QuestMeta fetchMeta(int startNpc, int endNpc,
                                                     Map<Integer, Integer> items, boolean scripted,
                                                     List<String> completeKeys) {
        return new BotQuestIndex.QuestMeta(9999, startNpc, endNpc, 0, Map.of(), 30, List.of(),
                false, false, scripted, completeKeys, false /*talk*/, items);
    }

    @Test
    void shouldQualifyFetchQuestWithDeliveredItem() {
        // complete = npc + item(4000000 x30): obtain 30 of an item, hand in. Not a mob quest.
        var q = fetchMeta(100, 200, Map.of(4000000, 30), false, List.of("npc", "item"));
        assertTrue(BotQuestIndex.qualifies(q));
        assertTrue(BotQuestIndex.qualifiesFetch(q));
        assertFalse(BotQuestIndex.qualifiesMob(q));
    }

    @Test
    void shouldQualifyFetchQuestThatAlsoKillsMobs() {
        // kill + collect: still a fetch shape (item present), gates allowed.
        var q = fetchMeta(100, 200, Map.of(4000000, 5), false, List.of("npc", "mob", "item", "lvmin"));
        assertTrue(BotQuestIndex.qualifiesFetch(q));
    }

    @Test
    void shouldRejectScriptedFetchQuest() {
        var q = fetchMeta(100, 200, Map.of(4000000, 30), true, List.of("npc", "item"));
        assertFalse(BotQuestIndex.qualifies(q));
    }

    @Test
    void shouldRejectFetchQuestWithMoneyOrPopReq() {
        assertFalse(BotQuestIndex.qualifiesFetch(
                fetchMeta(100, 200, Map.of(4000000, 30), false, List.of("npc", "item", "money"))));
        assertFalse(BotQuestIndex.qualifiesFetch(
                fetchMeta(100, 200, Map.of(4000000, 30), false, List.of("npc", "item", "pop"))));
    }

    @Test
    void shouldNotQualifyAsFetchWithoutRequiredItem() {
        var q = fetchMeta(100, 200, Map.of(), false, List.of("npc", "mob"));
        assertFalse(BotQuestIndex.qualifiesFetch(q));
    }

    @Test
    void shouldQualifyTalkQuest() {
        // A talk quest has no mobs but qualifies via the talk flag (e.g. 1031 Heena/Sera).
        assertTrue(BotQuestIndex.qualifies(talkMeta(2101, 2100, false)));
        // Even a scripted talk quest qualifies (scripts don't block start/complete) — e.g. 1021.
        assertTrue(BotQuestIndex.qualifies(talkMeta(2000, 2000, true)));
    }

    /**
     * Smoke test over the real Quest.wz tree (no DB needed) — exercises parseQuestMeta, which the
     * synthetic tests above can't. Ground truth verified by parsing QuestInfo.img directly: 65
     * quests have autoStart==1 AND (autoComplete==1 OR autoPreComplete==1) — the exact set
     * Quest.isAutoStart()/isAutoComplete() flag. (The original scout's "29" was a miscount.)
     * Quest 1019 completes at NPC 12100 by killing Green Snail (100100) x10, start NPC 2005.
     */
    @org.junit.jupiter.api.Test
    void shouldBuildRealIndexFromWz() {
        BotQuestIndex.Index index = BotQuestIndex.get();

        assertTrue(index.byId().size() > 50,
                "expected dozens of runnable mob quests, got " + index.byId().size());

        org.junit.jupiter.api.Assertions.assertEquals(65, index.autoBoth().size(),
                "QuestInfo.img ground truth: 65 quests are both autoStart and autoComplete");

        BotQuestIndex.QuestMeta q1019 = index.byId().get(1019);
        org.junit.jupiter.api.Assertions.assertNotNull(q1019, "1019 is a mob-only turn-in, must qualify");
        org.junit.jupiter.api.Assertions.assertEquals(2005, q1019.startNpc());
        org.junit.jupiter.api.Assertions.assertEquals(12100, q1019.endNpc());
        org.junit.jupiter.api.Assertions.assertEquals(10, q1019.mobs().get(100100));

        // Feature B: the item-req reverse map must be populated (guards the cache-version trap -
        // a warm v1 cache lacking ITEMREQ rows would leave this empty and stale-selling inert).
        assertTrue(index.itemReqs().size() > 50,
                "expected many quest item requirements indexed, got " + index.itemReqs().size());
    }

    /**
     * WZ ground truth for the map-10000 tutorial talk line (verified against Quest.wz Check.img /
     * Act.img on 2026-06-16). 1031 Heena(2101)->Sera(2100), 5 exp; 1021 Roger's Apple(2000->2000),
     * scripted, item gift 2010007; 1032 Nina(2102)->Sen(2001), 7 exp — all pure talk quests and so
     * indexed with talk=true. 1035 Todd's Hunting Method kills Jr. Sentinel 9300018 and hands in item
     * 4031802 — a FETCH quest (obtain mob-drop, deliver), now bot-runnable and indexed with that item.
     */
    @org.junit.jupiter.api.Test
    void shouldIndexTheTutorialTalkQuests() {
        BotQuestIndex.Index index = BotQuestIndex.get();

        BotQuestIndex.QuestMeta q1031 = index.byId().get(1031);
        org.junit.jupiter.api.Assertions.assertNotNull(q1031, "1031 is a pure talk quest, must index");
        assertTrue(q1031.talk(), "1031 must be flagged talk");
        org.junit.jupiter.api.Assertions.assertEquals(2101, q1031.startNpc());
        org.junit.jupiter.api.Assertions.assertEquals(2100, q1031.endNpc());
        assertTrue(q1031.mobs().isEmpty(), "talk quest has no kill reqs");

        BotQuestIndex.QuestMeta q1021 = index.byId().get(1021);
        org.junit.jupiter.api.Assertions.assertNotNull(q1021, "1021 (scripted, count-0 item) must index as talk");
        assertTrue(q1021.talk(), "1021 must be flagged talk");
        assertTrue(q1021.scripted(), "1021 has startscript/endscript");

        BotQuestIndex.QuestMeta q1032 = index.byId().get(1032);
        org.junit.jupiter.api.Assertions.assertNotNull(q1032, "1032 is a pure talk quest, must index");
        assertTrue(q1032.talk());
        org.junit.jupiter.api.Assertions.assertEquals(2102, q1032.startNpc());
        org.junit.jupiter.api.Assertions.assertEquals(2001, q1032.endNpc());

        // 1035 is a FETCH quest (kill Jr. Sentinel, deliver its drop 4031802) — now indexed.
        BotQuestIndex.QuestMeta q1035 = index.byId().get(1035);
        org.junit.jupiter.api.Assertions.assertNotNull(q1035, "1035 (kill+deliver) is a fetch quest, must index");
        assertFalse(q1035.talk(), "1035 is not a pure talk quest");
        org.junit.jupiter.api.Assertions.assertEquals(1, q1035.items().get(4031802),
                "1035 delivers 1x item 4031802");
        assertTrue(BotQuestIndex.qualifiesFetch(q1035), "1035 must qualify on the fetch shape");
    }
}
