---
name: kb_bot_town_nav_airborne_target
description: Bot town-nav stranding — region is NOT a false-merge (Orbis r84 is a legit floor); two confirmed causes (airborne move-target same-region steer; autopilot travel give-up/wander loop) + the SHIPPED fixes and path-log diagnostics
metadata: 
  node_type: memory
  type: project
  originSessionId: dc7d70f2-4f47-4221-9944-a0cc28d2a2ad
---

Bot autopilot town-nav stranding (Orbis 250000000, party autopilot). Diagnosed 2026-06-15.

**Disproved: the graph false-merge theory.** Verified with BotNavigationProbe — Orbis r84 is a LEGIT single floor (x=[-4320,1593], y=[-14,51], 85 segs). Don't re-chase region union/canWalkAcrossFootholds for this. Probe (fast, single-map, NOT the slow nav suite): `mvn exec:java -Dwz-path=<repo>\wz -Dexec.mainClass=server.bots.BotNavigationProbe "-Dexec.args=250000000 --rebuild --point X,Y --region N --edges N"`.

**Cause A — airborne move-target (follower, e.g. pathlog-Preston):** a move-target placed off the walkable surface (e.g. (781,-178), 229px above the floor) resolves via findGroundFoothold DOWN to the bot's own region → nav reads "same-region" → straight-line steers up into air → stuck (arrival check needs BOTH x and y). Source of that specific airborne target was NOT definitively pinned (it is NOT a portal). The new path-log `[set by: ...]` tag will name it on the next repro.

**Cause B — autopilot travel give-up/wander loop (leader, pathlog-Bowgurl 16:32) — CONFIRMED + FIXED (commit 01ec4f48d on experimental):** the path-log `[set by: travel-pin]` tag confirmed the source was BotTravelManager (NOT fidget — that earlier guess was wrong). The bot couldn't reach the one real exit (west00 (-4150,-150), on r65, up a multi-jump climb) before the distance-based travel deadline → giveUp → 45s give-up window that:
- was never reset by clear()/commands (followTravelGiveUpUntilMs reset NOWHERE) → follow/move-here couldn't unstick it (the "stuck state");
- drove tickWanderToRandomPortal, which (a) treated tm=999999999 spawn/door portals as exits, and (b) re-rolled a new random portal every tick because tickTravel's give-up-branch clear() wiped the wander's committed followTravelPortalId → thrash (even warped across the map).

**Shipped fixes (5; Fix 4 subsumed by Fix 2 — no redundant code):**
1. BotTravelManager.resetForModeChange() = clear() + drop give-up cooldown; routed through BotAutopilotManager.clear() (the single mode-change hub: follow/grind/move/stop). clear() still KEEPS the cooldown for the internal per-tick retry loop (both directions regression-tested).
2. BotAutopilotManager gates tickWanderToRandomPortal on `now >= followTravelGiveUpUntilMs` — never wander during the cooldown; wait/grind and let tickTravel retry the real hop.
3. pickRandomCrossMapPortal excludes tm=999999999 (NO_DESTINATION_MAPID).
4. Progress-aware deadline: a new closest manhattan to the portal (followTravelBestDist) refreshes the deadline via travelBudgetMs(dist), so a long climb isn't aborted mid-progress; only a stuck/oscillating bot times out. Hop-start/clear() reset bestDist=MAX.

**Path-log diagnostics added:** point `*MIDAIR +Npx*`/`*OFF-GRAPH*` surface flags (commit 81f441bb5); `moveTargetSource [set by:]`; give-up `reason=` (deadline/portal-closed/warp-no-land/ferry-board-fail/taxi-npc-missing/taxi-fare-fail) + hop `bestDist`.
