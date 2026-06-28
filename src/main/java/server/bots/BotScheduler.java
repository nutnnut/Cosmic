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
        // Familiarity tracking runs regardless of the population scheduler (players can party bots by
        // hand). 30s sampling bounds only the time-together granularity, which is plenty.
        TimerManager.getInstance().register(
                () -> BotFamiliarityManager.getInstance().sample(System.currentTimeMillis()),
                30_000L, ThreadLocalRandom.current().nextLong(5_000L));
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
        boolean was = BotManager.cfg.POPULATION_SCHED_ENABLED;
        BotManager.cfg.POPULATION_SCHED_ENABLED = on;
        if (on && !was) {
            kickFastStart();
        }
    }

    /** Just toggled on: sweep NOW and again every POPULATION_FASTSTART_INTERVAL_MS across
     *  POPULATION_FASTSTART_MS, so the world ramps in over ~30s instead of waiting up to a full
     *  POPULATION_SWEEP_MS for the steady timer's next tick. Combined with the small per-sweep fill
     *  this trickles bots in (organic) rather than bursting them at one timestamp. Each sweep
     *  self-guards on the enabled flag, so disabling again mid-ramp stops it. */
    private void kickFastStart() {
        TimerManager tm = TimerManager.getInstance();
        long step = Math.max(1_000L, BotManager.cfg.POPULATION_FASTSTART_INTERVAL_MS);
        for (long t = 0; t <= BotManager.cfg.POPULATION_FASTSTART_MS; t += step) {
            tm.schedule(this::sweep, t);
        }
    }

    /** Force a reconcile now (no-op if disabled). */
    public void sweepNow() {
        sweep();
    }

    /** Hourly online target, scaled by POPULATION_MULTIPLIER so the whole population is adjustable
     *  without rewriting the curve/noise. */
    private static int scaledTarget(int hour, int noise, double roll) {
        int base = BotScheduleMath.targetForHour(BotManager.cfg.POPULATION_CURVE, hour, noise, roll);
        return Math.max(0, (int) Math.round(base * BotManager.cfg.POPULATION_MULTIPLIER));
    }

    /** Set the population multiplier (0 = no bots, 1 = 1x curve, 3 = 3x, ...). Clamped >= 0. */
    public void setMultiplier(double mult) {
        BotManager.cfg.POPULATION_MULTIPLIER = Math.max(0.0, mult);
    }

    public double getMultiplier() {
        return BotManager.cfg.POPULATION_MULTIPLIER;
    }

    public List<String> statusLines() {
        int hour = LocalTime.now().getHour();
        int target = scaledTarget(hour, 0, 0.5);
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
                "multiplier: " + BotManager.cfg.POPULATION_MULTIPLIER + "x",
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
        int target = scaledTarget(hour, BotManager.cfg.POPULATION_NOISE, ThreadLocalRandom.current().nextDouble());

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

        // --- 1. SOLOIST session-length logouts + live census (crew members are handled as a unit in
        // step 2, so they're skipped here — never logged out individually mid-crew-session). ---
        List<Integer> soloLive = new ArrayList<>();
        for (ManagedBot m : managed) {
            if (!m.schedulable() || m.groupId() != null) {
                continue; // unschedulable, or crewed (step 2 owns crews as a unit)
            }
            BotEntry e = bm.getEntryByBotCharId(m.botCharId());
            if (e == null) {
                onlineSince.remove(m.botCharId());
                continue;
            }
            long since = onlineSince.computeIfAbsent(m.botCharId(), k -> now);
            // Stay-online QoL: a bot grouped with a real player keeps playing past its session end.
            if (BotScheduleMath.sessionElapsed(since, sessionMs(e), now)
                    && !BotManager.partyHasRealPlayer(e.bot)) {
                bm.logoutManagedBot(m.botCharId());
                onlineSince.remove(m.botCharId());
            } else {
                soloLive.add(m.botCharId());
            }
        }

        // --- 2. crews: keep each crew online together as one unit on its leader's schedule ---
        int crewLive = cohereCrews(bm, managed, hour, epochDay, now, target, soloLive.size());

        // --- 3. reconcile the SOLOIST count so total (crew + solo) tracks the target ---
        int live = soloLive.size() + crewLive;
        if (live < target) {
            bringOnline(bm, managed, hour, epochDay, target - live, now);
        } else if (live > target) {
            logOutExcess(bm, soloLive, hour, epochDay, live - target);
        }
    }

    /** charId of a crew's leader = its lowest member char id (deterministic, stable across restarts). */
    private static int crewLeader(List<ManagedBot> members) {
        int leader = Integer.MAX_VALUE;
        for (ManagedBot m : members) {
            leader = Math.min(leader, m.botCharId());
        }
        return leader;
    }

    /** when each crew (group id) was brought online together, for the shared leader-paced session. */
    private final Map<Integer, Long> crewOnlineSince = new ConcurrentHashMap<>();

    /** last live-cohort fingerprint per crew (leader id), so a join/leave triggers a party re-decide. */
    private static final Map<Integer, Integer> crewCohortSig = new ConcurrentHashMap<>();

    /**
     * Persistent crews ({@code managed_bot.group_id}): a crew logs in TOGETHER and parties up, on its
     * leader's personality schedule, and logs out together when that session elapses. Returns how many
     * crew members are live after this pass (counted toward the population target). New crews are brought
     * up only while under target (so crews still respect the curve), but once up the whole crew stays
     * together until its shared session ends — it's never thinned by the soloist reconcile.
     */
    private int cohereCrews(BotManager bm, List<ManagedBot> managed, int hour, long epochDay, long now,
                            int target, int soloLiveCount) {
        Map<Integer, List<ManagedBot>> crews = new java.util.HashMap<>();
        for (ManagedBot m : managed) {
            if (m.schedulable() && m.groupId() != null) {
                crews.computeIfAbsent(m.groupId(), k -> new ArrayList<>()).add(m);
            }
        }

        int crewLive = 0;
        List<Map.Entry<Integer, List<ManagedBot>>> offlineCrews = new ArrayList<>();

        // Pass A: maintain crews that already have a member online (cohere or end-of-session logout).
        for (Map.Entry<Integer, List<ManagedBot>> e : crews.entrySet()) {
            int gid = e.getKey();
            List<ManagedBot> members = e.getValue();
            boolean anyLive = members.stream().anyMatch(m -> bm.getEntryByBotCharId(m.botCharId()) != null);
            if (!anyLive) {
                crewOnlineSince.remove(gid);
                offlineCrews.add(e);
                continue;
            }
            long since = crewOnlineSince.computeIfAbsent(gid, k -> now);
            BotPersonality leaderP = BotPersonality.parse(
                    BotConfigService.getInstance().load(crewLeader(members)));
            // Stay-online QoL: keep the whole crew online if any member is partied with a real player.
            boolean crewWithPlayer = members.stream().anyMatch(m -> {
                BotEntry me = bm.getEntryByBotCharId(m.botCharId());
                return me != null && BotManager.partyHasRealPlayer(me.bot);
            });
            if (BotScheduleMath.sessionElapsed(since, sessionMsOf(leaderP), now) && !crewWithPlayer) {
                for (ManagedBot m : members) {
                    if (bm.getEntryByBotCharId(m.botCharId()) != null) {
                        bm.logoutManagedBot(m.botCharId());
                    }
                }
                crewOnlineSince.remove(gid);
                continue;
            }
            boolean broughtAny = false;
            for (ManagedBot m : members) {
                if (bm.getEntryByBotCharId(m.botCharId()) == null && bm.spawnManagedBot(m.botCharId())) {
                    broughtAny = true;
                }
            }
            formCrewParty(bm, members, broughtAny);
            crewLive += liveCount(bm, members);
        }

        // Pass B: bring up fully-offline crews (whole-crew), most-eager leader first, while under target.
        offlineCrews.sort((a, b) -> Double.compare(
                leaderDesire(b.getValue(), hour, epochDay), leaderDesire(a.getValue(), hour, epochDay)));
        int liveNow = soloLiveCount + crewLive;
        for (Map.Entry<Integer, List<ManagedBot>> e : offlineCrews) {
            if (liveNow >= target) {
                break;
            }
            if (leaderDesire(e.getValue(), hour, epochDay) <= 0.0) {
                continue;
            }
            if (liveNow + e.getValue().size() > target) {
                continue; // crew would overshoot; soloists fill the remaining gap
            }
            boolean broughtAny = false;
            for (ManagedBot m : e.getValue()) {
                if (bm.spawnManagedBot(m.botCharId())) {
                    broughtAny = true;
                }
            }
            if (broughtAny) {
                crewOnlineSince.put(e.getKey(), now);
                formCrewParty(bm, e.getValue(), true);
                int n = liveCount(bm, e.getValue());
                crewLive += n;
                liveNow += n;
            }
        }
        return crewLive;
    }

    private static double leaderDesire(List<ManagedBot> members, int hour, long epochDay) {
        BotPersonality p = BotPersonality.parse(BotConfigService.getInstance().load(crewLeader(members)));
        return BotScheduleMath.onlineDesire(p, hour, 1, epochDay);
    }

    private static int liveCount(BotManager bm, List<ManagedBot> members) {
        int n = 0;
        for (ManagedBot m : members) {
            if (bm.getEntryByBotCharId(m.botCharId()) != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * Form (or refresh) the crew's real party around its leader and, when a member was just brought up,
     * issue ONE shared cohort directive ({@link BotAutopilotManager#startParty}) so they grind together.
     * partyUp is skipped for members already in the leader's party, so the steady-state sweep is cheap.
     */
    private static void formCrewParty(BotManager bm, List<ManagedBot> members, boolean runStartParty) {
        List<BotEntry> live = new ArrayList<>();
        BotEntry leaderEntry = null;
        int leaderId = crewLeader(members);
        for (ManagedBot m : members) {
            BotEntry e = bm.getEntryByBotCharId(m.botCharId());
            if (e != null && e.bot != null) {
                live.add(e);
                if (m.botCharId() == leaderId) {
                    leaderEntry = e;
                }
            }
        }
        if (live.size() < 2) {
            return; // a lone crew member just parties up later when a mate arrives
        }
        BotEntry leader = leaderEntry != null ? leaderEntry : live.get(0);
        for (BotEntry e : live) {
            if (e == leader) {
                continue;
            }
            if (leader.bot.getParty() == null || e.bot.getParty() == null
                    || e.bot.getParty().getId() != leader.bot.getParty().getId()) {
                bm.partyUp(leader.bot, e.bot);
            }
        }
        // Self-heal: (re)issue the shared party directive when a member was just brought up
        // (runStartParty), when the live cohort changed since last sweep (a member joined OR left —
        // re-decide for the new composition), or when ANY live member isn't party-grinding. The
        // spawn-time startParty no-ops if it raced bot login (getMap() null) or decideParty returned
        // null under cold load, and the steady sweep otherwise wouldn't retry for a single straggler —
        // leaving it scattered in solo autopilot while its crew grinds (observed: gnumage apParty=false
        // while crewmates were true). anyMatch (was noneMatch) catches that one-member case; the
        // membership-change check covers leaves (remaining members all still have autopilotParty, so
        // anyMatch alone wouldn't fire). startParty's stay-put hysteresis keeps these re-decides from
        // relocating the party off a good map for a marginal gain.
        // ponytail: coarse scheduler cadence; if decideParty keeps returning null it re-fires per sweep
        // (fine at minute granularity) — add an in-flight gate only if it shows in perf.
        int crewKey = crewLeader(members);
        int sig = cohortSignature(live);
        Integer prevSig = crewCohortSig.put(crewKey, sig);
        boolean membershipChanged = prevSig == null || prevSig != sig;
        if (runStartParty || membershipChanged || live.stream().anyMatch(e -> !e.autopilotParty)) {
            BotAutopilotManager.startParty(leader.bot, live);
        }
    }

    /** Order-independent fingerprint of the live cohort's char ids — changes iff a member joins or
     *  leaves, so {@link #formCrewParty} can re-decide on composition change. */
    private static int cohortSignature(List<BotEntry> live) {
        int[] ids = live.stream().mapToInt(e -> e.bot.getId()).sorted().toArray();
        return java.util.Arrays.hashCode(ids);
    }

    private static long sessionMsOf(BotPersonality p) {
        int min = p != null ? p.sessionLenMeanMin() : 60;
        return Math.max(1, min) * 60_000L;
    }

    /** Bring the most-eager offline managed bots online (active-today, hour-preferred ranked first). */
    private void bringOnline(BotManager bm, List<ManagedBot> managed, int hour, long epochDay, int need, long now) {
        List<Candidate> cands = new ArrayList<>();
        for (ManagedBot m : managed) {
            if (!m.schedulable() || m.groupId() != null || bm.getEntryByBotCharId(m.botCharId()) != null) {
                continue; // crewed bots come online as a unit in cohereCrews, never as soloists
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
        // Still short after waking everyone eligible: generate fresh bots, gated by the autogen flag +
        // the non-retired pool cap. A deficit-proportional batch (not one per sweep) so a wiped/booted
        // world catches up to the curve in a couple of sweeps.
        if (brought < need) {
            int poolSize = 0;
            for (ManagedBot m : managed) {
                if (!m.retired()) {
                    poolSize++;
                }
            }
            int gen = BotScheduleMath.autogenCount(BotManager.cfg.POPULATION_AUTOGEN, need, brought, 0,
                    poolSize, BotManager.cfg.MANAGED_POOL_MAX,
                    BotManager.cfg.POPULATION_AUTOGEN_FILL, BotManager.cfg.POPULATION_AUTOGEN_MAX);
            if (gen > 0) {
                // ponytail: hardcore count computed once per sweep, not per generated bot — countHardcore
                // loads a personality blob per managed bot, so calling it per-create would be the cost
                // this batching was meant to avoid. Slight staleness within a sweep is fine (soft cap).
                int hardcore = BotGenerator.countHardcore(managed);
                // Generate the batch as a mix of lone newcomers and emergent CREWs (friend groups that
                // arrive together); each is bounded by the budget still left this sweep.
                int remaining = gen;
                while (remaining > 0) {
                    int crewSize = rollCrewSize(remaining);
                    if (crewSize >= 2) {
                        generateCrew(bm, managed, crewSize, now, hardcore);
                        remaining -= crewSize;
                    } else {
                        int newId = BotGenerator.generateManaged(BotManager.cfg.POPULATION_WORLD,
                                BotManager.cfg.POPULATION_CHANNEL, hardcore, BotManager.cfg.HARDCORE_CAP);
                        if (newId > 0 && bm.spawnManagedBot(newId)) {
                            onlineSince.put(newId, now);
                        }
                        remaining -= 1;
                    }
                }
            } else {
                log.debug("population under target by {} (autogen off or pool at cap)", need - brought);
            }
        }
    }

    /** Crew size for an autogen event: with {@code POPULATION_CREW_CHANCE} a 2..MAX crew (capped by the
     *  remaining pool room), else 1 (a lone newcomer). Returns 1 when there's no room for a crew. */
    private static int rollCrewSize(int room) {
        if (room < 2 || ThreadLocalRandom.current().nextDouble() >= BotManager.cfg.POPULATION_CREW_CHANCE) {
            return 1;
        }
        int lo = Math.max(2, BotManager.cfg.POPULATION_CREW_MIN);
        int hi = Math.min(room, Math.max(lo, BotManager.cfg.POPULATION_CREW_MAX));
        return lo >= hi ? lo : lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    /** Generate a fresh crew that arrives together: {@code size} new managed bots sharing one group id
     *  (the leader's char id), brought online and partied immediately. */
    private void generateCrew(BotManager bm, List<ManagedBot> managed, int size, long now, int hardcore) {
        ManagedBotService svc = ManagedBotService.getInstance();
        List<Integer> ids = new ArrayList<>();
        Integer gid = null;
        for (int i = 0; i < size; i++) {
            int id = BotGenerator.generateManaged(BotManager.cfg.POPULATION_WORLD,
                    BotManager.cfg.POPULATION_CHANNEL, hardcore, BotManager.cfg.HARDCORE_CAP);
            if (id <= 0) {
                continue;
            }
            if (gid == null) {
                gid = id; // crew id = its leader's (first member's) char id — unique, collision-free
            }
            svc.setGroup(id, gid);
            ids.add(id);
        }
        if (gid == null || ids.size() < 2) {
            // 0-1 actually created (gen failure / collisions): bring the lone one up as a soloist.
            for (int id : ids) {
                svc.setGroup(id, null);
                if (bm.spawnManagedBot(id)) {
                    onlineSince.put(id, now);
                }
            }
            return;
        }
        List<ManagedBot> crew = new ArrayList<>();
        for (int id : ids) {
            if (bm.spawnManagedBot(id)) {
                crew.add(new ManagedBot(id, gid, true, false, now));
            }
        }
        crewOnlineSince.put(gid, now);
        formCrewParty(bm, crew, true);
    }

    /** Log out the least-eager live bots down to the target. */
    private void logOutExcess(BotManager bm, List<Integer> live, int hour, long epochDay, int excess) {
        List<Candidate> ranked = new ArrayList<>();
        for (int charId : live) {
            BotEntry e = bm.getEntryByBotCharId(charId);
            if (e != null && BotManager.partyHasRealPlayer(e.bot)) {
                continue; // stay-online QoL: don't thin a bot grouped with a real player
            }
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
