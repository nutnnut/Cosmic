# Bot movement physics — client-truth audit

Audited `server.bots.BotPhysicsEngine` against the real v83 client disassembly
(IDA db `tmp/\`Angel.idb`, dumped via `D:\ReverseEngineer\dump_func.py`). Ground
constants come from `wz/Map.wz/Physics.img.xml` (loaded by the client into the
physics-config struct at `*[0xbebfa0]+8`) and from CVecCtrl/CUserLocal disasm.

Date: 2026-06-12. Bot HEAD at audit start: 48bcab404.

## TL;DR verdicts

| Subsystem | Verdict |
|-----------|---------|
| Down-jump probe distance (300px) | ~~confirmed~~ **RETRACTED — empirically wrong, cap removed (see §b)** |
| `fs` slipperiness model (scale force + friction, top speed unchanged) | **matches — confirmed in CalcWalk** (one minor nuance, reported) |
| Core constants (walkSpeed/gravity/jump/fall) | **matches** (= Physics.img) |
| Ground force/drag refit (HFORCE/GROUNDSLIP/FRICTION/SLOPEFACTOR) | **divergent-but-deliberate** (abstracted refit, correct emergent behavior) |
| Swim integrator (SWIM_FRICTION_HZ etc.) | **divergent-but-deliberate** (packet-fitted; SWIM_VEL=140 matches swimSpeed) |

No behavior-changing fixes were required. One comment was corrected to cite the
now-confirmed client evidence. **GRAPH_VERSION not bumped** (no arc/landing/runway
or ground-speed value changed).

---

## Key client EAs & helpers (resolved)

| EA | Symbol | Role |
|----|--------|------|
| 0x9b23f2 | `CVecCtrl::CalcWalk` | ground walk integrator |
| 0x9b2c3c | `CVecCtrl::CalcFloat` | swim/float integrator |
| 0x9b1d3d | `CVecCtrl::JustJump` | jump/rope-jump impulse |
| 0x9b1c51 | `CVecCtrl::FallDown` | applies gravity kick on lost ground |
| 0x94c4f8 | `CUserLocal::FallDown` | **down-jump gate (300px probe)** |
| 0x94e692 | `CUserLocal::TryDoingFallDown` | tiny — only sets fall-request flag, calls SetMovePathAttribute(0xe) |
| 0x6724fc | `TSecType<double>::GetData` | secured-double getter (the repeated `call 0x6724fc`) |
| 0x539338 / 0x416563 | `_ZtlSecureFuse<N/J>` | secured double / int decode |
| 0xa45585 / 0xa4549d | `CWvsPhysicalSpace2D::GetFootholdUnderneath / GetFootholdAbove` | foothold range queries |
| 0xa45b8c | `CWvsPhysicalSpace2D::SetFieldAttr` | builds the field-attr object (holds `fs`) |
| 0x4fe802 | `CAttrShoe` ctor | shoe-attr defaults |
| 0x9b1506 | inside `CVecCtrl::SetActive` | `[esi+0x1a0] = *[0xbebfa0]+0xac` → field attr |

VecCtrl members used by the integrators:
- `[esi+0x1a0]` = **field attr** (CWvsPhysicalSpace2D's +0xac; carries `fs`).
- `[esi+0x1a8]` = **shoe attr** (CAttrShoe).
- `[esi+0x110]` = physical-space/foothold context.
- physcfg = `*[0xbebfa0]+8`; member offsets follow Physics.img field order
  (walkForce=+0, walkSpeed=+8, walkDrag=+0x10, slipForce=+0x18, slipSpeed=+0x20,
  floatDrag1=+0x28, floatDrag2=+0x30, floatCoefficient=+0x38, swimForce=+0x40,
  swimSpeed=+0x48, flyForce=+0x50, flySpeed=+0x58, gravityAcc=+0x60, fallSpeed=+0x68,
  jumpSpeed=+0x70, maxFriction=+0x78, minFriction=+0x80).

### Raw double constants (read from IDB, struct.unpack '<d')

| Addr | Value | Meaning in physics code |
|------|-------|-------------------------|
| 0xaf0d40 | 0.8 | teleport-probe scale (TryDoingTeleport, type==2) |
| 0xaf0d48 | 0.5 | friction halved when factor < 1 (CalcWalk) |
| 0xaf0de0 | 1.0 | constant 1 |
| 0xaf0de8 | 0.0 | constant 0 |
| 0xaf0e10 | 0.001 | **ms → s** (dt = elapsed_ms × 0.001) |
| 0xaf1628 | 0.2 | drag fallback when friction==0 (walkDrag × 0.2) |
| 0xaf0e18 | 1000.0 | s → ms |
| 0xb3e3a0 | -0.35355339 | FallDown gravity-kick coefficient |
| 0xafe800/0xafe7f8/0xafe898 | 0.3 / 0.7 / 1.3 | JustJump rope/jump kick blend factors |
| 0xaf8298 | 5.0 | JustJump swim-thrust scale |
| 0xaf14f0 | 0.01 | shoe attr speed→factor scale (SetShoeAttr) |

---

## (a) `fs` slipperiness — CONFIRMED, model matches

`info/fs` (El Nath snow = 0.2) is the field's **slipperiness**, not a speed scale.

Trace: `CWvsPhysicalSpace2D::SetFieldAttr` (0xa45b8c) builds the field attr at
`+0xac`. Both `field+0` and `field+0xc` are `SetData`'d to the same value from
0xa45cd6, which `SecureFuse`-reads the field-info double at `+0x1d0` (the map's
`fs`) and returns **1.0 when zero/absent**. So a normal field → 1.0; El Nath → 0.2.

In `CalcWalk` these two field factors enter two different chains:

1. **Walk force** (0x9b250f-0x9b2527):
   `force = GetData(...) × walkForce × GetData(...) × GetData(field+0)`
   → force scaled by `fs`.

2. **Friction coefficient** (0x9b275f-0x9b2802):
   `fric = GetData(shoe+0x18) × GetData(shoe+0x30) × GetData(field+0xc)`
   → clamped to `[minFriction 0.05, maxFriction 2.0]`, **halved (×0.5) if < 1.0**,
   then `drag = fric × walkDrag`. So drag also scaled by `fs` (with the extra ×0.5).

The terminal speed is enforced by a separate clamp to `walkSpeed` (125), which is
**not** scaled by `fs`. Net effect: lower `fs` → smaller acceleration and smaller
drag, same 125 cap → **slow starts, long slides, unchanged top speed.**

**Verdict:** the bot's model (`hspeed += (hforce − drag) × slipScale`, slipScale =
`fs`, top speed unchanged) reproduces the correct emergent behavior and is
**confirmed correct in direction and cap**. The existing code comment
(`mapGroundSlipScale`) is accurate.

**Minor nuance (reported, not fixed):** the client halves the *friction* factor
when `fs < 1` (drag ∝ 0.5·fs) while scaling *force* by exactly `fs`; the bot scales
the whole `(force−drag)` delta by `fs` symmetrically. Because top speed is
clamp-limited, this only perturbs the approach curve slightly — not worth a
behavior-risky change.

## (b) Down-jump probe distance — ~~CONFIRMED 300px~~ **RETRACTED (empirically wrong)**

> **CORRECTION (2026-06-13).** The 300px cap below was **wrong in practice** and has
> been **removed from the code** (`DOWN_JUMP_MAX_DROP_PX` deleted; `simulateDownJumpLanding`
> no longer caps drop distance; GRAPH_VERSION 56→57). The owner verified in-client that a
> player **can** down-jump drops the capped bot refused — e.g. descending the Orbis station
> tower (200000000), a ~780px straight-down drop the cap deleted, which islanded the arrival
> platform and stranded follower bots (bisected to `48bcab404`; regression test
> `BotNavigationGraphProviderTest#shouldConnectOrbisStationLowerLedgesToUpperPlatform`).
>
> So the `0x12c` (300) value read out of `CUserLocal::FallDown` is **not** the down-jump
> drop-distance limit it was read as. The true eligibility rule is **still unknown** — it is
> neither a 300px probe nor purely the `forbidFallDown` flag (do not re-cap on either without
> in-client + disasm proof). Current policy: generate a down-jump edge wherever a real landing
> exists below (any distance); `canStartDownJump` still refuses a `forbidFallDown` source
> foothold; execution abandons any edge that proves unwalkable. **Do not reinstate the 300px
> cap.** The original disasm reading is preserved below for the record only.

The down-jump gate is **`CUserLocal::FallDown` @ 0x94c4f8** (NOT the 65-byte
`TryDoingFallDown` @ 0x94e692, which only sets the fall-request flag).

At 0x94c658: `add [probeY], 0x12c` → **player Y + 300**. It then calls
`GetFootholdAbove` (0xa4549d) and `GetFootholdUnderneath` (0xa45585) bracketing
that probe point, and rejects the down-jump if the only foothold found is the
current one (±5px, 0x94c6b0-0x94c6d2). So the real probe distance is **exactly
300px**.

**Verdict (RETRACTED — see correction box above):** the `0x12c` read was taken as a
down-jump drop cap and is empirically wrong; `DOWN_JUMP_MAX_DROP_PX` has been deleted.

Correction to a prior-attempt misattribution: the "min 0x1e (30) / ×0.8 (type 2)"
probe lives in `CUserLocal::TryDoingTeleport` (the earlier dump of
"TryDoingFallDown" overran into the adjacent function); it is the teleport landing
search, not the down-jump.

## (c) Constants table — client raw → bot

| Quantity | Client (Physics.img / disasm) | Bot cfg | Verdict |
|----------|-------------------------------|---------|---------|
| walkSpeed | 125 px/s | `WALK_VEL = 125` | matches |
| gravityAcc | 2000 px/s² | `GRAVITY_PXS2 = 2000` | matches |
| jumpSpeed | 555 px/s | `JUMP_SPEED_PXS = 555` | matches |
| fallSpeed (terminal) | 670 px/s | `MAX_FALL_PXS = 670` | matches |
| swimSpeed | 140 px/s | `SWIM_VEL_PXS = 140` | matches |
| flySpeed | 200 px/s | (no fly path) | n/a |
| walkForce / walkDrag | 140000 / 80000 | abstracted (see below) | deliberate refit |
| maxFriction / minFriction | 2.0 / 0.05 | (folded into refit) | deliberate refit |
| down-jump probe | ~~300px (0x12c)~~ | **cap removed** (was `DOWN_JUMP_MAX_DROP_PX`) | **RETRACTED — empirically wrong, see §b** |
| down-jump kick | not in Physics.img | `JUMP_DOWN_PXS = 196` (measured) | deliberate (packet-measured) |
| dt unit | ms × 0.001 (var step) | per-step `CLIENT_GROUND_STEP_MS = 8` | deliberate (fixed-step approximation) |

**Ground force/drag refit** (`HFORCE_PXS=16.667, GROUNDSLIP=3.0, FRICTION=0.3,
SLOPEFACTOR=0.1`): the client uses `v += (walkForce·shoe/mass)·dt` minus
`(friction·walkDrag/mass)·v·dt` with a variable `dt` and a 125 cap. The bot
replaces this with an equivalent fixed-step (`CLIENT_GROUND_STEP_MS=8`) abstraction
whose terminal `maxHForce·GROUNDSLIP/(FRICTION+SLOPEFACTOR)` reproduces 125 px/s.
This is a deliberate, calibrated abstraction — the literal client coefficients are
not directly portable because the bot ticks at 50ms and does not model shoe-attr
secured doubles or per-frame mass. Left unchanged (rule 2: behavior-risky).

**Swim integrator**: `CalcFloat` (0x9b2c3c) uses floatDrag1=100000, floatDrag2=10000,
floatCoefficient=0.01, swimForce=120000, swimSpeed=140, swimSpeedDec=0.9, with the
ms→s `0.001` factor and per-axis drag. The bot's swim constants
(`SWIM_FRICTION_HZ=4.21`, accel/thrust) are least-squares fits to packet captures,
with explicit packet-observed terminal caps. `SWIM_VEL_PXS=140` matches swimSpeed.
Left unchanged (packet-fitted, rule 2).

## (4) Fixes made

- `BotPhysicsEngine.java` — updated the `DOWN_JUMP_MAX_DROP_PX` comment to cite the
  confirmed client gate (`CUserLocal::FallDown @ 0x94c4f8`, `add y, 0x12c` = 300px)
  and correct the prior TryDoingFallDown/TryDoingTeleport misattribution. No value
  change. No GRAPH_VERSION bump.

## (5) Reported, not fixed

- `fs` friction-halving nuance (client: drag ∝ 0.5·fs when fs<1; bot scales force
  and drag symmetrically by fs). Clamp-dominated; cosmetic on the approach curve.
- Ground force/drag refit and swim integrator are deliberate calibrated
  abstractions, not literal client ports; flagged divergent-but-deliberate.

## (6) Airborne horizontal model — packet-fitted (2026-06)

Fitted from `logs/monitored-packets-elnath-tricky-jumps-spd100v2.log` (El Nath fs=0.2,
10 arcs) and `logs/monitored-packets - 100speedjumpmovement.log` (fs=1, 16 arcs),
parser `tmp/snowfit/airfit.py` (extends fit.py; air stances are 6/7, gravity sanity
check median ay=2000 px/s², terminal 670 — exact).

1. **Launch snap**: jump with a direction held sets vx = ±walkSpeed instantly,
   regardless of ground speed. fs=1: `62 → 125 px/s` within a 30 ms element
   (≥2100 px/s², vy0=-495 pins takeoff to element start); El Nath: `-34 → -124`,
   `-50 → -124`, `-67 → -125` over 240–270 ms where ground accel (280 px/s²) could
   only reach ~-110 and the fitted air accel (40 px/s²) only ~-45. With NO input the
   current ground hspeed carries: `0→0, 3→3, 9→10, 29→29`.
2. **Air control — CONFIRMED in disassembly** (`CVecCtrl::CalcFloat @ 0x9b2c3c`,
   Angel.idb). Input held calls `ApplyForce(vx, force=input*2*D2, M=100,
   vmax_air, dt)` with `D2 = fs×10000` and
   `vmax_air = (walkSpeed125/walkForce140000)×D2 = 8.93×fs px/s`: accel
   `200 × fs px/s²` toward the input, applied ONLY while the velocity component
   in the input direction is below `8.93×fs px/s`; hard clamp to that band edge
   on overshoot; NO-OP (no accel, no clamp) when already moving faster in the
   input direction. So a counter-strafe decelerates at 200×fs straight through
   zero and pins at 8.93×fs in the new direction; same-direction input adds
   ~nothing once moving. There is NO walkSpeed cap in the air — the observed
   ≤125 px/s is the ground cap carried in by the launch snap. Packet fits agree:
   fs=1 counter-strafe `-103 → -79` over 120 ms = exactly +200; El Nath counter
   elements `+1 px/s per 29 ms` ×3 = ~40 = 200×0.2 (200 fs-independent would
   predict +5.8/element — rejected); no sampled arc shows same-direction gain
   beyond the band. NOTE: map fs scales AIR control too, not just ground walk.
3. **No-input drag** (same disasm): vx decays toward 0 at `1 × fs px/s²`
   normally, switching to `100 × fs px/s²` while falling AT terminal velocity
   (vy = fallSpeed 670); zero-cross clamped. Consistent with the packets'
   near-constant neutral arcs (`29→26` over 480 ms ≈ -6 px/s² worst case —
   mostly pre-terminal, where 1×fs is invisible at packet resolution).
4. **Landing halves momentum**: touchdown sets hspeed = vx/2 — `125→62`,
   `-104→-52`, `26→13`, `9→4` (exact integer halving on both maps). With the
   OPPOSITE direction held at touchdown it zeroes outright: `-122→0`, `-124→0`
   (then 390 ms standing at vx=0 — no slide), `-79→0` at fs=1. This is the legal
   "counter-strafe to stop dead on an icy ledge" trick and the reason the El Nath
   foothold chain 171>262>264>267>277>278>279 is human-traversable.

Residuals: post-landing re-acceleration on ice replays exactly under the kinetic
model (e.g. landing→0 then `+33 px/s` after 120 ms of right input = 280×0.12 ✓,
position +2 px ✓). Bot model updated to all four rules
(`Config.AIR_CONTROL_ACCEL_PXSS` + `AIR_INPUT_BAND_DIVISOR` + `AIR_DRAG_PXSS` /
`AIR_DRAG_TERMINAL_PXSS`, `landingGroundHSpeed`, `landOnGround`,
`resolveAirVelocityX`); GRAPH_VERSION 55.

The earlier symmetry caveat is RESOLVED by the CalcFloat disasm: same-direction
air accel is NOT symmetric with the counter-strafe — it exists only inside the
8.93×fs band (ApplyForce no-op beyond it). The previously fitted ">=333 px/s²
with input" samples were contaminated with ground frames; the disasm caps air
accel at 200×fs. Committed nav arcs remain exact under the corrected model: the
launch key held for the whole flight is a no-op above the band and suppresses
drag, so the graph's constant-stepX arc simulation is unchanged.
