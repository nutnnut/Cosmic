package server.bots;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure scoring math for the slice-2 quest advisor (no WZ/DB). Value = reward exp + overlap-mob
 * exp + unique bonus; cost = forgone grind exp during travel + off-map kill time. Score = ratio.
 */
class BotQuestScorerTest {

    private static final BotQuestScorer.MobExp EXP_30 = mobId -> 30;

    @Test
    void overlappingMobKillsAddTheirExpAsValue() {
        // 10 kills of an overlapping mob at 30 exp = 300 free exp on top of the 700 reward.
        // baseline 50 exp/min, 20s travel => cost = (20/60)*50 = 16.67 exp.
        double score = BotQuestScorer.score(
                Map.of(100100, 10), 700, 0.0, Set.of(100100), EXP_30,
                4.0, 20.0, 50.0);
        // value 1000 / cost 16.67 ~= 60
        assertTrue(score > 50 && score < 70, "overlap exp should be counted as value; got " + score);
    }

    @Test
    void nonOverlapMobKillsAreCostNotValue() {
        // Same quest but the mob is NOT on the grind map: its 10 kills are pure cost (4s each),
        // and they add NO value. cost = travel 20s + 40s kills = 60s => (60/60)*50 = 50 exp.
        double score = BotQuestScorer.score(
                Map.of(100100, 10), 700, 0.0, Set.of() /*no overlap*/, EXP_30,
                4.0, 20.0, 50.0);
        // value = 700 (reward only) / 50 = 14
        assertTrue(score > 12 && score < 16, "non-overlap kills are cost-only; got " + score);
        // And it must be strictly lower than the overlap case (overlap adds value AND removes cost).
        double overlap = BotQuestScorer.score(
                Map.of(100100, 10), 700, 0.0, Set.of(100100), EXP_30, 4.0, 20.0, 50.0);
        assertTrue(overlap > score, "overlap must score higher than the same non-overlap quest");
    }

    @Test
    void higherTravelCostLowersScore() {
        double near = BotQuestScorer.score(
                Map.of(100100, 10), 700, 0.0, Set.of(100100), EXP_30, 4.0, 20.0, 50.0);
        double far = BotQuestScorer.score(
                Map.of(100100, 10), 700, 0.0, Set.of(100100), EXP_30, 4.0, 600.0, 50.0);
        assertTrue(far < near, "more travel time => lower score (more forgone grind exp)");
    }

    @Test
    void uniqueRewardBonusRaisesScore() {
        double without = BotQuestScorer.score(
                Map.of(100100, 10), 700, 0.0, Set.of(100100), EXP_30, 4.0, 20.0, 50.0);
        double with = BotQuestScorer.score(
                Map.of(100100, 10), 700, 5000.0, Set.of(100100), EXP_30, 4.0, 20.0, 50.0);
        assertTrue(with > without, "a unique equip reward bonus should raise the score");
    }

    @Test
    void zeroValueScoresZero() {
        // No reward, no overlap exp, no unique bonus => no value => score 0.
        double score = BotQuestScorer.score(
                Map.of(100100, 10), 0, 0.0, Set.of(), EXP_30, 4.0, 20.0, 50.0);
        assertEquals(0.0, score, 1e-9);
    }

    // ---- talk-quest scoring (talk NPC A -> talk NPC B, no kills, no fetched items) ----

    /** A fresh bot's grind exp/min: very low (it's level 1-10, killing little). The Mushroom Town
     *  tutorial NPCs are a short walk apart, so the round trip is on the order of tens of seconds. */
    private static final double FRESH_BASELINE = 8.0;   // ~8 exp/min for a level-1 beginner
    private static final double SHORT_TRIP = 30.0;       // NPC A -> NPC B and back, on/near map 10000

    @Test
    void talkQuestWithZeroRewardStillScoresWorthDoing() {
        // q1021 Roger's Apple awards no exp (the script gifts an item), but completing it advances
        // the tutorial line and costs only a short walk — for a fresh bot it must clear the bar.
        double score = BotQuestScorer.scoreTalk(0 /*rewardExp*/, 0.0, SHORT_TRIP, FRESH_BASELINE);
        assertTrue(score >= BotQuestScorer.RECOMMEND_MIN_SCORE,
                "a zero-exp tutorial talk quest must still be worth doing for a fresh bot; got " + score);
    }

    @Test
    void allFourCalibrationQuestsClearTheBarForAFreshBot() {
        // Verified rewards (wz/Quest.wz Act.img): 1031=5exp, 1021=0exp(item), 1032=7exp,
        // 1035=30exp + a beginner weapon. 1031/1021/1032 are talk quests; 1035 is a 1-kill quest
        // whose required mob is right by the start map (overlap => free). All must be worth doing.
        double s1031 = BotQuestScorer.scoreTalk(5, 0.0, SHORT_TRIP, FRESH_BASELINE);
        double s1021 = BotQuestScorer.scoreTalk(0, 0.0, SHORT_TRIP, FRESH_BASELINE);
        double s1032 = BotQuestScorer.scoreTalk(7, 0.0, SHORT_TRIP, FRESH_BASELINE);
        // 1035: kill 1 Jr. Sentinel (overlap), 30 exp + a low equip reward (~600 offense-equiv exp).
        double s1035 = BotQuestScorer.score(
                Map.of(9300018, 1), 30, 600.0, Set.of(9300018), mobId -> 3,
                BotQuestManager.DEFAULT_OFFMAP_KILL_SECONDS, SHORT_TRIP, FRESH_BASELINE);

        for (double s : new double[]{s1031, s1021, s1032, s1035}) {
            assertTrue(s >= BotQuestScorer.RECOMMEND_MIN_SCORE,
                    "every calibration quest must clear the worth-it bar for a fresh bot; got " + s);
        }
    }

    @Test
    void talkQuestRankingFollowsRewardValue() {
        // Among the talk trio, higher reward exp => higher score (same short trip + fresh baseline),
        // so they sort in a sensible value order (1032:7 > 1031:5 > 1021:0).
        double s1031 = BotQuestScorer.scoreTalk(5, 0.0, SHORT_TRIP, FRESH_BASELINE);
        double s1021 = BotQuestScorer.scoreTalk(0, 0.0, SHORT_TRIP, FRESH_BASELINE);
        double s1032 = BotQuestScorer.scoreTalk(7, 0.0, SHORT_TRIP, FRESH_BASELINE);
        assertTrue(s1032 > s1031, "more reward exp ranks higher; got 1032=" + s1032 + " 1031=" + s1031);
        assertTrue(s1031 > s1021, "more reward exp ranks higher; got 1031=" + s1031 + " 1021=" + s1021);
    }

    @Test
    void talkQuestFadesAgainstARealGrindBaseline() {
        // Once the bot levels and grinds for real exp, a 5-exp tutorial talk quest with a non-trivial
        // detour is no longer worth abandoning the grind for.
        double fresh = BotQuestScorer.scoreTalk(5, 0.0, SHORT_TRIP, FRESH_BASELINE);
        double leveled = BotQuestScorer.scoreTalk(5, 0.0, 120.0 /*2-min detour*/, 40_000.0);
        assertTrue(fresh >= BotQuestScorer.RECOMMEND_MIN_SCORE, "worth it fresh; got " + fresh);
        assertTrue(leveled < BotQuestScorer.RECOMMEND_MIN_SCORE,
                "a real grind baseline should bury a trivial talk quest; got " + leveled);
    }

    @Test
    void talkQuestAtTheNpcIsPureWin() {
        // Zero travel (already standing at the NPC) => no forgone grind => unbounded score.
        double score = BotQuestScorer.scoreTalk(5, 0.0, 0.0, FRESH_BASELINE);
        assertEquals(Double.MAX_VALUE, score, 0.0);
    }
}
