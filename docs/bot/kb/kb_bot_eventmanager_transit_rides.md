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

Additional verified travel added 2026-06-27:
- Ereve sky ferries: Orbis<->Ereve already modeled (200000161<->130000210); Ellinia<->Ereve uses NPC 1100007 @101000400 -> ride 200090030 -> 130000210 and NPC 1100003 @130000210 -> ride 200090031 -> 101000400. These are solo pay-NPC rides, 1000 meso each, delivered by MapleMap ride timers.
- Kerning City<->Singapore airplane: NPC 9270041 @103000000 sells/boards ticket 4031731 (5000) to waiting 540010100, AirPlane moves through 540010101 and lands 540010000. NPC 9270038 @540010000 sells/boards ticket 4031732 (5000) to waiting 540010001, AirPlane moves through 540010002 and lands 103000000.
- Spinel 9000020 world tour is a `TaxiEdge`: common towns -> Mushroom Shrine 800000000 for 3000; Boat Quay 541000000 -> Malaysia 550000000 for 10000. Scripted returns depend on saved `WORLDTOUR`; the bot taxi edge is stateless, so only the script fallbacks are modeled (Mushroom Shrine -> Lith 104000000, Malaysia Metropolis -> Boat Quay 541000000).
- Audrey 9201135 is a `TaxiEdge`: Singapore CBD 540000000 -> Malaysia Metropolis 550000000 for 42000; Malaysia Metropolis 550000000 -> Kampung Village 551000000 for 10000; Kampung -> Metropolis for 10000; Metropolis -> Boat Quay fallback is free.
- Ellin Forest Small Forest 300000100 is reached by scripted portal `move_elin` from Helios Tower Time Control Room 222020400 portal `in01`; the return is a normal portal from 300000100.

Tests: `BotFerryManagerTest` (startInstanceAction seam + findFerryEdge), `BotWorldGraphTest.shouldConnectEventManagerTransitLinksWhenOptedIn`. NOT touched: pre-existing baseline-failing `shouldRideTaxiOnlyWhenMesoCoversTheFare` (concurrent session).

CORRECTION (2026-06-24): El Nath 211000000 is NOT islanded — it's WALK-reachable via the Orbis Tower (211000000 → 211000200 → Orbis Tower 200082100 → … → Orbis), plain portals. The unmodeled Orbis↔El Nath ship is just a shortcut, not needed for reachability. The BotFerryManager TODO was wrong and was removed. Sharp Cliff I (211040300) is the one El Nath map with no plain forward portal — its sole entrance is Jeff (NPC 2030000) on Ice Valley II (211040200), a free level≥30 NPC-click warp now modeled as a `TaxiEdge(211040200,2030000,211040300,0,false,30)` + CONTINENT_RIDE_NPCS in BotWorldGraph. Florina round-trip also completed: return is Pison (1081001) on Florina Beach → Lith, free `TaxiEdge`.
