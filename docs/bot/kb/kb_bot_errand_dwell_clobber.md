---
name: kb_bot_errand_dwell_clobber
description: "ROOT CAUSE of bots stuck job-advancing \"at the cab, nothing in console\" = errand resets the SHARED npcDwellUntilMs every TRAVELING tick, zeroing the taxi/ferry's own 2-7s board dwell so the bot reaches the cab grounded+in-range but never pays the fare"
metadata: 
  node_type: memory
  type: project
  originSessionId: c525ecec-48bc-49d0-8a18-9d716d21ee2b
---

The long job-advance-stuck saga's true root cause (found 2026-06-23 from a full pathlog: iDriving on Henesys, `hop: taxiNpc=1012000 distToCab=488 grounded=true deadlineInMs=-158038`, Mode=grind, nothing in console).

**Mechanism:** `npcDwellUntilMs` is ONE shared timer. A taxi/ferry leg walks to its cab/usher, goes in-range, and `tickTaxiHop`/`walkToNpcThenAct` arm a 2-7s "one ticket please" dwell (`npcDwellReady`, NPC_TALK_DELAY 2000 + jitter 5000), returning true → `tickApproachNpc` reports **TRAVELING**. But `BotStarterKitManager.tickJobErrand` (and `BotQuestManager.tickErrand`) called `npcDwellReset(entry)` on EVERY TRAVELING tick (to re-arm the *instructor* read-dwell). So each tick: taxi arms the dwell, errand zeroes it → dwell never completes → fare never paid → bot stuck at the cab forever, grounded + in range, **no give-up, no console warn** (returns TRAVELING, not yielded).

**Why it surfaced when it did:** the earlier taxi/ferry "exempt from the line-218 deadline give-up" fix ([[kb_bot_npc_hop_hail_map_wide]]) removed the timeout that used to mask this — flipping the symptom from a periodic `deadline` give-up (with console error) to a silent dwell-forever. The dwell clobber was always the underlying bug.

**Fix (commit 8ac78c357):** reset the dwell only while `status == ApproachStatus.WALKING` (on the instructor/NPC's OWN map), never during cross-map `TRAVELING` where the transport NPC owns the timer. Applied to BOTH job errand (BotStarterKitManager) and quest errand (BotQuestManager TRAVELING case). Gacha doesn't reset the dwell (uses pinMoveTarget). Regression test `BotTravelSimulationLabTest.jobErrandDoesNotResetTheTaxiDwellWhileTravelingToTheCab` drives real tickJobErrand over real Ellinia geometry, asserts the cab dwell survives TRAVELING (verified RED without the fix: dwell→0).

**Offline-proven non-causes this round:** routing correct, per-leg hop creation correct (taxi/portal/ferry picked right with real meso), first-leg walk reaches the transport — all via the [[kb_bot_npc_hop_hail_map_wide]] travel lab + route diagnostics. The bug was purely the shared-timer reset, invisible to single-consumer offline tests until driven through tickJobErrand.
