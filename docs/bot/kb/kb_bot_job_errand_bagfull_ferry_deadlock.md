---
name: kb_bot_job_errand_bagfull_ferry_deadlock
description: "ROOT CAUSE job-advance stuck forever on ferry-board-fail: job errand's top DETOUR_ERRANDS priority starves the already-queued resupply errand, so a full ETC bag can never free up to buy the fare ticket. Fix = yieldForResupply escape valve."
metadata:
  type: project
---

**Symptom:** `BotStarterKitManager` logs `stuck trying to job-advance ... lastGiveUp=ferry-board-fail ...
route-reachable=true`, forever, with plenty of meso. Live repro: bot `itunes` (char 1212) stuck at NLC
Subway Station (600010001) trying to board the KC ferry (`NLC_TO_KC` in `BotFerryManager`) toward its
Marauder instructor at 105070200. `/api/mapinfo` chat showed it alternating "heading to Cave of Evil Eye
II to change job" / "bags are full enough to sell junk - popping back to town real quick" forever.

**Root cause (confirmed live + DB):**
1. `BotFerryManager.tickBoarding`'s ticket-purchase step (`ticketShop.buy`) needs a free ETC slot
   (`InventoryManipulator.checkSpace`) to buy the fare item (e.g. subway ticket 4031713). DB query
   confirmed: `inventoryitems` for char 1212, `inventorytype=4` (ETC) = 96/96 slots used (etcslots=96),
   no existing 4031713 stack to add to — `checkSpace` fails every attempt.
2. That failure bubbles up through `tickBoarding` -> `BotTravelManager.tickTravel` ->
   `giveUp(entry, now, "ferry-board-fail")` (BotTravelManager.java:293), which parks travel for 45s
   (`GIVE_UP_WARP_WINDOW_MS`) then retries — identically, forever.
3. The bag is full enough that `BotShopManager.shouldAutoSellTrash` had already fired and queued a
   resupply trip (`entry.autopilotErrandMapId=600000000` was live-observed set), which is the ONLY thing
   that could free ETC space. But `BotAutopilotManager.tick()`'s `DETOUR_ERRANDS` loop puts the job
   errand FIRST, and with `JOB_CHANGE_FALLBACK_ANYWHERE` off (default), `tickJobErrand` **unconditionally
   consumes the tick** (`return true`) whenever it's active and hasn't arrived — the resupply-trigger code
   further down `tick()` (gated on no detour errand having consumed the tick) never runs. Deadlock: job
   errand can't proceed without ETC space; only a resupply trip frees ETC space; job errand's own
   priority blocks that resupply trip from ever getting the tick.

**Fix (03aeb35 area, BotAutopilotManager.java):** Added `DetourErrand.yieldForResupply(entry, bot)`
(default `false`) — a detour can voluntarily skip its tick and fall through to the (non-detour) resupply
flow below. Job errand overrides it to `bagFull.bagFull(entry, bot)` (same `BotShopManager
.shouldAutoSellTrash` SSOT the reactive resupply trigger already uses). The `DETOUR_ERRANDS` loop now
checks `!errand.yieldForResupply(entry, bot)` before ticking. `jobErrandMapId`/`jobErrandTarget` are left
untouched while yielding — once the resupply trip clears `autopilotErrandMapId` and the bag is no longer
cramped, the very next tick resumes `tickJobErrand` normally (replans travel from wherever the bot ended
up, no separate "return to where I was" step needed since travel always replans from the current map).

**Live diagnosis tools used** (see `docs/bot/web-endpoints.md`, CLAUDE.md rule #8): `/api/mapinfo?id=<map>`
for the bot's status/chat line, `/api/botdebug?id=<charId>` for `errand`/`meso`/live fields, and
`/api/bot/pathlog?id=<charId>` (call twice, ~6s apart) for the full `Travel:`/`give-up window:` dump that
named the exact failed hop (`nextHop=103000100 viaFerry fromMap=600010001 failedMap=105070200`). DB cross-
check (`inventoryitems` grouped by `inventorytype`) confirmed the ETC bag was genuinely 96/96, not just
"cramped" — note DB rows can lag the live in-memory `Character` (per `/api/botdebug` docs), so treat DB
inventory reads as corroborating, not primary, evidence for a live bot.

See [[kb_bot_npc_hop_hail_map_wide]] (a different ferry-stuck root cause — dock NPC proximity, not bag
space) and [[kb_bot_maker_economy]] (the general ETC-bag-jam problem class).
