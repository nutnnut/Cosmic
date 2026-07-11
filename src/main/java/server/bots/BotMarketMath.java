package server.bots;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure math core of the living economy (docs/bot/economy.md).
 * No server dependencies; every function is deterministic and unit-testable.
 *
 * One update rule serves both belief owners (a bot's private book and the shared consensus):
 * confidence-weighted, gap-proportional steps - ignorant movers move fast, informed movers are
 * damped. The few numeric constants here are structural bounds (smoothing horizons, noise caps,
 * resource clamps), not price policy: none of them encodes a decision the market should make.
 */
final class BotMarketMath {

    /** Evidence weights by observation source (design sec 4 layer 2). */
    static final double W_TRADE = 1.0;      // own completed trade / stall sale: a clearing price
    static final double W_ASK = 0.4;        // ask physically browsed: bounds from above
    static final double W_SHOUT = 0.15;     // advertisement heard, not a clearing
    static final double W_GOSSIP = 0.15;    // secondhand line from another bot
    static final double W_OUTCOME = 0.3;    // exposed-unsold / sold-fast signal

    /** A fast clearing says the ask was a lower bound; probe one modest step above it. Repeated
     *  fast sales carry more outcome weight, so demand pressure controls the actual move size. */
    static final double FAST_SALE_PROBE_UP = 0.10;

    /** Ignore small censored-outcome deviations around consensus; real clearings remain unbanded. */
    static final double OUTCOME_DEADBAND = 0.05;

    /** Quantity contributes sublinearly: a large stack is stronger supply/demand evidence than one
     *  unit, but bulk consumables must not overwhelm a long clearing history in one observation. */
    static double outcomeQuantityWeight(int quantity) {
        return Math.min(8.0, 1.0 + Math.log(Math.max(1, quantity)) / Math.log(2.0));
    }

    /** Smoothing horizon: half-life = HALF_LIFE_GAPS x the key's median inter-event gap ... */
    static final double HALF_LIFE_GAPS = 3.0;
    /** ... clamped to [floor, cap] so dead keys neither flap nor remember forever. */
    static final long HALF_LIFE_FLOOR_MS = 6L * 60 * 60 * 1000;      // 6h
    static final long HALF_LIFE_CAP_MS = 14L * 24 * 60 * 60 * 1000;  // 14d

    /** Perception noise: max relative band for a fully unplugged bot; informed bots shrink it. */
    static final double NOISE_MAX_BAND = 0.25;

    /** Consensus prior can never outweigh more than this many trades' worth of own experience.
     *  Do NOT raise this to "trust consensus more": once the price belief is fed only realized
     *  clearings (the belief-model fix), a bot's private number and the consensus agree, and this cap
     *  is exactly what preserves per-bot disagreement. Ask formation reads the consensus mass through
     *  {@code BotMarketBook.perceivedConfidence} instead, so it does not need this cap relaxed. */
    static final double CONSENSUS_PRIOR_CAP = 4.0;

    private BotMarketMath() {
    }

    // ------------------------------------------------------------------ belief update / decay

    /** A (estimate, confidence) pair; confidence is in accumulated evidence weight. */
    record Belief(double estimate, double confidence) {
        static final Belief NONE = new Belief(0, 0);

        boolean isEmpty() {
            return confidence <= 0;
        }
    }

    /**
     * Confidence-weighted step toward evidence: step = gap * w / (w + confidence).
     * First observation dominates an empty belief; a well-fed belief barely moves.
     */
    static Belief updateBelief(Belief b, double evidence, double weight) {
        if (weight <= 0) {
            return b;
        }
        if (b.isEmpty()) {
            return new Belief(evidence, weight);
        }
        double step = (evidence - b.estimate) * weight / (weight + b.confidence);
        return new Belief(b.estimate + step, b.confidence + weight);
    }

    /** Multiplicative confidence decay: halves every halfLifeMs of elapsed silence. */
    static Belief decay(Belief b, long elapsedMs, long halfLifeMs) {
        if (b.isEmpty() || elapsedMs <= 0 || halfLifeMs <= 0) {
            return b;
        }
        double factor = Math.pow(0.5, (double) elapsedMs / halfLifeMs);
        return new Belief(b.estimate, b.confidence * factor);
    }

    /**
     * Self-normalizing memory horizon: liquid keys (small median gap) forget fast and track the
     * market; illiquid keys remember longer. medianGapMs <= 0 means "no history yet".
     */
    static long halfLifeMs(long medianGapMs) {
        if (medianGapMs <= 0) {
            return HALF_LIFE_CAP_MS;
        }
        double h = medianGapMs * HALF_LIFE_GAPS;
        return (long) Math.min(HALF_LIFE_CAP_MS, Math.max(HALF_LIFE_FLOOR_MS, h));
    }

    /** Median spacing of an ascending event-timestamp list; 0 when fewer than two events. */
    static long medianInterEventGapMs(List<Long> ascendingTimestamps) {
        int n = ascendingTimestamps.size();
        if (n < 2) {
            return 0;
        }
        List<Long> gaps = new ArrayList<>(n - 1);
        for (int i = 1; i < n; i++) {
            gaps.add(ascendingTimestamps.get(i) - ascendingTimestamps.get(i - 1));
        }
        gaps.sort(Long::compare);
        return gaps.get(gaps.size() / 2);
    }

    // ------------------------------------------------------------------ consensus (layer 1)

    /** One priced market event with a recency/source weight. */
    record PricePoint(double price, double weight) {
    }

    /**
     * Recency-weighted median of a window of clearing prices: the robust target the consensus
     * moves toward. Robustness is the point - one gift-price outlier shifts a median by at most
     * its weight share, never proportionally to how crazy the price is.
     */
    static double weightedMedian(List<PricePoint> points) {
        if (points.isEmpty()) {
            return 0;
        }
        List<PricePoint> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> Double.compare(a.price(), b.price()));
        double total = 0;
        for (PricePoint p : sorted) {
            total += p.weight();
        }
        double acc = 0;
        for (PricePoint p : sorted) {
            acc += p.weight();
            if (acc >= total / 2) {
                return p.price();
            }
        }
        return sorted.get(sorted.size() - 1).price();
    }

    /**
     * Volume-damped consensus move toward the window median - the same updateBelief form with
     * the decayed event volume as the standing confidence. Sparse markets move meaningfully per
     * event; liquid markets glide.
     */
    static Belief moveConsensus(Belief consensus, double windowMedian, double windowWeight) {
        return updateBelief(consensus, windowMedian, windowWeight);
    }

    // ------------------------------------------------------------------ perception (layer 2)

    /**
     * Stable per-(bot, key, day) noise multiplier in [1-band, 1+band],
     * band = NOISE_MAX_BAND * (1 - informedness). Seeded, not rolled: the same bot holds the
     * same slightly-wrong idea all day instead of jittering between reads (design sec 4).
     *
     * @param informedness 0..1, how plugged-in the bot is (derived from social traits upstream)
     */
    static double perceptionNoise(int botId, long priceKey, long dayBucket, double informedness) {
        double band = NOISE_MAX_BAND * (1.0 - clamp01(informedness));
        if (band <= 0) {
            return 1.0;
        }
        double u = hashToUnit(mix(mix(mix(0x9E3779B97F4A7C15L, botId), priceKey), dayBucket));
        return 1.0 + (u * 2.0 - 1.0) * band;
    }

    /**
     * Blend a private belief with the (already noise-sampled) consensus prior by precision:
     * prior weight grows with consensus volume but is capped so real personal experience always
     * comes to dominate hearsay (design sec 4: "trusts its own number").
     */
    static double blendWithConsensus(Belief priv, double sampledConsensus, double consensusVolume) {
        double priorW = Math.min(CONSENSUS_PRIOR_CAP, Math.max(0, consensusVolume));
        if (priorW <= 0) {
            return priv.isEmpty() ? 0 : priv.estimate();
        }
        if (priv.isEmpty()) {
            return sampledConsensus;
        }
        return (priv.estimate() * priv.confidence() + sampledConsensus * priorW)
                / (priv.confidence() + priorW);
    }

    // ------------------------------------------------------------------ structured priors

    /** An observed (x, price) sample with weight; x is a quality band or a stat score. */
    record Sample(double x, double price, double weight) {
    }

    /**
     * Per-item curve calibration: factor = weighted mean of observed price / curve(band) over
     * the item's observed bands. Quoting factor * curve(band) pins the WHOLE line to any traded
     * band, so a 10-STR sale re-prices the 11-STR band along the convex reproduction curve
     * (design sec 4 structured priors, relation 1). Returns <= 0 when nothing usable observed.
     */
    static double curveCalibration(List<Sample> observedBands, java.util.function.DoubleUnaryOperator curve) {
        double num = 0, den = 0;
        for (Sample s : observedBands) {
            double c = curve.applyAsDouble(s.x());
            if (c > 0 && s.price() > 0 && s.weight() > 0) {
                num += s.weight() * (s.price() / c);
                den += s.weight();
            }
        }
        return den > 0 ? num / den : 0;
    }

    /** Quote an unobserved band from a calibrated curve; 0 when the calibration is empty. */
    static double quoteFromCurve(double calibration, java.util.function.DoubleUnaryOperator curve, double band) {
        if (calibration <= 0) {
            return 0;
        }
        double c = curve.applyAsDouble(band);
        return c > 0 ? calibration * c : 0;
    }

    /**
     * Per-slot implied meso per stat-score point from traded comparables: least squares through
     * the origin (beta = sum w*s*p / sum w*s^2). A never-traded item then quotes score * beta -
     * "10-STR hats go 1m, so this 11-STR one is a bit more" (relation 2).
     */
    static double impliedStatPrice(List<Sample> tradedComparables) {
        double num = 0, den = 0;
        for (Sample s : tradedComparables) {
            if (s.x() > 0 && s.price() > 0 && s.weight() > 0) {
                num += s.weight() * s.x() * s.price();
                den += s.weight() * s.x() * s.x();
            }
        }
        return den > 0 ? num / den : 0;
    }

    /**
     * Structural quote ladder (design sec 4): anchor -> comparable-implied -> calibrated-curve,
     * each overlaid by precision so stronger relations dominate weaker ones. Direct per-key
     * evidence is blended on top by the caller (blendWithConsensus / updateBelief).
     * Weights are relative structural strengths, not price policy.
     */
    static double structuralQuote(double anchorQuote,
                                  double comparableQuote,
                                  double curveQuote) {
        Belief q = Belief.NONE;
        if (anchorQuote > 0) {
            q = updateBelief(q, anchorQuote, 0.5);
        }
        if (comparableQuote > 0) {
            q = updateBelief(q, comparableQuote, 1.0);
        }
        if (curveQuote > 0) {
            q = updateBelief(q, curveQuote, 2.0);
        }
        return q.isEmpty() ? 0 : q.estimate();
    }

    /** Quality band for an equip: how many average-scroll-successes above clean it sits. */
    static int qualityBand(double scoreDeltaOverClean, double commonScrollGainScore) {
        if (commonScrollGainScore <= 0 || scoreDeltaOverClean <= 0) {
            return 0;
        }
        long band = Math.round(scoreDeltaOverClean / commonScrollGainScore);
        return (int) Math.max(0, Math.min(255, band));
    }

    /** Price key packing shared with the DB schema (design sec 12): itemId * 256 + band. */
    static long priceKey(int itemId, int qualityBand) {
        return (long) itemId * 256L + Math.max(0, Math.min(255, qualityBand));
    }

    // ------------------------------------------------------------------ pricing behavior

    /**
     * Structural weight of the reproduction-cost anchor as a price prior, in the same evidence units
     * as a belief's confidence (W_TRADE = 1 per own clearing). Small on purpose: a couple of the
     * bot's own clearings outweigh "what it would cost to remake", so a piece that clears cheap stops
     * being asked at remake cost. Reproduction is a fading prior, not a hard floor.
     */
    static final double REPRO_ANCHOR_PRIOR = 1.0;

    /**
     * Opening ask base BEFORE the seller margin: the bot's market belief blended with the
     * reproduction-cost anchor by confidence, floored at salvage. With no belief the anchor stands; as
     * the bot's own clearing evidence accrues the base converges to what the market actually pays -
     * up OR down - so remake cost no longer floors the ask (the {@code max(belief, reproCost)} that
     * pinned illiquid/heavily-scrolled asks billions high). Salvage (NPC sell-back) is the true
     * reservation: a bot never opens below what the counter pays back for the item.
     *
     * @param belief     the bot's perceived market price at this key (0 = no belief)
     * @param confidence accumulated evidence weight behind {@code belief}
     * @param reproAnchor sanity-capped reproduction-cost quote (see {@link #reproSanityCap})
     * @param salvageFloor NPC sell-back unit price - the hard reservation
     */
    static double askBase(double belief, double confidence, double reproAnchor, double salvageFloor) {
        double anchor = Math.max(0, reproAnchor);
        double base;
        if (anchor <= 0) {
            base = Math.max(0, belief);
        } else if (belief > 0 && confidence > 0) {
            base = (belief * confidence + anchor * REPRO_ANCHOR_PRIOR) / (confidence + REPRO_ANCHOR_PRIOR);
        } else {
            base = anchor; // no confident belief yet - the reproduction prior stands
        }
        return Math.max(base, Math.max(0, salvageFloor));
    }

    /**
     * How many times an item's NPC salvage value the reproduction-cost anchor may reach before it is
     * treated as noise. The reproduction DP can imply a remake cost of billions for a never-cleared or
     * heavily-scrolled piece that nothing ever pays; capping the anchor at a generous multiple of the
     * item's salvage (a monotone tier proxy) keeps an unproven ask plausible. Real market evidence
     * still lifts the ask above this once the item actually clears - the cap only bounds the no-belief
     * prior. Generous enough never to bind on legitimately valued gear (nothing observed clears within
     * three orders of magnitude of the 2.1b cap it removes); tune with post-fix clearing data.
     */
    static final long REPRO_CAP_OVER_SALVAGE = 1000;

    /** Sanity ceiling for a reproduction-cost anchor, tied to the item's NPC salvage. */
    static double reproSanityCap(long salvageUnit) {
        return Math.max(1, salvageUnit) * (double) REPRO_CAP_OVER_SALVAGE;
    }

    /**
     * Seller opening margin over the perceived price: firmer stance asks more; low confidence
     * widens the margin (room to learn downward without selling below the market).
     */
    static double openingMargin(double haggleStance, double confidence) {
        double stance = 0.05 + 0.30 * clamp01(haggleStance);
        return stance * (1.0 + 1.0 / (1.0 + Math.max(0, confidence)));
    }

    /**
     * Gap-proportional, confidence-damped, pressure-scaled reprice of an unsold ask toward the
     * best evidence (competing ask / recent clearing). pressure 0..1+ grows with time-on-shelf.
     * Never crosses the seller's reservation.
     */
    static double repriceAsk(double currentAsk, double evidence, double confidence,
                             double pressure, double reservation) {
        if (evidence <= 0 || currentAsk <= 0) {
            return Math.max(currentAsk, reservation);
        }
        double step = (evidence - currentAsk) * Math.min(1.0, Math.max(0, pressure)) / (1.0 + Math.max(0, confidence));
        return Math.max(reservation, currentAsk + step);
    }

    /**
     * Reprice evidence when competing for the next sale: just under the best visible competing
     * ask (you must cross below it to win the buyer), but never chasing above your own perceived
     * value (a silly competitor doesn't drag you up). With no visible competition, evidence is
     * simply your perception. Reservation still floors the actual reprice step.
     */
    static double undercutTarget(double perceived, double bestCompetingAsk, double undercutFraction) {
        if (bestCompetingAsk <= 0) {
            return perceived;
        }
        double under = bestCompetingAsk * (1.0 - Math.max(0, undercutFraction));
        return perceived > 0 ? Math.min(perceived, under) : under;
    }

    /**
     * Counter-offer: concede from the current ask toward the best acceptable floor by the
     * trait-scaled concession fraction. firmness 1 = barely moves, 0 = meets the offer.
     */
    static double counterPrice(double currentAsk, double partnerOffer, double reservation,
                               double firmness) {
        double floor = Math.max(partnerOffer, reservation);
        if (floor >= currentAsk) {
            return currentAsk;
        }
        double concede = (currentAsk - floor) * (1.0 - clamp01(firmness));
        return Math.max(reservation, currentAsk - concede);
    }

    /** Accept when the offer clears reservation plus the trait-scaled slack of the asker. */
    static boolean acceptable(double offer, double reservation, double slackFraction) {
        return offer >= reservation * (1.0 + Math.max(0, slackFraction));
    }

    /** Below this a price is left exact — cheap capped consumables should not drift into charm. */
    static final long HUMANIZE_FLOOR = 100_000L;

    /**
     * Quantize a raw ask into a human-looking price so a stall or shout never reads like calculator
     * output (5,825,734). Each bot commits to ONE deterministic style keyed by its id, so its whole
     * shop stays coherent (a bot that lists round millions does it everywhere; another always ends in
     * 9s). Prices below {@link #HUMANIZE_FLOOR} pass through unchanged. This is presentation only: the
     * value is still the model's, we only round HOW it is spoken — always to a nearby number, never a
     * new economic decision. Styles (from the owner's examples): 2 significant figures (5,800,000),
     * charm 9s (5,799,999), charm with a .000 tail (5,799,000), nice half (6,000,000 / 550k), nice
     * quarter (5,750,000 / 575k), and repeated digits (5,555,555; 120k→111,111; 150k→155,555).
     */
    static long humanizeAsk(long price, int botId) {
        if (price < HUMANIZE_FLOOR) {
            return price;
        }
        long step = 1;                       // 10^(digits-3): the base carrying 3 significant figures
        int digits = 3;
        while (price / step >= 1000) {
            step *= 10;
            digits++;
        }
        long t = price / step;               // in [100, 1000): the three significant figures
        long twoSig = (t / 10) * 10 * step;  // rounded DOWN to two significant figures
        int style = (int) Math.floorMod(mix(botId, 0x505249434531L), 6L); // salt "PRICE1"
        long rounded = switch (style) {
            case 0 -> twoSig;                             // 2 sig figs, trailing zeros
            case 1 -> twoSig - 1;                         // charm 9s: 5,799,999
            case 2 -> twoSig - 1000;                      // charm with a .000 tail: 5,799,000
            case 3 -> Math.round(t / 50.0) * 50 * step;   // nice half (2nd digit -> 0 / 5)
            case 4 -> Math.round(t / 25.0) * 25 * step;   // nice quarter (2nd digit -> 0 / 25 / 5 / 75)
            default -> nearestRepeat(price, digits, (int) (t / 100)); // repeated digits: 5,555,555
        };
        return Math.max(1, rounded);
    }

    /**
     * Like {@link #humanizeAsk} but restricted to the styles that always land on a round thousand/
     * million so the value renders as a clean {@code k}/{@code m} string ({@code BotMarketGrammar.
     * mesoShort}) — no charm-9s, no repeated-digit tails. Shout trades speak prices out loud and the
     * owner wants only k/m there. Keeps a per-bot deterministic style (2-sig / nice-half / nice-quarter)
     * for coherence; sub-floor prices snap to the nearest thousand rather than passing through raw.
     */
    static long humanizeAskRound(long price, int botId) {
        if (price < HUMANIZE_FLOOR) {
            return Math.max(1000, Math.round(price / 1000.0) * 1000);
        }
        long step = 1;
        int digits = 3;
        while (price / step >= 1000) {
            step *= 10;
            digits++;
        }
        long t = price / step;
        long twoSig = (t / 10) * 10 * step;
        int style = (int) Math.floorMod(mix(botId, 0x505249434532L), 3L); // salt "PRICE2"
        long rounded = switch (style) {
            case 0 -> twoSig;                            // 2 sig figs (5,800,000)
            case 1 -> Math.round(t / 50.0) * 50 * step;  // nice half (6,000,000 / 5,500,000)
            default -> Math.round(t / 25.0) * 25 * step; // nice quarter (5,750,000)
        };
        return Math.max(1000, rounded);
    }

    /**
     * Nearest "repeated-digit" landmark to {@code price}: given its digit count and leading digit d,
     * the candidates are d-repeated (111,111), d-then-5s (155,555), and (d+1)-repeated (222,222).
     * Snapping to the nearest puts the switch-over midway (≈20% of value) — the owner's tolerance:
     * 120k→111,111 but 150k→155,555. A price whose lead digit is 5 collapses both charm forms onto
     * one repdigit (5.8M → 5,555,555).
     */
    private static long nearestRepeat(long price, int digits, int lead) {
        long repunit = 0;                    // {digits} ones: 111…1
        for (int i = 0; i < digits; i++) {
            repunit = repunit * 10 + 1;
        }
        long pow = 1;                        // 10^(digits-1): the leading digit's place value
        for (int i = 1; i < digits; i++) {
            pow *= 10;
        }
        long[] cands = {
            lead * repunit,                  // d d d d …   (d-repeated)
            lead * pow + 5 * (repunit / 10), // d 5 5 5 …   (d then 5s)
            (lead + 1) * repunit,            // (d+1) repeated (rolls over the top)
        };
        long best = cands[0];
        for (long c : cands) {
            if (Math.abs(price - c) < Math.abs(price - best)) {
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ helpers

    static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** splitmix64 finalizer-style mixing for stable seeded noise. */
    private static long mix(long h, long v) {
        long z = h ^ (v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2));
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double hashToUnit(long h) {
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }
}
