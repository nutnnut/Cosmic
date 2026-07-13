# Party autopilot

Status: the shared plan and decision seams are implemented. Two migrations remain deliberately
deferred and are tracked in [`ROADMAP.md`](ROADMAP.md).

## Current model

- `PartyAutopilotState` is the cohort's plan source of truth: destination, objective, commitment, and
  cohesion state. Per-entry destination fields are derived caches refreshed by
  `BotAutopilotManager.syncFromPartyState(...)`.
- The leader publishes decisions; followers consume the same plan. Soloists use the same policy seams
  without a party-state object.
- Detours (shop, quest, job, ferry, market, gacha, storage) temporarily preempt a plan without becoming
  a second plan source. The originating objective is resumed or explicitly re-decided afterward.
- Player-containing parties are protected from scheduler thinning, breaks, and logout behavior where
  leaving would disrupt a real player.

## Deferred work

1. `BotEntry` still represents mutually exclusive activity through `following`, `grinding`, and
   related flags. `TODO(bot-party-autopilot-stage4)` marks a future explicit state-model migration. Do not
   convert the flags piecemeal; first enumerate every legal transition and persistence/reset rule.
2. The decision seam can host a player-led policy, but Stage 5 is not implemented. Any such policy must
   define when bots follow the player, when they continue an autonomous objective, and how owner and
   ordinary party-member authority differ.

These are architecture choices, not an implementation handoff. Keep them deferred until behavior is
approved; do not resurrect the removed staged checklist.
