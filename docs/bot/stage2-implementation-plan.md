# Unobserved-LOD Stage 2 — implementation plan (read-only prep, NOT yet built)

Status: **PLAN ONLY** (2026-07-05). No tree/build/server changes made. Anchors are current
(post Stage 0+1, HEAD 6db7317bf + uncommitted Stage 0+1). Depends on Stage 0+1 already in tree.

Scope of Stage 2 (design §2.1, §2.2, §4, §5 stage 2): make LOD1 bots move by a **motion plan**
(no physics/nav) and travel by **timed warps** (no executed hops), and make the LOD1→LOD0 /
LOD0→LOD1 transitions seamless. Stage 3 (abstract grind) is out of scope here.

The Stage-1 substrate this builds on:
- `BotEntry.lod` / `lodUnobservedSinceMs` computed each tick by `BotManager.updateLod` (BotManager.java:874).
- The cadence flip point: `cadenceForLod` (BotManager.java:909) returns `TICK_MS` behind
  `final boolean stage2CoarseTickReady = false` — **Stage 2 flips this**.
- `retask(entry,ownerCharId,botCharId,intervalMs)` (BotManager.java:932) already registry-lock-safe.
- Toggles `SIMPLIFY_UNOBSERVED_BOTS_{PHYSICS,TRAVEL,CADENCE}` (BotManager.java:198-201).

---

## A. The cadence flip + the accumulator fix (bot-side, behavior-sensitive)

1. **Flip `stage2CoarseTickReady`** (BotManager.java:910) from `false`→`true`. After this,
   `cadenceForLod` returns `LOD1_TICK_MS` (500) for a LOD1 bot when `_CADENCE && _PHYSICS` are on;
   `maybeAdjustCadence` (BotManager.java:920, called at tickCore:3821) then retasks it to 500ms and
   back to 50ms on upgrade. Retask race already handled.

2. **`consumeAiTick` accumulator** (BotManager.java:6781) currently does
   `entry.aiTickAccumulatorMs += BotMovementManager.cfg.TICK_MS;` — a **constant**, not the real
   interval. At 500ms cadence that makes AI ticks fire 10× too slowly (accumulator gains 50ms per
   500ms wakeup). **Fix:** add the real elapsed interval instead. Options, safest first:
   - Add `entry.tickIntervalMs` (already set at registration = TICK_MS, updated by `retask`) —
     `entry.aiTickAccumulatorMs += entry.tickIntervalMs;`. **Guard:** test-built entries that never
     went through `registerBotInternal` have `tickIntervalMs==0` → would freeze AI. Default it:
     `+= (entry.tickIntervalMs > 0 ? entry.tickIntervalMs : BotMovementManager.cfg.TICK_MS)`.
   - This must land in the **same reviewable slice** as the flip — it is the one change that alters
     timing semantics.

3. `BotEntry.tickIntervalMs` is the SSOT for "what rate am I actually ticking at" — already
   maintained. No new field needed here.

## B. LOD1 in-map movement — motion plan (design §2.1)

New `BotEntry` fields (motion plan; ~5 fields):
```
Point   motionFrom;      // frozen start (current pos at LOD0->LOD1)
Point   motionTo;        // destination (route end or straight-line target)
long    motionDepartMs;  // wall clock
long    motionArriveMs;  // departMs + duration
int     motionFhY;       // last-known foothold Y to hold until arrival (§2.1: exact Y only at transition)
```
Dispatch branch at **`tickMovementPhase` (BotManager.java:6373)** — the physics SSOT dispatch
(`tickClimbing`/`tickSwimming`/`tickAirborne`/`tickGrounded`). Wrap:
```
if (entry.lod == LOD1 && cfg.SIMPLIFY_UNOBSERVED_BOTS_PHYSICS) { tickMotionPlan(entry); return; }
```
`tickMotionPlan`:
- If no plan or destination changed: build one. `duration = pixelPathDistance / groundSpeedPxPerSec`
  ×(1±0.10 jitter). **Speed source:** `entry.movementProfile.walkVelocityPxs()`
  (BotMovementProfile.java:93 → `BotPhysicsEngine.cfg.WALK_VEL=125 × speedMultiplier`) — the SAME
  speed physics integrates, so abstract time ≈ real time by construction.
- **pixelPathDistance without live A* (design §2.1):** if `entry.committedRoute` (BotEntry.java, the
  `List<BotNavigationGraph.Edge>`) exists, sum per-edge `|edge.startPoint → edge.endPoint|`
  (Edge.startPoint/endPoint at BotNavigationGraph.java:241-242) plus inter-edge gaps. If no committed
  route: straight-line `|from→to|` × 1.3 slack. **Never call findPath here.**
- Materialize lazily: `pos = lerp(motionFrom, motionTo, clamp01((now-depart)/(arrive-depart)))`;
  hold `motionFhY` for Y until arrival. On arrival clear the plan.
- Ladders/jumps/launch windows: not simulated (the point).
- No `broadcastMovement` needed while unobserved (nobody to send to) — but keep the bot's
  `Character` position updated so the addPlayer snap (§D) reads a sane lerp point.

## C. LOD1 cross-map travel — timed warps (design §2.2)

Keep `BotTravelManager` hop **planning** (portals/ferries/fares/errands — all outcome-relevant);
replace per-hop **execution** with dwell-then-warp. Hop seconds are the existing SSOT prices in
**BotTravelCost** (BotTravelCost.java): `PORTAL_HOP_SECONDS=25` (:27), `SCROLL_SECONDS=5` (:29),
`TAXI_SECONDS=30` (:31), `ferrySeconds(...)` (:92). Add ±20% jitter. Because these are the same
numbers the planner charges, abstract travel time = planned travel time by construction.
- **Hop-execution intercept (pinned):** the portal-hop SSOT is
  `BotTravelManager.walkToPortalAndEnter(entry, bot, portal, now, runAiTick)`
  (BotTravelManager.java:409). Today it walks toward the portal (:448-455: pin moveTarget +
  `movementStep.step`) and, once at the portal and past the human dwell, fires the real warp
  `portal.enterPortal(bot.getClient())` (BotTravelManager.java:441). **LOD1 branch:** at the TOP of
  this method, if `entry.lod==LOD1 && cfg.SIMPLIFY_UNOBSERVED_BOTS_TRAVEL`, replace the walk with a
  dwell of the modeled hop seconds (`BotTravelCost.PORTAL_HOP_SECONDS` ±20%) then call
  `portal.enterPortal(...)` directly — skips the walk + arrival geometry (:417-435) but keeps the
  REAL warp, so destination `addPlayer`, script portals (:437-445), and map-change bookkeeping all
  still fire. Reuse a dwell field (`portalEnterDwellUntilMs` or a motion-plan arrival stamp).
  - This method is the single funnel: autopilot hop-advance and follow-travel both route through it
    (BotTravelManager.java:388 and :774 both `return walkToPortalAndEnter(...)`).
- **Taxi hops:** `taxiRide` warps via `bot.changeMap(dest, dest.getPortal(0))` (BotTravelManager.java:175),
  reached after the walk-to-cab path (:488+). LOD1: dwell `BotTravelCost.TAXI_SECONDS` ±20% then
  `changeMap` — same intercept shape at the taxi execution site.
- **Ferry/EventManager rides stay real** (schedules are world-shared state; already cheap dwells) —
  do NOT intercept `BotFerryManager` boarding.
- LOD1→ warp uses the existing map-change path (so addPlayer fires on the destination and Stage-0
  calibration/quest bookkeeping stay intact).

## D. Transitions (design §4) — the one SHARED / non-bot edit

### LOD1→LOD0 (seamless upgrade) — **the flagged non-bot edit**
Trigger synchronously in **`MapleMap.addPlayer`** BEFORE existing-object spawn packets are built for
the entering player. The exact seam is **`sendObjectPlacement(chr.getClient())` at
MapleMap.java:2470** (this is what builds spawn packets for existing map objects, incl. bot
Characters, from their Character state). Insert *before* it:
```
if (!(chr.getClient() instanceof BotClient)) {
    BotManager.getInstance().materializeBotsForObserver(this);   // <-- new bot-side call
}
```
- **This is the only non-bot-source change in Stage 2** (one guarded call). Everything it does lives
  in `BotManager`. Minimal per fork rule 2. Flag for lead review before it lands.
- `materializeBotsForObserver(MapleMap)` iterates bots on this map at LOD1 and for each:
  materialize lerp pos (§B), **snap to foothold below at x**, clear motion plan, stance standing,
  force LOD0 + retask to 50ms. All primitives already exist:
  - foothold snap: `BotPhysicsEngine.spawnIntoMap(entry,bot)` (BotPhysicsEngine.java:843) or
    `teleportTo(entry,bot, findGroundPoint(map, x))` (teleportTo at :828; findGroundPoint used by
    spawnIntoMap:845). `MapleMap.getGroundBelow(Point)` (MapleMap.java:1837) is the map-side analogue.
  - retask to 50ms: `retask(entry, ownerCharId, botCharId, BotMovementManager.cfg.TICK_MS)` — but
    note retask needs ownerCharId/botCharId; materialize path must resolve them (botCharId =
    bot.getId(); ownerCharId = entry.owner id or the registry key). Simplest: add a
    `retaskToFullFidelity(entry)` helper that recomputes both like the tick does.
- Bots mid-abstract-travel on this map: place at the route's nearest node (committedRoute cursor
  point) — same materialize, target = current committed hop endpoint.
- 1-edge pre-warm (Stage 1 `effectivelyObserved`) already upgrades neighbor-map bots BEFORE the
  player portals in, so this snap is the backstop for teleport/scroll entry that skips adjacency.

### LOD0→LOD1 (downgrade)
Already gated by 10s hysteresis in `updateLod`/`decideLod` (BotManager.java:874/892). On the tick a
bot first reads LOD1: freeze physics state into a motion plan (`motionFrom = current pos`), retask
to 500ms (via the cadence flip in §A). No non-bot edit.

### Edge cases (design §4, already handled by Stage 1)
- GM `!hide` still counts as observer; `!hidebot` does not (MapleMap.isObservedByPlayer:3138 flag).
- Owner watching via ops console does NOT force LOD0 (text status only).

## E. What Stage 2 does NOT touch
Decide pool (grind/party/scroll decisions), economy, chat, quest planning, inventory hygiene —
all coarse/off-tick, keep running (design §2.4). **Scroll-DP advisor cost is decide-pool and is
NOT reduced by Stage 2** (that's the lead's separate fix).

## F. Verification (design §5 stage 2)
- Unobserved bots still **arrive** at grind maps (web `/api/botdebug` dst/map progress; `/api/live`).
- Perf CSV: move-ground / nav-resolve / step-movement-core / combat-target-search sections ≈0 for
  the LOD1 population; tick-total call rate for LOD1 bots drops ~10× (500ms cadence).
- A/B the toggles live (now non-inert): `_CADENCE`/`_PHYSICS` off → bots revert to 50ms full physics
  (bisectable). `/api/settings` set group=manager.
- Transition smoke test: walk a real player map→map, confirm no floating/underground bots on
  arrival (foothold snap), and neighbor pre-warm promotes ahead of the player (Stage-1 log).

## G. New/changed surface summary
- **Non-bot source (flag for review):** `MapleMap.addPlayer` — 1 guarded call before :2470.
- **Bot source:** `BotManager` (flip stage2CoarseTickReady; consumeAiTick fix; `tickMotionPlan`,
  `materializeBotsForObserver`, `retaskToFullFidelity`); `BotEntry` (~5 motion-plan fields);
  `BotTravelManager` (LOD1 timed-warp branch at the hop-exec site).
- **No new WZ/graph/config schema.** New cfg already exists (LOD1_TICK_MS etc.).
- Risk: transition glitch (bot seen mid-air/wrong foothold) — mitigated by foothold snap + 1-edge
  pre-warm; rate divergence — Stage 3 concern; retask race — reuses Stage-1 registry-lock discipline.
