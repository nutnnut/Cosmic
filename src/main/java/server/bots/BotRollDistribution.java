package server.bots;

/**
 * Pure model of a clean equip's TWO-STAGE drop-roll distribution for a single stat, and the
 * acquisition-rarity pricing that follows from it. No server dependencies; deterministic; unit-tested
 * in isolation ({@code BotRollDistributionTest}).
 *
 * <h2>The server's two-stage roll</h2>
 * {@code ItemInformationProvider.randomizeStats} rolls a dropped clean piece in two stages:
 * <ol>
 *   <li><b>Plain</b> ({@code getRandStat}): every NONZERO catalog stat rolls uniformly over the integers
 *       in {@code [c - r1, c + r1]}, {@code r1 = min(ceil(c*0.1), maxRange)} ({@code maxRange} 5 for
 *       stats, 10 for wdef/mdef/hp/mp). A zero catalog stat never rolls.</li>
 *   <li><b>Godly</b> ({@code randomizeGodlyStats}, guarded by one Bernoulli gate {@code g =
 *       GODLY_STATS_DROP_CHANCE/100}): with probability {@code g} the plain result is <em>upgraded</em> —
 *       {@code getRandUpgradedStat(plain (+ offset), m)} adds a uniform integer in {@code [0, m]}
 *       ({@code m = maxBonus}). Standard stats use {@code offset = 0}; hp/mp shift the base by
 *       {@code +maxHPMPBonus} first.</li>
 * </ol>
 *
 * So the final value is {@code F = P1 + B*(offset + U)} with {@code P1 ~ Uniform{c-r1..c+r1}},
 * {@code B ~ Bernoulli(g)}, {@code U ~ Uniform{0..m}}, all independent. This is a two-stage MIXTURE,
 * <em>not</em> a normal: its upper tail is exponentially thin (plain tail × {@code g} × godly tail),
 * which is exactly why a max roll is rare and — priced by expected attempts — exponentially expensive.
 */
final class BotRollDistribution {

    /** A single stat's two-stage roll parameters. Build via {@link #of}. */
    record Stat(int catalog, int plainHalfRange, double godlyGate, int godlyBonusMax, int godlyOffset) {

        /** @param maxRange getRandStat's {@code maxRange} (5 for stats, 10 for def/hp/mp). */
        static Stat of(int catalog, int maxRange, double godlyGate, int godlyBonusMax, int godlyOffset) {
            int r1 = (int) Math.min(Math.ceil(Math.abs(catalog) * 0.1), maxRange);
            double g = catalog == 0 ? 0.0 : Math.max(0.0, Math.min(1.0, godlyGate));
            return new Stat(catalog, r1, g, Math.max(0, godlyBonusMax), Math.max(0, godlyOffset));
        }

        /** Lowest achievable value (a floor plain roll). */
        int min() {
            return catalog - plainHalfRange;
        }

        /** Highest achievable value (a max plain roll upgraded to the godly ceiling). */
        int max() {
            return catalog + plainHalfRange + (godlyGate > 0 ? godlyOffset + godlyBonusMax : 0);
        }
    }

    private BotRollDistribution() {}

    /** P(plain roll >= x): the count of integers in {@code [max(x, c-r1), c+r1]} over {@code 2*r1+1}. */
    private static double plainSurvivor(Stat s, int x) {
        int lo = s.catalog() - s.plainHalfRange();
        int hi = s.catalog() + s.plainHalfRange();
        int from = Math.max(x, lo);
        if (from > hi) {
            return 0.0;
        }
        return (hi - from + 1) / (double) (2 * s.plainHalfRange() + 1);
    }

    /**
     * Exact survival function of the two-stage roll: {@code P(final stat >= x)}. Below the floor it is
     * 1, above the godly ceiling 0, and the {@code g} gate scales the whole godly branch — the edge
     * behavior a caller can rely on. For a zero catalog stat (never rolls) it is 1 iff {@code x <= 0}.
     */
    static double survivor(Stat s, int x) {
        if (s.catalog() == 0) {
            return x <= 0 ? 1.0 : 0.0;
        }
        double sp = plainSurvivor(s, x);
        if (s.godlyGate() <= 0) {
            return sp;
        }
        int m = s.godlyBonusMax();
        double pg = 0.0;
        for (int u = 0; u <= m; u++) {
            pg += plainSurvivor(s, x - s.godlyOffset() - u);
        }
        pg /= (m + 1);
        return (1.0 - s.godlyGate()) * sp + s.godlyGate() * pg;
    }

    /**
     * Expected number of clean drops to obtain one whose stat-SCORE surplus reaches
     * {@code scoreThreshold}, i.e. {@code 1 / P(weight*(final - catalog) >= scoreThreshold)}. This is
     * the same expected-attempts logic {@link BotFarmingCostModel#rarityMeso} applies to drop CHANCE,
     * applied here to ROLL rarity: multiply it by the per-drop acquisition cost to price a roll
     * threshold. A non-positive threshold needs no better-than-floor roll (returns 1); a threshold past
     * the godly ceiling is unobtainable (returns {@code +inf}).
     *
     * @param weight         the stat's job-neutral score worth per point (its coefficient in the band score)
     * @param scoreThreshold required score surplus over the clean catalog value
     */
    static double expectedDropsForScoreSurplus(Stat s, double weight, double scoreThreshold) {
        if (scoreThreshold <= 0.0) {
            return 1.0;
        }
        if (weight <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        int x = s.catalog() + (int) Math.ceil(scoreThreshold / weight);
        double p = survivor(s, x);
        return p > 0.0 ? 1.0 / p : Double.POSITIVE_INFINITY;
    }
}
