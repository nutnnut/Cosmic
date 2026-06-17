package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.bots.ManagedBotService.ManagedBot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Living-server population scheduler: every {@code POPULATION_SWEEP_MS} it logs managed bots in and
 * out so the online count tracks a target curve by server-local hour, biased by each bot's
 * {@link BotPersonality} (preferred hours + how often it plays). DEFAULT OFF
 * ({@code BotManager.cfg.POPULATION_SCHED_ENABLED}) — a server start never silently spawns a crowd.
 *
 * <p>Decision math is the pure, tested {@link BotScheduleMath}; this class only does the IO (count
 * live bots, spawn/logout via {@link BotManager}). Safety: it only ever touches characters in the
 * {@code managed_bot} registry. Career turnover + auto-generation are a follow-up (P3b); this pass
 * tracks the curve over the existing managed pool and under-fills (logged) when the pool is short.
 */
public final class BotScheduler {
    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
    private static final BotScheduler instance = new BotScheduler();

    public static BotScheduler getInstance() {
        return instance;
    }

    /** charId -> when the scheduler brought it online (for session-length logout). */
    private final Map<Integer, Long> onlineSince = new ConcurrentHashMap<>();
    private boolean started = false;

    private BotScheduler() {}

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        // The sweep self-guards on the enabled flag, so registering it always (even when disabled) is
        // free — flipping POPULATION_SCHED_ENABLED at runtime then just works.
        TimerManager.getInstance().register(this::sweep, BotManager.cfg.POPULATION_SWEEP_MS,
                ThreadLocalRandom.current().nextLong(5_000L));
    }

    void sweep() {
        if (!BotManager.cfg.POPULATION_SCHED_ENABLED) {
            return;
        }
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("bot population sweep failed", e);
        }
    }

    // ---- @botpop admin surface ----

    public void setEnabled(boolean on) {
        BotManager.cfg.POPULATION_SCHED_ENABLED = on;
    }

    /** Force a reconcile now (no-op if disabled). */
    public void sweepNow() {
        sweep();
    }

    public List<String> statusLines() {
        int hour = LocalTime.now().getHour();
        int target = BotScheduleMath.targetForHour(BotManager.cfg.POPULATION_CURVE, hour, 0, 0.5);
        List<ManagedBot> managed = ManagedBotService.getInstance().loadAll();
        int schedulable = 0;
        int live = 0;
        for (ManagedBot m : managed) {
            if (m.schedulable()) {
                schedulable++;
            }
            if (BotManager.getInstance().getEntryByBotCharId(m.botCharId()) != null) {
                live++;
            }
        }
        return List.of(
                "scheduler: " + (BotManager.cfg.POPULATION_SCHED_ENABLED ? "ON" : "OFF")
                        + " (autogen " + (BotManager.cfg.POPULATION_AUTOGEN ? "on" : "off") + ")",
                "hour " + hour + ": target=" + target + "  live=" + live,
                "managed pool: " + managed.size() + " (" + schedulable + " schedulable)");
    }

    public List<String> listLines() {
        List<String> out = new ArrayList<>();
        for (ManagedBot m : ManagedBotService.getInstance().loadAll()) {
            boolean liveNow = BotManager.getInstance().getEntryByBotCharId(m.botCharId()) != null;
            String state = m.retired() ? "retired" : m.enabled() ? "active" : "disabled";
            out.add("#" + m.botCharId()
                    + (m.groupId() != null ? " grp" + m.groupId() : "")
                    + " " + state + (liveNow ? " [online]" : ""));
        }
        return out;
    }

    private record Candidate(int charId, double desire) {}

    private void reconcile() {
        long now = System.currentTimeMillis();
        int hour = LocalTime.now().getHour();
        long epochDay = LocalDate.now().toEpochDay();
        int target = BotScheduleMath.targetForHour(BotManager.cfg.POPULATION_CURVE, hour,
                BotManager.cfg.POPULATION_NOISE, ThreadLocalRandom.current().nextDouble());

        BotManager bm = BotManager.getInstance();
        List<ManagedBot> managed = ManagedBotService.getInstance().loadAll();

        // --- 0. turnover: retire OFFLINE bots whose career has ended ("left"); never hardcore, never a
        // live session. They stay as records (no longer scheduled); auto-gen below refills the pool. ---
        boolean retiredAny = false;
        for (ManagedBot m : managed) {
            if (m.retired() || bm.getEntryByBotCharId(m.botCharId()) != null) {
                continue;
            }
            BotPersonality p = BotPersonality.parse(BotConfigService.getInstance().load(m.botCharId()));
            if (BotScheduleMath.careerEnded(m.ageDays(now), p.careerLenDays(), p.isHardcore())) {
                ManagedBotService.getInstance().retire(m.botCharId());
                retiredAny = true;
            }
        }
        if (retiredAny) {
            managed = ManagedBotService.getInstance().loadAll(); // re-read so the reconcile sees the live pool
        }

        // --- 1. session-length logouts + live census (independent of the target) ---
        List<Integer> live = new ArrayList<>();
        for (ManagedBot m : managed) {
            if (!m.schedulable()) {
                continue;
            }
            BotEntry e = bm.getEntryByBotCharId(m.botCharId());
            if (e == null) {
                onlineSince.remove(m.botCharId());
                continue;
            }
            long since = onlineSince.computeIfAbsent(m.botCharId(), k -> now);
            if (BotScheduleMath.sessionElapsed(since, sessionMs(e), now)) {
                bm.logoutManagedBot(m.botCharId());
                onlineSince.remove(m.botCharId());
            } else {
                live.add(m.botCharId());
            }
        }

        // --- 2. reconcile the live count toward the target ---
        if (live.size() < target) {
            bringOnline(bm, managed, hour, epochDay, target - live.size(), now);
        } else if (live.size() > target) {
            logOutExcess(bm, live, hour, epochDay, live.size() - target);
        }
    }

    /** Bring the most-eager offline managed bots online (active-today, hour-preferred ranked first). */
    private void bringOnline(BotManager bm, List<ManagedBot> managed, int hour, long epochDay, int need, long now) {
        List<Candidate> cands = new ArrayList<>();
        for (ManagedBot m : managed) {
            if (!m.schedulable() || bm.getEntryByBotCharId(m.botCharId()) != null) {
                continue;
            }
            BotPersonality p = BotPersonality.parse(BotConfigService.getInstance().load(m.botCharId()));
            double desire = BotScheduleMath.onlineDesire(p, hour, 1, epochDay); // level decay is a P3b refinement
            if (desire > 0.0) {
                cands.add(new Candidate(m.botCharId(), desire));
            }
        }
        cands.sort((a, b) -> Double.compare(b.desire(), a.desire()));
        int brought = 0;
        for (Candidate c : cands) {
            if (brought >= need) {
                break;
            }
            if (bm.spawnManagedBot(c.charId())) {
                onlineSince.put(c.charId(), now);
                brought++;
            }
        }
        // Still short after waking everyone eligible: trickle in fresh bots (one per sweep), gated by the
        // autogen flag + the non-retired pool cap, so the world grows toward the curve over time.
        if (brought < need) {
            int poolSize = 0;
            for (ManagedBot m : managed) {
                if (!m.retired()) {
                    poolSize++;
                }
            }
            int gen = BotScheduleMath.autogenCount(BotManager.cfg.POPULATION_AUTOGEN, need, brought, 0,
                    poolSize, BotManager.cfg.MANAGED_POOL_MAX);
            if (gen > 0) {
                int newId = BotGenerator.generateManaged(BotManager.cfg.POPULATION_WORLD,
                        BotManager.cfg.POPULATION_CHANNEL, BotGenerator.countHardcore(managed),
                        BotManager.cfg.HARDCORE_CAP);
                if (newId > 0 && bm.spawnManagedBot(newId)) {
                    onlineSince.put(newId, now);
                }
            } else {
                log.debug("population under target by {} (autogen off or pool at cap)", need - brought);
            }
        }
    }

    /** Log out the least-eager live bots down to the target. */
    private void logOutExcess(BotManager bm, List<Integer> live, int hour, long epochDay, int excess) {
        List<Candidate> ranked = new ArrayList<>();
        for (int charId : live) {
            BotEntry e = bm.getEntryByBotCharId(charId);
            BotPersonality p = e != null && e.personality != null ? e.personality : BotPersonality.defaults();
            ranked.add(new Candidate(charId, BotScheduleMath.onlineDesire(p, hour, 1, epochDay)));
        }
        ranked.sort((a, b) -> Double.compare(a.desire(), b.desire())); // lowest desire first
        for (int i = 0; i < excess && i < ranked.size(); i++) {
            bm.logoutManagedBot(ranked.get(i).charId());
            onlineSince.remove(ranked.get(i).charId());
        }
    }

    private static long sessionMs(BotEntry e) {
        int min = e.personality != null ? e.personality.sessionLenMeanMin() : 60;
        return Math.max(1, min) * 60_000L;
    }
}
