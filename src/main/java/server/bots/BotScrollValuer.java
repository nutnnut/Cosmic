package server.bots;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Map<Long, Double> curve = new HashMap<>();
        return score -> curve.computeIfAbsent(Math.round(score * 1000.0),
                k -> productionCost(score, baseScore, tuc, scrolls, base));
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
            restart = costFrom(tuc, baseScore, target, scrolls, baseCost, restart, memo);
        }
        return baseCost + restart;
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
        long key = s * 100000007L + Math.round(a * 1000.0);
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
