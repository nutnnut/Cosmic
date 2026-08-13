# Player-led Zakum PQ: companions frozen midair / bounced outside (2026-08-13)

Live repro: owner started Zakum PQ stage 1 with 5 companions. Four warped in but floated
midair at the spawn portal doing nothing; the fifth (not registered in the event) bounced
between the Door and the instance. Two distinct root causes, both fixed.

## Class 1: map-change housekeeping starved by tick-consuming hooks

- The per-bot tick ran `runCommonTickSystems` (which includes `BotPqHooks.tick`) BEFORE the
  map-change housekeeping block (`entry.lastMapId != mapId` → `spawnIntoMap`, foothold rebuild,
  physics reset).
- `BotZakumPqRun.tickSupervised` consumes EVERY tick while the bot stands on a PQ map, so after
  the warp into the instance the map-change block never ran again: `lastMapId` stayed stale,
  the bot floated at the warp-in point on the PREVIOUS map's footholds (physics said GND at a
  midair y), and every `travelInsidePq → BotTravelManager.tickTravel` no-opped on its
  `entry.lastMapId != bot.getMapId()` guard. Diagnostic signature: pathlog `Ticks: 0` +
  `*MIDAIR +Npx above floor*` + stale `Last nav decision`, while the PQ diag log lines keep
  flowing (the machine ticks, movement never does).
- The autonomous errand never hit this because `tickSupervised` defers to it (returns false),
  letting the tick fall through to the housekeeping block.
- **Fix**: moved the map-change housekeeping to the top of the tick (right after
  `refreshMovementProfile`), before ANY consuming system. Rule: physical map-change
  housekeeping is a precondition for every per-map decision; nothing may be ordered ahead of
  it that can consume the tick.

## Class 2: follow-warp into an event instance the bot isn't registered in

- Event scripts register party members present at start; a companion that lagged behind isn't
  in the `EventInstanceManager`. `syncFollowMap`'s warp fallback still warped it onto the
  owner's instance map; `tickSupervised` saw `getEventInstance() == null` and bounced it to
  the Door (`warpToDoor`); follow warped it back in — an infinite door↔instance loop (also
  destabilized the worker roster partition for the bots inside: their room assignments
  flapped with the phantom roster member).
- **Fix**: `syncFollowMap` now holds position (consumes the tick) while
  `followAnchor.getEventInstance() != null && bot.getEventInstance() != anchor's` — a real
  player couldn't enter a running instance either. The bot resumes following when the owner
  leaves the instance.

Related: [kb_bot_oneway_map_and_fare_deadlocks.md](kb_bot_oneway_map_and_fare_deadlocks.md)
(Instance 3: the Room of Tragedy forcedReturn dump these PQ maps eject into).
