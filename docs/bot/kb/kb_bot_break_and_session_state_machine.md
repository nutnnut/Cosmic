---
name: kb_bot_break_and_session_state_machine
description: Bot rest state machine — chill session vs in-session break vs town/rest-errand break vs the inert-autopilot LEAK. What each is, how a break resumes, and how the roster "break/chill/grind" buckets are derived. Read before touching BotBreakManager or diagnosing "too many idle bots".
metadata:
  node_type: memory
  type: project
---

Map of the bot "not grinding right now" states, so an idle bot can be classified
correctly. Investigated 2026-07-04 while diagnosing a skewed grind:break ratio.

## The states (all distinct — don't conflate)

1. **Chill session** (`entry.chillSession`) — a WHOLE-session decision made at login:
   this bot logs in to loiter in town all session, not grind. Gated by
   `BotBreakManager.rollChill` (config `CHILL_SESSION_ENABLED` + `CHILL_SESSION_MULTIPLIER`,
   level ≥ `CHILL_MIN_LEVEL`=5, personality `chillSessionChance`). Roster bucket = `chill`.
   Legit/by-design. For a CREW the decision is crew-wide, not per-bot — see "Crew chill" below.

2. **In-session break** — a grinding bot pauses. Rolled at most once/min in
   `BotBreakManager.maybeStartBreak` (per-minute prob = `breakFreqPerHour/60`, damped by
   `farmIdleRatio`; diligent bots break less). Sets `entry.breakUntilMs`. `grinding` STAYS
   TRUE — the pause is a sub-state of grind mode, executed inside `BotManager.tickGrindMode`
   (parks at a held `breakIdleAnchor` via `walkToOrIdleAt`; pots still run, no target search).
   Three flavors, chosen in `maybeStartBreak`/`startTownBreak`:
   - **in-place break** (default): idle right on the grind map, `breakDurationMs` (mean from
     `breakLenMeanMin`, ×0.5–1.5 jitter).
   - **town break**: self-scroll autopilot bots rest in a TOWN (sell trash + resupply +
     tinker gear). If already in town → arm `townBreakDurationMs` (10–60 min, scales with
     `laziness`). Else set `entry.restErrand=true` and let autopilot route to town; the
     in-town clock starts on arrival.
   - **nearby-safe-map break**: deep grind spots rest at a closer safe map instead of a full
     town round-trip (`findNearbyBreakMap`/`townBreakChance`, keyed on hops-back).
   Roster bucket = `break`. Legit/by-design.

3. **Rest errand / gacha / FM / quest / job errands** — `entry.restErrand`,
   `gachaErrandMapId`, `fmErrandMapId`, etc. Bot is travelling to do a town chore. Roster
   bucket = `break` (except job/quest which the status report treats as productive travel).
   Legit.

4. **Inert-autopilot LEAK** — `!isActive(entry)` i.e. `autopilotMapId == -1`, `grinding=false`,
   all errands −1, `breakUntilMs==0`, not logging out, self-owned. This is NOT a rest — the
   autopilot destination leaked to OFF and nothing re-armed it. Status string = **"idle rn"**,
   roster bucket = `break` (it falls into `activityCategory`'s `!isActive` arm). This is the
   bug state — see [[kb_bot_inert_autopilot_recovery]].

## Resume path (how a break ends)

`tickGrindMode` (BotManager, grind-mode only): once `now >= breakUntilMs` and `breakUntilMs != 0`
it calls `BotBreakManager.endBreak` → clears `breakUntilMs`/`breakIdleAnchor`, optional resume
chat, and grind resumes on the same tick. KEY: this resume ONLY runs while `grinding==true`
(inside tickGrindMode). A break never turns grinding off, so the resume is reliable — UNLESS
the autopilot has separately leaked to inert (state 4), in which case tickGrindMode isn't even
being called and the bot never resumes. So "resume grind breaks" almost always means the
inert LEAK, not the break timer.

## Login seeding (why bots don't stampede)

`loginBreakChance` / `startLoginBreak`: a fraction of bots log in ALREADY mid-break
(steady-state fraction = `breakFreqPerHour × breakLenMeanMin / 60`, capped 0.5) so a freshly
spawned population looks like a random snapshot of an established one instead of all grinding
at once then all breaking at once.

## Group breaks

`BotAutopilotManager.maybeStartGroupBreak`: a party cohort (autopilotParty, ≥2 members) breaks
together — only the leader (cohort[0]) rolls, on the AVERAGE of members' traits; low-cluster
members keep grinding to catch up. Solo bots (cohort < 2) use the per-bot `maybeStartBreak` path.

## Who sits out a group rest (SSOT)

`BotAutopilotManager.catchesUpThroughRest(member, cohort)` — the ONE predicate for "this member is far
enough below the pack to skip the group's rest and grind to catch up". Wraps the pure
`BotBreakManager.catchUpSplit` (levels + `PARTY_LEECH_GAP_TRIGGER`). Both rest paths call it — the
leader-triggered group break AND the crew chill session — so they can never disagree about who sits out.
The cohort is the party at break time, or the crew's live entries at crew-session start (the party isn't
formed yet then). A catch-up member is **never** flagged `chillSession`, so it buckets as `grind`.

`catchingUpWhileCohortRests(entry)` derives the status: catch-up member + any cohort mate chilling /
on a break / on a rest errand → **"grinding to catch up with my group"** (`statusReport`). No new flag;
it's read off the cohort.

## Crew chill (2026-07-10) — the decision is crew-wide, and `entry.chillSession` is NOT its SSOT

A crew's chill decision lives in `BotScheduler.crewChillSession` (group id → boolean), decided once per
crew session in `markCrewSession` (leader's personality) and **re-applied to every live member each sweep**
by `applyCrewChill`. `entry.chillSession` is a per-login cache of that decision, never the source.

Why: `BotEntry` is per-login. When a crew member relogs mid-session (straggler respawn in `cohereCrews`
Pass A) it comes back with a fresh, un-chilled entry. The old code re-derived the crew's chill from
`leaderEntry.chillSession` — so when the **leader** was the one that relogged, the crew's chill flag was
exactly the one that got lost, `crewChill` latched false forever, and any member that hadn't relogged kept
chilling alone. Observed 2026-07-10: crew 1212 (leader `itunes` + `TriOcean` grinding, `NatureFax` still
"just chilling in town today"). `chillSession` is never cleared anywhere, so the drift was permanent.

`applyCrewChill` also re-arms an expired in-town chill rest (`breakUntilMs == 0 && !restErrand`). Only the
`restErrand` arm of `BotAutopilotManager` (~line 809) re-arms chill; a bot that started its chill *already
in town* has no errand and would otherwise silently resume grinding when its first town break expired.

Pass A marks a session for any live crew with no `crewChillSession` entry (server restart, `!botpop`), so
the decision always has exactly one owner. `endCrewSession` drops both crew maps at session end.

## Roster bucket derivation (SSOT)

`BotAutopilotManager.activityCategory(entry, bot)` — used by `/api/live` (`a` field) and the
roster tally. Four buckets: `chill` (chillSession) → `break` (loggingOut / gacha/FM/quest/job/
rest errand / now<breakUntilMs / idleLeech — genuine rest or chore) → **`idle`** (`!isActive`
with none of the above = the inert LEAK, status "idle rn", a BUG) → `grind`. The `idle` bucket
(added 2026-07-04) is deliberately split from `break` so the leak is countable: the worldmap
roster shows a clickable "N possibly stuck" that filters to `a==='idle'` bots; hover a row for
its last decide reason. A high `break` count is normal; a high `idle` count is the bug —
see [[kb_bot_inert_autopilot_recovery]].

## Debugging idle bots

- `/api/botdebug` status ending `"idle rn"` = the inert leak; `"taking a break"` = state 2;
  `"just chilling…"` = state 1; `"grinding to catch up with my group"` = the catch-up split of a
  resting cohort (productive, bucket `grind`); errand phrases = state 3. Count these separately.
- One crewmate chilling while the others grind = crew-chill drift; check `crewChillSession` ownership
  before blaming the roll (see "Crew chill" above).
- `/api/bot/pathlog?id=<charId>` (toggle: call to start, call again to dump) — header shows
  `Autopilot: off destMap=-1`, `Mode: idle`, `Errands: …`, and a `Lifecycle:` line iff
  `loggingOut || breakUntilMs>0`. No Lifecycle line + Autopilot off = the leak, not a break.

Related: [[kb_bot_inert_autopilot_recovery]] (the leak + self-heal),
[[kb_bot_perf_stress_hotpaths]] (DECIDE_POOL is the recovery bottleneck at scale).

## 2026-07-05 — @botme/@botparty run a REAL char: grind-only, no recreation/economy

A player who `@botme`/`@botparty`s is autopiloting their OWN character (real gear, meso, NX). These
must NOT be confused with disposable `!botpop` population bots — **both are self-owned (`owner==bot`)**,
so ownership can't tell them apart. SSOT is an explicit flag: `BotEntry.commandAutopilot`, set true in
`swapToBotInPlace` (the single @botme/@botparty takeover site). `BotManager.isRealPlayerTakeover(entry)`
= `commandAutopilot || ownerIsBot(entry)` — the second term sweeps in a botified owner's companions
(the @botparty party) and auto-reverts when the owner reclaims (client flips back to a real `Client`).

Policy for `isRealPlayerTakeover` bots — **grind all the time, only grind-essential logistics**:
- OFF: personality break/chill (`tickGrindMode` break roll), gachapon (`common-gacha-scan`),
  free-market buy/sell (`common-fm-scan`), shout trades (`common-shout-trade`), quest piggyback
  (`common-quest-scan`), auto-scroll (real gear) + auto-craft (real mats) at `BotManager` ~3603.
- ON (unchanged): grind, resupply/sell shop visits (`shopVisitPending`), level-up, job advance,
  death/respawn, follow, HP-rest survival, party-up social. All gated in ONE place —
  `BotManager` common section (~5600) + `tickGrindMode` break roll — behind `isRealPlayerTakeover`.

Rationale: no human is present to approve risk to real assets, and the intent of "put my char on
autopilot" is to keep leveling, not to run the living-economy sim (that's the population bots' job).
Population bots (`owner==bot` but NOT `commandAutopilot`) keep doing everything.
