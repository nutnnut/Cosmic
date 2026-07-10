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
 * <em>measured</em> rate against the {@link BotGrindPlanner#killsPerHour} prediction the advisor
 * committed at plan time. The output is the correction factor Stage 3's abstract grind will use
 * so its exp/loot/meso outcomes match real grinding.
 *
 * <p>Two stores:
 * <ul>
 *   <li><b>Per-bot per-(map,mob) EMA</b> of the measured kills/hr (from the interval between
 *       consecutive kills of that mob by that bot). Stage 3 uses a bot's OWN fresh rate on the
 *       (map,mob) it is about to abstract-grind, overriding the bucket factor.</li>
 *   <li><b>Aggregate (job, level-band) buckets</b> of the measured/predicted ratio, the fallback
 *       correction factor when a bot has no fresh per-(map,mob) history.</li>
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

    /** Per-kill EMA weight for a bot's measured (map,mob) rate — responsive to the current spot. */
    private static final double RATE_ALPHA = 0.2;
    /** Slower EMA for the aggregate ratio bucket: it pools many bots/spots, so it should drift. */
    private static final double BUCKET_ALPHA = 0.05;
    /** Two kills closer than this are treated as one AoE multi-kill instant; not a rate sample. */
    private static final long MIN_INTERVAL_MS = 250L;
    /** A gap longer than this means the bot wasn't continuously grinding this mob (travel/break);
     *  re-anchor without measuring so a long idle can't fake a near-zero rate. */
    private static final long MAX_INTERVAL_MS = 5 * 60_000L;
    /** A committed prediction older than this is stale (bot has since re-decided); don't ratio against it. */
    private static final long PREDICTION_FRESH_MS = 30 * 60_000L;
    private static final long FLUSH_MS = 60_000L;
    private static final Path FILE = Path.of("logs", "bot-kill-calibration.tsv");

    private BotKillCalibration() {}

    /** A bot's measured kill rate for one (map,mob). Mutated only by that bot's tick thread. */
    private static final class RateState {
        volatile double emaKph;    // measured kills/hr EMA; 0 = no sample yet
        volatile long samples;
        long lastKillAtMs;         // wall clock of the previous kill (rate anchor); not persisted as live
    }

    /** Aggregate measured/predicted ratio for a (job, level-band) bucket. */
    private static final class BucketState {
        double emaRatio;   // measured/predicted EMA; 0 = no sample yet
        long samples;
    }

    /** The advisor prediction current for a bot (set at plan install), used as the ratio denominator. */
    private static final class Prediction {
        final double killsPerHour;
        final int mapId;
        final long atMs;
        Prediction(double killsPerHour, int mapId, long atMs) {
            this.killsPerHour = killsPerHour;
            this.mapId = mapId;
            this.atMs = atMs;
        }
    }

    // botCharId -> ((mapId<<32|mobId) -> RateState)
    private static final Map<Integer, Map<Long, RateState>> perBotRates = new ConcurrentHashMap<>();
    // (jobId*1000 + levelBand) -> BucketState
    private static final Map<Integer, BucketState> buckets = new ConcurrentHashMap<>();
    private static final Map<Integer, Prediction> predictionByBot = new ConcurrentHashMap<>();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile boolean dirty = false;

    private static long rateKey(int mapId, int mobId) {
        return ((long) mapId << 32) | (mobId & 0xffffffffL);
    }

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

    /** Record the advisor's committed kills/hr prediction for a bot's current grind plan (Stage 0
     *  ratio denominator). Called from {@link BotAutopilotManager} when a plan is installed. */
    static void notePrediction(BotEntry entry, double predictedKillsPerHour, int mapId) {
        if (!ENABLED || entry == null || entry.bot == null || predictedKillsPerHour <= 0) {
            return;
        }
        notePrediction(entry.bot.getId(), predictedKillsPerHour, mapId, System.currentTimeMillis());
    }

    /** Primitive prediction seam (single code path for live installs and unit tests). */
    static void notePrediction(int botCharId, double predictedKillsPerHour, int mapId, long atMs) {
        ensureStarted();
        predictionByBot.put(botCharId, new Prediction(predictedKillsPerHour, mapId, atMs));
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
        for (Monster m : preAlive) {
            if (m != null && !m.isAlive()) {
                recordKill(bot.getId(), jobId, level, mapId, m.getId(), now);
            }
        }
    }

    /** Record one kill for a bot, updating the per-(map,mob) measured-rate EMA and — when a fresh advisor
     *  prediction exists for the same map — the aggregate (job, level-band) ratio bucket. Takes primitives
     *  (not a Character) so it is the single code path for both live kills and unit tests. */
    static void recordKill(int botCharId, int jobId, int level, int mapId, int mobId, long now) {
        ensureStarted();
        Map<Long, RateState> byMob = perBotRates.computeIfAbsent(botCharId, k -> new ConcurrentHashMap<>());
        RateState st = byMob.computeIfAbsent(rateKey(mapId, mobId), k -> new RateState());
        long prev = st.lastKillAtMs;
        st.lastKillAtMs = now;
        if (prev == 0L) {
            return; // first kill of this (map,mob): no interval to measure, just anchor
        }
        long intervalMs = now - prev;
        if (intervalMs < MIN_INTERVAL_MS || intervalMs > MAX_INTERVAL_MS) {
            return; // AoE burst or a grinding gap — not a clean per-kill interval
        }
        double instKph = 3_600_000.0 / intervalMs;
        double ema = st.emaKph <= 0 ? instKph : (1 - RATE_ALPHA) * st.emaKph + RATE_ALPHA * instKph;
        st.emaKph = ema;
        st.samples++;
        dirty = true;

        Prediction pred = predictionByBot.get(botCharId);
        if (pred != null && pred.killsPerHour > 0 && pred.mapId == mapId
                && now - pred.atMs <= PREDICTION_FRESH_MS) {
            updateBucket(jobId, level, ema / pred.killsPerHour);
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

    /** Correction factor for a (job, level-band) bucket: measured/predicted, or 1.0 (no correction)
     *  when the bucket has no samples yet. Consumed by Stage 3's calibrated rate. */
    static double bucketFactor(int jobId, int levelBand) {
        BucketState st = buckets.get(bucketKey(jobId, levelBand));
        return st != null && st.samples > 0 && st.emaRatio > 0 ? st.emaRatio : 1.0;
    }

    /** The advisor's committed kills/hr prediction for a bot on {@code mapId}, or 0 when there is none /
     *  it is for a different map / it is stale. Stage 3's abstract-grind rate uses this (× the bucket
     *  correction) when the bot has no fresh own-measured rate yet. */
    static double predictedKph(int botCharId, int mapId, long freshWithinMs) {
        Prediction pred = predictionByBot.get(botCharId);
        if (pred == null || pred.killsPerHour <= 0 || pred.mapId != mapId) {
            return 0.0;
        }
        return System.currentTimeMillis() - pred.atMs <= freshWithinMs ? pred.killsPerHour : 0.0;
    }

    /** A bot's own fresh measured rate for a (map,mob), or 0 when it has none / it is stale. Stage 3
     *  prefers this over the bucket factor when present. */
    static double freshBotRate(int botCharId, int mapId, int mobId, long freshWithinMs) {
        Map<Long, RateState> byMob = perBotRates.get(botCharId);
        if (byMob == null) {
            return 0.0;
        }
        RateState st = byMob.get(rateKey(mapId, mobId));
        if (st == null || st.emaKph <= 0 || st.samples <= 0) {
            return 0.0;
        }
        return System.currentTimeMillis() - st.lastKillAtMs <= freshWithinMs ? st.emaKph : 0.0;
    }

    /** JSON calibration summary for the web endpoint: the aggregate buckets plus totals. */
    static String summaryJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"enabled\":").append(ENABLED);
        long rateSamples = 0;
        int trackedBots = perBotRates.size();
        int trackedRates = 0;
        for (Map<Long, RateState> byMob : perBotRates.values()) {
            trackedRates += byMob.size();
            for (RateState st : byMob.values()) {
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
            lines.add("BUCKET\t" + (e.getKey() / 1000) + "\t" + (e.getKey() % 1000) + "\t" + ratio + "\t" + samples);
        }
        for (Map.Entry<Integer, Map<Long, RateState>> be : perBotRates.entrySet()) {
            int botCharId = be.getKey();
            for (Map.Entry<Long, RateState> re : be.getValue().entrySet()) {
                long k = re.getKey();
                RateState st = re.getValue();
                if (st.samples <= 0) {
                    continue;
                }
                int mapId = (int) (k >> 32);
                int mobId = (int) (k & 0xffffffffL);
                lines.add("RATE\t" + botCharId + "\t" + mapId + "\t" + mobId + "\t" + st.emaKph + "\t" + st.samples);
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
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length == 0) {
                    continue;
                }
                if ("BUCKET".equals(p[0]) && p.length == 5) {
                    BucketState st = new BucketState();
                    st.emaRatio = Double.parseDouble(p[3]);
                    st.samples = Long.parseLong(p[4]);
                    buckets.put(bucketKey(Integer.parseInt(p[1]), Integer.parseInt(p[2])), st);
                } else if ("RATE".equals(p[0]) && p.length == 6) {
                    int botCharId = Integer.parseInt(p[1]);
                    int mapId = Integer.parseInt(p[2]);
                    int mobId = Integer.parseInt(p[3]);
                    RateState st = new RateState();
                    st.emaKph = Double.parseDouble(p[4]);
                    st.samples = Long.parseLong(p[5]);
                    st.lastKillAtMs = 0L; // stale across restart: first post-load kill just re-anchors
                    perBotRates.computeIfAbsent(botCharId, k -> new ConcurrentHashMap<>())
                            .put(rateKey(mapId, mobId), st);
                }
            }
            log.info("Loaded bot kill-calibration: {} buckets, {} tracked bots", buckets.size(), perBotRates.size());
        } catch (Exception e) {
            log.warn("kill-calibration load failed", e);
        }
    }

    /** Test hook: reset all in-memory state and mark started, so recordKill/notePrediction never touch
     *  disk or TimerManager during tests (keeps each test hermetic from any live logs/*.tsv). */
    static void resetForTest() {
        started.set(true);
        perBotRates.clear();
        buckets.clear();
        predictionByBot.clear();
        dirty = false;
    }
}
