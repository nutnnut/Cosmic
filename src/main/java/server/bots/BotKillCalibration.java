package server.bots;

import client.Character;
import server.TimerManager;
import server.life.Monster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kill-rate calibration for unobserved-map LOD (docs/bot/living-server-design.md):
 * permanent kill-rate calibration instrumentation. While a bot grinds at full fidelity it kills
 * real mobs through the shared damage handlers; here we observe those kills and compare the
 * <em>measured</em> sustained rate against {@link BotGrindAdvisor#modeledKillsPerHour}, the
 * advisor's model for the same bot on the same map. The output is the correction factor Stage 3's
 * abstract grind will use so its exp/loot/meso outcomes match real grinding: the abstract rate is
 * {@code model × bucket}, so learning and replay divide by the SAME model and its systematic error
 * cancels.
 *
 * <p>Two stores:
 * <ul>
 *   <li><b>Per-bot per-map</b> decayed measured kills/hr (all mob types pooled — see
 *       {@link RateState}). Stage 3 uses a bot's OWN fresh rate on the map it is about to
 *       abstract-grind, overriding the bucket factor.</li>
 *   <li><b>Aggregate (job, level-band) buckets</b> of the measured/model ratio, the correction
 *       factor when a bot has no fresh per-map history (the common case: an unobserved bot never
 *       records real kills, so its own rate goes stale minutes after demotion).</li>
 * </ul>
 *
 * <p>In-memory with a periodic TSV flush under {@code logs/} so the calibration survives restarts
 * (loaded on first use). Thread-safe: kills arrive on the multi-worker bot-tick pool.
 */
final class BotKillCalibration {
    private static final Logger log = LoggerFactory.getLogger(BotKillCalibration.class);

    /** Master kill switch. Instrumentation is meant to stay on permanently (design), but this lets
     *  it be flipped off without a rebuild if it ever misbehaves. */
    static volatile boolean ENABLED = true;

    /** Per-kill fade on the rate accumulators. Both numerator and denominator decay together, so the
     *  ratio stays a rate while old spots fade out; ~0.99 gives a half-life of about 69 kills. */
    private static final double RATE_DECAY = 0.99;
    /** Slower EMA for the aggregate ratio bucket: it pools many bots/spots, so it should drift. */
    private static final double BUCKET_ALPHA = 0.05;
    /** A gap longer than this means the bot wasn't continuously grinding this mob (travel/break);
     *  re-anchor without measuring so a long idle can't fake a near-zero rate. */
    private static final long MAX_INTERVAL_MS = 5 * 60_000L;
    /** Below this much accumulated grind time a rate is noise (a single AoE burst can supply many kills
     *  across almost no elapsed time); report nothing until the spot has been worked for a while. */
    private static final long MIN_ACTIVE_MS = 30_000L;
    private static final long FLUSH_MS = 60_000L;
    private static final Path FILE = Path.of("logs", "bot-kill-calibration.tsv");

    private BotKillCalibration() {}

    /**
     * A bot's measured kill rate for one MAP — all mob types pooled. Mutated only by that bot's tick
     * thread.
     *
     * <p>Per-map, not per-(map,mob): a real grinder interleaves the map's mob types, so any single
     * mob's kill stream carries only that mob's share of the rate. Replaying one mob's partial rate
     * under-granted multi-mob maps by roughly the mob-share factor (measured: snipers on a two-mob map
     * at bias 0.47 vs 0.92 on a single-dominant-mob map). "Kills per hour on this map" is the quantity
     * the abstract grind schedules; WHICH mob dies stays emergent (nearest-target picking).
     *
     * <p>Stored as a decayed (kills, activeMs) pair rather than an EMA of per-kill rates. The rate is
     * the RATIO {@code kills/activeMs}, which is what "kills per hour" means. Averaging instantaneous
     * {@code 3.6e6/interval} samples instead would estimate the mean of a reciprocal, which by Jensen's
     * inequality is strictly greater than the reciprocal of the mean — it reports a peak rate, not a
     * sustained one, and it cannot see an AoE multi-kill at all (several mobs die within the same few
     * ms, so every kill after the first looks like a degenerate interval). Accumulating the pair counts
     * every kill against exactly the time that elapsed to earn it, so a burst of five adds five kills
     * and almost no time, which is precisely the rate the bot really achieved.
     */
    private static final class RateState {
        volatile double kills;     // decayed count of kills
        volatile double activeMs;  // decayed grind time those kills spanned
        volatile long samples;
        long lastKillAtMs;         // wall clock of the previous kill (rate anchor); not persisted as live
    }

    /** Sustained kills/hr on a map, or 0 before the spot has been worked long enough to mean it. */
    private static double sustainedKph(RateState st) {
        return st.activeMs >= MIN_ACTIVE_MS ? st.kills * 3_600_000.0 / st.activeMs : 0.0;
    }

    /** Aggregate measured/model ratio for a (job, level-band) bucket. */
    private static final class BucketState {
        double emaRatio;   // measured/model EMA; 0 = no sample yet
        long samples;
    }

    // botCharId -> (mapId -> RateState)
    private static final Map<Integer, Map<Integer, RateState>> perBotRates = new ConcurrentHashMap<>();
    // (jobId*1000 + levelBand) -> BucketState
    private static final Map<Integer, BucketState> buckets = new ConcurrentHashMap<>();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile boolean dirty = false;

    private static int bucketKey(int jobId, int levelBand) {
        return jobId * 1000 + levelBand;
    }

    /** Lazy one-time boot: load any prior calibration and start the periodic flush. Called from the
     *  first observed kill / prediction, by which point TimerManager is up. */
    private static void ensureStarted() {
        if (started.compareAndSet(false, true)) {
            loadFromDisk();
            try {
                TimerManager.getInstance().register(BotKillCalibration::flush, FLUSH_MS);
            } catch (RuntimeException e) {
                log.warn("kill-calibration flush timer not started (TimerManager not up?)", e);
            }
        }
    }

    /** Snapshot the still-alive targets before an attack lands, so {@link #observeKills} can tell which
     *  ones this attack killed. Returns an empty list (no allocation churn) when disabled. */
    static List<Monster> aliveTargets(BotEntry entry, List<Monster> targets) {
        if (!ENABLED || entry == null || !entry.grinding || targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<Monster> alive = new ArrayList<>(targets.size());
        for (Monster m : targets) {
            if (m != null && m.isAlive()) {
                alive.add(m);
            }
        }
        return alive;
    }

    /** Called right after an attack executes: any {@code preAlive} target now dead was killed by this
     *  bot's attack. Records a kill-rate sample per newly-dead mob. Only counts full-fidelity grinding. */
    static void observeKills(BotEntry entry, Character bot, List<Monster> preAlive) {
        if (!ENABLED || preAlive.isEmpty() || entry == null || bot == null) {
            return;
        }
        // Only calibrate on real grind kills. This hook sits in the real-attack executor, so every kill
        // it sees is full-fidelity by construction — Stage 3's abstract LOD1 kills call MapleMap.killMonster
        // directly and never reach here. The grinding gate excludes irregular opportunity/travel kills that
        // aren't the sustained "normal grinding" the correction factor models.
        if (!entry.grinding) {
            return;
        }
        int mapId = bot.getMapId();
        int jobId = bot.getJob() != null ? bot.getJob().getId() : 0;
        int level = bot.getLevel();
        long now = System.currentTimeMillis();
        // The ratio denominator: the advisor's modeled rate for this bot on this map (entry-cached).
        // Same model the abstract grind replays, so the bucket's correction cancels the model's error.
        double modelKph = BotGrindAdvisor.modeledKillsPerHourCached(entry, bot);
        for (Monster m : preAlive) {
            if (m != null && !m.isAlive()) {
                entry.realKillCount++;   // raw ground truth: every kill, including AoE multi-kills
                recordKill(bot.getId(), jobId, level, mapId, now, modelKph);
            }
        }
    }

    /** Record one kill for a bot, updating the per-map measured rate and — when the advisor model has
     *  a rate for this map ({@code modelKph > 0}) — the aggregate (job, level-band) ratio bucket.
     *  Takes primitives (not a Character) so it is the single code path for live kills and unit tests. */
    static void recordKill(int botCharId, int jobId, int level, int mapId, long now, double modelKph) {
        ensureStarted();
        Map<Integer, RateState> byMap = perBotRates.computeIfAbsent(botCharId, k -> new ConcurrentHashMap<>());
        RateState st = byMap.computeIfAbsent(mapId, k -> new RateState());
        long prev = st.lastKillAtMs;
        st.lastKillAtMs = now;
        if (prev == 0L) {
            return; // first kill of this (map,mob): no elapsed time to attribute it to, just anchor
        }
        long intervalMs = now - prev;
        if (intervalMs > MAX_INTERVAL_MS) {
            return; // travel/break, not grinding: re-anchor rather than bill this kill for the idle time
        }
        // Attribute this kill to exactly the time it took to earn. An AoE multi-kill lands several kills
        // against a near-zero interval, which is the whole point: those kills were genuinely that cheap.
        st.kills = st.kills * RATE_DECAY + 1.0;
        st.activeMs = st.activeMs * RATE_DECAY + intervalMs;
        st.samples++;
        dirty = true;

        double measured = sustainedKph(st);
        if (measured > 0 && modelKph > 0) {
            updateBucket(jobId, level, measured / modelKph);
        }
    }

    private static void updateBucket(int jobId, int level, double ratio) {
        if (ratio <= 0 || Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            return;
        }
        int levelBand = level / 10;
        BucketState st = buckets.computeIfAbsent(bucketKey(jobId, levelBand), k -> new BucketState());
        synchronized (st) {
            st.emaRatio = st.emaRatio <= 0 ? ratio : (1 - BUCKET_ALPHA) * st.emaRatio + BUCKET_ALPHA * ratio;
            st.samples++;
        }
    }

    /** Correction factor for a (job, level-band) bucket: measured/model, or 1.0 (no correction)
     *  when the bucket has no samples yet. Consumed by Stage 3's calibrated rate. */
    static double bucketFactor(int jobId, int levelBand) {
        BucketState st = buckets.get(bucketKey(jobId, levelBand));
        return st != null && st.samples > 0 && st.emaRatio > 0 ? st.emaRatio : 1.0;
    }

    /** A bot's own fresh measured rate on a map, or 0 when it has none / it is stale. Stage 3 prefers
     *  this over the model × bucket when present. */
    static double freshBotRate(int botCharId, int mapId, long freshWithinMs) {
        Map<Integer, RateState> byMap = perBotRates.get(botCharId);
        if (byMap == null) {
            return 0.0;
        }
        RateState st = byMap.get(mapId);
        if (st == null) {
            return 0.0;
        }
        double kph = sustainedKph(st);
        return kph > 0 && System.currentTimeMillis() - st.lastKillAtMs <= freshWithinMs ? kph : 0.0;
    }

    /** JSON calibration summary for the web endpoint: the aggregate buckets plus totals. */
    static String summaryJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"enabled\":").append(ENABLED);
        long rateSamples = 0;
        int trackedBots = perBotRates.size();
        int trackedRates = 0;
        for (Map<Integer, RateState> byMap : perBotRates.values()) {
            trackedRates += byMap.size();
            for (RateState st : byMap.values()) {
                rateSamples += st.samples;
            }
        }
        sb.append(",\"trackedBots\":").append(trackedBots);
        sb.append(",\"trackedRates\":").append(trackedRates);
        sb.append(",\"rateSamples\":").append(rateSamples);
        sb.append(",\"buckets\":[");
        boolean first = true;
        List<Map.Entry<Integer, BucketState>> entries = new ArrayList<>(buckets.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Integer, BucketState> e : entries) {
            int key = e.getKey();
            BucketState st = e.getValue();
            double ratio;
            long samples;
            synchronized (st) {
                ratio = st.emaRatio;
                samples = st.samples;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"jobId\":").append(key / 1000)
                    .append(",\"levelBand\":").append(key % 1000)
                    .append(",\"ratio\":").append(round3(ratio))
                    .append(",\"samples\":").append(samples).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static synchronized void flush() {
        if (!dirty) {
            return;
        }
        dirty = false;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Integer, BucketState> e : buckets.entrySet()) {
            BucketState st = e.getValue();
            double ratio;
            long samples;
            synchronized (st) {
                ratio = st.emaRatio;
                samples = st.samples;
            }
            lines.add("BUCKET4\t" + (e.getKey() / 1000) + "\t" + (e.getKey() % 1000) + "\t" + ratio + "\t" + samples);
        }
        for (Map.Entry<Integer, Map<Integer, RateState>> be : perBotRates.entrySet()) {
            int botCharId = be.getKey();
            for (Map.Entry<Integer, RateState> re : be.getValue().entrySet()) {
                RateState st = re.getValue();
                if (st.samples <= 0) {
                    continue;
                }
                lines.add("RATE3\t" + botCharId + "\t" + re.getKey() + "\t"
                        + st.kills + "\t" + st.activeMs + "\t" + st.samples);
            }
        }
        try {
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("kill-calibration flush failed", e);
        }
    }

    private static void loadFromDisk() {
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            // Only the current schemas are read; older rows store a DIFFERENT quantity and are dropped
            // rather than relearned into the wrong statistic. RATE/BUCKET: an EMA of instantaneous
            // 3.6e6/interval samples — a peak rate (buckets ran as high as 400x). BUCKET2: measured over
            // the plan-time committed prediction (party-spawn-shared) instead of the advisor model.
            // RATE2/BUCKET3: per-(map,mob) rates — one mob's share of the kill stream, which under-grants
            // multi-mob maps; RATE3/BUCKET4 pool the whole map.
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length == 0) {
                    continue;
                }
                if ("BUCKET4".equals(p[0]) && p.length == 5) {
                    BucketState st = new BucketState();
                    st.emaRatio = Double.parseDouble(p[3]);
                    st.samples = Long.parseLong(p[4]);
                    buckets.put(bucketKey(Integer.parseInt(p[1]), Integer.parseInt(p[2])), st);
                } else if ("RATE3".equals(p[0]) && p.length == 6) {
                    int botCharId = Integer.parseInt(p[1]);
                    int mapId = Integer.parseInt(p[2]);
                    RateState st = new RateState();
                    st.kills = Double.parseDouble(p[3]);
                    st.activeMs = Double.parseDouble(p[4]);
                    st.samples = Long.parseLong(p[5]);
                    st.lastKillAtMs = 0L; // stale across restart: first post-load kill just re-anchors
                    perBotRates.computeIfAbsent(botCharId, k -> new ConcurrentHashMap<>())
                            .put(mapId, st);
                }
            }
            log.info("Loaded bot kill-calibration: {} buckets, {} tracked bots", buckets.size(), perBotRates.size());
        } catch (Exception e) {
            log.warn("kill-calibration load failed", e);
        }
    }

    /** Test hook: reset all in-memory state and mark started, so recordKill never touches disk or
     *  TimerManager during tests (keeps each test hermetic from any live logs/*.tsv). */
    static void resetForTest() {
        started.set(true);
        perBotRates.clear();
        buckets.clear();
        dirty = false;
    }
}
