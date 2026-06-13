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
    void shouldRejectNoMobRequirement() {
        var q = meta(100, 200, Map.of(), false, List.of("npc"));
        assertFalse(BotQuestIndex.qualifies(q));
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
    }
}
