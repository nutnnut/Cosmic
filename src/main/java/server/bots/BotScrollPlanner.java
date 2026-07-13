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
 * {@code slotsRemaining}, the boolean signals, and the two value lenses ({@code value} for the
 * profit pass, {@code combatValue} for the combat pass — see {@link EquipCandidate}). That keeps
 * the value math unit testable in isolation (see BotScrollPlannerTest).
 *
 * <h2>Decision rule — bounded stochastic DP</h2>
 * The value of an equip at state {@code (v, s)} (stat-score {@code v}, {@code s} free upgrade slots)
 * is computed by backward induction under the active lens:
 * <pre>
 *   valueOf(v, s) = max over { stop, and each owned scroll a } of
 *       stop:    lens(v)
 *       apply a: -cost_a
 *                + p_a              * valueOf(v + statGain_a, s-1)   // success
 *                + (1-p_a)(1-boom_a)* valueOf(v,             s-1)   // fail: slot gone, stat same
 *                + (1-p_a) boom_a   * destroyedValue                 // boom (combat: what's still worn; profit: 0)
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
     *   <li>{@code currentStatScore} — the equip's current stat-score (input to the value lenses).</li>
     *   <li>{@code slotsRemaining} — free upgrade slots, the DP horizon for this equip.</li>
     *   <li>{@code totalSlots} — the item's full upgrade-slot count (catalog tuc); with
     *       {@code slotsRemaining} it gives slots-consumed, the exponent for the profit-pass decay.</li>
     *   <li>{@code betterItemAvailable} — enough strictly-better same-slot items already exist to fill
     *       the slot, so this one is about to be benched: don't invest combat scrolls in it (the
     *       profit pass still may, since a benched piece can be scrolled to sell).</li>
     *   <li>{@code hasFallbackForSlot} — another usable equip for this slot exists, so a boom is
     *       recoverable rather than catastrophic (gate for destroy-capable scrolls).</li>
     *   <li>{@code value} — the PROFIT/market lens: this item's own reproduction value at a given
     *       stat-score, WITHOUT the slot-usage decay (the profit pass applies {@link #SLOT_DECAY}).</li>
     *   <li>{@code combatValue} — the COMBAT lens: what the bot's slot is worth when this piece ends
     *       at a given score. The wiring bakes in both the slot-substitution curve (combat usefulness
     *       = cheapest substitute providing the stats, so a rare base doesn't inflate itself) and the
     *       worn rival (a bag spare's terminal value is {@code slotCurve(max(v, wornScore))} — the bot
     *       wears the better of the two). "Must beat the worn item" is therefore priced by the value
     *       function itself: hopeless catch-ups are pure cost, cheap lotteries with convex upside
     *       stay alive. No separate floor or domination gate.</li>
     *   <li>{@code destroyedValue} — combat-lens value when this piece BOOMS: a bag spare's boom
     *       keeps the worn rival ({@code slotCurve(wornScore)}); a worn piece's boom is 0 (fallback
     *       quality unmodeled — the {@code hasFallbackForSlot} gate still guards it).</li>
     * </ul>
     */
    record EquipCandidate(int equipItemId,
                          String equipName,
                          double currentStatScore,
                          int slotsRemaining,
                          int totalSlots,
                          boolean betterItemAvailable,
                          boolean hasFallbackForSlot,
                          List<ScrollOption> options,
                          DoubleUnaryOperator value,
                          DoubleUnaryOperator combatValue,
                          double destroyedValue) {}

    /** The single best scroll play to propose, with the ASCII chat line for owner confirmation. */
    record ScrollPlan(EquipCandidate equip,
                      ScrollOption scroll,
                      double expectedValue,    // value gained over doing nothing (the combat lens already prices keeping the worn rival)
                      double achievableValue,  // expected value of the chosen play itself (EV at current state)
                      boolean profitDriven,    // false = self-combat upgrade; true = scroll-to-sell (decayed)
                      boolean usesBoomCapableScroll,
                      String proposal) {}

    /** Profit-pass market decay: each <em>consumed</em> upgrade slot discounts an item's resale value
     *  by this factor. A scrolled item is worth less than its pristine potential, so the profit pass is
     *  intrinsically loss-leaning and only fires when a price premium beats the decay + scroll cost.
     *  The combat pass does NOT apply it (the bot upgrades its own gear on raw expected stat). */
    private static final double SLOT_DECAY = 0.9;

    /** Per-scroll opportunity-cost margin. {@link #evApply} already nets out the consumed scroll's
     *  value (cost paid win-or-lose), so a play with {@code improvement > 0} is nominally EV-positive.
     *  But a scroll is reusable on a future better item, so a near-zero net gain isn't worth burning
     *  one: require the net improvement to also clear this fraction of the scroll's own value. Stops
     *  "burned a 60% att scroll on spare gear for a tiny avg->good bump." 0.2 = gain must beat the
     *  scroll cost by 20%. */
    static double SCROLL_OPPORTUNITY_MARGIN = 0.2;

    /** Scroll-to-sell ("profit") pass: scroll benched gear purely to resell at a markup. Disabled until
     *  the bot economy / trading exists to actually sell the scrolled item — otherwise it just burns
     *  scrolls for meso the bot can never realize.
     *  ponytail: flip true once economy/bot-trading lands. */
    static boolean SCROLL_FOR_PROFIT_ENABLED = false;

    /**
     * Best eligible (equip, scroll) play across all candidates, or null if none clear the bar.
     *
     * <p>Two passes, in priority order:
     * <ol>
     *   <li><b>Self-combat</b> — the {@code combatValue} lens, no decay. Keeping the worn rival is
     *       already the lens's floor (a spare's every sub-worn state is worth exactly the worn state),
     *       so a play only shows improvement when it plausibly makes this the better piece to wear.
     *       Skips out-classed gear ({@code betterItemAvailable}).</li>
     *   <li><b>Profit</b> (only if combat found nothing) — the {@code value} lens with decay; the bar
     *       is the piece's own sell-as-is value. Scroll-to-sell stays eligible but the
     *       {@link #SLOT_DECAY} haircut makes it almost always net-negative until a real economy
     *       prices some scroll-ups above their cost.</li>
     * </ol>
     * Value curves are meso reproduction cost — convex above the base; see {@link BotScrollValuer}.
     */
    static ScrollPlan planBest(List<EquipCandidate> candidates) {
        if (candidates == null) {
            return null;
        }
        ScrollPlan combat = bestPlay(candidates, false);
        if (combat != null || !SCROLL_FOR_PROFIT_ENABLED) {
            return combat;
        }
        return bestPlay(candidates, true);
    }

    /** One pass of the search. {@code profit} selects the decayed per-item market lens; else the
     *  combat lens (worn rival baked in). Returns the best EV-positive play or null. */
    private static ScrollPlan bestPlay(List<EquipCandidate> candidates, boolean profit) {
        ScrollPlan best = null;
        for (EquipCandidate eq : candidates) {
            if (eq == null || eq.options() == null || eq.options().isEmpty()
                    || eq.value() == null || eq.combatValue() == null) {
                continue;
            }
            if (eq.slotsRemaining() <= 0) {
                continue;
            }
            // Combat won't invest in soon-benched gear; profit still may (you can scroll it to sell).
            if (!profit && eq.betterItemAvailable()) {
                continue;
            }
            Map<Long, Double> memo = new HashMap<>();
            // Do-nothing value. Combat: the lens already returns "the better of this piece and the
            // worn rival", so this IS the keep-wearing-the-rival floor. Profit: sell this piece as-is.
            double stopNow = lens(eq, eq.currentStatScore(), eq.slotsRemaining(), profit);
            ScrollOption pick = null;
            double pickValue = stopNow;
            for (ScrollOption op : eq.options()) {
                if (op == null || !allowed(eq, op)) {
                    continue;
                }
                double ev = evApply(eq, op, eq.currentStatScore(), eq.slotsRemaining(), profit, memo);
                if (ev > pickValue) {
                    pickValue = ev;
                    pick = op;
                }
            }
            if (pick == null) {
                continue;
            }
            double improvement = pickValue - stopNow;
            // Opportunity-cost floor: the net gain must beat not just zero but a margin of the
            // consumed scroll's own value (it could be saved for a better item / better base).
            if (improvement <= pick.cost() * SCROLL_OPPORTUNITY_MARGIN) {
                continue;
            }
            if (best == null || improvement > best.expectedValue()) {
                best = new ScrollPlan(eq, pick, improvement, pickValue, profit,
                        pick.boomRate() > 0.0, buildProposal(eq, pick));
            }
        }
        return best;
    }

    /** Item value at state {@code (v, s)} under the active lens: the combat lens raw, or the per-item
     *  market value times the slot-usage decay ({@code SLOT_DECAY^slotsConsumed}) in the profit pass. */
    private static double lens(EquipCandidate eq, double v, int s, boolean profit) {
        if (!profit) {
            return eq.combatValue().applyAsDouble(v);
        }
        int consumed = Math.max(0, eq.totalSlots() - s);
        return eq.value().applyAsDouble(v) * Math.pow(SLOT_DECAY, consumed);
    }

    /** Optimal value achievable from state {@code (v, s)} under the recurrence above (memoized). */
    private static double valueOf(EquipCandidate eq, double v, int s, boolean profit, Map<Long, Double> memo) {
        if (s <= 0) {
            return lens(eq, v, 0, profit);
        }
        long key = s * 1_000_003L + Math.round(v * 1000.0);
        Double cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        double best = lens(eq, v, s, profit); // stop: leave the remaining slots unused
        for (ScrollOption op : eq.options()) {
            if (op == null || !allowed(eq, op)) {
                continue;
            }
            double ev = evApply(eq, op, v, s, profit, memo);
            if (ev > best) {
                best = ev;
            }
        }
        memo.put(key, best);
        return best;
    }

    /** Expected value of applying {@code op} at state {@code (v, s)} and then playing optimally. */
    private static double evApply(EquipCandidate eq, ScrollOption op, double v, int s,
                                  boolean profit, Map<Long, Double> memo) {
        double p = clamp01(op.successRate());
        double boom = clamp01(op.boomRate());
        double keepFail = (1.0 - p) * (1.0 - boom);
        double success = p * valueOf(eq, v + op.statGain(), s - 1, profit, memo);
        double fail = keepFail * valueOf(eq, v, s - 1, profit, memo);
        // Boom branch, prob (1-p)*boom: THIS item is gone. Under the combat lens a boomed bag spare
        // still leaves the worn rival on the bot (destroyedValue); for a sellable asset (profit) or
        // the worn piece itself it's a total loss. Cost is paid on every attempt.
        double destroyed = (1.0 - p) * boom * (profit ? 0.0 : eq.destroyedValue());
        return success + fail + destroyed - op.cost();
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
