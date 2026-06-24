---
name: kb_bot_inert_autopilot_recovery
description: Managed bots idling in town = autopilot leaked OFF (autopilotMapId=-1) with no recovery; NOT the break ratio. Self-heal in tickIdleEntry.
metadata: 
  node_type: memory
  type: project
  originSessionId: f555682e-ff38-4d43-bf3a-5affa1306e77
---

Symptom: after `!botpop` runs hours, >90% of managed bots idle in town. Pathlog of an
afflicted bot shows `Autopilot: off  destMap=-1`, `Mode: idle`, all errands -1, not dead,
self-owned. This is NOT a break — a break keeps autopilot ON (`grinding=true`,
`autopilotMapId` set, idles AT the grind map, cleared in `tickGrindMode` BotManager.java
~3238). It's the autopilot itself leaked to OFF.

Root cause class: `isActive(entry)` == `entry.autopilotMapId != -1`. EVERY re-decide path
gates on isActive (`BotAutopilotManager.tick` early-returns; `maybeRedecide` callback
bails). So once autopilotMapId hits -1, NOTHING turns it back on — permanent inert idle,
standing on the town spawn portal (so they also visibly stack). Leak sites:
1. `start()` initial decide returns null -> NO_SPOT reply, no plan, no retry (fires at
   spawn: cold caches + 67 bots contending the single-thread DECIDE_POOL).
2. `onDeathLoop()` sets -1 expecting "next tick re-decides" — but isActive is now false.

Two null kinds, were both silent+indistinguishable: legit "no reachable worthwhile spot"
(non-null Decision, null rec()) vs a swallowed RuntimeException turned into null at TWO
sites — `decide()`'s catch AND the `decisionRunner` lambda (likely CME iterating live
state off DECIDE_POOL; see the CME note in BotAutopilotManager).

Fix (commit on experimental, 2026-06-19):
- `BotManager.maybeRecoverInertAutopilot` called from `tickIdleEntry`: for a self-owned
  (`owner==null||owner==bot`) idle bot that's not loggingOut/dead/decision-in-flight and
  `!isActive`, throttled (~30-60s, reuses `autopilotNextDecisionAtMs` as backoff clock)
  re-call `BotAutopilotManager.start`. One hook covers BOTH leak sites. Player companions
  excluded (their idle may be owner-intended). Test:
  `BotManagerTest#recoversInertSelfOwnedAutopilotThrottledAndScoped` (swaps decisionRunner).
- Un-swallowed both catches -> `log.warn` (still returns null so recovery retries, just
  visible now); legit no-spot is `log.debug`.
- Town idle placement: `pickTownLoiterAnchor` (random NPC ∪ map character, snapshot ONCE
  no-chase, through the NPC-approach SSOT `BotTravelManager.pickReachableApproachPoint`)
  replaces the old botPos±spread in `tickLogout`. Players-as-anchor OK because one-shot.

Related: [[kb_bot_town_nav_airborne_target]] (different cause: nav stranding, not
autopilot-off), [[kb_bot_self_owned_owner_assumptions]] (owner==bot trap),
[[project_grind_advisor_perf]] (cold-cache DECIDE_POOL tax that triggers the null decides).
