---
name: kb_bot_eventmanager_transit_rides
description: EventManager rides (subway/train/crane/elevator) modeled as BotFerryManager FerryRoutes; 3 boarding styles + verified ids
metadata: 
  node_type: memory
  type: project
  originSessionId: c525ecec-48bc-49d0-8a18-9d716d21ee2b
---

Cross-map transports the WZ scan can't see (EventManager rides) are modeled as `FerryRoute`s in `BotFerryManager`, reusing the existing stateless boarding/transit machinery (`routesBoardingAt` → world-graph edge when `RouteOptions.withFerry()`; `TRANSIT_MAP_TO_ROUTE` → `tickTransit` waits on ride maps). Adding rows needs NO graph/cache bump (cache is pure-WZ, ferry edges re-added in memory). See [[kb_bot_navigation_architecture]], [[kb_bot_errand_progress_ssot]].

**Three boarding styles, dispatched in `tickBoarding` by route shape:**
1. **ticket+gate** (existing ferries + KC↔NLC `Subway`): `ticketItemId!=0`. Buy ticket → usher → gate "entry" → board.
2. **NPC start-instance** (KC→KSquare `KerningTrain`, Orbis↔MuLung `Hak`): `ticketItemId==0 && !eventName.isEmpty() && boardingPortal==""`. Walk to NPC, call `startInstanceAction.start` (seam → `EventManager.startInstance(bot)`) FIRST, then pay meso on success (matches Hak script order; lobby-full=false→failed hop). Free rides have ticketCost 0.
3. **portal start-instance/gated** (KSquare→KC `out00`/Depart_ToKerning, Helios elevator `in00`): `boardingPortal!=""` (16-arg ctor; 15-arg convenience ctor defaults ""). Walk the REAL scripted portal via `walkToPortalAndEnter(getPortal(name))`; its own script runs the gate/startInstance/warp (no Java reimpl). Re-arms `followTravelDeadlineMs` while waiting for the gate (elevator cycles on a timer).

Whale rides (Ereve/Rien) stay `ticketItemId==0 && eventName==""` → existing `changeMap` solo branch.

**Verified ids (Map.wz + scripts/npc + scripts/event + scripts/portal):**
- KC↔NLC: ticket 4031711(KC)/4031713(NLC) 5k, Bell 9201057, gates 1052007(KC)/9201068(NLC), waiting 600010004/600010002, deck 600010005/600010003, dock 103000100/600010001.
- KC↔KSquare: NPC 1052007 sel0 (free); station 103000310 (`enter00`→town 103040000); ride 103000301/103000302; return portal `out00`.
- Orbis↔MuLung: Hak NPC 2090005 @cabin 200000141 (Orbis 200000100→200000140→200000141 plain portals) / temple 250000100 (`west00`→town 250000000); 1500 meso; ride 200090300/200090310.
- Helios elevator: 2F=222020100, 99F=222020200; portal `in00`; transit 222020110/222020111 (up), 222020210/222020211 (down).
- Orbis↔Ereve already modeled (200000161↔130000210); dock 130000210→130000000 by portal.

Tests: `BotFerryManagerTest` (startInstanceAction seam + findFerryEdge), `BotWorldGraphTest.shouldConnectEventManagerTransitLinksWhenOptedIn`. NOT touched: pre-existing baseline-failing `shouldRideTaxiOnlyWhenMesoCoversTheFare` (concurrent session).

CORRECTION (2026-06-24): El Nath 211000000 is NOT islanded — it's WALK-reachable via the Orbis Tower (211000000 → 211000200 → Orbis Tower 200082100 → … → Orbis), plain portals. The unmodeled Orbis↔El Nath ship is just a shortcut, not needed for reachability. The BotFerryManager TODO was wrong and was removed. Sharp Cliff I (211040300) is the one El Nath map with no plain forward portal — its sole entrance is Jeff (NPC 2030000) on Ice Valley II (211040200), a free level≥30 NPC-click warp now modeled as a `TaxiEdge(211040200,2030000,211040300,0,false,30)` + CONTINENT_RIDE_NPCS in BotWorldGraph. Florina round-trip also completed: return is Pison (1081001) on Florina Beach → Lith, free `TaxiEdge`.
