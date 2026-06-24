---
name: kb_bot_npc_hop_hail_map_wide
description: "Taxi/ferry job-advance \"deadline\" give-ups = bot can't stand exactly on a dock/tree NPC; fix = hail map-wide on budget lapse (NPCs are clickable from anywhere in real client), exempt from generic deadline give-up"
metadata: 
  node_type: memory
  type: project
  originSessionId: c525ecec-48bc-49d0-8a18-9d716d21ee2b
---

Recurring bug class (hit taxi first, then ferry — same root cause both times; user pushed back "are you sure that is the right bug" and was right that the deadline theory was a symptom).

**Symptom:** bot stuck job-advancing, `lastGiveUp=deadline`, `route-reachable=true`, plenty of meso. For taxi: `bestDist` (204/490) is INSIDE the 500px hail radius yet still times out. For ferry (Ariant→Orbis Genie, map 260000100): leg diagnostic shows `gateOpen=true` but bot grind-wanders instead of boarding.

**Root cause:** the generic deadline give-up in `BotTravelManager.tickTravel` (~line 218) fires BEFORE the hop's own approach handler runs, so the "act from where you stand" fallback was dead code. And the bot never satisfies the strict grounded-within-range / at-the-usher gate because the cab/seller/usher sits on a foothold the nav can't stand exactly on (Ellinia rope-tree, Perion cliff, the Genie pier) — it oscillates until the budget lapses → give-up → cooldown → grind-wander (the captured snapshot). Ferry gate-CLOSED waits already re-arm the deadline (never time out while waiting); the gate-OPEN walk-to-seller/usher steps do NOT, so they trip it.

**Fix (SSOT, mirrors the shop visit's stuck-near):** real MapleStory NPCs are clickable map-wide — no proximity gate on dialog (verified: scripts/npc/2102000 Genie usher, 2102002 seller just warp/sell on click). So:
- Exempt taxi (`followTravelTaxiNpcId!=0`) AND ferry (`followTravelFerry`) hops from the generic line-218 deadline give-up.
- `tickTaxiHop` / `BotFerryManager.walkToNpcThenAct`: ride/buy/board when `inRange || stuckNear || deadlineHail`, where `deadlineHail = followTravelDeadlineMs > 0 && now >= followTravelDeadlineMs`. The `>0` guard is REQUIRED — unset deadline (0) would spuriously hail (unit tests call tickBoarding with now=0L). The walk still runs the full budget first; hail is last resort.
- Commits 03c26d440 (taxi), 184814714 (ferry).

**Caveat:** exempting ferry from line-218 removes the give-up backstop for the non-NPC guided-route steps (walkway portals on Orbis hub) — accepted (flat maps, won't strand; deadlineHail only after full ~45s budget). Same tradeoff the taxi fix made.

**Offline repro/verify (no live server):** `BotTravelSimulationLabTest` drives the real `tickTravel`->`tickTaxiHop`->`stepMovementCore` over real WZ footholds (nav-lab substrate). `BotNavigationMapLoader.npcGroundedPosition(mapId,npcId)` reads the NPC's real (x,cy) from the map `life` node. Finding: bot's closest GROUNDED approach to the Ellinia cab is bestDist=499 of the 500px radius — one pixel of margin, which is why an airborne-pass approach (live albums bestDist=204 while not grounded) gave up on deadline. To force a deadline-hail in-lab, pre-seed `followTravelBestDist` so tick-1 sees no progress (else the progress-aware deadline re-pushes). Auto-skips when wz/ absent.

**Pathlog now shows the geometry** (was invisible): taxi hop `cab=(x,y) distToCab grounded/inAir/climbing`; ferry hop `ferry: <BotFerryManager.describeLeg>` = onRide-waiting / gateOpen / OFF-chain; `no-ai` decoded (logging-out/break/cadence). See [[kb_bot_eventmanager_transit_rides]], [[kb_bot_job_change_walk_to_npc]], [[kb_bot_npc_dwell_walk_in_place]].
