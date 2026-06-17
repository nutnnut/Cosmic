# 07 — Party level-gap idle-leech (let lower bots catch up)

**Status:** scoped, exp rule VERIFIED, owner-approved. Read `README.md` first. Touches party/cohort + combat gating.

## Goal
In a `@botparty`, bots inevitably level apart. Past a level gap the server stops sharing exp with the
lagging bot, so it falls further behind (runaway gap). When the gap **approaches the limit**, the
**higher-level** bots should **idle on a safe platform and stop attacking** so the **lower** bots become
the damage-dealers and gain full exp to catch up; resume normally once the gap closes.

## VERIFIED exp-share / level-gap rule (server truth)
`server/life/Monster.java:distributePartyExperience` (`:549`) + `config.yaml`:
- `USE_ENFORCE_MOB_LEVEL_RANGE: true` (`config.yaml:256`).
- `EXP_SPLIT_LEECH_INTERVAL: 5` (`:307`), `EXP_SPLIT_LEVEL_INTERVAL: 5` (`:306`).
- A party member receives a kill's exp only if its level is within **5 of the mob** OR within **5 of a
  damage-dealing member** (the code unions those intervals via `IntervalBuilder`; out-of-interval members
  are added to `underleveled` and get nothing).
- **Consequence:** at a **gap ≥ 6** between the lagging bot and the lowest damage-dealer (and the mob), the
  lagging bot gets **zero** exp. So the actionable threshold is **gap ≈ 5**.

## Design
- Compute the party's level spread: `maxLevel - minLevel` across cohort members on the same map.
- When the spread reaches a trigger (e.g. **≥ 4**, just under the 5 cutoff — make it a config knob, e.g.
  `PARTY_LEECH_GAP_TRIGGER`), the members **above** `minLevel + EXP_SPLIT_LEECH_INTERVAL - margin` enter an
  **idle-leech** state: stop attacking (do no damage), move to / stay on a safe mob-free platform near the
  party, and just hold. The **lower** bots keep grinding as the damage-dealers; with the higher bots not
  dealing damage, the leech interval centers on the low bots and they get full exp.
- Exit idle-leech when the gap drops back under a lower hysteresis bound (e.g. ≤ 2) — don't flip-flop.
- Only applies to autopilot/cohort bots (not a supervised follow with an online human owner). The leader's
  travel/cohesion still works; this only suppresses combat for the over-levelled members.

## Investigate first (file:line)
- Cohort/party state: `BotAutopilotManager` (party cohort, `members`, leader, `partyInputs`/`decideParty`,
  the cohesion/straggler logic) — where to read member levels and mark a member idle.
- Combat suppression: the cleanest "do no damage" gate is the same early-return pattern the quest/job
  errands use in the autopilot tick (return before grind/combat). See `BotManager.tryLocalOpportunityAttack`
  / the attack gate, and how the job errand suppresses attacks (handoff 04/`BotStarterKitManager.tickJobErrand`
  → `BotAutopilotManager.tick` early return). Reuse a similar "idle, don't attack" state.
- Safe-platform idle: reuse the low-HP idle-regen / no-grind-target wander/idle machinery
  (`resolveNoGrindTargetPosition`, `idleOnGround`) to park on a mob-free spot.

## Risks / notes
- "Do no damage" must be real (no opportunity attacks, no buffs that tag mobs). Verify the higher bot
  truly contributes no damage so it drops out of the leech interval and the low bots get full share.
- Idle bots must still pot/heal/survive and follow the party on map changes (don't get left behind).
- Humanlike: stagger which bot idles, small jitter; announce briefly (ASCII).

## Verify
- Compile. Unit-test the gap-trigger/hysteresis decision (pure over member levels). Don't run nav/graph (rule #4).
