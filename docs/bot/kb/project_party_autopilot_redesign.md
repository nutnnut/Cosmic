---
name: project_party_autopilot_redesign
description: "Staged redesign of bot party/crew autopilot into a single-source-of-truth PartyAutopilotState; Stage 1 (plan SSOT) landed, Stages 2-5 designed"
metadata: 
  node_type: memory
  type: project
  originSessionId: 5bbdb7cc-e4a9-40ad-919a-082510f38a48
---

Bot autopilot was originally party-only; solo/crew/errands/operator-cmds were bolted on later as flags on BotEntry, so the plan was stored N times (one copy per member) and code fought to keep copies in sync. Every recurring party bug = redundant-state-drift (see [[kb_bot_self_owned_owner_assumptions]]). Full design + staged plan in repo: **docs/bot/party-autopilot-redesign.md** (SSOT — read it before continuing).

**Stage 1 LANDED (this session):** `PartyAutopilotState` (one shared plan per cohort, new file) + registry in `BotAutopilotManager` keyed by `partyStateKey` (game party id, or `-owner.getId()` for an owner's-own-bots no-party cohort, null=unkeyable→per-entry fallback). Leader publishes plan in `applyPartyPlan`; every member refreshes its cached `autopilotMapId`/destName/objective from the SSOT at top of `tick()` via `syncFromPartyState`. Per-BotEntry fields are now a per-tick derived cache, not independent state → follower drift impossible. Removed the earlier re-pin BANDAID in `redecideParty` (restored simple early-return). Pruned in `clear()` when leader stands down.

**Root cause it fixed** (live debug of crew "ValorDelays" lvl23 warrior, party 1000000044): leader at shared map, followers drifted to own solo picks (100040004/105050400) because `redecideParty` early-return checked ONLY leader's `autopilotMapId`, never correcting drifted followers. First attempt was a re-pin loop (bandaid); user pushed for real redesign.

**Stages 2-5 (designed, NOT built):** 2=solo as cohort-of-one (delete autopilotParty fork, move decision clock into SSOT); 3=Errand abstraction (collapse 4 ad-hoc errand systems resupply/quest/gacha/job/rest behind one interface+registry — the key enabler for future errands); 4=AutopilotState enum (replace grinding/following/transitFollow/waitAnchor/cohortMember boolean soup); 5=PlayerLedDecider (Part B: bots in a PLAYER's party follow player, grind player's map if mobs else follow, never decide map). Principle: rot is in decision/state-OWNERSHIP layer; KEEP the cohesion execution layer (straggler hysteresis/portal-anchor/formation — battle-tested).

**Verification caveat:** shared `experimental` branch carries unrelated concurrent-session work that leaves ~21 errors+2 fails in BotAutopilotManagerTest (Job.getId() NPE on unmocked bots). Stage 1 verified to add ZERO new failures vs that baseline, but the party-plan test assertions are themselves blocked by the breakage — re-confirm once branch is green. Build command in [[reference_build_tools]].
