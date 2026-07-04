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

---

## 2026-07-04 re-diagnosis — a SLOW ACCUMULATION driven by decide() NPEs

**It accumulates over hours, it is not instant.** Same server, two states:
- After a fresh restart (~15 min up, 656 bots): **553 grinding (84%), 19 "idle rn" (3%)** — healthy.
- After hours up (626 bots): **~99 grinding (16%), 257 "idle rn" (41%)** — the leak.

So bots leak into inert slightly faster than recovery drains them, and the pile grows over
hours. Restart resets it; "ratio still dropping" = re-accumulating from zero.

**Root cause: recovery FAILS whenever `decide()` throws.** `maybeRecoverInertAutopilot` fires
fine (confirmed: pathlog of stuck CapitaL passes every visible gate — `Autopilot: off`,
`Mode: idle`, errands −1, no Lifecycle/AdminBind line), but it calls `start() → decide()`, and
when decide throws it returns null → no plan installed → the bot stays inert until the next
30–60s retry, which throws again. Evidence: pre-restart run had `bot decide failed` NPEs on the
`bot-grind-advisor` thread AND 226/258 bots stayed inert across 7.5 min (many retries, none
recovered); post-restart run has ZERO decide-NPEs and recovery works (only 3% idle). Tight
correlation: **decide-NPE present ⟺ recovery fails ⟺ inert accumulates.** The NPE hits LIVE
bots (failing-bot names incl. SpEaKsAiM/PollsPos9000 that were live in the roster), not just
disposing ones.

**The exact decide NPE line is NOT yet pinned** — the JVM strips the stack after the first few
throws (`OmitStackTraceInFastThrow`), so the WARNs are stackless. NEXT STEP: relaunch with
`-XX:-OmitStackTraceInFastThrow` and the next accumulation reveals the site. decide()'s catch
now logs the map + a `[stackless fast-throw]` marker (BotAutopilotManager.java ~1400) to
narrow it meanwhile. Candidates are the today valuation commits that feed decide:
`c0383e17e` (unify equip scorer + best-use market valuation), `bc0a2d150` (weapon pricing),
`573ba3d05` (scroll market feedback), `ddb12be6a` (scroll valuation opt) — the living-economy
market lookups now feed grind-spot scoring, so a market/belief null hole that only exists once
enough market state accrues would explain the hours-to-manifest timing.

**NOT the cause — the handled mob-load path.** `[SEVERE] MOB <id> failed to load` (e.g.
9101000/9410019) on the decide thread via `BotScrollManager.farmingCostMeso →
LifeFactory.getMonster` looks alarming but `getMonster` CATCHES the NPE, caches the bad id,
returns null, and `farmingCostMeso` handles null (→ +∞). The logged stack is just the caught
exception's origin, not a propagation. Benign (noisy).

**NOT the cause — formation commit c6d43755e** (user hypothesis, checked negative). It left a
cosmetic `followOffsetX` leftover (pathlog showed `offsetX=60`) but that's a position value, not
a recovery gate: `isAdminFollowActive` needs `debugCommanderId>0` and inert bots have `=0`.

**`DECIDE_POOL` is a real but SECONDARY concern here.** It IS a single thread
(`newSingleThreadExecutor("bot-grind-advisor")`, warm ~15ms / cold ~16.5s) and can peg at scale
(see [[kb_bot_perf_stress_hotpaths]]) — but post-restart it serves 656 bots at 84% grind fine,
so throughput is not what drops the ratio; failed decides are. Throughput would only compound
once the inert pile is already large (all retrying).

**Isolated, separate:** quest-errand NPE `ItemAction.check` (extSelection null) → `Quest.complete`
aborts the whole bot tick (`tick failed N/3`, 3 = bot removed). Only 1 bot hit it (FarmsEnding,
map 541000000). A specific quest's data, not a mass cause — but a live tick-crash worth fixing.

## Instrumentation added (2026-07-04) to watch it

`activityCategory` now returns a distinct **`"idle"`** bucket for the true leak (autopilot off
with NO rest reason), split out of `"break"` (which stays = genuine break/chill/errand). The
worldmap roster shows a clickable **"N possibly stuck"** count that filters the roster to only
the leaked bots; hovering a row shows its `statusReport` incl. `lastDecisionSuffix` = the last
decide reason ("decide failed …" vs "no reachable grind spot …"), so the two failure modes are
distinguishable live. See [[kb_bot_break_and_session_state_machine]].
