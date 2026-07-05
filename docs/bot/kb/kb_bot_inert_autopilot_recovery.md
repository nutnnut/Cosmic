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

## 2026-07-04 (session 2) — live triage with the new `idle` roster split

Watched a fresh-ish server (656 bots) with the roster split live. Findings:
- **Most "possibly stuck" are TRANSIENT, not wedged.** Snapshot: 35 idle; 75s later 12 idle —
  **28 of 32 recovered within 75s.** That's normal recovery churn (30–60s backoff). The instant
  count is NOT the bug; the small set that PERSISTS across snapshots is.
- **Two stuck classes, distinguished by the new `lastDecisionSuffix` on hover:**
  - *never-decided* (`autopilotLastDecisionAtMs<=0`, no suffix) — recovery hasn't completed a
    `start()→decide()` for them (a completed decide always records). Mostly the transient churn.
  - *decided-then-leaked* (suffix "last decided Nm ago: grind …") — got a plan, then went inert.
- Persistent set (idle across both snapshots) = the accumulation seeds. All solo self-owned
  (party-size-1, apParty=false), `grinding=false`, autopilot off. One was **FarmsEnding** = the
  quest-NPE tick-crash force-idle (isolated, see below), others were farm-item bots
  (biggercoming/AdamsMaybe "farm Gold Surfboard from Slime") stuck in town 16 min.
- **Formation offset is DEFINITIVELY a red herring:** a healthy GRINDING bot's pathlog also shows
  `Formation: offsetX=60` + `Follow base [anchor + formation offset]`. It's a universal default
  stagger, identical on grind and idle bots. Not the wedge.

### Root-cause candidate found + fixed (partial): onDeathLoop stale decision clock
`maybeRecoverInertAutopilot` reuses `autopilotNextDecisionAtMs` as its backoff gate
(`if now < autopilotNextDecisionAtMs return`). But `nextDecisionAt() = now + DECISION_INTERVAL_MS`
(**12 min**). `onDeathLoop` (BotAutopilotManager:206) sets `autopilotMapId=-1` — comment says
"maybeRecoverInertAutopilot re-decides" — but left the clock at the last plan's `decide+12min`, so
recovery was gated for up to 12 min after a death-loop. `clear()` (402) and `escapeTrappedRegion`
(186) both zero it; onDeathLoop didn't. **Fix: onDeathLoop now sets `autopilotNextDecisionAtMs=0L`.**
CAVEAT: the death caller (BotManager ~6287) calls `clearMode`→`clear()` (which already zeroes the
clock + grinding) WHEN `onDeathLoop` returns a town (!= -1). So the fix is load-bearing mainly for
the `town==-1` branch (no clearMode → also leaves `grinding=true`, which then trips tickIdleEntry's
guard and skips recovery entirely). So this fix is correct but likely NOT the whole accumulation.

### Still open — the real persistent wedge, now instrumented
Recovery lives in `tickIdleEntry`/`tickTownIdleDestack`, which SKIP it if any leftover reachability
state is set: `following | grinding | moveTarget!=null | farmAnchor!=null | shopVisitPending |
autopilotWaitAnchor!=null`. A farm-item bot that goes inert with a stale `farmAnchor` (only
`clearMode` clears it, not `clear()`/onDeathLoop) would never reach recovery → wedged. None of
these fields were visible in any endpoint. **Added a `Recovery:` line to the pathlog** (BotPathLogger)
that, for an inert bot, prints `ELIGIBLE` or `BLOCKED by <field(s)>` across ALL reachability guards
+ recovery gates (incl. `nextDecideIn=Ns`). Next step: after restart, pathlog a persistent-stuck
bot and read the exact blocker — that pins it. The quest NPE (`ItemAction.check` extSelection null →
tick-crash force-idle) is a separate, confirmed wedge for the one bot that hits it (FarmsEnding).

## Instrumentation added (2026-07-04) to watch it

`activityCategory` now returns a distinct **`"idle"`** bucket for the true leak (autopilot off
with NO rest reason), split out of `"break"` (which stays = genuine break/chill/errand). The
worldmap roster shows a clickable **"N possibly stuck"** count that filters the roster to only
the leaked bots; hovering a row shows its `statusReport` incl. `lastDecisionSuffix` = the last
decide reason ("decide failed …" vs "no reachable grind spot …"), so the two failure modes are
distinguishable live. See [[kb_bot_break_and_session_state_machine]].

## 2026-07-05 — @botparty ownership seam + SILENT party-decide failure

Live report: `@botparty` on a player (Bowgurl) who OWNS 5 companion bots → whole party fell idle,
and the web view showed **5 companions online** (not 0 or 6). Two distinct bugs, one shared root
(the same decide-NPE), found by pathlogging Bowgurl (`Recovery: BLOCKED by nextDecideIn=52s`,
`AdminBind: commanderId=2`).

**(1) Ownership SSOT seam — "owner is a bot" was unmodeled.** Bots key by ownerCharId; "self-owned"
= `owner==null || owner==bot`. When Bowgurl `@botparty`-botifies: its OWN entry is `owner==bot`
(managed ✓), but its 5 companions keep `owner==Bowgurl` — now a bot. `owner!=bot` and
`owner.isLoggedin()==true` (online AS a bot) → they fail every managed test → linger as
"companions of a logged-in owner" that is actually a bot. Consequences beyond the cosmetic count:
they were **excluded from `maybeRecoverInertAutopilot`** (`selfOwned` check) so they could never
self-heal, and RTS couldn't command them. Fix: `BotManager.ownerIsBot(entry)` +
`isSelfDrivingBot(entry)` (= `owner==null || owner==bot || ownerIsBot`) as the SSOT, used by both
recovery (`tickIdleEntry`/`tickTownIdleDestack` selfOwned) and web `commandableEntry`. The
companions self-drive while the owner is a bot and revert to companions when it reclaims (client
flips back to a real `Client`) — no re-parenting, so reclaim stays seamless. Did NOT touch the FM
gate (`owner!=null && owner!=bot && owner.isLoggedin()` still parks them — no new economy risk) or
the scroll gate. Tests: BotManagerTest 86/0, BotAutopilotManagerTest 53/0 still green.

**(2) `startParty`/`decideParty` failed SILENTLY.** `@botparty` → `startTakeoverAutopilot` →
`startParty(owner, all 6)` → `decideParty` which had `catch (RuntimeException e) { return null; }`
with **no log** (unlike `decide()`). So a party-decide NPE (almost certainly the same unpinned
NPE as the solo leak — solo `bot decide failed … NPE` was streaming in the same log) → null plan →
"can't find a spot we can all reach" → all 6 idle, and (bug 1) the 5 couldn't self-recover → stuck.
`startParty` had TWO more silent exits: a stale-`activityEpoch` drop and the genuine no-spot reply.
Fix: `decideParty` now `log.warn`s the exception (leader name + map + members + stackless marker,
mirroring `decide()`); the epoch-drop and no-spot paths now `log.debug`. Next `@botparty` that
fails will name the cause in the log. The shared decide-NPE is STILL unpinned (needs
`-XX:-OmitStackTraceInFastThrow` or code-inspection of the market/valuation null holes).

## 2026-07-05 (session 2) — the logging paid off: THREE distinct causes

The `party plan dropped` / `party decide failed` logs (above) plus a live pathlog nailed three
separate bugs behind "party autopilot silently fails" and "bots stuck grinding a town":

1. **Party-autopilot silently no-ops = EPOCH RACE (the big one).** Log filled with `party plan
   dropped: X epoch changed mid-decide`. `startParty` snapshots each member's `activityEpoch`, runs
   `decideParty` off-thread, and drops the plan if any epoch changed meanwhile. `activityEpoch` is
   bumped ONLY by `clearScriptTasks` (BotManager) — which `issueFollow`/`issueGrind`/`issueStop` all
   call. A **following** cohort (the exact pre-`@botparty` state) re-asserts follow via the combat/pot
   `issueFollowOwner` hooks, and each redundant re-assert bumped the epoch → EVERY decide dropped →
   the command "didn't register", bots kept following. Same race from per-bot recovery/redecide firing
   mid-decide. Two fixes: (a) `issueFollow` is now **idempotent** — already cleanly following the same
   target → no-op, no `clearScriptTasks`, no epoch bump; (b) `startParty` sets
   `autopilotDecisionInFlight=true` on all members during the decide (mirrors `redecideParty`), cleared
   first thing in the callback, so recovery/redecide can't race it.
2. **Stuck "grinding" a town = the inert+grinding WEDGE.** Live pathlog of an @botparty member
   (Clawer, owner=Bowgurl-the-bot): `Mode: grind`, `Autopilot: off destMap=-1`, at Mushroom Shrine
   (a town), `Recovery: BLOCKED by grinding`. A self-driving bot with `grinding=true` but
   `autopilotMapId=-1` local-grinds forever, and `maybeRecoverInertAutopilot` only runs for IDLE bots
   so `grinding=true` blocks it. Producer not fully pinned (the death path's own `clearMode` at
   respawnBot clears grinding; likely a race between a party re-plan and a death), so fixed at the
   invariant layer: `tickCore` now detects a self-driving bot (`isSelfDrivingBot`) that is grinding
   with no autopilot dest (and not following/errand/operator) and `clearMode`s it to idle — same
   remedy the death-loop escape uses — so the next idle tick recovers it. Logs a WARN naming the bot so
   the producer can still be traced.
3. **The scary `[SEVERE] MOB X failed to load` NPE is a RED HERRING** (`mobStats is null` →
   `Pair.getLeft()`), CAUGHT in `LifeFactory.getMonster` (cached in `failedMonsterLoads`, logged once,
   callers handle null). It is NOT the decide crash. Downgraded to a one-line WARN, no stack — it's a
   stale dropper id with no WZ row, not a fault. The real decide-NPE (bare stackless
   `NullPointerException` on `bot-grind-advisor`) is still unpinned.
