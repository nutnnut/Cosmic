package server.bots;

import java.util.List;

/**
 * Pure decision core for bot self-scrolling. Given a bot's scrollable equips and the scrolls it
 * owns (already translated into {@link EquipCandidate}/{@link ScrollOption} records by the wiring
 * layer), it picks the single best scroll play to propose to the owner — or returns null when no
 * play clears the bar.
 *
 * <p>This class has NO dependency on WZ data, the equip optimizer, or live game state: callers
 * precompute {@code valueGain} (optimizer-score delta on success), {@code currentValue} (the
 * equip's current optimizer score), {@code slotReserve} (the slot's opportunity cost), and the
 * boolean signals. That keeps the value math unit testable in isolation (see BotScrollPlannerTest)
 * and mirrors the seam style used by BotShopManager.
 *
 * <h2>Decision rule</h2>
 * For a regular (non-destroying) scroll, every attempt consumes exactly one upgrade slot whether it
 * succeeds or fails, so the attempts are order-independent: with S slots you get S attempts and the
 * optimal policy is simply to spend each slot on the highest-{@code worth} scroll available. Hence
 * the worth of attempting a scroll is just its success-weighted gain, {@code p * valueGain} — the
 * slot would be spent on this (best) scroll regardless, so a failure carries no extra penalty here.
 * The only real reason NOT to spend a slot now is to keep it open for a better scroll later; that
 * cost is {@link EquipCandidate#slotReserve} (the slot opportunity cost). A play is worth proposing
 * when {@code worth - slotReserve > 0}, and we propose the play maximizing that net.
 *
 * <p>A destroy-capable (boom) scroll additionally carries the expected loss of destroying the item,
 * and is gated behind a recoverable fallback and a strongly-positive worth.
 *
 * <p>With {@code slotReserve == 0} (no forward-looking estimate yet) this reduces to greedy
 * best-worth-first, which is optimal for the bot's currently-owned scrolls. A future increment can
 * feed a real {@code slotReserve} from the economy ledger (expected value of a better future scroll
 * for that slot) — see docs/bot/economy-design.md §2.1.
 */
final class BotScrollPlanner {

    /**
     * How strongly worth must beat the expected boom loss before a destroy-capable scroll is allowed.
     * 1.0 means the success-weighted gain must be at least double the failure-weighted item loss.
     * v1 stand-in for the reproduction-cost comparison in the economy design; tune/derive later.
     */
    private static final double STRONG_EV_MARGIN = 1.0;

    private BotScrollPlanner() {}

    /** One scroll the bot owns and could apply to a given equip. */
    record ScrollOption(int scrollItemId,
                        String scrollName,
                        double successRate,    // 0..1
                        double boomRate,       // 0..1: chance to DESTROY the item on a failed roll
                        double valueGain) {}   // optimizer-score gain on success (job-weighted)

    /**
     * An equip the bot is using that still has at least one free upgrade slot, plus the signals the
     * caller derived from the optimizer:
     * <ul>
     *   <li>{@code currentValue} — the equip's current optimizer score (the stake a boom would lose).</li>
     *   <li>{@code betterItemAvailable} — enough strictly-better same-slot items already exist to fill
     *       the slot, so this one is about to be benched: don't invest scrolls in it.</li>
     *   <li>{@code slotReserve} — the slot's opportunity cost: the value of keeping this upgrade slot
     *       open for a better (current or expected-future) scroll. Spend the slot only when a play
     *       beats this. 0 means no forward-looking reason to save it (greedy-optimal for now).</li>
     *   <li>{@code hasFallbackForSlot} — another usable equip for this slot exists, so a boom is
     *       recoverable rather than catastrophic.</li>
     * </ul>
     */
    record EquipCandidate(int equipItemId,
                          String equipName,
                          double currentValue,
                          boolean betterItemAvailable,
                          double slotReserve,
                          boolean hasFallbackForSlot,
                          List<ScrollOption> options) {}

    /** The single best scroll play to propose, with the ASCII chat line for owner confirmation. */
    record ScrollPlan(EquipCandidate equip,
                      ScrollOption scroll,
                      double expectedValue,
                      boolean usesBoomCapableScroll,
                      String proposal) {}

    /** Best eligible (equip, scroll) play across all candidates, or null if none clear the bar. */
    static ScrollPlan planBest(List<EquipCandidate> candidates) {
        if (candidates == null) {
            return null;
        }
        ScrollPlan best = null;
        for (EquipCandidate eq : candidates) {
            if (eq == null || eq.options() == null || !investable(eq)) {
                continue;
            }
            for (ScrollOption op : eq.options()) {
                if (op == null || !eligible(eq, op)) {
                    continue;
                }
                double net = net(eq, op);
                if (best == null || net > best.expectedValue()) {
                    best = new ScrollPlan(eq, op, net, op.boomRate() > 0.0, buildProposal(eq, op));
                }
            }
        }
        return best;
    }

    /** Conservative gate that applies to the equip regardless of which scroll is considered. */
    private static boolean investable(EquipCandidate eq) {
        return !eq.betterItemAvailable();
    }

    /** Whether this specific scroll play is worth proposing. */
    private static boolean eligible(EquipCandidate eq, ScrollOption op) {
        double worth = worth(eq, op);
        if (worth <= 0.0) {
            return false;
        }
        if (worth - Math.max(0.0, eq.slotReserve()) <= 0.0) {
            return false; // doesn't beat the cost of keeping the slot for something better
        }
        if (op.boomRate() > 0.0) {
            // Destroy-capable scroll: only with a recoverable fallback AND a strongly positive worth.
            if (!eq.hasFallbackForSlot()) {
                return false;
            }
            if (worth < STRONG_EV_MARGIN * boomLoss(eq, op)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Expected immediate value of attempting the scroll. Regular scrolls: the success-weighted gain
     * (the burned-on-failure slot's only real cost is the chance to use it on a better future scroll,
     * accounted for separately via {@link EquipCandidate#slotReserve}). Boom scrolls additionally
     * subtract the expected loss of destroying the item.
     */
    private static double worth(EquipCandidate eq, ScrollOption op) {
        double p = clamp01(op.successRate());
        double gain = p * op.valueGain();
        double boom = clamp01(op.boomRate());
        if (boom <= 0.0) {
            return gain;
        }
        return gain - (1.0 - p) * boom * Math.max(0.0, eq.currentValue());
    }

    /** Net benefit of spending the slot now: worth minus the slot's opportunity cost. */
    private static double net(EquipCandidate eq, ScrollOption op) {
        return worth(eq, op) - Math.max(0.0, eq.slotReserve());
    }

    /** Failure-weighted expected loss from the item being destroyed. */
    private static double boomLoss(EquipCandidate eq, ScrollOption op) {
        double p = clamp01(op.successRate());
        return (1.0 - p) * clamp01(op.boomRate()) * Math.max(0.0, eq.currentValue());
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
