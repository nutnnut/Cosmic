/*
    This file is part of the OdinMS Maple Story Server.
    Bot quest scoring (AI companion feature) - slice 2: the real advisor model.
*/
package server.bots;

import java.util.Map;

/**
 * Slice-2 quest worth model: is a kill-and-turn-in quest worth running, measured in the bot's
 * own grind currency (exp-per-minute on its current map)? This replaces the slice-1 rough
 * "reward exp >= level * floor" heuristic with a value-vs-cost comparison that reuses the same
 * patterns as {@link BotGrindAdvisor} (per-mob exp, kills-per-hour) and {@link BotTravelCost}
 * (world-graph travel seconds).
 *
 * <p><b>Value</b> (exp-equivalent the quest yields):
 * <ul>
 *   <li>the complete-action {@code rewardExp};</li>
 *   <li>plus, for each required mob, {@code killsNeeded * mobExp} — but ONLY when that mob also
 *       spawns on a reachable grind map: those kills are exp the bot would earn anyway, so they
 *       come free with the quest. A required mob that is NOT on any grind map the bot frequents
 *       contributes no value (its kills are pure cost, counted below);</li>
 *   <li>plus a {@code uniqueRewardBonus} (exp-equivalent of a unique equip reward, valued by the
 *       caller through the equip-value SSOT).</li>
 * </ul>
 *
 * <p><b>Cost</b> (exp the bot forgoes to run the quest): the grind exp it would have earned
 * during the time spent NOT grinding — the NPC round trip ({@code travelSeconds}) plus the time
 * to kill any required mobs that do NOT overlap the grind map (overlapping ones cost nothing
 * extra, they're killed while grinding). Time is converted to forgone exp through the bot's own
 * {@code grindExpPerMinute} baseline.
 *
 * <p><b>Score</b> = value / cost (a multiple of the grind baseline). A score of 1.0 means the
 * quest breaks even with grinding; above 1.0 it beats grinding. The command threshold is low
 * (show anything net-positive); auto-suggest is high (only clearly-better, nearby quests).
 *
 * <p>Pure over injected numbers (no WZ/DB) so it unit-tests directly; the production wiring lives
 * in {@link BotQuestManager}'s seams.
 */
final class BotQuestScorer {

    /** "recommend quest" command: rank and show quests scoring at least this (net-positive, low
     *  bar — the owner asked, so even a modest win is worth surfacing). */
    static final double RECOMMEND_MIN_SCORE = 1.0;
    /** Auto-suggest (supervised, unprompted): only fire when the quest beats the grind baseline
     *  by this strong a multiple — a quest worth several minutes of the current grind. */
    static final double AUTO_SUGGEST_MIN_SCORE = 3.0;
    /** Auto-suggest reachability cap: the start NPC must be within this many world-graph hops. */
    static final int AUTO_SUGGEST_MAX_HOPS = 2;

    /** A grind baseline at or below this (exp/min) is treated as "barely grinding" — avoid a
     *  divide-by-tiny that makes every quest look infinitely good. Floors the denominator. */
    private static final double MIN_BASELINE_EXP_PER_MINUTE = 1.0;

    private BotQuestScorer() {}

    /** Per-mob exp lookup (BotGrindAdvisor-style); seam-injected so scoring stays WZ-free. */
    @FunctionalInterface
    interface MobExp {
        int expFor(int mobId);
    }

    /**
     * Score one quest for a bot grinding {@code grindMapId}.
     *
     * @param mobs            required mob id -> kills needed (quest complete reqs)
     * @param rewardExp       complete-action exp reward
     * @param uniqueRewardBonus exp-equivalent of a unique equip reward (0 when none)
     * @param overlapMobs     the subset of {@code mobs} keys that spawn on a reachable grind map
     *                        (their kills come free with grinding; others cost kill time)
     * @param mobExp          per-mob exp
     * @param killSecondsPerMob seconds to kill one mob of any required type (bot-specific, blended)
     * @param travelSeconds   round-trip travel seconds to the start+end NPC
     * @param grindExpPerMinute the bot's actual grind exp/min on its current map (the baseline)
     * @return value / cost, a multiple of the grind baseline (>1 beats grinding); 0 when no value.
     */
    static double score(Map<Integer, Integer> mobs, int rewardExp, double uniqueRewardBonus,
                        java.util.Set<Integer> overlapMobs, MobExp mobExp,
                        double killSecondsPerMob, double travelSeconds, double grindExpPerMinute) {
        double baseline = Math.max(MIN_BASELINE_EXP_PER_MINUTE, grindExpPerMinute);

        // Value: reward exp + overlap-mob kill exp (free during grinding) + unique reward bonus.
        double value = rewardExp + uniqueRewardBonus;
        long nonOverlapKills = 0;
        for (Map.Entry<Integer, Integer> need : mobs.entrySet()) {
            int killsNeeded = need.getValue();
            if (overlapMobs.contains(need.getKey())) {
                value += (double) killsNeeded * mobExp.expFor(need.getKey());
            } else {
                nonOverlapKills += killsNeeded;
            }
        }
        if (value <= 0) {
            return 0.0;
        }

        // Cost: forgone grind exp during the trip + the time killing non-overlap required mobs.
        double costSeconds = travelSeconds + nonOverlapKills * Math.max(0.0, killSecondsPerMob);
        double forgoneExp = (costSeconds / 60.0) * baseline;
        if (forgoneExp <= 0.0) {
            // No cost (already here, all mobs overlap): any positive value is pure win.
            return Double.MAX_VALUE;
        }
        return value / forgoneExp;
    }
}
