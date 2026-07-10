# LOD abstract grind: the kill-rate estimator measured a peak, not a sustained rate (2026-07-10)

`BotKillCalibration` feeds the unobserved-map abstract grind. It measured the wrong statistic, and the
error was large enough to distort the whole living economy. This entry records the failure mode, the
signatures that identify it, and how to verify a fix against live ground truth.

## The defect

`recordKill` estimated kills/hr as an EMA of *instantaneous* samples `3_600_000 / interval`. That is a
mean of reciprocals. By Jensen's inequality `E[1/X] > 1/E[X]`, so it reports the rate the bot hits on its
best kills, not the rate it sustains. The quantity it was compared against — and the quantity the abstract
grind must replay — is `BotGrindPlanner.killsPerHour`, which is explicitly **sustained**: it adds
`seekSeconds` and caps at spawn supply. Two different statistics, silently divided by each other.

Two amplifiers rode on top:

1. **The guard ate the anchor.** `st.lastKillAtMs = now` ran *before* the `MIN_INTERVAL_MS` (250ms)
   rejection. An AoE cast that killed five mobs advanced the anchor five times but recorded one sample,
   at the *burst-gap* rate. Kills 2..5 vanished. The estimator's output is pinned near the burst rate no
   matter how many mobs die per burst — so it **inverts the class ranking**: mages measured *slower* than
   single-target classes.
2. **The uncalibrated fallback killed anyway.** `abstractKillDelayMs` returned `5_000L` when no rate
   existed, but `tickAbstractGrind` killed first and re-armed after. The javadoc called it an "idle
   re-check"; it was a flat **720 kills/hr floor** applied regardless of job, level, or map.

A third, subtler mismatch: `updateBucket`'s denominator is the advisor prediction *after*
`withSpawnShare` divides spawn points by party size, while the numerator was a solo peak. The worst
buckets were therefore all party classes.

## Signatures (how to recognise it again)

- `logs/bot-kill-calibration.tsv` rates saturating at exactly **14400.0** = `3_600_000 / MIN_INTERVAL_MS`.
  A stored rate pinned to a constant derived from a guard means the guard, not the world, is setting it.
- `/api/killcalib` bucket `ratio` far from 1.0 — observed up to **432x** (FPMage), 409x (ChiefBandit),
  250x (Cleric), 144x (IL). Warriors/Pages/Spearmen sat at 1.3-1.5x because their kills are slow and
  single-target, i.e. least affected by both biases.
- AoE-capable bots realising *lower* kills/hr than single-target bots. Real MapleStory is the reverse.
- Realised kills/hr flat across level bands (80/90/100/110 all at ~690) — the 720/hr fallback floor,
  which by construction cannot depend on level.
- Bots exceeding `3600 / seekSeconds(spawnPoints)`, the rate an *observed* bot could not beat even with
  an instant kill. 33% of the abstract-grinding roster was over that ceiling.

## The corrected estimator

`RateState` stores a decayed `(kills, activeMs)` pair; the rate is the **ratio** `kills / activeMs`. Each
kill is billed to exactly the time that elapsed to earn it. An AoE burst adds five kills and almost no
time, which is precisely the rate achieved — so `MIN_INTERVAL_MS` is not needed and was deleted. Gaps
above `MAX_INTERVAL_MS` re-anchor without billing idle time. `MIN_ACTIVE_MS` suppresses a rate until the
spot has been worked long enough to mean something.

Decayed numerator and denominator make the ratio a fading sustained rate: uniform intervals give exactly
`3.6e6 / gap`, and the ratio is invariant to the decay. The estimate lands a few percent above a flat
cycle mean when a burst ends the cycle, because the cheap kills are the most recent — benign.

Persistence is versioned `RATE2` / `BUCKET2`. The old `RATE` / `BUCKET` rows store a different quantity
and are deliberately dropped on load; loading them would re-poison the store.

## One denominator everywhere (no committed predictions)

The first fix ratioed measured rates against the advisor's plan-time prediction, kept per bot with a
30-minute freshness window. That both starved and skewed: a directed `!goto` pins
`autopilotNextDecisionAtMs = Long.MAX_VALUE` (never re-decides), owner-commanded grinds and restarts
never install a prediction at all — those bots fell to `kph = 0` and stopped earning while unobserved.
And the prediction was party-spawn-shared at plan time, so the bucket's numerator and denominator were
not the same statistic.

The prediction store is deleted. The single denominator is `BotGrindAdvisor.modeledKillsPerHour` — the
same planner math (`profileFor → blendCandidate → killsPerHour`) restricted to the bot's current map,
entry-cached ~10 min, cheap enough for a tick thread (no gear pass, no aspirational-mob caching: those
exist to *pick* a map, and the map is already picked). The bucket learns `measured / model` and the
abstract grind replays `model × bucket` (own fresh per-map rate still wins when present), so the
model's systematic error cancels and every grinding bot has a rate immediately — no plan required, no
staleness cliff. Average contention is absorbed into the bucket; the spawn-limited miss on an empty map
stays as a supply *cap* (it clips excess demand, it does not thin proportionally), so contention is not
double-counted.

## Rates are per-map, not per-(map,mob)

The first corrected store keyed rates by (map,mob). A real grinder interleaves the map's mob types, so
one mob's kill stream carries only that mob's *share* of the rate — and the abstract grind replayed a
single mob's partial rate. Measured live: snipers on a two-mob map at bias 0.47, vs 0.92 on a
single-dominant-mob map. The store now pools the whole map (`RATE3`/`BUCKET4`); which mob dies stays
emergent (nearest-target picking, quest mobs preferred, bot steps to the mob so loot lands in
passive-loot range).

## Verified (2026-07-10, ~200-bot roster)

Paired pinned-map run (14 maps, 5-min windows): **median bias 1.00x** (range 0.88–1.28, n=5 joined),
AoE median 0.93 vs single-target 1.03 — no class inversion, no floor cluster, no zero-kill cluster in
the abstract arm. Residual known bias: a bot that is *broken at LOD0* (stuck/nav, kills nothing for
minutes while `grinding`) still earns the modeled rate abstractly — LOD models a working grinder;
fix the stuck bot, not the model. The audit tool warns about zero-kill stable grinders in both arms
to surface exactly these.

Known imperfection: `/api/killcalib` bucket ratios spread wide (typically 0.2–0.7; isolated extremes
like 14.6x for one bandit band with many samples). The bucket cancels model error *on average per
(job, band)* — which is why bias lands at 1.0 — but a bucket learned mostly on one map generalizes
imperfectly to another. The next accuracy lever is shrinking the model error itself (kill-time /
attack-cycle realism per class), not more calibration machinery.

## Verifying against ground truth

Full fidelity for the whole roster does not fit: on a 6-core box, clearing all four
`SIMPLIFY_UNOBSERVED_BOTS_*` flags with ~560 unobserved bots exceeds 4.7 process cores and **starves the
bot tick pool** (ticks queue, `tick-total` *falls* as the machine works harder). A starved pool depresses
the very kill rate you are trying to measure, so a global flag flip cannot produce ground truth.

Instead pin a few maps with `/api/lod?maps=<ids>` — those bots hold LOD0 and run real combat while the
rest of the server stays coarse. `tools/lod_grind_audit.py` drives the paired experiment: pin, measure
`realKills`/hr, release, measure `absKills`/hr over the same bots on the same maps with the same rivals.
`bias = abstract / real`; LOD is outcome-preserving only at 1.0. The pinned arm doubles as the training
signal, since the calibration only learns from real kills.

`realKills` and `absKills` on `/api/botdebug` are the audit pair. Note the asymmetry that makes pinning
necessary: an abstract-grinding bot never records a real kill, so calibration is fed only by bots on
observed maps, travelling, partied with a real player, or pinned.

## Related

- [`kb_bot_grind_doctrine.md`](kb_bot_grind_doctrine.md) — the shared grind path the abstract kill replaces.
- [`kb_bot_aoe_cluster_target_bias.md`](kb_bot_aoe_cluster_target_bias.md) — why AoE kills arrive in bursts.
- [`../web-endpoints.md`](../web-endpoints.md) — `/api/lod`, `/api/killcalib`, `/api/botdebug`, `/api/perf`.
