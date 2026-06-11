package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BotPerformanceMonitor {
    static final class Config {
        public boolean ENABLED = false;
        public int LOG_INTERVAL_MS = 15000;
        public double SLOW_SAMPLE_MS = 50.0;
        public double REPORT_MAX_MS = 250.0;
    }

    private static final class Stat {
        long count = 0;
        long totalNs = 0;
        long maxNs = 0;
        long slowCount = 0;
        long slowTotalNs = 0;
    }

    /** Immutable snapshot of one section's stats — returned by {@link #snapshot()}. */
    public record SectionSnapshot(String section,
                                  long count,
                                  long totalNs,
                                  long maxNs,
                                  long slowCount,
                                  long slowTotalNs) {
        public double avgMs() {
            return totalNs / (double) Math.max(1L, count) / 1_000_000.0;
        }
        public double maxMs() {
            return maxNs / 1_000_000.0;
        }
        public double slowAvgMs() {
            return slowTotalNs / (double) Math.max(1L, slowCount) / 1_000_000.0;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(BotPerformanceMonitor.class);
    private static final Object LOCK = new Object();
    private static final int MAX_LOGGED_SECTIONS = 12;
    static Config cfg = new Config();
    private static volatile boolean enabled = cfg.ENABLED;

    private static long lastLogAtMs = System.currentTimeMillis();
    private static long nextLogAtMs = lastLogAtMs + cfg.LOG_INTERVAL_MS;
    private static final Map<String, Stat> statsBySection = new LinkedHashMap<>();
    private static final Map<String, String> SECTION_NOTES;
    static {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("tick-total", "complete bot tick, including common systems, AI, combat, navigation, and movement");
        notes.put("move-ground", "ground physics, foothold collision, fallback steering, and movement packet sync");
        notes.put("move-air", "air physics, foothold landing checks, and air steering");
        notes.put("move-climb", "rope/ladder attachment, climb movement, and dismount checks");
        notes.put("move-swim", "swim physics, vertical hold selection, and movement packet sync");
        notes.put("nav-resolve", "region lookup, graph/path selection, edge reuse, and waypoint selection");
        notes.put("pathfind", "A* search over the current bot navigation graph");
        notes.put("pathfind-target-score", "A* called while ranking grind target regions");
        notes.put("combat-target-search", "monster scan, distance filtering, foothold lookup, and candidate sorting");
        notes.put("combat-plan", "skill/basic attack route selection and hitbox construction");
        // Common tick systems (run every tick, instrumented in BotManager.runCommonTickSystems)
        notes.put("common-mob-damage", "BotCombatManager.tickMobDamage (mob damage decay timers)");
        notes.put("common-release-mob", "tickReleaseMonsterControl (release stale controlled mobs)");
        notes.put("common-passive-loot", "BotInventoryManager.tickPassiveLoot (scan drops + pickup + autoEquip)");
        notes.put("common-potion-check", "BotPotionManager.tickPotionCheck (HP/MP potion request)");
        notes.put("potion-autopot", "BotPotionManager.setupAutopotForBot (scan USE bag + choose HP/MP autopot bindings)");
        notes.put("potion-ammo-check", "BotCombatManager.tickAmmoCheck invoked from potion check");
        notes.put("potion-ammo-share", "BotAmmoManager.tickAmmoShareCheck invoked from potion check");
        notes.put("potion-count", "BotPotionManager.countPotions for the active bot");
        notes.put("potion-recovery-scan", "BotPotionManager.recoveryPotions (USE inventory scan + recovery classification)");
        notes.put("potion-recovery-count", "BotPotionManager.countPotions second pass over recovery items");
        notes.put("potion-share-hp", "HP low-pot branch including donor search / scheduling");
        notes.put("potion-share-mp", "MP low-pot branch including donor search / scheduling");
        notes.put("potion-grind-stop", "low-pot grind-stop branch (follow owner + emote)");
        notes.put("potion-request", "BotPotionManager.requestPotShare total");
        notes.put("potion-donor-select", "BotPotionManager.selectPotDonor sibling scan");
        notes.put("common-passive-recovery", "BotPotionManager.tickPassiveRecovery (regen / mana recovery)");
        notes.put("common-build-levelup", "BotBuildManager.checkLevelUp (skill point allocation)");
        notes.put("common-afk-check", "BotChatManager.tickAfkCheck (owner-AFK detection)");
        notes.put("common-trade", "BotInventoryManager.tickTrade (in-progress bot trade state machine)");
        notes.put("common-manual-trade", "BotInventoryManager.tickManualTrade (manual bot/player trade)");
        notes.put("common-pq-hooks", "BotPqHooks.tick (KPQ / OPQ / LPQ state machines)");
        notes.put("common-script-tasks", "tickScriptTasks (BotScriptRunner)");
        notes.put("common-action-lock", "BotCombatManager.tickActionLock (attack/move cooldown decay)");
        notes.put("common-skill-cache", "BotCombatManager.rebuildSkillCacheIfNeeded");
        notes.put("common-support-heal", "BotCombatManager.tickSupportHealing (cleric heal)");
        notes.put("common-combat-buffs", "BotCombatManager.tickBuffs (player skill rebuff)");
        notes.put("common-buff-pots", "BotBuffManager.tick (consumable buff pots)");
        // Dispatch buckets
        notes.put("tick-idle", "tickIdleEntry physics-only idle dispatch");
        notes.put("tick-trade-physics", "tickTradePhysicsOnly (trade-window safe physics)");
        notes.put("tick-shop-visit", "BotShopManager.tickShopVisit");
        notes.put("tick-anchored-farm", "tickAnchoredFarm dispatch");
        notes.put("tick-standalone-move", "tickStandaloneMoveTarget (owner-offline move)");
        notes.put("tick-grind-dispatch", "grind mode dispatch in tickCore");
        notes.put("tick-map-change", "map change handler (rebuild footholds, regrounding)");
        notes.put("step-movement-core", "stepMovementCore wrapper (nav resolve + movement phase)");
        notes.put("opportunity-attack", "tryLocalOpportunityAttack");
        notes.put("broadcast-move", "BotMovementManager.broadcastMovement (packet build + map broadcast)");
        notes.put("stuck-detect", "tickStuckDetection");
        notes.put("grind-loot-scan", "BotInventoryManager.findNearestGrindLootTarget");
        notes.put("auto-equip", "BotEquipManager.autoEquip (Pareto DP) triggered on equip pickup");
        SECTION_NOTES = notes;
    }

    private BotPerformanceMonitor() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabledValue) {
        cfg.ENABLED = enabledValue;
        enabled = enabledValue;
        reset();
    }

    public static boolean toggleEnabled() {
        boolean next = !enabled;
        setEnabled(next);
        return next;
    }

    /** Returns a start timestamp suitable for {@link #recordSince}, or 0 if no tracing is active. */
    static long start() {
        return enabled || STALL_PHASE_TRACE.get().active ? System.nanoTime() : 0L;
    }

    /** Records elapsed time since the matching {@link #start} call. No-op when start returned 0. */
    static void recordSince(String section, long startedAtNs) {
        if (startedAtNs != 0L) {
            long elapsedNs = System.nanoTime() - startedAtNs;
            recordStallPhaseElapsed(section, elapsedNs);
            record(section, elapsedNs);
        }
    }

    // ---- always-on worst-stall watch (independent of the opt-in report above) ----

    /** A single bot tick taking this long is a real stutter worth a terminal line even with
     *  monitoring off. Everything milder belongs to the opt-in aggregated report. */
    static final double STALL_WARN_MS = 250.0;
    private static final long STALL_WARN_COOLDOWN_MS = 30_000L;
    private static final java.util.concurrent.atomic.AtomicLong stallNextWarnAtMs =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger stallSuppressed =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong stallSuppressedWorstNs =
            new java.util.concurrent.atomic.AtomicLong();
    private static final int MAX_STALL_PHASES = 16;
    private static final long STALL_PHASE_RECORD_MIN_NS = 1_000_000L;

    private static final class StallPhaseTrace {
        final String[] sections = new String[MAX_STALL_PHASES];
        final long[] elapsedNs = new long[MAX_STALL_PHASES];
        int count = 0;
        boolean active = false;
    }

    private static final ThreadLocal<StallPhaseTrace> STALL_PHASE_TRACE =
            ThreadLocal.withInitial(StallPhaseTrace::new);

    /** Starts the cheap always-on per-tick trace used only when a stall warning fires. */
    static void beginTickTrace() {
        StallPhaseTrace trace = STALL_PHASE_TRACE.get();
        trace.count = 0;
        trace.active = true;
    }

    static long startStallPhase() {
        return STALL_PHASE_TRACE.get().active ? System.nanoTime() : 0L;
    }

    static void recordStallPhase(String section, long startedAtNs) {
        if (startedAtNs == 0L) {
            return;
        }
        recordStallPhaseElapsed(section, System.nanoTime() - startedAtNs);
    }

    static void recordStallPhaseElapsed(String section, long elapsedNs) {
        if (elapsedNs < STALL_PHASE_RECORD_MIN_NS) {
            return;
        }
        StallPhaseTrace trace = STALL_PHASE_TRACE.get();
        if (!trace.active) {
            return;
        }
        int slot = trace.count;
        if (slot < MAX_STALL_PHASES) {
            trace.count++;
        } else {
            slot = smallestPhaseSlot(trace);
            if (slot < 0 || elapsedNs <= trace.elapsedNs[slot]) {
                return;
            }
        }
        trace.sections[slot] = section;
        trace.elapsedNs[slot] = elapsedNs;
    }

    static void endTickTrace() {
        STALL_PHASE_TRACE.get().active = false;
    }

    /** Always-on: logs the absolute worst tick stalls, rate-limited to one line per
     *  {@link #STALL_WARN_COOLDOWN_MS} (suppressed stalls are counted into the next line). */
    static void noteTickStall(BotEntry entry, long elapsedNs) {
        if (elapsedNs < (long) (STALL_WARN_MS * 1_000_000.0)) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = stallNextWarnAtMs.get();
        if (now < next || !stallNextWarnAtMs.compareAndSet(next, now + STALL_WARN_COOLDOWN_MS)) {
            stallSuppressed.incrementAndGet();
            stallSuppressedWorstNs.accumulateAndGet(elapsedNs, Math::max);
            return;
        }
        int suppressed = stallSuppressed.getAndSet(0);
        long suppressedWorst = stallSuppressedWorstNs.getAndSet(0);
        String botName = entry != null && entry.bot != null ? entry.bot.getName() : "?";
        int mapId = entry != null && entry.bot != null ? entry.bot.getMapId() : -1;
        if (suppressed > 0) {
            log.warn("Bot tick stall: {} on map {} took {} ms{} ({} more stalls >= {} ms in the last {}s, worst {} ms)",
                    botName, mapId, formatMs(elapsedNs / 1_000_000.0),
                    formatStallPhases(), suppressed,
                    formatMs(STALL_WARN_MS), STALL_WARN_COOLDOWN_MS / 1000,
                    formatMs(suppressedWorst / 1_000_000.0));
        } else {
            log.warn("Bot tick stall: {} on map {} took {} ms{}",
                    botName, mapId, formatMs(elapsedNs / 1_000_000.0), formatStallPhases());
        }
    }

    private static int smallestPhaseSlot(StallPhaseTrace trace) {
        int smallest = -1;
        long smallestNs = Long.MAX_VALUE;
        for (int i = 0; i < trace.count; i++) {
            if (trace.elapsedNs[i] < smallestNs) {
                smallestNs = trace.elapsedNs[i];
                smallest = i;
            }
        }
        return smallest;
    }

    private static String formatStallPhases() {
        StallPhaseTrace trace = STALL_PHASE_TRACE.get();
        if (trace.count <= 0) {
            return "";
        }
        List<Integer> order = new ArrayList<>(trace.count);
        for (int i = 0; i < trace.count; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingLong((Integer i) -> trace.elapsedNs[i]).reversed());
        StringBuilder sb = new StringBuilder(" phases=");
        int limit = Math.min(5, order.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(",");
            }
            int idx = order.get(i);
            sb.append(trace.sections[idx])
                    .append("=")
                    .append(formatMs(trace.elapsedNs[idx] / 1_000_000.0))
                    .append("ms");
        }
        return sb.toString();
    }

    static void record(String section, long elapsedNs) {
        if (!enabled || elapsedNs < 0) {
            return;
        }

        synchronized (LOCK) {
            Stat stat = statsBySection.computeIfAbsent(section, ignored -> new Stat());
            stat.count++;
            stat.totalNs += elapsedNs;
            stat.maxNs = Math.max(stat.maxNs, elapsedNs);
            if (elapsedNs >= slowThresholdNs()) {
                stat.slowCount++;
                stat.slowTotalNs += elapsedNs;
            }
            maybeLog();
        }
    }

    /** Clears all accumulated stats. Used by perf harnesses to start a clean window. */
    public static void reset() {
        synchronized (LOCK) {
            statsBySection.clear();
            lastLogAtMs = System.currentTimeMillis();
            nextLogAtMs = lastLogAtMs + cfg.LOG_INTERVAL_MS;
        }
    }

    /** Returns an immutable per-section snapshot of current stats. */
    public static List<SectionSnapshot> snapshot() {
        synchronized (LOCK) {
            List<SectionSnapshot> snapshots = new ArrayList<>(statsBySection.size());
            for (Map.Entry<String, Stat> entry : statsBySection.entrySet()) {
                Stat s = entry.getValue();
                snapshots.add(new SectionSnapshot(entry.getKey(), s.count, s.totalNs, s.maxNs, s.slowCount, s.slowTotalNs));
            }
            return snapshots;
        }
    }

    static void recordPathfind(long elapsedNs) {
        record("pathfind", elapsedNs);
    }

    static void recordPathfind(String caller, long elapsedNs) {
        if (caller == null || caller.isBlank()) {
            recordPathfind(elapsedNs);
            return;
        }
        record("pathfind-" + caller, elapsedNs);
    }

    private static void maybeLog() {
        long now = System.currentTimeMillis();
        if (now < nextLogAtMs || statsBySection.isEmpty()) {
            return;
        }

        double intervalSeconds = Math.max(0.001, (now - lastLogAtMs) / 1000.0);
        List<Map.Entry<String, Stat>> reportSections = new ArrayList<>();
        for (Map.Entry<String, Stat> entry : statsBySection.entrySet()) {
            if (entry.getValue().maxNs >= reportThresholdNs()) {
                reportSections.add(entry);
            }
        }
        reportSections.sort(Comparator.comparingLong((Map.Entry<String, Stat> entry) -> entry.getValue().maxNs).reversed());

        if (reportSections.isEmpty()) {
            statsBySection.clear();
            lastLogAtMs = now;
            nextLogAtMs = now + cfg.LOG_INTERVAL_MS;
            return;
        }

        StringBuilder line = new StringBuilder("bot-perf report>=")
                .append(formatMs(cfg.REPORT_MAX_MS))
                .append("ms")
                .append(" slow>=")
                .append(formatMs(cfg.SLOW_SAMPLE_MS))
                .append("ms ");
        boolean first = true;
        int loggedSections = 0;
        for (Map.Entry<String, Stat> entry : reportSections) {
            Stat stat = entry.getValue();
            if (loggedSections >= MAX_LOGGED_SECTIONS) {
                break;
            }
            if (!first) {
                line.append(" | ");
            }
            first = false;
            loggedSections++;

            double averageMs = stat.totalNs / (double) Math.max(1L, stat.count) / 1_000_000.0;
            double totalMs = stat.totalNs / 1_000_000.0;
            double cpuMsPerSec = totalMs / intervalSeconds;
            double cpuCore = cpuMsPerSec / 1000.0;
            double maxMs = stat.maxNs / 1_000_000.0;
            double slowAverageMs = stat.slowTotalNs / (double) Math.max(1L, stat.slowCount) / 1_000_000.0;
            double slowPct = stat.slowCount * 100.0 / Math.max(1L, stat.count);
            line.append(entry.getKey())
                    .append(" avg=")
                    .append(formatMs(averageMs))
                    .append("ms")
                    .append(" cps=")
                    .append(String.format(Locale.ROOT, "%.1f", stat.count / intervalSeconds))
                    .append(" cpu=")
                    .append(formatMs(cpuMsPerSec))
                    .append("ms/s")
                    .append(" core=")
                    .append(formatCore(cpuCore))
                    .append(" max=")
                    .append(formatMs(maxMs))
                    .append("ms")
                    .append(" n=")
                    .append(stat.count)
                    .append(" slow=")
                    .append(stat.slowCount)
                    .append("/")
                    .append(stat.count)
                    .append(" slow%=")
                    .append(formatPct(slowPct))
                    .append("%")
                    .append(" slowAvg=")
                    .append(formatMs(slowAverageMs))
                    .append("ms")
                    .append(" note=")
                    .append(noteFor(entry.getKey()));
        }
        if (reportSections.size() > loggedSections) {
            line.append(" | omitted=")
                    .append(reportSections.size() - loggedSections)
                    .append(" lower-max report sections");
        }

        if (!first) {
            log.info(line.toString());
        }
        statsBySection.clear();
        lastLogAtMs = now;
        nextLogAtMs = now + cfg.LOG_INTERVAL_MS;
    }

    private static String noteFor(String section) {
        String note = SECTION_NOTES.get(section);
        if (note != null) {
            return note;
        }
        if (section != null && section.startsWith("pathfind-")) {
            return "A* search over the current bot navigation graph";
        }
        return "instrumented bot subsystem";
    }

    private static long slowThresholdNs() {
        return (long) (Math.max(0.0, cfg.SLOW_SAMPLE_MS) * 1_000_000.0);
    }

    private static long reportThresholdNs() {
        return (long) (Math.max(cfg.SLOW_SAMPLE_MS, cfg.REPORT_MAX_MS) * 1_000_000.0);
    }

    private static String formatMs(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatPct(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatCore(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
