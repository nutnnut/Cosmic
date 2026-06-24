# Party-autopilot redesign

Status: **Stage 1 landed.** Stages 2–5 designed, not yet built.

## Why

Bot autopilot was originally party-only. Solo, dynamic-party, self-owned-crew, errands and
operator-commands were each bolted on later as flags and special cases on `BotEntry`. The result is a
system where **the plan is stored N times** (once per member's `BotEntry`: `autopilotMapId`,
`autopilotDestinationName`, `autopilotObjectiveSummary`, `autopilotCohortMember`, `autopilotParty`,
`followTargetId`, …) and the code fights to keep N copies in sync.

Every recurring party bug is the same class — **redundant state that fell out of sync**:
- "the omission was the party-desync bug" (members reset by `follow` planned around)
- the crew-split-up bug (2026-06-24): followers drifted onto stale solo picks; `redecideParty`'s
  early-return checked only the *leader's* map, so a drifted follower was never corrected.

You can't bandaid your way out of a missing single-source-of-truth. The first fix for the crew-split
(a re-pin loop in `redecideParty`) was literally a sync patch — it was replaced in Stage 1.

Other smells from the retrofit:
- `autopilotParty` boolean forks solo vs party decide paths → duplicated logic.
- **Two** notions of "leader": `members.get(0)` (decides) vs `effectiveCohesionLeader` (first
  non-errand member, for following).
- `resolveFollowAnchor`'s `owner==self` → null special case, plus an explicit-target pre-check that
  exists only to work around that null.
- Errands (resupply / quest / gacha / job / rest) are **four separate ad-hoc systems**, each with its
  own `BotEntry` fields, its own tick phase, and its own guards scattered through `tick()`.

## Principle

The rot is in the **decision / state-ownership layer**, not the execution layer. The cohesion
mechanics (straggler hysteresis, portal-anchor waits, formation offsets) are good and battle-tested —
their comments cite real pathlog incidents. **Keep the execution layer. Restructure ownership.**

## Target architecture

Three layers, cleanly separated, plus policy:

### Layer 1 — Plan (WHERE + WHY), SSOT
`PartyAutopilotState`: one object per cohort (keyed by game party id, or owner id for an
owner's-own-bots cohort with no formal party). The leader is the **only writer**; members read. One
destination means it can't drift. Solo = a degenerate cohort of one (Stage 2).

### Layer 2 — Role/state (WHAT AM I DOING NOW), explicit state machine
Replace the implicit combination of booleans (`grinding` / `following` / `autopilotTransitFollow` /
`autopilotWaitAnchor` / `autopilotCohortMember` / `autopilotWaitingForStragglers`) with one
`enum AutopilotState { TRAVELING, GRINDING, FOLLOWING, WAITING_AT_PORTAL, ON_ERRAND, IDLE }` and
centralized transitions. New behavior = new state + handler, not a new boolean checked in ten places.

### Layer 3 — Errand (DETOURS), first-class abstraction
`interface Errand { boolean shouldStart(entry,bot); int destinationMap(entry); TickResult tick(...);
void onComplete(entry); }` with a registered list (Resupply, Quest, Gacha, Job, Rest). The tick loop
iterates registered errands uniformly instead of four hardcoded phases. **New errand = one impl +
register once** — this is the key enabler for "more errands/features later".

### Decider as policy (Layer 1's brain)
`interface AutopilotDecider { Plan decide(cohort); }`:
- `GrindAdvisorDecider` — current grind planning (`scorePartyBest` already picks one shared map for
  the cohort's max summed score).
- `PlayerLedDecider` — destination = the player leader's map; grind-if-mobs-else-follow; never picks
  a map. Selected when `partyHasRealPlayer`. This is Part B, dropped in as a policy, **no new flags**.

## Migration stages

Each stage is independently shippable and guarded by the existing tests.

- **Stage 1 — Plan SSOT. ✅ DONE.**
  - `PartyAutopilotState` (one per cohort) + registry in `BotAutopilotManager`.
  - Leader publishes the plan in `applyPartyPlan`; every member refreshes its cached
    `autopilotMapId` / destinationName / objective from the SSOT at the top of `tick()`
    (`syncFromPartyState`) — the per-`BotEntry` fields are now an explicitly-derived per-tick cache,
    not independent state.
  - `redecideParty`'s re-pin bandaid removed (the per-tick sync supersedes it); restored the simple
    early-return.
  - Drift is now impossible by construction: one destination, refreshed every tick.
  - Cohort key handles the no-game-party case (owner's-own-bots cohort) and is null-safe for
    unkeyable cohorts (solo / test bots), which fall back to per-entry fields.
- **Stage 2 — Decision-dispatch seam. ✅ DONE (refined scope).**
  - `maybeRedecide` no longer forks inline; it calls one `redecide(entry,bot)` dispatcher that routes
    to `redecideParty` (group, leader-driven shared plan) vs `redecideSolo` (own advisor pass: gear /
    farm-item + ferry teaser). The split is expressed by ONE predicate, `decidesAsGroupMember(entry)`,
    which all decision call sites (`noteGearUpgraded`, `noteLevelUp`, the async guard) now read
    instead of touching `autopilotParty` directly.
  - This is the hook point for Stage 5's player-led policy — it plugs into `redecide` as one branch,
    not smeared across call sites. Solo behavior is byte-for-byte preserved (suite stays 49/0/0).
  - **Deliberately NOT done: literally flipping soloists to a cohort-of-one.** Two concrete reasons:
    (1) a single solo bot has no plan-drift to fix — its `BotEntry` already *is* the single source,
    so the party SSOT solves nothing there; (2) `autopilotParty` is load-bearing for cohort grouping
    (`defaultPartyMembers`), so flipping it true would wrongly merge an owner's separate non-party
    bots into one cohort. The seam gives the uniformity benefit without that risk. If a future stage
    needs true universal cohorts, introduce an explicit cohort-identity field decoupled from
    `autopilotParty` rather than overloading the flag.
- **Stage 3 — Errand abstraction.** Collapse the four errand systems behind `Errand` + a registry;
  remove the bespoke per-errand fields and tick phases.
- **Stage 4 — AutopilotState enum.** Replace the boolean soup with the explicit state machine.
- **Stage 5 — Player-led decider (Part B).** `PlayerLedDecider`: bots in a player's party follow the
  player, grind in the player's map when mobs are present, follow when not, and never decide a map.

## Risk

Hot path; ~30 `BotAutopilotManagerTest` methods + `BotGrindPlannerTest` guard it. Do each stage with a
compile + targeted test run. Do **not** touch the cohesion execution layer except to have it read
from the SSOT. (Note: as of Stage 1 the shared `experimental` branch carries unrelated concurrent
work that leaves much of `BotAutopilotManagerTest` red — Stage 1 was verified to add **zero** new
failures vs that baseline, but the party-plan assertions themselves are currently blocked by that
breakage and should be re-confirmed once the branch is green.)
