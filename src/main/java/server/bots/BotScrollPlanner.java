package server.bots;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Pure decision core for bot self-scrolling. Given a bot's scrollable equips and the scrolls it
 * owns (already translated into {@link EquipCandidate}/{@link ScrollOption} records by the wiring
 * layer), it picks the single best scroll to apply <em>now</em> — or returns null when no play
 * clears the bar. The bot applies that one scroll, then rescans (the wiring re-derives candidates),
 * so the policy is naturally adaptive: a success makes continuing the piece more attractive, a
 * failure lowers its ceiling and the next pass may favour a different piece.
 *
 * <p>This class has NO dependency on WZ data, the equip optimizer, or live game state: callers
 * supply each scroll's {@code statGain} (weighted stat-score added on success) and {@code cost}
 * (the scroll's farm-value, paid win-or-lose), each equip's {@code currentStatScore} and free
 * {@code slotsRemaining}, the boolean signals, and a {@code rawValue} function mapping a stat-score
 * to its value. That keeps the value math unit testable in isolation (see BotScrollPlannerTest).
 *
 * <h2>Decision rule — bounded stochastic DP</h2>
 * The value of an equip at state {@code (v, s)} (stat-score {@code v}, {@code s} free upgrade slots)
 * is computed by backward induction:
 * <pre>
 *   valueOf(v, s) = max over { stop, and each owned scroll a } of
 *       stop:    rawValue(v)
 *       apply a: -cost_a
 *                + p_a              * valueOf(v + statGain_a, s-1)   // success
 *                + (1-p_a)(1-boom_a)* valueOf(v,             s-1)   // fail: slot gone, stat same
 *                + (1-p_a) boom_a   * 0                              // destroyed
 * </pre>
 * The play to propose is the scroll whose {@code apply} branch maximizes {@code valueOf} at the
 * equip's current state, across all candidate equips, provided it beats stopping.
 *
 * <p><b>Why a DP and not greedy?</b> When {@code rawValue} is linear in the stat-score and scrolls
 * are unlimited, this reduces exactly to greedy best-worth-first (spend each slot on the highest
 * {@code p*statGain}) — every attempt deterministically burns one slot, so the attempts are
 * order-independent and the sum of success-weighted gains is all that matters. The DP earns its keep
 * once {@code rawValue} is <em>convex</em> (a rarer, harder-to-reproduce stat-score is worth more
 * than linear): then it is rational to snowball an already-good piece (success compounds future
 * value) and abandon a piece whose ceiling a failure has lowered. See docs/bot/economy-design.md.
 */
final class BotScrollPlanner {

    private BotScrollPlanner() {}

    /** One scroll the bot owns and could apply to a given equip. */
    record ScrollOption(int scrollItemId,
                        String scrollName,
                        double successRate,  // 0..1
                        double boomRate,     // 0..1: chance to DESTROY the item on a failed roll
                        double statGain,     // weighted stat-score added on success
                        double cost) {}      // scroll's farm-value, consumed win or lose

    /**
     * An equip the bot is using that still has at least one free upgrade slot.
     * <ul>
     *   <li>{@code currentStatScore} — the equip's current stat-score (input to {@code rawValue}).</li>
     *   <li>{@code slotsRemaining} — free upgrade slots, the DP horizon for this equip.</li>
     *   <li>{@code betterItemAvailable} — enough strictly-better same-slot items already exist to fill
     *       the slot, so this one is about to be benched: don't invest scrolls in it.</li>
     *   <li>{@code hasFallbackForSlot} — another usable equip for this slot exists, so a boom is
     *       recoverable rather than catastrophic (gate for destroy-capable scrolls).</li>
     * </ul>
     */
    record EquipCandidate(int equipItemId,
                          String equipName,
                          double currentStatScore,
                          int slotsRemaining,
                          boolean betterItemAvailable,
                          boolean hasFallbackForSlot,
                          List<ScrollOption> options,
                          DoubleUnaryOperator value) {}

    /** The single best scroll play to propose, with the ASCII chat line for owner confirmation. */
    record ScrollPlan(EquipCandidate equip,
                      ScrollOption scroll,
                      double expectedValue,   // expected value gained over not scrolling at all
                      boolean usesBoomCapableScroll,
                      String proposal) {}

    /**
     * Best eligible (equip, scroll) play across all candidates, or null if none clear the bar. Each
     * candidate carries its own {@code value} curve (meso reproduction cost — convex above the base);
     * see {@link BotScrollValuer}.
     */
    static ScrollPlan planBest(List<EquipCandidate> candidates) {
        if (candidates == null) {
            return null;
        }
        ScrollPlan best = null;
        for (EquipCandidate eq : candidates) {
            if (eq == null || eq.options() == null || eq.options().isEmpty() || eq.value() == null) {
                continue;
            }
            if (eq.betterItemAvailable() || eq.slotsRemaining() <= 0) {
                continue;
            }
            Map<Long, Double> memo = new HashMap<>();
            double stopNow = eq.value().applyAsDouble(eq.currentStatScore());
            ScrollOption pick = null;
            double pickValue = stopNow;
            for (ScrollOption op : eq.options()) {
                if (op == null || !allowed(eq, op)) {
                    continue;
                }
                double ev = evApply(eq, op, eq.currentStatScore(), eq.slotsRemaining(), memo);
                if (ev > pickValue) {
                    pickValue = ev;
                    pick = op;
                }
            }
            if (pick == null) {
                continue;
            }
            double improvement = pickValue - stopNow;
            if (improvement <= 0.0) {
                continue;
            }
            if (best == null || improvement > best.expectedValue()) {
                best = new ScrollPlan(eq, pick, improvement, pick.boomRate() > 0.0, buildProposal(eq, pick));
            }
        }
        return best;
    }

    /** Optimal value achievable from state {@code (v, s)} under the recurrence above (memoized). */
    private static double valueOf(EquipCandidate eq, double v, int s, Map<Long, Double> memo) {
        if (s <= 0) {
            return eq.value().applyAsDouble(v);
        }
        long key = s * 1_000_003L + Math.round(v * 1000.0);
        Double cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        double best = eq.value().applyAsDouble(v); // stop: leave the remaining slots unused
        for (ScrollOption op : eq.options()) {
            if (op == null || !allowed(eq, op)) {
                continue;
            }
            double ev = evApply(eq, op, v, s, memo);
            if (ev > best) {
                best = ev;
            }
        }
        memo.put(key, best);
        return best;
    }

    /** Expected value of applying {@code op} at state {@code (v, s)} and then playing optimally. */
    private static double evApply(EquipCandidate eq, ScrollOption op, double v, int s,
                                  Map<Long, Double> memo) {
        double p = clamp01(op.successRate());
        double boom = clamp01(op.boomRate());
        double keepFail = (1.0 - p) * (1.0 - boom);
        double success = p * valueOf(eq, v + op.statGain(), s - 1, memo);
        double fail = keepFail * valueOf(eq, v, s - 1, memo);
        // destroy branch, prob (1-p)*boom: item gone, value 0. Cost is paid on every attempt.
        return success + fail - op.cost();
    }

    /**
     * Equip-level gate independent of the value math. A destroy-capable scroll is only ever
     * considered when a fallback exists for the slot; the expected destruction loss is already
     * priced into {@link #evApply}, so EV-positivity carries the rest of the decision.
     */
    private static boolean allowed(EquipCandidate eq, ScrollOption op) {
        return !(op.boomRate() > 0.0 && !eq.hasFallbackForSlot());
    }

    private static String buildProposal(EquipCandidate eq, ScrollOption op) {
        int pct = (int) Math.round(clamp01(op.successRate()) * 100.0);
        StringBuilder sb = new StringBuilder();
        sb.append("want me to use ").append(op.scrollName())
                .append(" on my ").append(eq.equipName())
                .append("? ~").append(pct).append("% success");
        if (op.boomRate() > 0.0) {
            int boomPct = (int) Math.round(clamp01(op.boomRate()) * 100.0);
            sb.append(", ").append(boomPct).append("% boom but i have a backup");
        }
        sb.append(" (yes/no)");
        return sb.toString();
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }
}
