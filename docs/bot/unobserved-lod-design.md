# Unobserved-map LOD ("nobody's watching") — design

Status: **DESIGN, approved direction by owner (2026-07-05), not yet implemented.**
Goal: 2000+ bots on this 6-core box (today: ~7.2 millicores/bot ⇒ ~600-bot ceiling).
Owner directive: when no real player can observe a bot, simplifying/abstracting its
simulation is allowed — but outcomes (exp, loot, meso, quests, resource consumption)
must stay as close as possible to full fidelity. Full fidelity restores the moment a
player can see the bot.

Companion: `docs/bot/perf-optimization-plan.md` (lossless fixes P0-P6; being landed
separately). This doc is the big lossy lever (formerly plan §P2 "tick thinning",
now generalized).

## 1. LOD levels

| Level | When | Simulation |
|---|---|---|
| LOD0 | bot's map is observed OR within 1 portal edge of an observed map, OR bot's party contains a real player, OR bot is in an active trade/owner interaction | today's full 50ms physics/nav/combat |
| LOD1 | otherwise (with ~10s hysteresis on downgrade) | coarse 500ms tick, no physics, no live A*, abstract grind |

With 0-1 players online (the normal case), ~all bots run LOD1; only the one player's
map neighborhood (plus their party's bots) pays full price.

### Observer tracking
- Observer SSOT = existing `MapleMap.isObservedByPlayer()` (`MapleMap.java:3134`) —
  already the broadcast-suppression predicate at 4 bot call sites, already counts
  hidden GMs, already excludes `BotClient`s. No new counter unless profiling shows the
  `chrRLock` scan hot at LOD-check cadence (LOD checks run at coarse cadence, so
  unlikely; do NOT pre-optimize).
- **1-edge pre-warm:** a map is *effectively observed* if it or any portal-neighbor map
  (via `BotWorldGraph` portal adjacency, resolved once per map, cached) is observed.
  This warms bots on adjacent maps BEFORE a player can walk through the portal, so
  nobody sees a snap-into-position on map entry; combined with the addPlayer snap
  (§4) as the backstop for teleports/scrolls that skip adjacency.
- **`!hidebot` GM command:** toggles a per-GM "non-observer" flag consulted inside
  `isObservedByPlayer()` (a flagged GM never counts as an observer anywhere). Purpose:
  let the owner walk around and watch LOD1 bots in their raw coarse state (lerped
  positions, kill events, no physics) for debugging/curiosity without their presence
  promoting maps to LOD0. Because the same predicate gates broadcasts, a hidebot GM
  sees the true unobserved simulation — that is the point. Distinct from `!hide`
  (which stays a real observer).
- `BotEntry.lod` field + `BotManager` decides per tick-entry: effectivelyObserved(map)
  || partyHasRealPlayer (predicate already exists for scheduler gating) → LOD0.
- Downgrade LOD0→LOD1 only after the map (and its 1-edge neighborhood) has been
  player-free for ≥10s (hysteresis, avoids thrash on map-hopping players).

### Cadence switch
Per-bot tick is a fixed-rate 50ms TimerManager task registered at
`BotManager.registerBotInternal` (`BotManager.java:903`); `BotEntry.task` is final today.
Make it reassignable behind a small `retask(entry, intervalMs)` helper (cancel + re-register,
same pattern as registration; guard against the double-register race class —
see `kb_bot_double_register_botpop_race`). LOD1 interval: 500ms (10x fewer wakeups).
The `consumeAiTick` accumulator (`BotManager.java:6652`) keeps working — it accumulates
elapsed ms, not tick counts.

### Config toggles (owner requirement)
Each LOD1 simplification is its own boolean in the `BotManager.cfg` block (next to
`POPULATION_MULTIPLIER`, `BotManager.java:190`), **default true**; `false` restores
existing full-fidelity behavior for that subsystem independently (so any one
simplification can be bisected/disabled live without touching the others):
- `SIMPLIFY_UNOBSERVED_BOTS_PHYSICS` — §2.1 motion-plan movement instead of physics/nav
- `SIMPLIFY_UNOBSERVED_BOTS_TRAVEL` — §2.2 timed warps instead of executed hops
- `SIMPLIFY_UNOBSERVED_BOTS_GRIND` — §2.3 abstract kill events instead of real combat
- `SIMPLIFY_UNOBSERVED_BOTS_CADENCE` — the 50ms→500ms retask itself
All four false ⇒ bit-for-bit today's behavior (LOD predicate still computed but inert).
Toggles should be flippable at runtime via the existing `/api/settings` cmd surface.

## 2. LOD1 behavior by subsystem

### 2.1 In-map movement — "fly at realistic speed", no physics
- No `move-ground`/`move-air`/`nav-resolve`/foothold collision. Bot state = a
  **motion plan**: (fromPos, toPos, departAtMs, arriveAtMs).
- Duration = pixelPathDistance / groundSpeedPxPerSec (real speed stat from the movement
  profile — same source physics uses), plus jitter (±10%). For pathDistance use the
  committed-route edge chain if one exists (`BotEntry.committedRoute`,
  `BotEntry.java:815`); if not, straight-line ×1.3 slack factor. Do NOT run live A* to
  get a distance — if no route is committed, straight-line is fine (nobody sees it).
- Position is only materialized lazily: on coarse tick, lerp along (from,to) by time.
  Y: keep last-known foothold Y until arrival; exact Y only matters at observation
  transition (§4).
- Ladders/jumps/launch windows: not simulated at all. That's the point.

### 2.2 Cross-map travel — timed warps
- Keep `BotTravelManager` hop *planning* (which portals/ferries, resupply errands, fares —
  all outcome-relevant), but execution per hop becomes: dwell for the hop's modeled
  seconds, then warp. Hop seconds already exist as the SSOT travel prices:
  `BotTravelCost.PORTAL_HOP_SECONDS=25 / SCROLL_SECONDS=5 / TAXI_SECONDS=30 / ferrySeconds`
  (`BotTravelCost.java:27-31,:92`) — the same numbers pathfinding already charges, so
  abstract travel time matches planned travel time by construction. Add jitter (±20%).
- Ferry/EventManager rides: keep real boarding (schedules are world-shared state);
  they're cheap dwells already.
- Future (not this pass): bake real per-(map,portal→portal) traversal seconds from nav
  graphs into `BotTravelCost` to replace the hardcoded 25s — improves both the planner
  and this simulation. Nav edges carry difficulty cost, not time (`BotNavigationGraph.java:263`),
  so this needs a speed-conversion pass at bake time.

### 2.3 Grinding — abstract kill events, real outcomes
- Kill cadence: `BotGrindPlanner.killsPerHour(MobCandidate)`
  (`BotGrindPlanner.java:116-121`) is the *model*, but it is a rough planning estimate —
  do NOT trust it raw (owner directive). **Calibrate first (Stage 3 prerequisite):**
  instrument actual kills/hr in normal LOD0 grinding — per-bot EMA of observed
  kill intervals tagged (mapId, mobId, job, level band) plus the killsPerHour
  prediction at that moment, logged/persisted (perf-CSV-style capture or a small
  table). Run the current population for a few hours, derive a correction factor
  (predicted → measured), ideally bucketed by job/level band; apply it as
  `calibratedKillsPerHour = killsPerHour × factor(bucket)`. The instrumentation stays
  on permanently: whenever a bot grinds in LOD0 its measured rate keeps updating the
  calibration, and a bot's own recent measured rate on the same (map, mob) — when
  fresh — overrides the bucket factor entirely.
- Draw next-kill delay from an exponential-ish jittered interval around the calibrated
  rate.
- On each kill event: pick a live spawned mob of the target type on the map and kill it
  through the real path (`MapleMap.killMonster`, `MapleMap.java:1353` — same entry real
  player kills reach via the damage handlers). Exp split, drops, quest counters, spawn
  bookkeeping all stay real. If no live mob (spawn-limited), skip — the supply cap in
  the rate model already covers this, so rates still match.
- Loot: after each kill, run the existing eligibility filter
  (`BotInventoryManager.tickPassiveLoot` path / `BotLootEligibility`) with the bot's
  materialized position set near the mob's death spot, after a 0.5-2s humanlike delay.
- Resource consumption (must stay honest): per kill, charge attacksPerKill =
  ceil(killSeconds / attackPeriod) × (MP cost + ammo/bullet consume) via the existing
  cost paths; potions/HP intake from the existing danger/expected-damage model
  (`BotDangerAssessment`) charged per coarse tick; passive regen unchanged
  (`tickPassiveRecovery` is already 10s-cadence).
- Buffs: rebuff on the deadline model (P3), applied via the normal StatEffect path
  (packets go to an empty map — measured cheap). No hitboxes, no target search, no
  attack packets, no AoE repositioning in LOD1.
- Death: LOD1 bots don't die (advisor already prunes danger-blocked maps; expected-HP
  charging covers the potion economics). Deliberate simplification — revisit if it
  distorts anything owner-visible.

### 2.4 Unchanged in LOD1
Decide pool (grind/party/scroll decisions), economy (market books, FM errands, stalls),
social/chat/shout, quest planning, inventory hygiene, job advance planning — all already
coarse-cadence or off-tick; they keep running so long-horizon behavior is identical.
The ops console / web endpoints keep reporting status (statuses are strings + state,
not physics).

## 3. What LOD1 eliminates (from the 217-bot capture)
move-ground + move-air + nav-resolve + step-movement-core + combat-target-search +
combat-plan + broadcast-move + per-tick commons × (50ms→500ms cadence). That is ~70%+
of tick-total gone at 10x fewer ticks for unobserved bots ⇒ order-of-magnitude
per-bot reduction while unobserved; combined with the lossless P0-P4 fixes, 2000 bots
@ 0-1 players should fit in ~2-3 cores.

## 4. Observation transitions
- **LOD1→LOD0 (must be seamless):** trigger synchronously in the `addPlayer` hook
  BEFORE existing-character spawn packets are built for the entering player. Steps:
  materialize position (lerp), snap to nearest foothold below at that x (existing
  foothold tree query), clear motion plan, set stance standing, cancel+re-register task
  at 50ms. Bots mid-abstract-travel on this map: place at the route's nearest node.
  The entering client then builds spawn packets from ordinary Character state.
  Same-map party bots already LOD0 by the party rule.
- **LOD0→LOD1:** after 10s player-free, freeze physics state into a motion plan
  (current pos = from), retask at 500ms.
- **World edge cases:** GM `!hide` counts as a real player (they can see); GM `!hidebot`
  does not (deliberate raw-LOD observation mode, §1). Bot owner watching through ops
  console does NOT force LOD0 (text status only) — owner call, revisit if it feels dead.

## 5. Implementation stages (each verifiable)
0. **Kill-rate calibration instrumentation** (prereq for Stage 3, independent of the
   rest — can land first and start collecting while Stages 1-2 are built): measured
   kills/hr EMA vs killsPerHour prediction, per (mapId, mobId, job, level band).
   Verify: a few hours of live data; distribution of measured/predicted ratios.
1. **Observer substrate:** effectivelyObserved predicate (isObservedByPlayer +
   1-portal-edge neighborhood via BotWorldGraph, cached adjacency) + `!hidebot` GM flag
   inside isObservedByPlayer + partyHasRealPlayer LOD predicate + BotEntry.lod +
   retask() + hysteresis. Verify: `/api/botdebug` shows lod flags; walking a player
   map-to-map promotes the neighborhood ahead of them (log); `!hidebot` GM does not
   promote.
2. **LOD1 movement/travel:** motion-plan interpolation + timed warps behind
   `if (entry.lod==LOD1)` branches at the tickCore movement dispatch. Verify: unobserved
   bots still arrive at grind maps (web endpoints), perf CSV shows move/nav sections ≈0
   for LOD1 population.
3. **LOD1 abstract grind:** kill-event loop (calibrated rate from Stage 0) + loot +
   resource charging. Verify: pick 20 bots, compare exp/hr + drops/hr + meso/hr +
   potion burn over an hour LOD1 vs measured LOD0 baselines (Stage 0 data); quest
   progress still advances.
4. **Transition polish:** enter-map snap correctness (no floating/underground bots on
   arrival — should be rare given 1-edge pre-warm; snap covers teleport/scroll entry),
   party-rule correctness, botpop scale test to 2000.

Risks: transition glitches (bot seen mid-air/wrong foothold) — mitigated by foothold
snap in addPlayer hook; rate divergence between abstract and real grind — mitigated by
using the advisor's own killsPerHour as the generator; task retask races — reuse the
registry-lock discipline from `kb_bot_double_register_botpop_race`.
