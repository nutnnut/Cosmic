---
name: Bot Swim Mode + Foothold forbidFallDown
description: How swim maps and forbidFallDown got wired into Cosmic's bot physics + nav graph
type: project
originSessionId: 5d91e9bb-b7df-4dff-8d09-3d47fc2cda61
---
Bot supports swim maps (Aqua Road family) and respects WZ foothold
`forbidFallDown=1`. Both shipped on `experimental`.

## What's where

| Concern | File | Anchor |
|---|---|---|
| Map "swim" flag (info node) | `MapleMap.isSwim()` | + setter |
| Real-server map load | `MapFactory.loadMapFromWz` | reads `info/swim` |
| Bot map load | `BotNavigationMapLoader.loadMapGeometry` | reads `info/swim` and `foothold/forbidFallDown` |
| Foothold flag | `Foothold.isForbidFallDown()` | + setter |
| Swim graph segment flag | `BotNavigationGraph.Segment.forbidFallDown` (snapshotted at build) |
| Swim physics tick | `BotMovementManager.tickSwimming` |
| Swim integrator | `BotPhysicsEngine.applySwimMotion` |
| Swim config | `BotPhysicsEngine.Config.SWIM_*` |
| Swim stance | `CharacterStance.SWIM_RIGHT_STANCE = 12`, `SWIM_LEFT_STANCE = 13` + `isSwimming` |
| Bot swim flag | `BotEntry.swimming` |
| Swim dispatch | `BotManager.tickMovementPhase` (+ 4 other call sites) — uses `isSwimMap(entry)` |
| Swim short-circuit | `BotNavigationManager.resolveTarget` — bypasses graph routing for swim maps |
| forbidFallDown drop gate | `BotNavigationGraphProvider.addDropEdges` + `addDirectionalDropEdge` |

## Swim physics — calibration unfinished

Constants in `BotPhysicsEngine.Config` are first-pass guesses; calibrate
against captured CP_USER_MOVE packets on Aqua Road and the v83 client's
own `CalcFloat`/`JustJump` formulas (see IDB section below). Bot ticks
at 50ms (TICK_MS = 50), so all `*_PXS`/`*_PXS2` constants are in
per-second units and are tick-rate independent.

**Do NOT use maplestory-wasm as a reference.** Its physics model
diverges from the real v83 client; values copied from it gave a swim
jump magnitude *lower* than the existing 555 px/s, but the actual
client value is *higher*. See `feedback_no_wasm_reference.md`.

## v83 client (Angel.idb) reverse-engineering anchors

Use `D:\ReverseEngineer\dump_func.py` against
`D:\GameServers\Maplestory\Cosmic\tmp\`Angel.idb`.

**Swim physics integrator** = `CalcFloat@CVecCtrl @ 0x9b2c3c`
(sibling of `CalcWalk@CVecCtrl @ 0x9b23f2`; xrefs from
`IsSwimming@CVecCtrl @ 0x7046ac` confirm). Function size ~5.6 KB,
~2000 insns. Dispatched from `WorkUpdateActive@CVecCtrlUser @ 0x9cbefb`.

**Swim physics constants are loaded from a global config struct** at
`*[0xbebfa0] + 8`, NOT inline. CalcFloat reads fields:

| Offset | Used as |
|---|---|
| `[+0x28]` | mass/scale for fmul'd terms |
| `[+0x50]` | denominator in fdiv (probably FRICTION) |
| `[+0x58]` | per-axis fmul (probably GRAVITY component) |
| `[+0x68]` | swim-specific fmul (CalcFloat-only) |
| `[+0x70]` | swim-specific fmul (CalcFloat-only) |

Only inline FP literals in CalcFloat:

| Addr | Value | Role |
|---|---|---|
| `0xaf0e10` | 0.001 | ms → s conversion |
| `0xaf0d48` | 0.5 | midpoint integration |
| `0xaf0de8` | 0.0 | sign comparisons |
| `0xaf3720` | 0.499999999 | round-bias before truncation |
| `0xafd3c0` | 0.015 | per-step gravity scalar (combines with `[ebx+0x68]`) |
| `0xafe800` | 0.3 | speed scalar |
| `0xafe808` | 1.5 | speed scalar |

**Swim jump impulse** = `JustJump@CVecCtrl @ 0x9b1d3d`. Three branches
share the join at `0x9b211c` (`fstp [ebp-0x20]; fld; fchs; call store→vspeed`):

| Path | Where | Formula |
|---|---|---|
| Walk | `0x9b20fd` | `stat[+0x84] × physcfg[+0x58] × speedScale` |
| Swim | `0x9b207b` | `stat[+0x6c] × physcfg[+0x48] × speedScale × 5.0` |
| Fly  | `0x9b20aa` | `physcfg[+0x90] × physcfg[+0x58] × speedScale × 5.0` |
| Rope | `0x9b1db4` | `stat[+0x48] × physcfg[+0x70] / [esi+0x1a0+0x24] × scale (0.3 or 0.5)` |

**physcfg** = `*[0xbebfa0] + 8`, mirrors `Map.wz/Physics.img.xml` in
declaration order (each named double becomes one 8-byte slot):

| Offset | Field | Value |
|---|---|---|
| 0x00 | walkForce | 140000 |
| 0x08 | walkSpeed | 125 |
| 0x10 | walkDrag | 80000 |
| 0x18 | slipForce | 60000 |
| 0x20 | slipSpeed | 120 |
| 0x28 | floatDrag1 | 100000 |
| 0x30 | floatDrag2 | 10000 |
| 0x38 | floatCoefficient | 0.01 |
| 0x40 | swimForce | 120000 |
| 0x48 | swimSpeed | 140 ← swim-jump multiplier |
| 0x50 | flyForce | 120000 |
| 0x58 | flySpeed | 200 ← walk-jump multiplier |
| 0x60 | gravityAcc | 2000 ✓ matches bot |
| 0x68 | fallSpeed | 670 ✓ matches bot |
| 0x70 | jumpSpeed | 555 ✓ matches bot (rope path) |
| 0x90 | flyJumpDec | 35.0 |

**Verification:** rope-jump observed at -277 px/s in real client. Formula
`stat[+0x48] × 555 / X × 0.5` gives 277.5 if `stat[+0x48] = X` (cancels) →
**confirms physcfg[+0x70] = 555 = jumpSpeed**, hence declaration-order
mapping is correct.

**Swim jump derivation:** stat factors and speedScale are shared between
walk and swim paths (both read jump-stat-derived doubles from the same
character stat block, both multiplied by the same `[ebp-0x20]`). Ratio:

```
swim/walk = (physcfg[+0x48] × 5.0) / physcfg[+0x58]
          = (140 × 5.0) / 200 = 3.5
swim_jump_pxs = 555 × 3.5 = 1942.5 px/s
```

Bot's `SWIM_JUMP_BURST_PXS` updated 555 → 1942.5.

**SWIM_VEL_PXS** updated 125 → 140 (Physics.img `swimSpeed = 140`; old
value was walkSpeed by mistake).

**Still TODO** (not in Physics.img directly — derive from CalcFloat):
SWIM_GRAVITY_PXS2, SWIM_FRICTION_HZ, SWIM_ACCEL_PXS2. CalcFloat uses
inline 0.015 plus physcfg fields including `[ebx+0x28]=floatDrag1=100000`,
`[ebx+0x50]=flyForce=120000`, `[ebx+0x58]=flySpeed=200`,
`[ebx+0x68]=fallSpeed=670`, `[ebx+0x70]=jumpSpeed=555`. Need full trace
of integration sequence to extract effective px/s and px/s² values.

**Source of truth for physcfg values: `Map.wz/Physics.img.xml`** —
the loader populates `*[0xbebfa0]+8` in declaration order, one 8-byte
slot per named double. No need to RE the loader; just read the WZ.

## Swim intent suppression during animation lock

`BotMovementManager.computeSwimIntents` now zeroes all intent
(swimMoveDir, swimVerticalHold, swimJumpRequested) when
`entry.attackCooldownMs > 0`. Mirrors the real client where movement
input dispatch is suppressed while `CUserLocal::IsAttacking`. Without
this gate the bot kept strafing through animation lock and could
tunnel through thin platforms because vx accumulated unchecked.

## External-teleport physX/physY rebase

`BotPhysicsEngine.applySwimMotion` now resyncs `physX/physY` from
`bot.getPosition()` whenever it diverges by >2 px from the
integrator's tracked values (in addition to the existing first-tick
rebase via `!entry.swimming`). Mob-touch knockback and `!warp` move
the authoritative position via `Character.setPosition()` without
notifying the integrator; without resync the next collision sweep
starts from a stale point and can advance past a foothold without
registering the floor.

## Swim follow target — preserve mid-water Y

`BotManager.resolveFollowTargetPos` previously fell back to
`clampedOnOwnerRegion` when no foothold was within snapRange — that
finds the floor far below the owner, so a bot following an owner
swimming high in open water sat on the floor. In swim maps the
fallback now returns `(followBase.x, ownerPos.y)` (raw Y), so the bot
swims directly toward the owner's mid-water position. Platform-snap
is still attempted first when one is in range.

## Swim downjump

`BotMovementManager.tickGrounded` now queues a downjump when on a swim
map, on a foothold without `forbidFallDown`, and the target is
directly below (`dy > SWIM_LEVEL_BAND_PX`, `|dx| <= 4 ×
SWIM_ARRIVAL_RADIUS_PX`). Needed because swim maps short-circuit graph
routing — there are no DROP edges to walk the bot off the platform.

## Swim jump scales with SPEED stat (not jump)

Per JustJump@CVecCtrl swim branch:
`swim_jump = stat[+0x6c] × physcfg[+0x48] × speedScale × 5.0`.
`stat[+0x6c]` is the speed-related field (cf. walk path
`stat[+0x84]`). `applySwimMotion` now multiplies
`SWIM_JUMP_BURST_PXS` by `entry.movementProfile.speedMultiplier()`,
not jumpMultiplier — important for any character with non-100 speed
or jump.

## Packet-calibrated swim values (Apr 27)

`logs/monitored-packets-swim-burst.log` and `-burst-upheld.log` (real
v83 client, 100/100 speed/jump-stat character UNCONFIRMED — likely
~70/70 based on packet vy magnitudes) show:

| Source | Observed vy | Inferred base-100 |
|---|---|---|
| Ground jump in water (player on foothold presses jump) | -388 px/s | 555 (= JUMP_SPEED_PXS) — matches walk-jump scaled |
| First mid-water swim burst (JUMP type-1 segment) | -700 px/s | ~1000 px/s |
| Subsequent mid-water re-burst (held UP, jumps again) | -700 px/s | ~1000 px/s (same impulse) |

**Key correction:** `SWIM_JUMP_BURST_PXS` was 1942.5 (IDB-derived
3.5× walk ratio). Real client value at base-100 is ~1000. The
discrepancy is because the IDB derivation assumed `stat[+0x6c] =
stat[+0x84] = 1.0` — packet capture shows stat[+0x6c] is significantly
smaller than stat[+0x84].

**UP held = continuous upward thrust, but always less than gravity.**
Real-client behaviour: UP is a binary on/off, no analog, and
**cannot maintain altitude** — bot still sinks slowly while UP is
held. Therefore `SWIM_UP_THRUST_PXS2 < SWIM_GRAVITY_PXS2`.

## Drag/gravity calibration from packet logs (Apr 27 v2)

Earlier (gravity=428, friction=1.6) values were "stand-ins" with no
direct evidence; they predicted ~510 px rise in 2s from vy=-700 vs
observed 927 (with UP) / ~674 (without UP). Refit with linear-drag
model `dv/dt = g - k·v`:

| Constraint | Value |
|---|---|
| WITH UP, 2s rise from vy=-700 | 927 px observed |
| WITHOUT UP, 2s rise from vy=-700 | 674 px observed |
| **g (SWIM_GRAVITY_PXS2)** | **200** (was 428) |
| **k (SWIM_FRICTION_HZ)** | **0.4** (was 1.6) |
| **UP_thrust** | **180** (terminal sink = (200-180)/0.4 = 50 ≈ ceiling 60) |
| **DOWN_thrust** | **100** (kept low; user reported old 480 felt too fast — with k=0.4 even small thrust accelerates hard) |
| **MAX_SPEED_PXS** | **500** (raised from 250 so free-sink terminal g/k=500 isn't clipped) |
| **DOWN_MAX_SPEED_PXS** | **350** (was 480, user-reported too high) |

Predicts 933 / 653 rise — within 2% of both observations.

## IDB CalcFloat structure (verified Apr 27)

Custom dumper `D:\ReverseEngineer\dump_calcfloat.py` (handles >400
insns where dump_func.py truncates). Key reads inside the swim
integrator at `0x9b2c3c`:

| EA | Op | Field | Value | Role |
|---|---|---|---|---|
| 0x9b2c92 | `fmul [ebx+0x28]` | floatDrag1 | 100000 | drag scaling A |
| 0x9b2cae | `fmul [ebx+0x30]` | floatDrag2 | 10000 | drag scaling B |
| 0x9b2cfb | `fld [ebx+8] / fdiv [ebx]` | walkSpeed/walkForce | 125/140000 | inverse time const |
| 0x9b2ce5 | `fmul [ebx+0x68]` | fallSpeed | 670 | free-fall scalar |
| 0x9b2d1e | `fmul [ebx+0x70]` | jumpSpeed | 555 | **UP/DOWN thrust scalar** |
| 0x9b2db3 | `fmul [ebx+0x60]` | gravityAcc | 2000 | full v83 gravity |
| 0x9b2e44 | `fmul [eax+0x38]` | floatCoefficient | 0.01 | swim-only reduction in one branch |

`[esi+0x184]` is the integer UP/DOWN key state (loaded via
`fild [esi+0x184]` at 0x9b2d18, multiplied by jumpSpeed=555 then
×0.001 for ms→s). That's how UP/DOWN apply continuous thrust each
tick — not a velocity cap.

The `× 0.01 floatCoefficient` at 0x9b2e44 explains why effective
swim gravity is ~2% of full v83 gravity: 2000 × 0.01 = 20 — but in
practice the drag composition + stat scaling raises the working
value to the empirical ~200 we calibrated.

## Hover-oscillation fix

`computeSwimIntents` previously fired a JUMP burst on **any**
`dy<0` (target above bot) — when bot was within a few px above the
target, the burst sent it 600+ px up, then it fell back through
target Y, looped. Fixed:
1. Arrival band: `|dx|<=hRadius && |dy|<=SWIM_LEVEL_BAND_PX` → zero
   intent + UP hold (just maintain altitude).
2. Burst threshold: only fire when `dy <= -SWIM_JUMP_TRIGGER_DY_PX`
   (existing constant 100, was unused).

**Toolkit reuse:** `dump_func.py` caps at 400 insns (`MAX_INSNS`). For
~5KB functions like CalcFloat, write a one-off script that calls
`md.disasm(api.idc.GetManyBytes(ea, 8000), ea)` directly.

**Tick conversion:** Bot ticks at 50ms. Per-tick values extracted from
the IDB must be converted to per-second form using the calibrated
anchor `JUMP_SPEED_PXS = 555` (land jump) and `GRAVITY_PXS2 = 2000`
(land gravity, exact from packet captures). Ratios between client
formulas convert directly; do NOT assume any specific client tick (8ms,
16ms, etc.) — derive from the calibrated anchors instead.

## Why no swim nav graph yet

Phase C v1 short-circuits `resolveTarget` on swim maps and returns
`rawTargetPos` directly. `tickSwimming` drives the bot toward it via
free-water physics. Aqua Road is mostly open so direct line works.

A grid-sampled swim graph (60×60 px, 4-neighbor, line-of-sight wall
checks) would be needed for obstacle avoidance in mob-cluttered swim
maps. Plan in `D:/GameServers/Maplestory/Cosmic/notes/swim-and-downjump-plan.md` §3 Phase C.

## Travel portal reachability in swim maps

Do not use the ground nav graph or map-partition reachability to reject
cross-map portals while the current map is a swim map. The movement
runtime bypasses A* and swims directly to the raw target, so the ground
graph can falsely mark valid swim portals unreachable. Live repro:
`iArroWLanE` job-advancing from `230010400` (Forked Road : West Sea) to
Bowman instructor map `106010000` stood at `east00` on the lower
platform while the route wanted `west00` on the upper platform. The
ground graph had only top-to-bottom edges, so travel yielded forever with
`route-reachable=true` / `warp-no-land`. Fix: `BotTravelManager`
disables ground reachability filtering and partition routing for
`map.isSwim()`, and `BotMapPartitionProvider.forMap` returns a
fully-connected partition for swim maps instead of persisting false
ground-only splits.

## Transition rebase

`applySwimMotion` checks `!entry.swimming` at top and re-anchors
`physX/physY/hspeed/velY` from `bot.getPosition()` on the first swim
tick after a transition. Mirror this if you add another physics mode
(e.g. flying). Without it, accumulated land-mode velocity carries into
swim and causes visible drift.

## forbidFallDown

WZ marks platforms that block down-jumps as
`<int name="forbidFallDown" value="1"/>`. Many maps have it
(`000010000`, `100040001-4`, `101020001-10`, `106020600/601`, ...);
Aqua Road has zero. Cosmic loads it via `BotNavigationMapLoader` and
gates both vertical and directional drop edges at graph build.
`GRAPH_VERSION` bumped 32→33 to invalidate stale caches.

**The real-server `MapFactory.loadMapFromWz` does NOT load
forbidFallDown.** Only the bot path consumes it. If anything other
than the bot graph generator ever needs the flag, also add the
`setForbidFallDown` call in `MapFactory`.

## Non-obvious gotchas

- WZ `forbidFallDown` is on individual footholds, but `swim` is on the
  map info node — different semantics, different load paths.
- v83 partial-water maps (separate water rectangle in info node) exist
  but are deferred — current bot treats `swim=1` as full-map water.
- `Char::SWIM = 12` overrides STAND/WALK/JUMP/PRONE in v83 client.
  `resolveStance` puts the swim branch right after climbing (climbing
  still wins because rope/ladder anims are explicit).
- Swim drag is symmetric on both axes; gravity is weak (~21% of land).
  Bot will sink slowly when idle if you don't set a target.
