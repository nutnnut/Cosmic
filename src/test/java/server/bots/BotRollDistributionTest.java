package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact survival + rarity of the two-stage drop roll ({@link BotRollDistribution}). */
class BotRollDistributionTest {

    private static final double EPS = 1e-9;

    // ---- plain-only (godly disabled): a bare uniform over [c-r1, c+r1] ----
    @Test
    void plainUniformSurvivorAndEdges() {
        // base 20, maxRange 5 -> r1 = min(ceil(2.0), 5) = 2 -> uniform {18,19,20,21,22}
        BotRollDistribution.Stat s = BotRollDistribution.Stat.of(20, 5, 0.0, 5, 0);
        assertEquals(18, s.min());
        assertEquals(22, s.max());
        assertEquals(1.0, BotRollDistribution.survivor(s, 18), EPS, "x at/below floor -> 1");
        assertEquals(1.0, BotRollDistribution.survivor(s, 0), EPS, "x below floor -> 1");
        assertEquals(3.0 / 5.0, BotRollDistribution.survivor(s, 20), EPS);
        assertEquals(1.0 / 5.0, BotRollDistribution.survivor(s, 22), EPS);
        assertEquals(0.0, BotRollDistribution.survivor(s, 23), EPS, "x above max -> 0");
    }

    // ---- two-stage mixture with known params: base 2, r1=1, g=0.2, m=5 ----
    @Test
    void twoStageMixtureSurvivorExact() {
        BotRollDistribution.Stat s = BotRollDistribution.Stat.of(2, 5, 0.2, 5, 0);
        assertEquals(1, s.min());        // 2 - 1
        assertEquals(8, s.max());        // 2 + 1 + 5 (godly)
        assertEquals(1.0, BotRollDistribution.survivor(s, 1), EPS, "floor -> 1");
        assertEquals(0.0, BotRollDistribution.survivor(s, 9), EPS, "above godly ceiling -> 0");
        // dex 8 reachable ONLY as plain=3, u=5: P = g * (1/3)*(1/6) = 0.2/18
        assertEquals(0.2 / 18.0, BotRollDistribution.survivor(s, 8), EPS, "top roll is exponentially rare");
        // dex 3: plain P>=3 = 1/3; godly P(plain+u>=3) = 1 - 3/18 = 5/6
        assertEquals(0.8 * (1.0 / 3.0) + 0.2 * (5.0 / 6.0), BotRollDistribution.survivor(s, 3), EPS);
    }

    // ---- the godly gate scales the whole upgraded branch by g ----
    @Test
    void godlyGateFactorScalesTheTail() {
        BotRollDistribution.Stat gated = BotRollDistribution.Stat.of(2, 5, 0.2, 5, 0);
        BotRollDistribution.Stat always = BotRollDistribution.Stat.of(2, 5, 1.0, 5, 0);
        // At a godly-ONLY value (8, unreachable by plain) survival is pure godly branch: gated = 0.2 * always.
        assertEquals(0.2 * BotRollDistribution.survivor(always, 8),
                BotRollDistribution.survivor(gated, 8), EPS);
    }

    // ---- rarity: expected drops rises monotonically and blows up at the ceiling ----
    @Test
    void expectedDropsMonotoneAndExponentialTail() {
        BotRollDistribution.Stat s = BotRollDistribution.Stat.of(2, 5, 0.2, 5, 0); // weight 1 -> surplus == stat points
        double floor = BotRollDistribution.expectedDropsForScoreSurplus(s, 1.0, 0.0);
        double mid = BotRollDistribution.expectedDropsForScoreSurplus(s, 1.0, 1.0);   // dex>=3
        double top = BotRollDistribution.expectedDropsForScoreSurplus(s, 1.0, 6.0);   // dex>=8
        assertEquals(1.0, floor, EPS, "floor roll needs one drop");
        assertTrue(mid > floor && top > mid, "monotone in the roll threshold");
        assertEquals(90.0, top, 1e-6, "top roll ~90 expected drops (1 / (0.2/18))");
        assertTrue(Double.isInfinite(BotRollDistribution.expectedDropsForScoreSurplus(s, 1.0, 7.0)),
                "past the godly ceiling is unobtainable");
    }

    // ---- scale-awareness: each item's spread falls out of its own catalog value / roll range ----
    @Test
    void scaleAwarenessSpreadFromParams() {
        BotRollDistribution.Stat small = BotRollDistribution.Stat.of(2, 5, 0.2, 5, 0);  // r1=1, max 8
        BotRollDistribution.Stat big = BotRollDistribution.Stat.of(20, 5, 0.2, 5, 0);   // r1=2, max 27
        // Different plain granularity at +1 point: 1/3 vs 2/5 (before the shared godly mass).
        assertTrue(BotRollDistribution.survivor(big, 21) > BotRollDistribution.survivor(small, 3),
                "wider plain range keeps a +1 roll commoner on the big-base item");
        // Reachable ceilings differ purely from r1: base-2 tops at 8, base-20 at 27.
        assertEquals(8, small.max());
        assertEquals(27, big.max());
        // The small base's max roll is a huge multiple of its base yet still costs many drops -> a real
        // (not hardcoded) exponential spread emerges from the params alone.
        assertTrue(BotRollDistribution.expectedDropsForScoreSurplus(small, 1.0, 6.0) >= 50.0);
    }
}
