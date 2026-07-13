# Stall funnel: why trips do or don't end in a published stall

`BotFreeMarketManager` turns a market trip into a stall only at the end of a funnel, and each
stage can downgrade the trip to browse-only. When "there are few stalls," diagnose the funnel
stage, not the trip count — live tape showed 1,429 armed trips producing 24 stalls, and the
loss was almost entirely one stage.

## The funnel

1. **Trip arming** (`startMarketTrip`): rides a rest break or chill session; needs a reason
   (≥`MIN_LISTINGS_TO_TRIP` trip-worthy stacks, stall service due, Fredrick pickup, social roll,
   or shout-trip roll). Supply is rarely the bottleneck: ~73% of a random bot sample was
   trip-worthy (most with 16+ listable stacks).
2. **Setup gate** (`tickSetup`): browse-only if the plan's items vanished, Fredrick still holds
   proceeds (opening would DELETE the uncollected rows via `saveItemsMerchant`), or
   `ensurePermit` fails.
3. **Placement** (`pickStallSpot` → walk → `canPlaceStore` + own spacing re-check →
   `openAndStockStall`): bounded by `MAX_PLACE_TRIES`.

## Permit economics (the dominant historical loss)

- The hired-merchant permit (5030000) costs **5,900 NX** (Commodity SSOT, `permitPrice`).
- Bots do **not** consume the permit on open (players do, `USE_ERASE_PERMIT_ON_OPENSHOP: true`)
  — for a bot it is a one-time purchase that permanently unlocks stalls.
- NX comes from looted NX cards, and gachapon spends everything above its floor. With only the
  flat `GACHA_NX_RESERVE` (1,000) as the floor, bots gambled NX away faster than 5,900 could
  accumulate: live snapshot had avg 2,630 NX, 234/1,307 bots owning a permit, and 572 of 630
  browse-only downgrades logged as "no permit".
- Fix in place: `BotGachaponManager.nxFloor(bot)` adds the permit price to the gacha reserve
  while `BotFreeMarketManager.savingForPermit` holds (market enabled, `fmLastTripWorthy`, no
  permit yet) — a would-be merchant saves for its stall before it gambles. All four spare-NX
  sites (plannedRolls / tickScan / resetTripCounters / rollOnce) go through the floor.

## Placement geometry

- Server rules (`canPlaceStore`): no other merchant/shop within distanceSq 23000 (~152px), no
  teleport portal within 120px. The check is NOT atomic with publish, so the bot re-checks its
  own spacing right before opening (`stallSpotTaken`).
- The bot's own min gap (`STALL_MIN_SPACING_SQ`, 160px) **must stay below the column spacing**
  (`STALL_SPACING_PX`, 170px). When the gap was 200px, every adjacent column failed the check
  and rows spread 340–465px apart — half the room's capacity silently gone.
- FM rooms are multi-story: room template 1 has full floors at y=34/-206/-416, template 10 at
  y=102/-108 (its y=-318 top is chopped stair segments). Floors connect only via `up/dn`
  teleport portals, which the intra-map nav graph bakes as always-usable PORTAL edges — so a
  stand spot on another floor is walkable by the normal setup walk. `pickStallSpot` picks a
  floor strip by free-slot roulette (bottom double-weighted for foot traffic), then column-walks
  from a random anchor; each placement retry re-rolls strip + anchor. `floorStrips` merges flat
  footholds per level and drops strips narrower than two columns (decorative ledges, stair
  segments).
- The walk watchdog (`PLACE_WALK_STUCK_MS`) must tolerate cross-floor legs: routing to an up/dn
  portal can raise manhattan distance for most of a room's width before the teleport jump.

## Restart seeding

A restart force-closes every stall to Fredrick, so a fresh boot has an empty market until break
RNG rebuilds it. `maybeSeedLoginMarketDay` restores state at login: backlog holders (closed
stall / proceeds) start their market day at `STALL_REBUILD_LOGIN_CHANCE` (0.9 — state
restoration, not phase seeding), ordinary sellers at their steady-state mid-trip fraction
(`loginMarketTripChance`).

## Debugging

- `/api/market/stalls` — every open stall with position/stock (spacing + floor spread checks).
- `/api/market/bot?id=` — one bot's dry-run listing verdicts and FM phase.
- `MARKET_TX_CONSOLE` (BotLogConfig; admin `/admin` "Debug logging" panel, or POST
  `/api/settings {"cmd":"set","group":"log","field":"MARKET_TX_CONSOLE","value":"true"}`) — re-enable
  the `fm[...]` phase traces + tape console lines (default off; the tape DB is always written).
  `grep "browse-only" cosmic-log.log` then tallies funnel losses by reason.
