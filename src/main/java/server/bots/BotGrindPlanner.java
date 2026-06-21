package server.bots;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntToDoubleFunction;

/**
 * Pure decision core for "where should I grind?": <b>gear progression first</b>, exp as the
 * tiebreaker, across candidate (mob, map) pairs — with enough numbers attached for the chat
 * layer to explain the choice.
 *
 * <p><b>Model.</b> Each candidate yields a kill cycle (kill time + seek time, where seek shrinks
 * with spawn density), hence exp/h and gear-value/h. Gear value of a drop = expected offense-score
 * gain over the bot's own currently-worn item (stat randomization is the caller's job — it feeds
 * the <em>expected</em> roll, godly mixture included), discounted by <em>attainability</em>: a
 * +30% DPS drop the bot can expect within a couple of hours is worth chasing; a 1-in-a-million
 * jackpot is noise. Exp and gear are put on ONE scale (each normalized to the best candidate) and
 * blended by how gear-hungry the bot is ({@code needGear}): no meaningful upgrade anywhere => pure
 * exp; a dominant upgrade => gear-led; in between => a continuous weighted sum, so a marginal drop
 * can't gate out a much higher-exp map (and vice versa). An optional per-map score weight discounts
 * far-away maps (travel-time penalty, see {@code BotTravelCost}).
 *
 * <p><b>Anti-clogging.</b> With many bots asking the same question, a deterministic argmax sends
 * everyone to the same map. {@link #planBest} samples among near-best candidates (within
 * {@link #NEAR_BEST_FRACTION} of the top score) weighted by score, using the caller's RNG.
 *
 * <p>Pure and unit-tested; the wiring layer (BotGrindAdvisor) supplies candidates from the spawn
 * index, drop data and the bot's combat/equip state.
 */
final class BotGrindPlanner {

    /** Seek seconds on a typically-dense map, scaled with the map area each spawn has to
     *  itself (clamped). Matches the farming-cost anchor (3s overhead) at typical density. */
    static final double SEEK_BASE_SECONDS = 3.0;
    static final double TYPICAL_SPAWN_POINTS = 8.0;
    /** px&sup2; of field per spawn point on a comfortably-dense map (~3000x650 / 8). */
    static final double TYPICAL_AREA_PER_SPAWN = 250_000.0;
    static final double SEEK_MIN_SECONDS = 0.5;
    static final double SEEK_MAX_SECONDS = 15.0;
    /** Server respawn cadence (config RESPAWN_INTERVAL = 10s): a map can't sustain more than
     *  spawnPoints kills per cycle, however fast the killing — the supply cap that makes
     *  small/sparse maps poor for parties. */
    static final double RESPAWN_PERIOD_SECONDS = 10.0;

    /** A best attainable upgrade of this DPS fraction (or more) makes gear the dominant need. */
    static final double GEAR_DOMINANT_DPS_GAIN = 0.30;
    /** Horizon for "can I realistically expect the drop": expected copies within this many hours. */
    static final double ATTAINABILITY_HORIZON_HOURS = 2.0;
    /** Candidates scoring within this fraction of the best join the weighted-random draw. */
    static final double NEAR_BEST_FRACTION = 0.85;
    /** A meaningful attainable upgrade (DPS-gain x attainability) anywhere in the pool; below this
     *  the bot is treated as not gear-driven for reporting (the {@code gearFocused} flag). */
    static final double MEANINGFUL_GEAR_DESIRE = 0.02;

    private BotGrindPlanner() {}

    /** A wearable equip drop that would improve the bot's own gear.
     * @param chancePerKill   drop probability per kill (already rate-adjusted by the caller).
     * @param scoreGain       expected offense-score improvement over the currently worn item,
     *                        i.e. E[score of the rolled drop] - score(worn); &gt; 0.
     * @param dpsGainFraction scoreGain relative to the bot's total worn offense score. */
    record GearProspect(int itemId, String itemName, double chancePerKill,
                        double scoreGain, double dpsGainFraction) {}

    /** One map's grind option (mob mix blended by the advisor). killSeconds is the bot-specific
     *  time to kill one mob; mapAreaPx is the playable field size (0 = unknown, density falls
     *  back to spawn count alone). */
    record MobCandidate(int mobId, String mobName, int mobLevel, int exp, double killSeconds,
                        int mapId, String mapName, int spawnPoints, int mapAreaPx,
                        double touchDanger, List<GearProspect> gearDrops) {
        /** With area, no danger (tests, farm pick): touch-danger defaults to 0 (unweighted). */
        MobCandidate(int mobId, String mobName, int mobLevel, int exp, double killSeconds,
                     int mapId, String mapName, int spawnPoints, int mapAreaPx,
                     List<GearProspect> gearDrops) {
            this(mobId, mobName, mobLevel, exp, killSeconds, mapId, mapName, spawnPoints, mapAreaPx,
                    0.0, gearDrops);
        }

        /** Area-less convenience (tests, legacy callers): density from spawn count only, no danger. */
        MobCandidate(int mobId, String mobName, int mobLevel, int exp, double killSeconds,
                     int mapId, String mapName, int spawnPoints, List<GearProspect> gearDrops) {
            this(mobId, mobName, mobLevel, exp, killSeconds, mapId, mapName, spawnPoints, 0,
                    0.0, gearDrops);
        }
    }

    /** The chosen option plus the numbers that justify it (for the chat reply). {@code score}
     *  is the pick's selection-lens value (travel-weighted gear-value/h when gear-first,
     *  exp/h otherwise; expected items/h for farm picks) — same units across decision passes
     *  for the same bot, so callers can compare alternative plans (ferry teaser). */
    record Recommendation(MobCandidate pick, double killsPerHour, double expPerHour,
                          boolean gearFocused, double needGear,
                          GearProspect wantedGear, double wantedGearPerHour, double score) {}

    /** Seek overhead per kill, from real DENSITY when the map's area is known: the field each
     *  spawn has to itself relative to a comfortable map. A big sparse map costs walking time;
     *  a small packed one barely any. Without area data, falls back to spawn count alone. */
    static double seekSeconds(int mapAreaPx, int spawnPoints) {
        double sparseness = mapAreaPx > 0
                ? (mapAreaPx / (double) Math.max(1, spawnPoints)) / TYPICAL_AREA_PER_SPAWN
                : TYPICAL_SPAWN_POINTS / Math.max(1, spawnPoints);
        return Math.clamp(SEEK_BASE_SECONDS * sparseness, SEEK_MIN_SECONDS, SEEK_MAX_SECONDS);
    }

    /** Kill rate = what the bot can do, capped by what the map can SUPPLY: respawn refills at
     *  most spawnPoints per cycle, so a small map starves a fast killer — and a party (whose
     *  members share the spawn points, see {@link #withSpawnShare}) even more so. */
    static double killsPerHour(MobCandidate c) {
        double demand = 3600.0
                / (Math.max(0.1, c.killSeconds()) + seekSeconds(c.mapAreaPx(), c.spawnPoints()));
        double supply = c.spawnPoints() * 3600.0 / RESPAWN_PERIOD_SECONDS;
        return Math.min(demand, supply);
    }

    /** How much the bot should want this prospect: DPS gain discounted by whether the drop is
     *  realistically obtainable within the horizon at this kill rate. */
    static double desirability(GearProspect g, double killsPerHour) {
        double expectedCopies = g.chancePerKill() * killsPerHour * ATTAINABILITY_HORIZON_HOURS;
        return g.dpsGainFraction() * Math.min(1.0, expectedCopies);
    }

    /** Both lenses for one candidate list: raw exp/h (for reporting) plus the travel-weighted
     *  exp/h and gear-value/h used for selection, and the dynamic gear-need. */
    private record Lenses(double[] expPerHour, double[] weightedExp, double[] weightedGear,
                          double bestDesire, double needGear) {
        /** Meaningful attainable gear value anywhere => gear progression drives the pick. */
        boolean gearFirst() {
            return bestDesire >= MEANINGFUL_GEAR_DESIRE;
        }
    }

    private static Lenses computeLenses(List<MobCandidate> candidates, IntToDoubleFunction mapScoreWeight) {
        // Dynamic need: the best attainable upgrade anywhere sets how gear-hungry the bot is.
        double bestDesire = 0.0;
        for (MobCandidate c : candidates) {
            double kph = killsPerHour(c);
            for (GearProspect g : c.gearDrops()) {
                bestDesire = Math.max(bestDesire, desirability(g, kph));
            }
        }
        double needGear = Math.min(1.0, bestDesire / GEAR_DOMINANT_DPS_GAIN);

        double[] expPerHour = new double[candidates.size()];
        double[] weightedExp = new double[candidates.size()];
        double[] weightedGear = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            MobCandidate c = candidates.get(i);
            double kph = killsPerHour(c);
            double weight = mapScoreWeight.applyAsDouble(c.mapId());
            expPerHour[i] = c.exp() * kph;
            weightedExp[i] = expPerHour[i] * weight;
            double gear = 0.0;
            for (GearProspect g : c.gearDrops()) {
                gear += desirability(g, kph) * kph * g.chancePerKill();
            }
            weightedGear[i] = gear * weight;
        }
        return new Lenses(expPerHour, weightedExp, weightedGear, bestDesire, needGear);
    }

    /** Weighted-random index among entries scoring within NEAR_BEST_FRACTION of the best. */
    private static int drawNearBest(double[] score, double best, Random rng) {
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
        return picked;
    }

    static Recommendation planBest(List<MobCandidate> candidates, Random rng) {
        return planBest(candidates, mapId -> 1.0, rng);
    }

    /** Solo pick with crowd dispersion: spawn-share each candidate by 1 + the per-map crowd surcharge
     *  (other bots/players there), so the believed kill rate — and thus the pick — accounts for who is
     *  already on the map. {@code extraCompetitors} returns 0 when alone (then this == the plain pick). */
    static Recommendation planBest(List<MobCandidate> candidates, IntToDoubleFunction mapScoreWeight,
                                   IntToDoubleFunction extraCompetitors, Random rng) {
        return planBest(shareForCrowd(candidates, 1.0, extraCompetitors), mapScoreWeight, rng);
    }

    /** Unified pick: rank candidates by the blended exp+gear score ({@link #unifiedScores}) and draw
     *  among the near-best weighted by score. {@code mapScoreWeight} discounts far maps (travel-time
     *  penalty, BotTravelCost). */
    static Recommendation planBest(List<MobCandidate> candidates, IntToDoubleFunction mapScoreWeight,
                                   Random rng) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Lenses lenses = computeLenses(candidates, mapScoreWeight);
        double[] unified = unifiedScores(lenses);
        double best = max(unified);
        if (best <= 0.0) {
            return null;
        }
        int picked = drawNearBest(unified, best, rng);

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
        boolean gearFocused = lenses.gearFirst() && wanted != null;
        double wantedPerHour = wanted != null ? wanted.chancePerKill() * kph : 0.0;
        double score = lenses.gearFirst() ? lenses.weightedGear()[picked] : lenses.weightedExp()[picked];
        return new Recommendation(pick, kph, lenses.expPerHour()[picked], gearFocused,
                lenses.needGear(), wanted, wantedPerHour, score);
    }

    /** One score per candidate with exp and gear on the SAME [0,1] scale (each divided by the best
     *  candidate's value in that lens), blended by how gear-hungry the bot is ({@code needGear}).
     *  Replaces the old gear-first/exp lexicographic switch — which gated exp out the moment any
     *  meaningful gear existed — so the two trade off continuously: needGear=0 -> pure exp,
     *  needGear=1 -> pure gear, between -> weighted sum. SSOT for both solo and party picks. */
    private static double[] unifiedScores(Lenses lenses) {
        double bestExp = max(lenses.weightedExp());
        double bestGear = max(lenses.weightedGear());
        double need = lenses.needGear();
        double[] out = new double[lenses.weightedExp().length];
        for (int i = 0; i < out.length; i++) {
            double exp = bestExp > 0 ? lenses.weightedExp()[i] / bestExp : 0.0;
            double gear = bestGear > 0 ? lenses.weightedGear()[i] / bestGear : 0.0;
            out[i] = (1.0 - need) * exp + need * gear;
        }
        return out;
    }

    private static double max(double[] values) {
        double best = 0.0;
        for (double v : values) {
            best = Math.max(best, v);
        }
        return best;
    }

    // ---- party planning: one shared map, per-member objectives ----

    /** The shared map plus each member's own best objective there (null = nothing worthwhile
     *  for that member on this map — they tag along to back the party up). */
    record PartyPlan(int mapId, List<Recommendation> perMember) {}

    static PartyPlan planPartyBest(List<List<MobCandidate>> perMember, Random rng) {
        List<IntToDoubleFunction> flat = perMember == null ? List.of()
                : Collections.nCopies(perMember.size(), (IntToDoubleFunction) mapId -> 1.0);
        return planPartyBest(perMember, flat, rng);
    }

    /**
     * Pick ONE map for the whole group: each member's candidates are competition-adjusted
     * (N members share the spawns), scored with the same gear-first model as solo planning
     * (gear primary, exp the tiebreaker — see {@link #partyScores}), and each member's best
     * score per map is summed — so a map where several members gain (one farms its gear drop,
     * others take good exp) beats a map that's optimal for only one. The near-best weighted
     * draw keeps multiple parties from clogging the same spot. {@code mapScoreWeights} is the
     * per-member travel-time penalty (members can start scattered).
     */
    static PartyPlan planPartyBest(List<List<MobCandidate>> perMember,
                                   List<IntToDoubleFunction> mapScoreWeights, Random rng) {
        return planPartyBest(perMember, mapScoreWeights, mapId -> 0.0, rng);
    }

    /** Party pick with crowd dispersion: members share spawns by party size PLUS the per-map crowd
     *  surcharge (bots/players outside the party already there), steering the cohort off contested maps. */
    static PartyPlan planPartyBest(List<List<MobCandidate>> perMember,
                                   List<IntToDoubleFunction> mapScoreWeights,
                                   IntToDoubleFunction extraCompetitors, Random rng) {
        PartyScoring scoring = scorePartyBest(perMember, mapScoreWeights, extraCompetitors, rng);
        return scoring == null ? null : scoring.plan();
    }

    /**
     * The full intermediate of {@link #planPartyBest}: the competition-adjusted candidates, each
     * member's per-candidate {@link #partyScores} value, the summed-best-per-map score, the chosen
     * map and the resulting plan. The "autopilot debug" report renders this so a mis-valuation
     * (a weapon prospect losing to a stat-scroll cape) is visible at a glance; production planning
     * just takes {@link #plan()}. {@code memberScores.get(m)} is parallel to {@code adjusted.get(m)}.
     */
    record PartyScoring(List<List<MobCandidate>> adjusted, List<double[]> memberScores,
                        Map<Integer, Double> scoreByMap, int pickedMapId, PartyPlan plan) {}

    /** Shared core for {@link #planPartyBest} and the autopilot-debug dump: identical numbers,
     *  one implementation. Null when no member has any positive-scoring candidate. */
    static PartyScoring scorePartyBest(List<List<MobCandidate>> perMember,
                                       List<IntToDoubleFunction> mapScoreWeights, Random rng) {
        return scorePartyBest(perMember, mapScoreWeights, mapId -> 0.0, rng);
    }

    /** {@link #scorePartyBest} with crowd dispersion: spawn-share divisor = party size + per-map surcharge. */
    static PartyScoring scorePartyBest(List<List<MobCandidate>> perMember,
                                       List<IntToDoubleFunction> mapScoreWeights,
                                       IntToDoubleFunction extraCompetitors, Random rng) {
        if (perMember == null || perMember.isEmpty()) {
            return null;
        }
        int partySize = perMember.size();
        List<List<MobCandidate>> adjusted = new ArrayList<>(partySize);
        for (List<MobCandidate> candidates : perMember) {
            adjusted.add(shareForCrowd(candidates, partySize, extraCompetitors));
        }

        // Sum each member's best score per map.
        List<double[]> memberScores = new ArrayList<>(partySize);
        Map<Integer, Double> scoreByMap = new HashMap<>();
        for (int m = 0; m < adjusted.size(); m++) {
            List<MobCandidate> candidates = adjusted.get(m);
            if (candidates.isEmpty()) {
                memberScores.add(new double[0]);
                continue;
            }
            double[] score = partyScores(candidates, mapScoreWeights.get(m));
            memberScores.add(score);
            Map<Integer, Double> bestByMap = new HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                bestByMap.merge(candidates.get(i).mapId(), score[i], Math::max);
            }
            for (Map.Entry<Integer, Double> e : bestByMap.entrySet()) {
                scoreByMap.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }
        if (scoreByMap.isEmpty()) {
            return null;
        }

        List<Integer> mapIds = new ArrayList<>(scoreByMap.keySet());
        double[] score = new double[mapIds.size()];
        double best = 0.0;
        for (int i = 0; i < mapIds.size(); i++) {
            score[i] = scoreByMap.get(mapIds.get(i));
            best = Math.max(best, score[i]);
        }
        if (best <= 0.0) {
            return null;
        }
        int pickedMapId = mapIds.get(drawNearBest(score, best, rng));

        List<Recommendation> recs = new ArrayList<>(partySize);
        for (int m = 0; m < adjusted.size(); m++) {
            List<MobCandidate> onMap = new ArrayList<>();
            for (MobCandidate c : adjusted.get(m)) {
                if (c.mapId() == pickedMapId) {
                    onMap.add(c);
                }
            }
            recs.add(planBest(onMap, mapScoreWeights.get(m), rng));
        }
        return new PartyScoring(adjusted, memberScores, scoreByMap, pickedMapId,
                new PartyPlan(pickedMapId, recs));
    }

    /** One comparable number per candidate for the party sum: the same unified exp+gear blend the
     *  solo pick uses ({@link #unifiedScores}), so members rank maps identically before summing. */
    private static double[] partyScores(List<MobCandidate> candidates, IntToDoubleFunction mapScoreWeight) {
        return unifiedScores(computeLenses(candidates, mapScoreWeight));
    }

    /**
     * Farm-item site pick: every candidate carries the target item as its single gear prospect;
     * the score is purely expected items/hour (chance × kill rate). The returned Recommendation
     * is farm-shaped: gearFocused, wantedGear = the item, wantedGearPerHour = items/hour.
     */
    static Recommendation planFarmBest(List<MobCandidate> candidates, Random rng) {
        return planFarmBest(candidates, mapId -> 1.0, rng);
    }

    /** Farm-item pick with crowd dispersion (spawn-shared by 1 + the per-map crowd surcharge). */
    static Recommendation planFarmBest(List<MobCandidate> candidates, IntToDoubleFunction mapScoreWeight,
                                       IntToDoubleFunction extraCompetitors, Random rng) {
        return planFarmBest(shareForCrowd(candidates, 1.0, extraCompetitors), mapScoreWeight, rng);
    }

    /** Like {@link #planFarmBest(List, Random)} with a per-map travel-time score weight. */
    static Recommendation planFarmBest(List<MobCandidate> candidates, IntToDoubleFunction mapScoreWeight,
                                       Random rng) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        double[] score = new double[candidates.size()];
        double best = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            MobCandidate c = candidates.get(i);
            double chance = c.gearDrops().isEmpty() ? 0.0 : c.gearDrops().get(0).chancePerKill();
            score[i] = chance * killsPerHour(c) * mapScoreWeight.applyAsDouble(c.mapId());
            best = Math.max(best, score[i]);
        }
        if (best <= 0.0) {
            return null;
        }
        int picked = drawNearBest(score, best, rng);
        MobCandidate pick = candidates.get(picked);
        double kph = killsPerHour(pick);
        GearProspect want = pick.gearDrops().isEmpty() ? null : pick.gearDrops().get(0);
        // wantedGearPerHour reports the RAW items/hour at the site, not the travel-weighted score.
        double rawPerHour = want != null ? want.chancePerKill() * kph : 0.0;
        return new Recommendation(pick, kph, pick.exp() * kph, true, 1.0, want, rawPerHour,
                score[picked]);
    }

    /** A bot's view of a contested map: the spawn points are shared among all competitors (raising
     *  seek time — same area, fewer free mobs — and cutting the respawn supply cap), the area is not.
     *  {@code competitors} = party size + crowd surcharge (other bots/players already there, weighted),
     *  so a map with strangers on it yields fewer kills/h and the bot is steered toward emptier spots. */
    private static MobCandidate withSpawnShare(MobCandidate c, double competitors) {
        int shared = Math.max(1, (int) Math.round(c.spawnPoints() / Math.max(1.0, competitors)));
        return new MobCandidate(c.mobId(), c.mobName(), c.mobLevel(), c.exp(), c.killSeconds(),
                c.mapId(), c.mapName(), shared, c.mapAreaPx(), c.touchDanger(), c.gearDrops());
    }

    /** Spawn-share every candidate for crowding: divisor = {@code base} (1 solo / party size) plus the
     *  per-map crowd surcharge. Identity when nobody else is around (surcharge 0, base 1). */
    private static List<MobCandidate> shareForCrowd(List<MobCandidate> candidates, double base,
                                                    IntToDoubleFunction extraCompetitors) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        List<MobCandidate> out = new ArrayList<>(candidates.size());
        for (MobCandidate c : candidates) {
            out.add(withSpawnShare(c, base + extraCompetitors.applyAsDouble(c.mapId())));
        }
        return out;
    }
}
