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
}
