package server.bots;

import server.bots.BotMarketMath.Belief;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 2 of the belief model (docs/bot/living-economy-design.md sec 4): one bot's private,
 * imperfect view of prices. Blends the bot's own observations with a noisy, seeded sample of the
 * shared consensus; personal experience outweighs hearsay as confidence grows.
 *
 * <p>This is the ONLY price source bot decision code may read (design sec 10.6). No server
 * dependencies: the consensus arrives through {@link ConsensusSource}, time through arguments —
 * fully unit/sim-testable. Not thread-safe by design: a book belongs to its bot's tick context.
 */
final class BotMarketBook {

    /** The shared layer-1 statistic, injected (production: BotMarketConsensus singleton). */
    interface ConsensusSource {
        /** Consensus meso for a key, or 0 when the market has never seen it. */
        double consensus(long priceKey);

        /** Decayed event volume backing that number (its damping mass / prior weight). */
        double volume(long priceKey);
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final int botId;
    private final ConsensusSource consensus;
    /** 0..1 how plugged-in this bot is; shrinks perception noise (from social traits). */
    private final double informedness;

    private final Map<Long, KeyState> byKey = new HashMap<>();

    private static final class KeyState {
        Belief belief = Belief.NONE;
        int obs;
        long lastSeenMs;
        double gapEmaMs; // self-normalizing memory horizon input (design sec 4)
        boolean dirty;
    }

    BotMarketBook(int botId, double informedness, ConsensusSource consensus) {
        this.botId = botId;
        this.informedness = BotMarketMath.clamp01(informedness);
        this.consensus = consensus;
    }

    // ------------------------------------------------------------------ intake

    /**
     * Record a personally-witnessed price (weight = BotMarketMath.W_* by source). Decays the
     * stored confidence for the elapsed silence first, so liquid keys track and illiquid keys
     * remember (half-life from the key's own observation spacing).
     */
    void observe(long priceKey, double price, double weight, long nowMs) {
        if (price <= 0 || weight <= 0) {
            return;
        }
        KeyState ks = byKey.computeIfAbsent(priceKey, k -> new KeyState());
        if (ks.lastSeenMs > 0) {
            long gap = Math.max(0, nowMs - ks.lastSeenMs);
            ks.gapEmaMs = ks.gapEmaMs <= 0 ? gap : (ks.gapEmaMs * 3 + gap) / 4;
            ks.belief = BotMarketMath.decay(ks.belief, gap, BotMarketMath.halfLifeMs((long) ks.gapEmaMs));
        }
        ks.belief = BotMarketMath.updateBelief(ks.belief, price, weight);
        ks.obs++;
        ks.lastSeenMs = nowMs;
        ks.dirty = true;
        evictIfOverCap();
    }

    // ------------------------------------------------------------------ read

    /**
     * The bot's working idea of a price: private belief blended with the noise-sampled consensus
     * (design sec 4 layer 2). 0 when neither layer knows anything — the caller then falls back to
     * structural quotes / its own cost anchor (BotMarketMath.structuralQuote).
     */
    double perceivedPrice(long priceKey, long nowMs) {
        Belief priv = decayedPrivate(priceKey, nowMs);
        double c = consensus.consensus(priceKey);
        if (c <= 0) {
            return priv.isEmpty() ? 0 : priv.estimate();
        }
        double sampled = c * BotMarketMath.perceptionNoise(botId, priceKey, nowMs / DAY_MS, informedness);
        return BotMarketMath.blendWithConsensus(priv, sampled, consensus.volume(priceKey));
    }

    /** Private-layer confidence (post-decay) — margin/ask formation reads this. */
    double privateConfidence(long priceKey, long nowMs) {
        return decayedPrivate(priceKey, nowMs).confidence();
    }

    private Belief decayedPrivate(long priceKey, long nowMs) {
        KeyState ks = byKey.get(priceKey);
        if (ks == null || ks.belief.isEmpty()) {
            return Belief.NONE;
        }
        long silent = Math.max(0, nowMs - ks.lastSeenMs);
        return BotMarketMath.decay(ks.belief, silent, BotMarketMath.halfLifeMs((long) ks.gapEmaMs));
    }

    // ------------------------------------------------------------------ persistence bridge

    void loadFrom(Map<Long, BotMarketStore.StoredBelief> rows) {
        for (BotMarketStore.StoredBelief b : rows.values()) {
            KeyState ks = new KeyState();
            ks.belief = new Belief(b.estimate(), b.confidence());
            ks.obs = b.obs();
            ks.lastSeenMs = b.lastSeenMs();
            byKey.put(b.priceKey(), ks);
        }
    }

    /** Rows changed since the last flush; marks them clean. */
    List<BotMarketStore.StoredBelief> drainDirty() {
        List<BotMarketStore.StoredBelief> out = new ArrayList<>();
        for (Map.Entry<Long, KeyState> e : byKey.entrySet()) {
            KeyState ks = e.getValue();
            if (ks.dirty) {
                out.add(new BotMarketStore.StoredBelief(e.getKey(), Math.round(ks.belief.estimate()),
                        ks.belief.confidence(), ks.obs, ks.lastSeenMs));
                ks.dirty = false;
            }
        }
        return out;
    }

    int trackedKeys() {
        return byKey.size();
    }

    private void evictIfOverCap() {
        if (byKey.size() <= BotMarketStore.BELIEFS_PER_BOT_CAP) {
            return;
        }
        Long coldest = null;
        long coldestSeen = Long.MAX_VALUE;
        for (Map.Entry<Long, KeyState> e : byKey.entrySet()) {
            if (e.getValue().lastSeenMs < coldestSeen) {
                coldestSeen = e.getValue().lastSeenMs;
                coldest = e.getKey();
            }
        }
        if (coldest != null) {
            byKey.remove(coldest);
        }
    }
}
