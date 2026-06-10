package server.bots;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure decision core for "where should I grind?": balances <b>exp rate</b> against <b>gear
 * progression</b> across candidate (mob, map) pairs and picks one, with enough numbers attached
 * for the chat layer to explain the choice.
 *
 * <p><b>Model.</b> Each candidate yields a kill cycle (kill time + seek time, where seek shrinks
 * with spawn density), hence exp/h and gear-value/h. Gear value of a drop = expected offense-score
 * gain over the bot's own currently-worn item (stat randomization is the caller's job — it feeds
 * the <em>expected</em> roll, godly mixture included). The two lenses are blended by a dynamic
 * <em>need</em> weight: the bigger and more <em>attainable</em> the best wearable upgrade is, the
 * more the bot "wants gear" — a +30% DPS drop it can expect within a couple of hours fully
 * dominates exp; a 1-in-a-million jackpot does not (attainability discounts it).
 *
 * <p><b>Anti-clogging.</b> With many bots asking the same question, a deterministic argmax sends
 * everyone to the same map. {@link #planBest} samples among near-best candidates (within
 * {@link #NEAR_BEST_FRACTION} of the top score) weighted by score, using the caller's RNG.
 *
 * <p>Pure and unit-tested; the wiring layer (BotGrindAdvisor) supplies candidates from the spawn
 * index, drop data and the bot's combat/equip state.
 */
final class BotGrindPlanner {

    /** Seek seconds at TYPICAL_SPAWN_POINTS, scaled inversely with spawn density (clamped). Matches
     *  the farming-cost anchor (3s overhead) on a typically-populated map. */
    static final double SEEK_BASE_SECONDS = 3.0;
    static final double TYPICAL_SPAWN_POINTS = 8.0;
    static final double SEEK_MIN_SECONDS = 0.5;
    static final double SEEK_MAX_SECONDS = 15.0;

    /** A best attainable upgrade of this DPS fraction (or more) makes gear the dominant need. */
    static final double GEAR_DOMINANT_DPS_GAIN = 0.30;
    /** Horizon for "can I realistically expect the drop": expected copies within this many hours. */
    static final double ATTAINABILITY_HORIZON_HOURS = 2.0;
    /** Candidates scoring within this fraction of the best join the weighted-random draw. */
    static final double NEAR_BEST_FRACTION = 0.85;

    private BotGrindPlanner() {}

    /** A wearable equip drop that would improve the bot's own gear.
     * @param chancePerKill   drop probability per kill (already rate-adjusted by the caller).
     * @param scoreGain       expected offense-score improvement over the currently worn item,
     *                        i.e. E[score of the rolled drop] - score(worn); &gt; 0.
     * @param dpsGainFraction scoreGain relative to the bot's total worn offense score. */
    record GearProspect(int itemId, String itemName, double chancePerKill,
                        double scoreGain, double dpsGainFraction) {}

    /** One (mob, map) grind option. killSeconds is the bot-specific time to kill one mob. */
    record MobCandidate(int mobId, String mobName, int mobLevel, int exp, double killSeconds,
                        int mapId, String mapName, int spawnPoints,
                        List<GearProspect> gearDrops) {}

    /** The chosen option plus the numbers that justify it (for the chat reply). */
    record Recommendation(MobCandidate pick, double killsPerHour, double expPerHour,
                          boolean gearFocused, double needGear,
                          GearProspect wantedGear, double wantedGearPerHour) {}

    /** Seek overhead per kill: sparse maps cost walking time, dense maps barely any. */
    static double seekSeconds(int spawnPoints) {
        double seek = SEEK_BASE_SECONDS * (TYPICAL_SPAWN_POINTS / Math.max(1, spawnPoints));
        return Math.clamp(seek, SEEK_MIN_SECONDS, SEEK_MAX_SECONDS);
    }

    static double killsPerHour(MobCandidate c) {
        return 3600.0 / (Math.max(0.1, c.killSeconds()) + seekSeconds(c.spawnPoints()));
    }

    /** How much the bot should want this prospect: DPS gain discounted by whether the drop is
     *  realistically obtainable within the horizon at this kill rate. */
    static double desirability(GearProspect g, double killsPerHour) {
        double expectedCopies = g.chancePerKill() * killsPerHour * ATTAINABILITY_HORIZON_HOURS;
        return g.dpsGainFraction() * Math.min(1.0, expectedCopies);
    }

    static Recommendation planBest(List<MobCandidate> candidates, Random rng) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // Dynamic need: the best attainable upgrade anywhere sets how gear-hungry the bot is.
        double bestDesire = 0.0;
        for (MobCandidate c : candidates) {
            double kph = killsPerHour(c);
            for (GearProspect g : c.gearDrops()) {
                bestDesire = Math.max(bestDesire, desirability(g, kph));
            }
        }
        double needGear = Math.min(1.0, bestDesire / GEAR_DOMINANT_DPS_GAIN);

        // Score every candidate on both lenses, normalized so the blend is scale-free.
        double maxExpPerHour = 0.0;
        double maxGearPerHour = 0.0;
        double[] expPerHour = new double[candidates.size()];
        double[] gearPerHour = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            MobCandidate c = candidates.get(i);
            double kph = killsPerHour(c);
            expPerHour[i] = c.exp() * kph;
            double gear = 0.0;
            for (GearProspect g : c.gearDrops()) {
                gear += desirability(g, kph) * kph * g.chancePerKill();
            }
            gearPerHour[i] = gear;
            maxExpPerHour = Math.max(maxExpPerHour, expPerHour[i]);
            maxGearPerHour = Math.max(maxGearPerHour, gearPerHour[i]);
        }

        double[] score = new double[candidates.size()];
        double best = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            double exp = maxExpPerHour > 0 ? expPerHour[i] / maxExpPerHour : 0.0;
            double gear = maxGearPerHour > 0 ? gearPerHour[i] / maxGearPerHour : 0.0;
            score[i] = (1.0 - needGear) * exp + needGear * gear;
            best = Math.max(best, score[i]);
        }
        if (best <= 0.0) {
            return null;
        }

        // Weighted-random draw among near-best options so a fleet of bots spreads out.
        List<Integer> nearBest = new ArrayList<>();
        double totalWeight = 0.0;
        for (int i = 0; i < score.length; i++) {
            if (score[i] >= best * NEAR_BEST_FRACTION) {
                nearBest.add(i);
                totalWeight += score[i];
            }
        }
        double roll = rng.nextDouble() * totalWeight;
        int picked = nearBest.get(nearBest.size() - 1);
        for (int idx : nearBest) {
            roll -= score[idx];
            if (roll <= 0) {
                picked = idx;
                break;
            }
        }

        MobCandidate pick = candidates.get(picked);
        double kph = killsPerHour(pick);
        GearProspect wanted = null;
        double wantedDesire = 0.0;
        for (GearProspect g : pick.gearDrops()) {
            double d = desirability(g, kph);
            if (d > wantedDesire) {
                wantedDesire = d;
                wanted = g;
            }
        }
        boolean gearFocused = needGear >= 0.5 && wanted != null;
        double wantedPerHour = wanted != null ? wanted.chancePerKill() * kph : 0.0;
        return new Recommendation(pick, kph, expPerHour[picked], gearFocused, needGear,
                wanted, wantedPerHour);
    }
}
