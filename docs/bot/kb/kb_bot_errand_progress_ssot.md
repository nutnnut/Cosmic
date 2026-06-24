---
name: kb_bot_errand_progress_ssot
description: "BotTravelManager.ErrandProgress is the SSOT no-progress deadline shared by all long-travel bot errands (job/quest/gacha); refreshes on hop+active-travel (incl. ferry waits), not a trip budget"
metadata: 
  node_type: memory
  type: reference
  originSessionId: c525ecec-48bc-49d0-8a18-9d716d21ee2b
---

`BotTravelManager.ErrandProgress` (static nested holder) is the SSOT "no-progress deadline" for every long-travel bot errand. Replaced three ad-hoc timers (`job/quest/gachaErrandStartedAtMs` + the short-lived `jobErrandProgress{MapId,Ms}` fields) with one `ErrandProgress` instance per errand on `BotEntry` (`jobErrandProgress`, `questErrandProgress`, `gachaErrandProgress`). Commit `332cae052` (experimental).

**Why it exists:** a wall-clock-from-START timeout is wrong for legal travel — a cross-continent route can take ~30 min and boat waits alone exceed 90s, so a trip-budget timer force-drops mid-journey. The deadline must measure time WITHOUT PROGRESS, not total trip time.

**API (call sites pick threshold + what to do on stall):**
- `begin(nowMs)` — arm at errand start (sets mapId=-1 so first `record` latches the start map).
- `clear()` — reset in the errand's clear method.
- `stalled(nowMs, thresholdMs)` — pure read; check BEFORE travel work so a wedge drops early.
- `record(bot, traveling, nowMs)` — feed each tick: a map change OR `traveling` refreshes the deadline; a wedged single map does not.
- `touch(nowMs)` — mark busy-but-not-hopping (e.g. gacha rolling) so a legit on-map phase can't time out.

**Key insight — boats are free:** the `traveling` signal is `ApproachStatus.TRAVELING` (job/quest, from `tickApproachNpc`) or `tickTravel`'s `moved` bool (gacha). Ferry sailing (`BotFerryManager.tickTransit`) AND the dock-gate wait (`tickBoarding` returns true while standing at the usher, BotFerryManager.java ~366-372) both surface as TRAVELING/moved — so boat waits refresh the deadline with NO ferry special-casing. Only a genuinely wedged non-ferry map accumulates (can't hop off; or WALKING on the target map but can't close the last gap to the NPC — WALKING does NOT refresh, so that still times out).

**Per-errand wiring** (`tickErrand`/`tickJobErrand` order = `stalled`-check → step → `record`):
- quest (`BotQuestManager`, 90s): drop errand on stall. Previously had inline reset-on-TRAVELING; now uses SSOT (same behavior, less code).
- gacha (`BotGachaponManager`, 120s): drop trip on stall; `touch()` during rolling so a long roll session can't time out. Was the only one with a true trip-budget bug.
- job (`BotStarterKitManager`, 90s `ERRAND_NO_PROGRESS_MS`): only acts when `JOB_CHANGE_FALLBACK_ANYWHERE` is ON (force-advance); OFF stays stuck + retries. See [[kb_bot_job_change_walk_to_npc]].

All three long-travel errands also report distinctly in `!botstatus` (`BotAutopilotManager.statusReport`, branches above the grind/break states): job "going to <town> to job advance", quest "heading out for a quest"/"talking to a quest npc", gacha "heading to the gachapon"/"at the gachapon".
