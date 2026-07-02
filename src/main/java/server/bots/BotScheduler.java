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
        sweep(false);
    }

    /** {@code stableTarget}=true reconciles to the deterministic median target (the value
     *  {@link #statusLines()} displays) instead of rolling fresh noise — used by manual admin sweeps so
     *  repeated clicks converge to the shown target rather than chasing a random, multiplier-amplified
     *  noise band (and ratcheting up, since spawns are immediate but logouts linger). */
    void sweep(boolean stableTarget) {
        if (!BotManager.cfg.POPULATION_SCHED_ENABLED) {
            return;
        }
        try {
            reconcile(stableTarget);
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

    /** Force a reconcile now (no-op if disabled). Reconciles to the deterministic median target the
     *  status line shows, so an admin "Sweep now" converges to that count instead of overshooting a
     *  fresh random noise roll each click. */
    public void sweepNow() {
        sweep(true);
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

    private void reconcile(boolean stableTarget) {
        long now = System.currentTimeMillis();
        int hour = LocalTime.now().getHour();
        long epochDay = LocalDate.now().toEpochDay();
        int target = stableTarget
                ? scaledTarget(hour, 0, 0.5)
                : scaledTarget(hour, BotManager.cfg.POPULATION_NOISE, ThreadLocalRandom.current().nextDouble());

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

        // --- 2. crews: keep each crew online together as one unit on its leader's schedule, capped at the
        // crew SHARE of the target so crews don't crowd out soloists. ---
        int crewTarget = (int) Math.round(target * BotManager.cfg.POPULATION_CREW_FRACTION);
        int crewLive = cohereCrews(bm, managed, hour, epochDay, now, crewTarget);

        // --- 3. reconcile the SOLOIST count to fill the rest of the target (whatever crews didn't) ---
        int soloTarget = Math.max(0, target - crewLive);
        if (soloLive.size() < soloTarget) {
            bringOnline(bm, managed, hour, epochDay, soloTarget - soloLive.size(), now);
        } else if (soloLive.size() > soloTarget) {
            logOutExcess(bm, soloLive, hour, epochDay, soloLive.size() - soloTarget);
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
     * up (or generated) only while under {@code crewTarget} (the crew share of the population), so crews
     * track the curve AND their target distribution instead of crowding out soloists. Once up the whole
     * crew stays together until its shared session ends — it's never thinned by the soloist reconcile.
     */
    private int cohereCrews(BotManager bm, List<ManagedBot> managed, int hour, long epochDay, long now,
                            int crewTarget) {
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
            // A crew that logged in to chill runs a half-length session too (driven off the leader's flag).
            BotEntry leaderEntry = bm.getEntryByBotCharId(crewLeader(members));
            boolean crewChill = leaderEntry != null && leaderEntry.chillSession;
            long crewSessionMs = crewChill ? sessionMsOf(leaderP) / 2 : sessionMsOf(leaderP);
            // Stay-online QoL: keep the whole crew online if any member is partied with a real player.
            boolean crewWithPlayer = members.stream().anyMatch(m -> {
                BotEntry me = bm.getEntryByBotCharId(m.botCharId());
                return me != null && BotManager.partyHasRealPlayer(me.bot);
            });
            if (BotScheduleMath.sessionElapsed(since, crewSessionMs, now) && !crewWithPlayer) {
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
            if (crewChill) { // a respawned straggler joins its crew's chill instead of grinding alone
                for (ManagedBot m : members) {
                    applyCrewChill(bm, m.botCharId(), now);
                }
            }
            formCrewParty(bm, members, broughtAny);
            crewLive += liveCount(bm, members);
        }

        // Pass B: bring up fully-offline crews (whole-crew), most-eager leader first, up to the crew share.
        offlineCrews.sort((a, b) -> Double.compare(
                leaderDesire(b.getValue(), hour, epochDay), leaderDesire(a.getValue(), hour, epochDay)));
        for (Map.Entry<Integer, List<ManagedBot>> e : offlineCrews) {
            if (crewLive >= crewTarget) {
                break;
            }
            if (leaderDesire(e.getValue(), hour, epochDay) <= 0.0) {
                continue;
            }
            if (crewLive + e.getValue().size() > crewTarget) {
                continue; // crew would overshoot its share; a smaller crew (or soloists) fills the gap
            }
            boolean broughtAny = false;
            for (ManagedBot m : e.getValue()) {
                if (bm.spawnManagedBot(m.botCharId())) {
                    broughtAny = true;
                }
            }
            if (broughtAny) {
                crewOnlineSince.put(e.getKey(), now);
                markCrewSession(bm, e.getValue(), now);
                formCrewParty(bm, e.getValue(), true);
                crewLive += liveCount(bm, e.getValue());
            }
        }
        // Still short of the crew share after waking every offline crew: generate fresh crews. Crews erode
        // via career retirement, so without this the live crew share would decay to zero over time.
        if (crewLive < crewTarget) {
            crewLive += autogenCrews(bm, managed, crewTarget - crewLive, now);
        }
        return crewLive;
    }

    /** Generate fresh crews to fill up to {@code crewGap} more live crew members, so the crew share holds
     *  as crews retire. Shares the soloist autogen budget (flag + per-sweep batch + pool cap). Returns the
     *  number of crew members actually brought online. */
    private int autogenCrews(BotManager bm, List<ManagedBot> managed, int crewGap, long now) {
        int poolSize = 0;
        for (ManagedBot m : managed) {
            if (!m.retired()) {
                poolSize++;
            }
        }
        int gen = BotScheduleMath.autogenCount(BotManager.cfg.POPULATION_AUTOGEN, crewGap, 0, 0,
                poolSize, BotManager.cfg.MANAGED_POOL_MAX,
                BotManager.cfg.POPULATION_AUTOGEN_FILL, BotManager.cfg.POPULATION_AUTOGEN_MAX);
        if (gen < 2) {
            return 0; // no room this sweep for even a 2-member crew
        }
        int hardcore = BotGenerator.countHardcore(managed);
        int brought = 0;
        int remaining = gen;
        while (remaining >= 2) {
            int size = crewSize(remaining);
            int n = generateCrew(bm, managed, size, now, hardcore);
            if (n == 0) {
                break; // generation failing — don't spin
            }
            brought += n;
            remaining -= size;
        }
        return brought;
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
                beginSoloSession(bm, c.charId(), now);
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
                // Soloist deficit → generate soloists only; crews are generated by cohereCrews so they stay
                // bounded by the crew share (POPULATION_CREW_FRACTION) instead of skewing the live split.
                for (int i = 0; i < gen; i++) {
                    int newId = BotGenerator.generateManaged(BotManager.cfg.POPULATION_WORLD,
                            BotManager.cfg.POPULATION_CHANNEL, hardcore, BotManager.cfg.HARDCORE_CAP);
                    if (newId > 0 && bm.spawnManagedBot(newId)) {
                        beginSoloSession(bm, newId, now);
                    }
                }
            } else {
                log.debug("population under target by {} (autogen off or pool at cap)", need - brought);
            }
        }
    }

    /** A crew's size, in [CREW_MIN, CREW_MAX], capped by the remaining {@code room} this sweep. */
    private static int crewSize(int room) {
        int lo = Math.max(2, BotManager.cfg.POPULATION_CREW_MIN);
        int hi = Math.min(room, Math.max(lo, BotManager.cfg.POPULATION_CREW_MAX));
        return lo >= hi ? lo : lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    /** Generate a fresh crew that arrives together: {@code size} new managed bots sharing one group id
     *  (the leader's char id), brought online and partied immediately. Returns the number of CREW members
     *  brought online (0 if it degenerated to a lone soloist). */
    private int generateCrew(BotManager bm, List<ManagedBot> managed, int size, long now, int hardcore) {
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
            return 0;
        }
        List<ManagedBot> crew = new ArrayList<>();
        for (int id : ids) {
            if (bm.spawnManagedBot(id)) {
                crew.add(new ManagedBot(id, gid, true, false, now));
            }
        }
        crewOnlineSince.put(gid, now);
        markCrewSession(bm, crew, now);
        formCrewParty(bm, crew, true);
        return crew.size();
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

    /** Mark a soloist's session start: record login time and, for a personality-driven fraction of
     *  logins, make it a CHILL session — the bot routes to town and lingers there the whole (half-length)
     *  session instead of grinding (reuses the town-break machinery; near-zero tick cost). */
    private void beginSoloSession(BotManager bm, int charId, long now) {
        onlineSince.put(charId, now);
        BotEntry e = bm.getEntryByBotCharId(charId);
        if (e == null) {
            return;
        }
        BotPersonality p = e.personality != null ? e.personality : BotPersonality.defaults();
        if (BotBreakManager.rollChill(p, e.bot.getLevel(), ThreadLocalRandom.current().nextDouble())) {
            e.chillSession = true;
            BotBreakManager.startTownBreak(e, e.bot, now);
        } else if (ThreadLocalRandom.current().nextDouble()
                < BotBreakManager.loginBreakChance(p.breakFreqPerHour(), p.breakLenMeanMin())) {
            // Seed the resting fraction at login so the population hits its break/grind equilibrium
            // immediately, instead of every bot grinding at spawn and only settling over many minutes.
            BotBreakManager.startLoginBreak(e, e.bot, now);
        }
        // Same equilibrium trick for the market: seed the mid-market-day fraction (and stall
        // rebuilders) so the FM repopulates right after a restart instead of over the first hour.
        BotFreeMarketManager.maybeSeedLoginMarketDay(e, e.bot, now);
    }

    /** Decide once, when a crew session begins, whether the whole crew is logging in to CHILL (leader's
     *  personality + the global chance config); if so flag every live member and route them to town to
     *  linger together. Mirrors {@link #beginSoloSession} for crews — the leader speaks for the unit. */
    private void markCrewSession(BotManager bm, List<ManagedBot> members, long now) {
        BotEntry leaderEntry = bm.getEntryByBotCharId(crewLeader(members));
        if (leaderEntry == null || leaderEntry.bot == null) {
            return;
        }
        BotPersonality leaderP = BotPersonality.parse(BotConfigService.getInstance().load(crewLeader(members)));
        if (BotBreakManager.rollChill(leaderP, leaderEntry.bot.getLevel(), ThreadLocalRandom.current().nextDouble())) {
            for (ManagedBot m : members) {
                applyCrewChill(bm, m.botCharId(), now);
            }
        } else if (ThreadLocalRandom.current().nextDouble()
                < BotBreakManager.loginBreakChance(leaderP.breakFreqPerHour(), leaderP.breakLenMeanMin())) {
            // Seed the crew's resting fraction at login too, so crews hit equilibrium from spawn.
            for (ManagedBot m : members) {
                BotEntry e = bm.getEntryByBotCharId(m.botCharId());
                if (e != null) {
                    BotBreakManager.startLoginBreak(e, e.bot, now);
                }
            }
        }
        // Market-day seeding for crew members too (a third of the population): tickScan's reason
        // checks still own the trip decision, and crews already tolerate individual errands.
        for (ManagedBot m : members) {
            BotEntry e = bm.getEntryByBotCharId(m.botCharId());
            if (e != null) {
                BotFreeMarketManager.maybeSeedLoginMarketDay(e, e.bot, now);
            }
        }
    }

    /** Flag one crew member's session as chill and route it to town (no-op if not live or already chill).
     *  Used at crew session start and to fold a self-healed straggler into an already-chilling crew. */
    private static void applyCrewChill(BotManager bm, int charId, long now) {
        BotEntry e = bm.getEntryByBotCharId(charId);
        if (e != null && !e.chillSession) {
            e.chillSession = true;
            BotBreakManager.startTownBreak(e, e.bot, now);
        }
    }

    private static long sessionMs(BotEntry e) {
        int min = e.personality != null ? e.personality.sessionLenMeanMin() : 60;
        long ms = Math.max(1, min) * 60_000L;
        return e.chillSession ? ms / 2 : ms; // chill logins run half-length so more bots cycle in/out
    }
}
