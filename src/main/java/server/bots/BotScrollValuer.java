package server.bots;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;

/**
 * Reproduction-cost valuation for bot equips, denominated in meso. Produces the convex value curve
 * that {@link BotScrollPlanner} consumes as an equip's {@code value}: how much an item is worth =
 * the cheapest expected meso to <em>reproduce</em> one this good from scratch.
 *
 * <h2>Model</h2>
 * To make an item of a given type reach a weighted stat-score {@code >= target}, you buy a clean base
 * ({@code baseCostMeso} each, with {@code tuc} upgrade slots) and scroll it. A regular scroll consumes
 * a slot win-or-lose, so the only "retry" is at the item level: a base that runs out of slots below
 * the target (or is destroyed) is discarded and a fresh one bought. The cheapest expected meso to
 * reach the target is a small stochastic DP over {@code (slots_remaining, score)} with an explicit
 * abandon-and-rebuy option; {@code D = } the restart value is a 1-D fixed point solved by iteration.
 *
 * <p>The resulting curve is flat at {@code baseCostMeso} for scores at/below the clean base and
 * <b>convex</b> above it (the binomial upper tail under a fixed slot budget makes the last points
 * exponentially dearer). That convexity is what lets the planner snowball an already-strong piece and
 * abandon one a failure has crippled — see docs/bot/economy-design.md §"Validated computational model".
 *
 * <p>v1 scope (owned inventory, no live market): the scroll set and {@code baseCostMeso} are supplied
 * by the wiring layer — scroll costs from {@code shopitems}, the clean-base cost a configurable stub
 * until the rarity→meso / economy ledger lands. This class is pure and unit tested in isolation.
 */
final class BotScrollValuer {

    /** Iterations for the restart fixed point; D converges geometrically so this is comfortably ample
     *  even for high-restart (low success-rate) targets where the contraction rate approaches 1. */
    private static final int RESTART_ITERS = 120;
    /** Fixed-point convergence tolerance, RELATIVE to the value: reproduction costs run from a few
     *  thousand meso (cheap low-level bases) to the millions (scrolled rares), so a flat meso epsilon
     *  either over-iterates the millions or terminates cheap items prematurely. Stop when the restart
     *  value moves less than {@code REL} of itself, floored by {@code FLOOR} meso. At 0.1% of value that
     *  is a few-thousand-meso residual on a multi-million valuation — far below any decision-relevant
     *  precision — and lands in ~10 iterations instead of the ~20 a 1-meso tolerance needed. The 1-meso
     *  floor keeps the fixed point exact on the small synthetic values the unit tests assert, where the
     *  relative term would otherwise over-loosen convergence (residual amplifies with the contraction
     *  rate). Past ~1M the relative term dominates and gives a few-hundred-meso tolerance — negligible. */
    private static final double RESTART_EPSILON_REL = 0.0003;
    private static final double RESTART_EPSILON_FLOOR_MESO = 1.0;

    /** Convergence tolerance for the restart fixed point at a given value (see {@link #RESTART_EPSILON_REL}). */
    private static double convergenceEps(double value) {
        return Math.max(RESTART_EPSILON_FLOOR_MESO, Math.abs(value) * RESTART_EPSILON_REL);
    }

    private BotScrollValuer() {}

    /** A scroll usable in reproduction: success probability, weighted stat-score gain, meso cost. */
    record ScrollSpec(double successRate, double statGain, double mesoCost) {}

    /**
     * Reproduction-cost value curve in meso. {@code value(score)} = cheapest expected meso to produce
     * an item of this type with weighted stat-score {@code >= score}. Lazily memoized per queried score.
     *
     * @param baseScore   the clean base's weighted stat-score (curve floor).
     * @param tuc         the item type's total upgrade slots.
     * @param scrolls     obtainable scrolls applicable to this item (success, gain, meso cost).
     * @param baseCostMeso meso cost to acquire one clean base.
     */
    static DoubleUnaryOperator reproductionValue(double baseScore, int tuc,
                                                 List<ScrollSpec> scrolls, double baseCostMeso) {
        double base = Math.max(0.0, baseCostMeso);
        if (tuc <= 0 || scrolls == null || scrolls.isEmpty()) {
            return score -> base; // can't be improved -> worth exactly its base cost everywhere
        }
        Map<Long, Double> curve = new ConcurrentHashMap<>();
        return score -> {
            // Quantize the queried score to 0.1 — the same resolution the inner DP memoizes at
            // (costFrom keys score at round(a*10)), so it can't distinguish anything finer anyway.
            // The planner samples many reachable stat-scores per candidate at 0.001 (BotScrollPlanner),
            // and each distinct score on a cold curve is a full DP solve (`scroll-dp`); merging them into
            // 0.1 bands collapses ~100x of those near-duplicate solves into memo hits with no
            // decision-relevant precision loss (curve values are sampled at integer stat bands).
            double q = Math.round(score * 10.0) / 10.0;
            return curve.computeIfAbsent(Math.round(q * 10.0),
                    k -> {
                        long t0 = BotPerformanceMonitor.start(); // perf: lazy reproduction-cost DP
                        try {
                            return productionCost(q, baseScore, tuc, scrolls, base);
                        } finally {
                            BotPerformanceMonitor.recordSince("scroll-dp", t0);
                        }
                    });
        };
    }

    /** Cheapest expected meso to produce an item reaching {@code >= target} (restart-on-ruin DP). */
    private static double productionCost(double target, double baseScore, int tuc,
                                         List<ScrollSpec> scrolls, double baseCost) {
        if (target <= baseScore) {
            return baseCost;
        }
        double restart = 0.0;
        for (int iter = 0; iter < RESTART_ITERS; iter++) {
            Map<Long, Double> memo = new HashMap<>();
            double next = costFrom(tuc, baseScore, target, scrolls, baseCost, restart, memo);
            if (Math.abs(next - restart) <= convergenceEps(next)) {
                restart = next;
                break;
            }
            restart = next;
        }
        return baseCost + restart;
    }

    /** One row of the reproduction-cost table (debug/export). */
    record CurveRow(double target,
                    double cost,
                    int firstScroll) {} // index into the scroll list; -1 = abandon+rebuy, -2 = at/below base

    /**
     * Reproduction-cost table sampled on an integer stat-score grid from the clean base up to the max
     * reachable score, each row tagged with the optimal FIRST scroll to throw. For the debug command.
     */
    static List<CurveRow> explain(double baseScore, int tuc, List<ScrollSpec> scrolls, double baseCostMeso) {
        double base = Math.max(0.0, baseCostMeso);
        List<CurveRow> rows = new ArrayList<>();
        if (tuc <= 0 || scrolls == null || scrolls.isEmpty()) {
            rows.add(new CurveRow(baseScore, base, -2));
            return rows;
        }
        double maxGain = 0.0;
        for (ScrollSpec s : scrolls) {
            maxGain = Math.max(maxGain, Math.max(0.0, s.statGain()));
        }
        int lo = (int) Math.ceil(baseScore);
        int hi = (int) Math.ceil(baseScore + tuc * maxGain);
        for (int t = lo; t <= hi; t++) {
            double[] cp = productionCostAndPolicy(t, baseScore, tuc, scrolls, base);
            rows.add(new CurveRow(t, cp[0], (int) cp[1]));
        }
        return rows;
    }

    /** {@code [cost, firstScrollIndex]} for a target — same DP as {@link #productionCost} plus the root move. */
    private static double[] productionCostAndPolicy(double target, double baseScore, int tuc,
                                                    List<ScrollSpec> scrolls, double baseCost) {
        if (target <= baseScore) {
            return new double[]{baseCost, -2};
        }
        double restart = 0.0;
        for (int iter = 0; iter < RESTART_ITERS; iter++) {
            Map<Long, Double> memo = new HashMap<>();
            double next = costFrom(tuc, baseScore, target, scrolls, baseCost, restart, memo);
            if (Math.abs(next - restart) <= convergenceEps(next)) {
                restart = next;
                break;
            }
            restart = next;
        }
        Map<Long, Double> memo = new HashMap<>();
        double best = baseCost + restart; // abandon+rebuy at the root
        int move = -1;
        for (int k = 0; k < scrolls.size(); k++) {
            ScrollSpec sc = scrolls.get(k);
            double p = clamp01(sc.successRate());
            double na = Math.min(baseScore + sc.statGain(), target);
            double v = sc.mesoCost()
                    + p * costFrom(tuc - 1, na, target, scrolls, baseCost, restart, memo)
                    + (1.0 - p) * costFrom(tuc - 1, baseScore, target, scrolls, baseCost, restart, memo);
            if (v < best) {
                best = v;
                move = k;
            }
        }
        return new double[]{baseCost + restart, move};
    }

    /** Expected meso from state {@code (s slots, a score)} to a finished item, given restart value D. */
    private static double costFrom(int s, double a, double target, List<ScrollSpec> scrolls,
                                   double baseCost, double D, Map<Long, Double> memo) {
        if (a >= target) {
            return 0.0;
        }
        if (s <= 0) {
            return baseCost + D; // ruined: rebuy a fresh base and start over
        }
        // Memoize score at 0.1-stat resolution, not 0.001: fractional weighted-stat gains make the
        // reachable-score set combinatorially dense, and at 0.001 nearly every (s,a) is a distinct memo
        // entry — the DP was ~156ms/call. The curve feeds million-meso valuations sampled at integer
        // bands, so 0.1-score merging is far below any decision-relevant precision.
        long key = s * 100000007L + Math.round(a * 10.0);
        Double cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        double best = baseCost + D; // option: abandon this base now and rebuy
        for (ScrollSpec sc : scrolls) {
            double p = clamp01(sc.successRate());
            double na = Math.min(a + sc.statGain(), target);
            double v = sc.mesoCost()
                    + p * costFrom(s - 1, na, target, scrolls, baseCost, D, memo)
                    + (1.0 - p) * costFrom(s - 1, a, target, scrolls, baseCost, D, memo);
            if (v < best) {
                best = v;
            }
        }
        memo.put(key, best);
        return best;
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }
}
