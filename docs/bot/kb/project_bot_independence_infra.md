---
name: project_bot_independence_infra
description: "Bot independence groundwork (2026-06-10): sell-trash USE/ETC + rare-keep gate, BotSpawnIndex (Map.wz), BotGrindPlanner/Advisor 'where to grind' chat decision, BotTravelManager adjacent-map legal portal follow (warp fallback); ownerless bots deferred"
metadata: 
  node_type: memory
  type: project
  originSessionId: 98af4b28-cb86-4509-9476-5ab53b0235d3
---

Goal session 2026-06-10 (branch `experimental`): infrastructure toward bots playing independently,
scoped to party-play (bots WITH owner) first. USER CONSTRAINTS locked: bots only follow owner for
now โ€” grind decision is CHAT-ONLY (map + reasoning), NO travel execution yet; ownerless bots not
implemented. Bot features must mimic humans, be dynamic not hardcoded (see
[[feedback_bot_coding_guidelines]] โ€” updated with the dynamic/100-bots-anti-clogging rule).

COMMITS (in order): 736884f08 sell-trash USE/ETC, e60879cd0 grind advisor, d452f50d5 auto sell-trash.

## Sell trash USE/ETC (done, tested)
- `BotInventoryManager.collectSellTrashUseItems` โ€” ONLY off-weapon non-rechargeable ammo (stars/
  bullets + potions/buffs/scrolls all kept; selling useful > slot value).
- `collectSellTrashEtcItems` โ€” NPC-sellable etc MINUS: maker reagents (425xxxx), skill rocks
  (4006000/4006001 hardcoded SKILL_CONSUMED_ETC), crystal leftovers IF bot has Maker skill,
  RARE drops, quest/untradeable (isSafeToDrop).
- RARE-KEEP GATE: best drop_data chance <= 10_000 (1%) => keep (future sale value). DB-grounded:
  keeps 75/869 droppable ETC items. Deliberately NOT applied to equips (70% of droppable equips
  are <=1% but clean avg rolls are NPC fodder; good rolls stat-protected via shouldKeepForSellTrash,
  self-useful gear reserved via collectPotentialSelfUpgradeItems). `BotScrollManager.bestDropChance`
  exposes the cached drop_data lookup.
- `collectSellTrashItems` = equips + use + etc; `BotShopManager` sell loop now sells whole stacks
  via `item.getInventoryType()`. "sell trash" chat cmd unchanged; messages say "junk".
- AUTO-TRIGGER (d452f50d5): on map change, tab with <=4 free slots AND sellable trash in THAT tab
  => piggyback sell on shop visit (`shouldAutoSellTrash`).
- TEST SEAMS added (ItemInformationProvider class-init hits DB via loadCardIdData โ’ CANNOT load in
  unit tests, can't even mockStatic it): BotInventoryManager.sellPrice/makerCrystalFromLeftover/
  bestDropChance/makerSkillLevel/questItem/untradeable static fields (Item.isUntradeable also hits
  ii!). Same pattern as BotShopManager.projectileWatk.

## Grind-map advisor (done, chat-only)
- `BotSpawnIndex` โ€” world spawn index from Map.wz `life` nodes (follows info/link, skips hide=1),
  mapIdโ’{town,mobIdโ’spawnPoints} + reverse mobโ’sites. TSV cache `cache/bot-spawn/v1/spawn-index.tsv`
  (~14s first scan of 5.8k maps, instant after). No LifeFactory/DB โ’ testable from WZ alone.
  Ground truth: 104040000 = 39 spawn pts (12x Orange Mushroom 130101).
- `BotGrindPlanner` (pure, 6 tests) โ€” two-lens: exp/h vs gear-value/h. killsPerHour = 3600/(killSec
  + seek), seek = 3s ร— 8/spawnPoints clamped [0.5,15]. needGear = bestDesirability/0.30 where
  desirability = dpsGainFraction ร— min(1, expectedCopies in 2h) โ€” ATTAINABILITY discounts jackpot
  drops. Blend normalized lenses; pick = weighted-random among candidates within 85% of best
  (ANTI-CLOGGING for 100+ bots).
- `BotGrindAdvisor` (wiring) โ€” candidates: every spawn-index mob the bot can damage (combat SSOT
  estimateBestSkillHitDamage, physical fallback, 0.72s cycle anchor), top-2 densest sites, skip
  towns/mapId>=9e8/spawnPoints<3/boss/friendly/exp<=0; drop_data equip drops per mob (cached query);
  expected drop score = catalog offense score + godly mixture E[bonus] via PRESENCE-WEIGHT TRICK
  (offenseValueFromStats over 0/1 map = summed weights of present stats); chance ร— bot.getDropRate().
  drop_data_global deliberately skipped (washes out map differentiation).
- Chat: "where should we grind/train/farm/level" (GRIND_WHERE_PATTERN) โ’ 1-2 line reply with map,
  mob, exp/hr, wanted item + dps%; "grind debug" โ’ logs/bot-grind/grind-debug-<bot>.txt.
- Widened to package-private in BotScrollManager for reuse: wearable, primarySlot, wornInSlot,
  offenseValue, offenseValueFromStats.

## Follow-mode cross-map travel (done, 6 tests โ€” second session pass)
- Commits 9fa9c9486 (sell report) + follow-travel commit after it.
- `BotShopManager.cfg.REPORT_SOLD_USE_ETC` (default true): after sell-trash the bot chats
  "unloaded: 12 Squid Ink, ..." (USE/ETC only, equips excluded; lines chunked at ~90 ASCII chars)
  so the owner can audit for misclassified valuables.
- `BotTravelManager.tickFollowTravel` โ€” called from `BotManager.syncFollowMap` (now takes
  runAiTick). Owner 1 portal hop away => walk to nearest open unscripted non-door portal with
  targetMapId == owner's map, enter via Portal.enterPortal (legal player path). Consumes the
  tick and drives movement itself via stepMovementCore (now package-private) โ€” moveTarget
  pinned by INSTANCE IDENTITY (entry.followTravelMoveTarget) so player-issued moveTargets are
  never clobbered. Budget 10s + 15ms/px (cap 45s); 2s land grace after enterPortal; on failure
  45s give-up window where it warps directly (legacy). Multi-hop / no portal / scripted-only =>
  warp fallback unchanged; recovery teleports (off-map, >4000 manhattan) untouched.
- GOTCHA learned: targetPos in tickEntry is snapshotted BEFORE syncFollowMap, so travel must
  consume the tick (return true) or recoverTeleportDistance acts on the owner's cross-map coords.
- `entry.lastMapId != bot.getMapId()` guard: never drive movement before the map-change tick
  rebuilds footholds.

## Multi-hop travel + world graph (done, 2+3 tests โ€” third session pass)
- `BotWorldGraph` โ€” directed map adjacency from Map.wz portal nodes (follows info/link like
  MapFactory; edges = unscripted, non-door portals with real tm; mirrors what
  findAdjacentPortal can execute). TSV cache `cache/bot-world/v1/portal-graph.tsv`, ~15s first
  scan. Pure BFS `route(from,to,maxHops)`; `indexOf(Map)` test helper. Chose WZ scan over
  loading all ~5.8k MapleMaps (too heavy); live MapleMap portals still used at execution time.
- `BotTravelManager` multi-hop: owner not adjacent => first hop of shortest route, cap
  MAX_FOLLOW_TRAVEL_HOPS=4; re-plans from every map landed in (fromMapId mismatch => clear =>
  replan same tick). `routeLookup` seam (default triggers WZ scan โ€” tests MUST stub).
  followTravelNextHopMapId on BotEntry.
- TICK-ORDER FIX (important invariant): map-change rebuild block now runs BEFORE syncFollowMap
  + recovery teleports in BOTH tick paths (tickEntry + stepMovementOnly). Reason: intermediate
  hop landings must rebuild footholds before any follow/warp/recovery decision; recoveries
  acting on stale targetPos/footholds caused wrong same-map teleports. BotManagerTest
  out-of-bounds fixture now needs `entry.lastMapId = map.getId()`.

## Autopilot (done โ€” 4th/5th session pass, partly via a second agent)
- `BotAutopilotManager` (10 tests): owner-gated independence. Commands: "go grind somewhere/
  autopilot/go solo" (individual, per-bot), "go grind together / party grind" (party, ONE
  shared decision intercepted at BotManager.handleChat owner level BEFORE broadcast),
  "farm <item name|id>" (objective override). Tick sits in tickEntry between map-change
  rebuild and syncFollowMap; consumes while traveling, yields on-site to normal grind flow.
- Plan state on BotEntry: autopilotMapId/-Party/-FarmItemId/-ErrandMapId, destinationName,
  objectiveSummary, arrivalAnnounced. clear() hooked into clearMode + enterActiveMode +
  startFollow (any owner mode command cancels). start() calls issueGrind FIRST then installs
  (enterActiveMode clears autopilot โ€” ordering matters).
- Party: BotGrindPlanner.planPartyBest โ€” per-member candidates competition-adjusted
  (spawnPoints / partySize), per-member normalized two-lens scores, best-per-map summed,
  reachable-by-ALL filter (BotWorldGraph intersection), near-best weighted draw; per-member
  Recommendation on chosen map (null = "back the party up" tag-along). Leader (first
  party-mode entry in owner list) re-decides for everyone; member timers trail +2s.
- Farm-item: BotGrindAdvisor.recommendFarmItem (droppersForItem seam: drop_data
  SELECT dropperid,MAX(chance) WHERE itemid=?) -> planFarmBest scores chance*kph only.
  entry.autopilotFarmItemId routes re-decides to site-only re-pick AND protects the item in
  collectSellTrashItems. Chat: exact name wins, ambiguity -> "which one? Name (id), ..."
  + "say 'farm <id>'". Seams: farmItemSearch/farmItemName (ii can't load in tests).
- Resupply errand: BotPotionManager grind-stop hook โ€” HP pots < POT_STOP (proactive) or
  shouldAutoSellTrash mid-grind -> requestResupplyErrand: detour to getReturnMap() town,
  auto shop visit on arrival, then travel resumes to autopilotMapId. 5-min cooldown survives
  clear(). Non-autopilot keeps legacy "low on pots!! walking to you".
- Death: respawnBot = player-legal bot.respawn(returnMapId) (50 HP) + walk back; 5s
  BOT_DEAD_MS; PQ/event instance keeps legacy warp-to-owner; autopilot destination survives
  death. Other agent moved handleDeadTick BEFORE owner-null branch (dead bots respawn even
  with owner offline).

## Stability/perf pass (commit 7c4a2d517, after live testing)
- FROZEN-AIR WATCHDOG: WALL collision at a map edge with no foothold below re-pins the same
  airborne point forever (collideWithAirWall keeps inAir, zeroes airVelX; bot sat 3px under
  the 600px OOB recovery threshold; travel-consumed ticks skip recoveries; doStuckDetection
  skipped inAir). Fix: tickFrozenAirborneWatchdog in doStuckDetection โ€” 30 identical airborne
  positions => executeRecoveryTeleport to moveTarget/navTarget/closest-portal ground.
  Evidence: logs/bot-nav/pathlog-Clawer-2026-06-10T094813.txt.
- ASYNC ADVISOR: BotGrindAdvisor.DECIDE_POOL (single daemon thread). BotManager.after = shared
  TimerManager โ€” heavy passes there froze game timers (CPU spike on "where to farm"). All
  decisions (advice, debug export, autopilot start/redecide/party/farm) compute on the pool,
  apply via after(0) with activityEpoch guard + autopilotDecisionInFlight. warmCachesAsync at
  first registerSpawnedBot pre-builds spawn index + world graph (~30s).
- OWNER-OFFLINE: normal bots = 5-min town safe mode (unchanged). Autopilot bots keep the FULL
  pipeline running with owner==null (tickAfkCheck + BotPqHooks.tick guarded; snapshot/follow/
  recovery already null-safe) until cfg.AUTOPILOT_OWNER_OFFLINE_LIMIT_MS (1h test) => town.

## Deferred / next (independence gaps surfaced 2026-06-10)
- Ownerless lifecycle: owner-inactive safe mode still cancels autopilot after 5 min offline
  (intended for supervised scope; revisit for true ownerless).
- Live occupancy/competition check (channel map player+bot counts) in grind choice.
- Boats & scripted gates: outside world graph -> unreachable continents (Victoria taxis now baked in).
- Meso runway policy (reserve floor before pot purchases); MP-pot errand trigger (HP-only
  now โ€” naive MP trigger would loop on potless warriors); ammo-dry errand.
- Channel hopping; storage for valuables; per-weapon attack cycle still flat 0.72s.
- Advisor gaps: scroll-farm targets not in scoring (self-scroll plan wants), occupancy/competition
  check (channel map player count), distance-from-current penalty, defensive/utility gear worth 0
  (offense-only SSOT), per-weapon attack cycle still flat 0.72s.
- In-game verify: ask bot "where should we grind" / "grind debug" (needs running server).
See [[project_bot_economy_and_self_scrolling]] [[kb_bot_equip_optimizer]].

## Travel graph v2: return scrolls + taxis baked in (2026-06-10)
- BotWorldGraph GRAPH_VERSION 2 (cache/bot-world/v2/portal-graph.tsv: mapId TAB portals TAB scrollTarget).
  Scan reads info/returnMap (from the ORIGINAL map info, link redirect only affects portals).
  computeScrollTargets: scroll edge map->returnMap ONLY when portal route needs >= RETURN_SCROLL_MIN_HOPS(3)
  (route within 2 hops == null), built post-scan over portal-only index.
- RouteOptions(withReturnScroll, meso): route/reachableWithin overloads expand portal edges +
  scroll edge (if opted in) + hardcoded TAXI_EDGES (per-edge meso >= fare, NOT cumulative --
  executor re-checks each ride). PORTALS_ONLY keeps old behavior.
- TAXI_EDGES verified from scripts/npc/<id>.js + town WZ: Lith 1002007 (VIP 1002004->105070001 10k),
  Henesys 1012000, Perion 1022001, Ellinia 1032000 (VIP 1032005), Kerning 1052016, Nautilus 1092014;
  regular fares 800/1000, warp to portal 0 of dest.
- Execution (BotTravelManager.tryConsumableHop): next hop with no live portal -> scroll hop
  (nextHop == scrollTargetLookup(bot.getMapId()) && has 2030000: use on the spot, EnteredAtMs set)
  else taxi hop (walk within TAXI_TRIGGER_RADIUS_PX=500 manhattan of cab NPC via
  followTravelTaxiNpcId/Pos entry fields, then taxiRide: meso check + gainMeso(-fare,false) +
  changeMap(dest, portal 0)). Seams: scrollTargetLookup/returnScrollCount/returnScrollUse/
  taxiNpcLocator/taxiRide (tests MUST stub scrollTargetLookup or WZ scan fires).
- BotAutopilotManager: ad-hoc tryUseReturnScrollForLongErrand REMOVED (graph is SSOT);
  advisor/farmAdvisor/partyDecider reachableWithin now pass travelOptions(bot) so candidate
  pools include scroll/taxi-reachable maps. BotShopManager.countReturnScrolls package-private.
- Reviewed other agent's commits 8a3e1d3f4 (spawn-in-place + legal follow travel, grind-wander,
  LifeFactory PDDamage/MDDamage default 0 for mob 8641013 NPE), ecb9af70e (resupply: ammo/MP
  errands, owner-supply grace, stock 10 return scrolls), 11887e8da (sell-trash keeps maker
  materials, 5k party-arrow reserve, crystal leftovers >= 100) -- all sound, tests green.

## Cross-continent ferry (Ellinia<->Orbis), BotFerryManager (2026-06-10)
- Exact player path, no warps of our own: buy ticket from seller NPC -> usher NPC while Boats
  event "entry"=="true" (ticket consumed, warp to waiting map) -> event takeoff -> deck ->
  arrival warp by scripts/event/Boats.js. Route data verified: Ellinia 101000300 (seller
  1032007 item 4031045 5k, usher 1032008 -> 101000301, deck 200090010, cabin 200090011,
  arrive 200000100); Orbis hall 200000100 (seller 2012000 item 4031047 5k, platform guide
  2012006 warps walkway 200000110 west00) -> pier 200000111 (usher 2012001 -> 200000112),
  deck 200090000, cabin 200090001, arrive 101000300 portal 1.
- BALROG: Boats.js 42% invasion (2x mob 8150000 per deck, haveBalrog property). Deck transit
  tick: threatCheck (haveBalrog prop OR alive mobs) -> walk+enter unscripted in00 cabin portal;
  cabin riders get arrival-warped too. Also follows owner into/out of cabin (targetMapId check).
- STATELESS stage machine keyed on (current map, hasTicket, gate open): every map-change
  re-plans; boarding chain maps {200000100,200000110,200000111} / {101000300} ALL carry the
  graph edge so mid-chain bots keep planning forward (no loop back to hall). Transit maps
  (waiting/deck/cabin) consume ticks unconditionally in tickTravel BEFORE give-up check.
- RouteOptions gained withFerry (3-arg now): autopilot/advisor pass true (Ossyria in candidate
  pools), follow-travel passes false (owner never waits 15min for a boat; cross-sea follow
  still warps). tickTravel signature: (entry,bot,target,maxHops,runAiTick,allowFerry).
- Seams: BotFerryManager ticketCheck/ticketShop/boardAction/guideAction/gateCheck/threatCheck +
  BotTravelManager walkToPortalAndEnter/pinMoveTarget/clearMoveTargetPin now package-private.
  followTravelFerry flag on BotEntry (reset in BotTravelManager.clear).
- Remaining: Orbis multi-line (Ludi train 4031074, Leafre 4031331, Ariant 4031576 from seller
  2012000) NOT wired -- FerryRoute table is extensible; Trains.js/Genie.js same pattern.

## Gear-first autopilot + runtime travel cost + warmup perf (commits b56f74979, c6163a10c)
- WARMUP: BotWorldGraph/BotSpawnIndex scans parallelized (worker pool, ThreadLocal DataProvider --
  XMLWZFile.getData synchronized per instance; output verified identical to serial scan);
  spawn+world warm on 2 daemon threads; BotTravelManager routePrewarm seam pre-warms next 3
  route maps nav graphs via BotNavigationGraphProvider.warmGraphsForRouteAsync (off-thread,
  MapManager.getMap loads maps).
- TRAVEL COST: BotTravelCost.floodSeconds = Dijkstra over portal(25s)/scroll(5s)/taxi(30s)/
  ferry edges; ferry secs = getTransportationTime(5min)/2 + getTransportationTime(10min) --
  RUNTIME via World.getTransportationTime (travel_rate config mutable, never baked in graph).
  Advisor multiplies candidate map scores by max(0.25, 1 - travelSec/3600).
- GEAR-FIRST: BotGrindPlanner shortlists gearScore >= 0.7 x best (MEANINGFUL_GEAR_DESIRE=0.02
  trigger), exp tiebreaks via near-best draw; no attainable gear -> pure exp. Recommendation
  gained a `score` component (selection-lens value) used for the ferry-teaser 1.5x compare.
- FERRY PERMISSION: ferryAllowed(entry) = autopilotFerryApproved || owner null/offline; gates
  travelOptions withFerry + tickTravel allowFerry. Owner online: decision computes local +
  ferry-on pass; overseas >= 1.5x local -> one teaser reply; SAIL_AWAY_PATTERN chat command
  (sail away/take the boat/go sail) sets approval + immediate redecide. Reset in clear().
- ANNOUNCEMENTS: objectiveSummary number-free ("farm <item> from <mob>" / "grind <mob>",
  party: "... for <member>"); dropRateText/exp-per-hr chat texts deleted per user requirement.
- Lesson: subagents share the session usage limit -- 3 died mid-work at once; reconstruct from
  git status/worktree diffs, parity-check caches before committing (sorted TSV compare).

## Roll-aware gear prospect valuation (commit 9bd8c7f5a) -- emergent gear-farm stop condition
- GearProspect gain = E[max(0, score(roll) - score(worn))]: Monte Carlo ROLL_SAMPLES=32 over
  ii.randomizeStats(ii.getEquipById(id)) (exact MapleMap drop path, godly mixture), scored by
  BotScrollManager.offenseValue SSOT vs wornInSlot. RollScoreSampler seam (rollScores static)
  for deterministic tests; per-pass rollScoreCache by itemId. Old analytic expectedDropScore
  (catalog stats + godly presence-weight) removed.
- Empty slot = full mean; below-avg worn roll = moderate re-farm value; near-godly = ~0.
  Stay-or-leave EMERGES from gear-first shortlist + BotTravelCost.scoreWeight (current map has
  no travel penalty). No drop counters/timers.
- BotEquipManager.autoEquip/applyEquipPlan return changed-flag; BotInventoryManager loot path
  calls BotAutopilotManager.noteGearUpgraded -> pulls autopilotNextDecisionAtMs to now+25s
  (UPGRADE_REDECIDE_DELAY_MS; never pushes back; skips inactive/farm-item-pinned/party).
- Travel rate confirmed boat-only in BotTravelCost (portal 25s/scroll 5s/taxi 30s flat; only
  ferrySeconds goes through getTransportationTime).

## Scroll drops as gear prospects (commit 4531f17c5)
Grind advisor drop pool widened to equip scrolls (drop_data 2040000-2049999, gearDropsByMob).
One scroll = effectiveSuccessPct x offenseValueFromStats (same SSOT: att 5.0 vs main stat 1.0,
MAD=0 for non-mages -> att scrolls dominate, 60%/30% big-payload scrolls win on EV, no tiering),
gated on a WORN equip with upgradeSlots>=1 that BotScrollManager.applicable() accepts; boom
(cursed) + clean-slate/modifier/white scrolls valued 0 (self-scroll v1 refuses them). Pure core
BotGrindAdvisor.scrollExpectedGain + scrollGains seam; per-pass scrollGainCache. VALUATION ONLY:
auto-scroll use stays behind the owner command (user: do not wire yet). Sell-trash already keeps
scrolls. Party: planPartyBest already sums each member's best score per map (multi-beneficiary
maps beat single-best ones); scroll prospects priced per member vs their own gear. Known gap:
within ONE member a map's score is the best single mob (max), not a sum across mobs.

## Multi-mob map blend (commit 21115d2fe)
BotGrindAdvisor candidates are now ONE PER MAP: grindable mobs blended by spawn-point share
(share_i = points_i/total) - killSeconds/exp/drop chances all share-weighted; same-item chances
sum across droppers; unkillable/boss/friendly/0-exp excluded from blend (bot will not engage);
candidate labeled by dominant mob (points, exp tiebreak). Farm-item path blends too (junk mobs
dilute items/hr; 0-exp droppers still count as sources). addSiteCandidates + MAX_SITES_PER_MOB
removed; MIN_SPAWN_POINTS=3 now per-map total. Pure helpers MobProfile + blendCandidate tested
in BotGrindAdvisorTest. Full formula doc (SSOT weights, MC equip EV, scroll EV, blend, travel
weight, gear-first selection, party map-sum, difficulty = kill time only, death risk unmodeled):
docs/bot/grind-planning.html. The one-member best-single-mob-per-map gap is CLOSED by the blend.

## Scroll headroom in gear valuation (commit 8b0befe67)
Drop-vs-worn comparisons in BotGrindAdvisor use BotScrollManager.potentialValue = offenseValue
+ SCROLL_HEADROOM_FRACTION(0.5) x remainingUpgradeSlots x bestScrollEvPerSlot(equip type).
bestScrollEvPerSlot scans the item catalog once (ii.getAllItems, scrollsByCategory static cache
keyed (equipId/10000)%100; scrollReqs scrolls bucketed by req categories) and reuses self-scroll
rules: applicable(), effectiveSuccessPct, offenseValueFromStats, boom/meta skipped. Result:
maxed-out better item can lose to slotted weaker drop; slot value equip-type based (glove att
~6/slot vs stat ~1.2/slot), never flat. Wired in sampleRollScores (evPerSlot hoisted, rolled
Equip slots from tuc) + wornScoreBySlot lambda. totalWornOffense denominator stays raw offense.
Full BotScrollPlanner EV-DP delegation deliberately NOT used for drop valuation (plans over
owned scrolls + meso; wrong shape + too heavy per pass) - documented in commit msg + HTML doc.

## Owned-gear bar + level-gated drops (commit 351588731)
Grind planning drop bar = bestOwnedScore(slot): max over WORN + BAGGED (InventoryType.EQUIP)
equips of levelDiscount(levelsUntilWearable) x potentialValue; drops symmetric (roll samples
scaled by 0.9^levelsToGo, horizon 10, via expectedImprovement 3-arg overload). Outcomes: bagged
better copy (even benched 5 lv) zeroes worse same-wait drops; wear-now drops keep interim value
= discounted gap. BotScrollManager.levelsUntilWearable: 0/n/-1; only LEVEL is projected (two
meetsEquipRequirements calls, second at reqLevel); unmet stat/job/weapon req = -1 = never,
because BotBuildManager AP builds park secondary at fixed target (low-secondary builds never
grow into secondary-gated gear). wearable() delegates. VERIFIED for user: MATK gets full
ATT_WEIGHT 5.0 for mages in offenseValue/offenseValueFromStats (mainSecondary mage flag jobs
2xx/12xx/2001/22xx); getEquipStats strips inc prefix (incMAD->MAD) so scroll stat keys match.

## MATK weight + density/supply model (commit 350f717e7)
USER-TUNED: MATK_WEIGHT=1.0 for mages (a stat point, NOT watk 5.0); 0 non-mages - in
offenseValue + offenseValueFromStats. BotSpawnIndex INDEX_VERSION=2: MapSpawns gained areaPx
(playableArea: VR bounds else miniMap w*h else 0; from original img, resolved-link fallback;
cache row mapId\ttown\tareaPx\tmobs - old cache auto-rebuilds). BotGrindPlanner: seekSeconds(
areaPx, points) = 3s x (area/points)/TYPICAL_AREA_PER_SPAWN(250k px2) clamp 0.5-15 (area 0 ->
old count-only fallback; MobCandidate gained mapAreaPx + 9-arg convenience ctor for tests);
killsPerHour = min(demand, points x 3600/RESPAWN_PERIOD_SECONDS(10, config RESPAWN_INTERVAL)).
withSpawnShare divides points/N -> per-member seek rises AND supply cap drops: small dense map
wins solo, roomy map wins for party (test shouldPreferRoomierMapForAPartyButDenserMapSolo).
PS5.1 gotcha hit again: -Encoding utf8 writes BOM (javac illegal ﻿) + Get-Content -Raw
reads UTF8 as ANSI mojibaking em-dashes; fix via [IO.File]::WriteAllText + UTF8Encoding($false).

## Perf pass + monitoring tune (commit ed26f62b0)
100-bot audit verdicts: DECIDE_POOL single thread ~5% utilized at 15-min decision cadence (OK);
reachableWithin + floodSeconds once per decision, O(1) per-map lookups (OK); static caches
(gearDropsByMob, scrollsByCategory, LifeFactory monsters) OK. Fixed (lossless): mapAllowed
pushed INTO buildCandidates (skip unreachable maps BEFORE per-mob killSeconds + 32-roll MC -
autopilot pass now profiles ~reachable subset, not the world; candidatesFor takes IntPredicate,
party passes common::contains); mapNameCache ConcurrentHashMap (loadPlaceName walks String.wz
per call, synchronized provider, thousands/pass). Monitoring: slow-pathfind warn 50ms->250ms +
10s rate limit w/ suppressedSinceLast (was the terminal spam at 5 bots); always-on
BotPerformanceMonitor.noteTickStall: tick >= 250ms (STALL_WARN_MS) -> one warn / 30s with
suppressed count + worst, works with monitor disabled; tick() now times every tick (2 nanoTime).
NOT done (proposed/deferred): ferry-teaser second advisor pass could reuse candidate pool
(halves owner-online decisions); cross-pass roll-sample cache (reuses RNG draws, unneeded now).

## Party cohesion + @botme/@botparty (commits 2a17b6e12 test fix, 7ed634eb5)
COHESION (user: "use formation, i love how formation work"): in party-autopilot transit only
the LEADER travels; followers flip into the regular follow pipeline behind the leader bot
(entry.following=true + followTargetId=leaderBotId WITHOUT issueFollow - that would clear()
autopilot) so formation offsets, legal portal-follow and warp catch-up are all reused; swap
back to grind on arrival via BotManager.resumeAutopilotGrind (= enterActiveModeCore, the
extracted non-autopilot-clearing half of enterActiveMode). Transition tick MUST return true
(consume): the tick's followAnchor was resolved BEFORE autopilot ran - acting on the stale
anchor could warp to the owner. Leader holds a map (grind flow runs there) while any member is
> STRAGGLER_WAIT_HOPS(2) portal hops behind (BotWorldGraph.route walk-only, maxHops 3, checked
every 3s, cached verdict, announced once on state edge). Party member enumeration now game-party
FIRST (BotManager.partyBotEntries, party order = deterministic leader) with owner-bot-list
fallback - @botme groups span owners. Owner==null strips following every tick -> cohesion
falls back to independent travel (guard in tickPartyCohesion). Fields on BotEntry:
autopilotTransitFollow/NextStragglerCheckAtMs/WaitingForStragglers (reset in clear()).
Seams: BotAutopilotManager.partyMembers + hopDistance.
## Snow physics + the WZ float-parse bug (commits fb44c9cad, 9e7a6d756, 48bcab404, 2026-06-11/12)
- WZ map info/fs (El Nath town 0.2) = field SLIPPERINESS. Was consumed as a ground-SPEED
  multiplier (mapGroundSpeedScale -> El Nath bots would crawl at 20%)... except it never even
  loaded, because of the FLOAT-PARSE BUG below. Now: applyGroundPhysicsStep does
  hspeed += (hforce - drag) * fs -> top speed unchanged, accel/brake stretched 1/fs (slow
  start, long slide); launchRunwayPx scales 1/fs. mapGroundSlipScale(map) in BotPhysicsEngine.
  CONFIRMED vs Angel.idb: client scales force+friction by fs, top speed clamped separately at 125 - bot model correct (docs/bot/physics-client-audit.md).
- CRITICAL SERVERWIDE GOTCHA (9e7a6d756): with USE_UNITPRICE_WITH_COMMA=true, XMLDomMapleData
  parsed value attributes via a FRENCH-locale NumberFormat that stops at '.' - EVERY dot-decimal
  WZ float was silently truncated ("0.2"->0, "1.4"->1): map fs, mobRate, recovery, unitPrice
  fractions. Fixed locale-free in XMLDomMapleData (FLOAT/DOUBLE: parse with ','->'.'); INT/SHORT
  keep the tolerant parser. Any past balance observation involving WZ floats predates this fix.
- DOWN-JUMP TRUTH (48bcab404): most "can't fall down here" platforms have NO forbidFallDown
  flag (Orbis tower rim foothold 257: next floor 860px below). The client gates down-jump in
  CUserLocal::TryDoingFallDown @ 0x0094e692 by probing for a landing foothold within a bounded
  range below. Bot: DOWN_JUMP_MAX_DROP_PX=300 in simulateDownJumpLanding (gates graph edges +
  fallback); 300 CONFIRMED as the exact client constant (FallDown @ 0x0094c4f8 probes Y+0x12c). GRAPH_VERSION 50 after graphgen index.
- RE toolkit env change: python-idb now needs `py -3.9` (plain `py` = new 3.14 without idb).

## Inventory hygiene pass (2026-06-12, commits 77e689c3c a9102c99d 3f15890ca 1e941d2f1)
- SHOP IDLE TIMEOUT: SHOP_SEQUENCE_IDLE_TIMEOUT_MS(45s) measures inactivity, not total visit -
  every executed scheduleShopStep refreshes shopSequenceStartedAtMs (long hauls finish; only a
  lost callback aborts).
- SELF-RESERVE CAP: selectOwnedItemsForSelfReserve keeps top SELF_RESERVE_TRACK_CAP=3 per track
  after dominance, ranked by selfReserveCeiling (counts scroll upside) desc, tiebreak
  usefulStatSum/upgradeSlots. Evidence: equiplog-Clawer (lv64 sin, ~40/60 bag SELF=Y, Pareto
  front unbounded). Demoted items fall to the bounded valuables shelf, not the void.
- IRRELEVANT SCROLLS SELL: collectSellTrashUseItems trashes equip scrolls granting ONLY
  hp/mp/def/avoid (+acc when job never values acc). USER RULE: STR/DEX/INT/LUK/PAD/MAD/Speed/
  Jump scrolls are UNIVERSALLY kept regardless of job (warrior keeps INT scrolls - stat scrolls
  are trade goods); only ACC is judged via relevantStatsFor(job). chaos/clean-slate/modifier
  kept, NO isRareDrop gate (would nullify), scrollStats test seam.
- STATUS CLASSIFIER + INV DEBUG: BotInventoryManager.classifyBagEquips -> RESV-SELF/RESV-OTHER/
  HOARD#r (shelf rank or >=NEVER_SELL gate)/HLIM#r (beyond shelf, sells)/TRASH; shares
  rankKeptValuables with valuableEquipOverflow (one ranking SSOT). Equip dump SELF col -> STATUS.
  Chat "inv debug" (INVENTORY_DEBUG_PATTERN, before plain inventory match) writes
  logs/bot-equip/invlog-<name>-<ts>.txt with per-item KEEP/SELL verdicts+reasons for
  EQUIP/USE/ETC - built to debug the DEFERRED follow-up: bot hoards too much USE/ETC (user
  parked it pending this tooling).
- @AUTOSELL (gm6, e586d5bb3): preview/execute the bot sell pipeline on the TYPING player's own
  char (collectSellTrashItems now null-entry tolerant). Preview grouped Equip/Use/Etc, equips
  shown as above-base deltas (+3str +4dex Name); "confirm" sells instantly via removeFromSlot
  + gainMeso (no shop, no delays, client stays connected - debug only, not bot play).

## Leroy stuck fixes DONE (2026-06-13, commits 09db7c0d3 a3dca10ee d172c918b 76b5823e9)
- Blocked-edge give-up: reuse[blocked: *-pos] for 6-10 jittered ticks -> drop edge + fresh A*
  replan; wide DROP/climb windows accept the WHOLE window, steering targets nearest in-window
  x inset 4px (never a [precise] endpoint pixel).
- Tight-window pulse-creep (LEGAL, no cheat needed): slipperyApproachDir gained
  overshootSlackPx (room between steering target and window far edge, clamped to walkStep);
  ground physics keeps FRACTIONAL physX between ticks on slippery ground (int snap unchanged
  on normal ground) so sub-pixel 50ms pulses accumulate. Lab regression replays the 2px-window
  geometry end to end. feedback_micro_position_cheat_allowance fallback NOT needed.
- Facing realism (user rules): ground facing follows the effective held key ONLY (stop-policy
  emulated counter-input counts), changes ONLY on ticks with real displacement, and with no
  key held the LAST pressed direction persists (glide never turns the character into the
  slide). groundBrakeDir same gating. Tests in BotPhysicsEngineTest.
- OPEN: why graphgen emitted a 2px launch window for jump r17->r44 at all (never checked).

## Admin commands + owner-anchor cleanup DONE (2026-06-13)
- a13b31154: gm6 admin name-targets ANY spawned bot ("Leroy follow"); transient
  debugCommanderId binding (~5min, owner command clears) -> whisper replies/follow/trade/
  name-targeted confirmations resolve to the admin. Name-only cross-owner resolver
  (BotCommandParser.resolveTargetedBotByName) - numeric slots stay own-list. 45193399e: NX
  card redirect to owner now cfg.REDIRECT_NX_CARDS_TO_OWNER, default OFF.
- 1aa8a987d + 37363a84b: owner-anchor fixes per feedback_owner_features_gated_not_removed.
  BotManager.canWalkToOwner SSOT gate (= !isAutopilotActive && owner real+online+not-self) on
  pot/ammo/MP emergencies; captureTargetSnapshot autopilot-hold safety net (the owner-raw
  branch was unreachable for grinding autopilot - real mechanism was issueFollowOwner sites);
  BotTravelManager.pickRandomCrossMapPortal + tickWanderToRandomPortal for STRANDED autopilot
  bots only (on grind map = grind-wander, no farm abandonment); PQ respawn + job-milestone
  follow gated; resolveFollowAnchor null for owner==bot (after admin-commander check);
  requestPotShare early-out on self/null owner. Supervised online-owner perks untouched.

## QUEST SLICE 1 DONE (2026-06-13, commits 464c679f5 05086149b e52d003a9 4754d3fbd 774c80e94)
BotQuestIndex (cache/bot-quest/v1/ TSV: id, start/end NPC, lvmin, mob->count, rewardExp,
reward items) - 273 runnable mob quests + 65 auto-both (scout's 29 was a miscount).
BotQuestManager: auto quests, piggyback errand loop, rough worthwhile(), NPC_TRIGGER_RADIUS_PX
500 walk-to-NPC, seams gate/mapMobs/hopCount/reply. LEGAL Quest.start(chr,npc)/complete(chr,
npc,null) ONLY (never force*; complete uses null not -1). Supervised bots never errand
(autopilot-only). Config AUTO_QUESTS/QUEST_PIGGYBACK. "quests" chat status. docs/bot/
quest-loop-slice1.md. Ticket 5220000 has NO tradeBlock flag (owner-supply viable).

## QUEST RECOMMEND + ITEM HYGIENE DONE (2026-06-13, commits f1922e211 c6bf51d31 eec5c502d)
- BotQuestScorer.score (value/cost vs grind baseline): value = rewardExp + overlap-mob exp +
  unique-reward (BotScrollManager.offenseValue); cost = BotTravelCost.floodSeconds round-trip
  + non-overlap kill time; baseline = NEW BotGrindAdvisor.currentMapExpPerMinute (current map,
  no DB, tick-safe). Autopilot piggyback pickStartable now ranks by this too.
- "recommend quest" command (RECOMMEND_QUEST_PATTERN, also "best quest"/"suggest quest"/"quest
  rec") -> top 1-3 lines "q1019 @ Maya in Henesys: kill 10 Green Snail -> 70 exp", off-thread
  on DECIDE_POOL. RECOMMEND_MIN_SCORE 1.0.
- Auto-suggest: supervised only (owner online + following + not autopilot), AUTO_SUGGEST_
  COOLDOWN_MS 5min, new map, baseline>0, score>AUTO_SUGGEST_MIN_SCORE 3.0, hops<=AUTO_SUGGEST_
  MAX_HOPS 2, not in suggestedQuestExpiry (TTL 30min). One ASCII line, no repeat.
- Feature B: BotQuestIndex v2 adds item-req->quest reverse map (QuestItemReq, ITEMREQ TSV
  rows). isStaleQuestItem true only when item used by >=1 quest, NONE started, every using
  quest COMPLETED or severely-outleveled (level >= cap+30, cap=max(lvmax,lvmin)>0). Wired via
  bot-aware isSafeToDrop(bot,item) into USE/ETC sell-trash (EQUIP keeps strict gate);
  inv-debug reason "quest-stale". CONSERVATIVE: unstartable-branch NOT implemented (under-level
  bots grow in), no-cap quests kept, rare/maker/skill-consumed still protected.

## GACHAPON DONE (2026-06-13, commits 5b384731d c6e3ec1c8)
NX path verified: NX cards credit account NX on PICKUP (Character.pickupItem ->
getCashShop().gainCash(NX_CREDIT, 100|250), card never bags) - bots already bank NX via
bot.pickupItem; no separate "use card" step. 5b384731d fixed the loot loop skipping ETC-full
NX cards (now bypasses space check for isNxCard, mirroring player path). BotGachaponManager:
ticket 5220000 price 800 NX (Commodity.img SN 10101513, live via CashItemFactory); EV town
ranking (P(tier) 90/8/2 x uniform over local+GLOBAL pool, value = offenseValueFromStats OR
ii.getPrice proxy so cosmetic capes still chased - NOT tradeValueScore which is 0 for id-based)
minus travel (BotTravelCost.floodSeconds round-trip); supervised bots don't gacha (isActive
gate); execute = travel -> walk 500px -> per ticket: afford-check -> SPACE guard BEFORE charge
-> gainCash(NX_CREDIT,-price) -> Gachapon.process(npc) SSOT roll -> addById. Config
GACHAPON_ENABLED/GACHA_NX_RESERVE 1000/GACHA_TICKETS_PER_TRIP 5/GACHA_MIN_NET_EV 50.

## Live fixes (2026-06-13): gachapon NPE, quest baseline, pathlog-any-bot
- f93e2fd0b GACHAPON NPE (live tick crash): BotGachaponManager.travelSeconds passed
  floodSeconds(from,hops,null,null); floodSeconds computes ferrySeconds(transportationTime) up
  front -> NPE on null. Fixed to PORTALS_ONLY + ms->ms (mirrors BotQuestManager errand seam).
- e59e95f03 QUEST RECOMMEND baseline: lv64 got "kill 30 / 1300 exp" because currentMapExpPerMinute
  returns 0 off-grind (town/transit) -> scorer floored baseline to ~1 exp/min -> trivial quests
  score huge. recommendQuests now falls back to BotGrindAdvisor.bestGrindExpPerMinute (full grind
  pass, off-thread only) when current=0; tick paths keep cheap current-map seam. scoreQuest
  baseline overload + bestGrindExpBaseline seam + regression test.
- 6bb40edf2 PATHLOG any bot: !botnav (gm3) selectBotEntry now falls back to
  BotManager.findSpawnedBotByName (global) so admins debug ownerless/independent bots by name.

## El Nath drop-window fix DONE (2026-06-13, commits 67ba3ae16 3c3d0b40a)
GRAPH_VERSION 56. findDownJumpBoundary uncapped to from.minX/maxX (was +/-20) -> El Nath
DROP r54->r56 one wide [1231..1299] edge (was 14 fragments). A* reconciliation (Edge.pointAt
NearestLaunchX): for straight DROP (stepX==0) only, cost approach AND land next state at
clamp(botX, launchMin, launchMax) - matches execution, fixes Kerning rope-oscillation + ledge-
drop-vs-jump regressions. All nav suites + El Nath benchmarks green.

## El Nath 2px-window ROOT CAUSE (2026-06-13, DONE - see above)
Investigated the two Leroy stuck logs against real El Nath foothold geometry (diagnostic: build
graph, dump region segments + edges). VERDICT:
- pathlog 140609 JUMP r17->r44 window=[72..73]: GENUINE. Region 17 = fh279, a 15px ledge
  x[58..73]; horizontal+descending jump (stepX=+6), physics-constrained. Leave it.
- pathlog 141517 DROP r54->r56 window=[1245..1285]: GRAPHGEN BUG. Region 54 (fh146+148)
  x[1220..1310] sits entirely above region 56 (the giant ground). A straight down-jump is valid
  across the WHOLE 90px span, but graphgen capped each drop edge at +/-DOWN_JUMP_PRELAUNCH_
  WINDOW_PX(20) and seeded at interior anchors -> 14 fragmented 40px edges, union [1231..1299]
  not even covering the ends; bot at 1287 stranded outside its committed fragment.
FIX (working tree, uncommitted): findDownJumpBoundary bound by from.minX/from.maxX (uncap, like
findJumpBoundary); removed DOWN_JUMP_PRELAUNCH_WINDOW_PX; GRAPH_VERSION 55->56; cap-test renamed
to assert wide span. Verified: 14 fragments dedup-collapse to ONE DROP r54->r56 [1231..1299].
RIPPLE (the hard part, agent landing it): uncap breaks 2 Kerning/ledge nav regression tests.
Mechanism: A* (BotNavigationManager findPath ~L1040) costs intra-region walk to an edge's
AUTHORED startPoint, but execution launches from NEAREST in-window x. Merging drops moved the
representative startPoint (window midpoint) away from where it mattered (Kerning PORTAL r31->r11
exits x=1640 right at the old DROP r11->r18 start; after merge the midpoint shifts, inflates
cost, rope-climb path wins -> shouldNotPathThroughRopeOscillationLoop fails). Blanket "cost
nearest-in-window for all windowed edges" FAILED (didn't fix rope, broke shouldPreferSynthetic
LedgeDropsOverDownJumps by making JUMP approach cheap). Agent reconciling (scope to DROP, or
keep per-anchor startPoints with full window, or hybrid) - must keep all nav green + window wide.

## Party cohesion + resupply independence DONE (2026-06-13)
- Leader portal-wait (2517817c5, 3e9eeb11e): STRAGGLER_WAIT_HOPS=1 (BotManager.cfg, runtime),
  same-map gap SAME_MAP_STRAGGLER_PX 700 / RESUME_PX 350 hysteresis; leader loiters AT the
  next-hop portal (loiterAtAnchor, autopilotWaitAnchor via BotTravelManager.nextHopPortalPosition)
  doing IN-RANGE opportunity attacks (no chase) instead of whole-map grind-wander; portal entry
  gated structurally (wait returns false before tickTravel). Arrived/no-portal -> hold+grind.
- Resupply independence (8d4f1c62e, d9ebcb25b): effectiveCohesionLeader = first member with
  errandMapId==-1 (dynamic leader, skips resupplying nominal leader; all-resupplying -> no
  cohesion, independent); waitingForStragglers skips members with errandMapId!=-1; followers
  off grind map re-anchor to next non-resupplying member or travel to autopilotMapId (never
  chase a resupplying leader to town); PRE-TRAVEL resupply gate in tick() (SupplyLevel seam ->
  BotPotionManager.countPotions vs cfg.POT_STOP, off-destination + not-returning) resupplies
  before departing to kill the map->town->map bounce. BotAutopilotDebug shows effective vs
  nominal leader + per-member resupply-excluded state.

## REMAINING / DEFERRED (2026-06-13)
1. Deferred by user: USE/ETC hoarding tightening (use "inv debug" dumps as evidence; quest-item
   hygiene already cut quest clutter).
2. OPEN: why graphgen emitted a 2px jump launch window (El Nath r17->r44).
3. Slice 2+ quest advisor polish: global NPC->map resolution (slice 1 only current+return-map);
   scripted-quest support deferred (no bot script engine); Silver Deputy Star quest 8239
   (L80 NLC scripted chain) still out of reach.
4. Gachapon economy note: feature works off looted NX (global drop). Pink-cape-class cosmetic
   value via ii.getPrice proxy - if a unique has price 0 in WZ it won't be chased; revisit a
   cosmetic-desirability table if user wants specific uniques targeted.
2. NEXT (user-approved design, launch after quest agent): GACHAPON loop - (a) NX cards are a
   global drop; with redirect-to-owner now OFF bots accumulate NX on pickup (verify the
   pickup->NX credit path); (b) when NX affords N tickets, ABSTRACTED cash-shop buy of ticket
   5220000: mirror the cash shop purchase server effect (deduct NX, add item, inventory-space
   check) - user explicitly allowed abstraction, only the affordability check must be real
   (nobody can see the cash shop UI anyway); (c) gacha ADVISOR: score per-town pools
   (server/gachapon/*.java common/uncommon/rare + Global, tier odds in Gachapon.java) by
   expected value (equip upgrade value SSOT + trade value) minus travel cost, pick best town,
   walk to gachapon NPC legally, mirror doGachapon per ticket with humanlike delays.
   Quest scout facts: doGachapon at NPCConversationManager:409, Gachapon.java:42/144 NPE-gate
   on getByNpcId != null; Silver Deputy Star = quest 8239 (L80 NLC scripted chain, defer).
3. QUEST RECOMMENDATIONS (user 2026-06-13, design approved): supervised bots (owner online)
   NEVER quest on their own (already gated) but SUGGEST quests. (a) "recommend quest" chat
   command - SENSITIVE: scores startable quests by mob-overlap with current/nearby maps,
   travel time to NPCs, reward exp vs party grind baseline + unique-reward bonus; replies
   with top pick(s): quest name, NPC name + map, objective summary, reward. (b) AUTO-SUGGEST
   - LOW sensitivity, supervised mode only: rate-limited (per-map + multi-min cooldown), only
   when VERY good AND close (reward ~= several minutes of grind exp, mobs already being
   killed, NPC within 1-2 hops); one ASCII line, never repeat declined/stale suggestions.
   Build with item 3b below after quest slice 1 lands (both ride the quest index).
3b. QUEST-ITEM HYGIENE (user 2026-06-13): quest items that are no longer needed - quest
   completed, severely outleveled, or quest never doable - must be discarded/sold so they do
   not clog ETC/USE. Needs the quest index (item->quest mapping from Check.img complete-req
   items + Item.wz quest flag); currently isSafeToDrop excludes ALL quest items from sell-trash
   forever. Build after quest slice 1 lands (BotQuestManager owns the index).
4. docs/bot/quests-worth-doing-benchmark.md = community ground truth (110 quests) for tuning
   the slice-2 advisor worthwhile thresholds (rankings are the signal; rewards from Act.img).
5. Deferred by user: USE/ETC hoarding tightening (use "inv debug" dumps as evidence).
6. OPEN: why graphgen emitted a 2px jump launch window (El Nath r17->r44).

## Straggler + errand warp fixes (2026-06-14, branch experimental)
- AHEAD-OF-LEADER STRAGGLER BUG: leader stuck in town after resupply, holding for a member that
  was ALREADY at the grind dest. waitingForStragglers measured hops(member->leaderMap); a member
  sitting AT/past dest routes BACKWARD past the cap -> MAX_VALUE -> falsely flagged off-map.
  FIX: BotAutopilotManager.aheadOfLeaderTowardDest(memberMap,destMap,leaderHopsToDest) = member
  strictly closer to dest than leader -> not a straggler (skip the hops hold). leaderHopsToDest
  hoisted out of the member loop. Conservative under the hop cap (both MAX -> MAX<MAX false ->
  still wait, never masks a real straggler). BotPathLogger mirrors it (shows "ahead(toDest<leader)",
  suppresses *STRAGGLER(hops)*). Evidence: pathlog-Bowgurl-2026-06-14T112537. SHARED predicate
  (SSOT) called from both. Test: shouldNotWaitForMemberAlreadyAtTheDestination (PAIR-KEYED hop mock
  from==to?0:MAX -- a constant mock makes the ahead-guard invisible).
- WAIT-FOR-STRAGGLER CHAT MUTED (user): removed reply.accept(WAIT_REPLIES) on the wait edge +
  the WAIT_REPLIES list; verdict still in pathlog. Test asserts replies.size()==0 now.
- RESUPPLY ERRAND WARP-BACK: a follower peels off to town for an errand but transit-follow left
  entry.following=true (errand entry never clears it; cohesion is gated off at tick L375 so it
  can't clean up). syncFollowMap (the ONLY cross-map warp; bot.changeMap @ BotManager ~3974) then
  yanks it back to the leader's grind map before it reaches the shop. FIX: gate at BotManager
  ~2511 -> `!shopVisitPending && autopilotErrandMapId==-1 && syncFollowMap(...)` (mirrors the
  cohesion gate, completes the existing "don't pull back to owner" comment). requiresFollow is
  KPQ-maps-only so it can't set following at Orbis -- lingering transit-follow is the only source.
  recoverTeleportDistance is IN-MAP (bot.getMap()), not the cross-map symptom. NOT unit-tested
  (private method in heavy tick path, no existing harness). DEFERRED: findNearestShopMap may
  return town (200000000) not grocery (200000002) -> shopVisitPending never sets (downstream,
  separate).
- RESUPPLY SHOP ROUTING (BotShopManager.findNearestShopMap): errand destination now keyed on the
  TRIGGER via needsToBuySupplies (pots OR ammo, shop-independent: recharge takes no shop, fixed-ammo
  treats null shop as "any") -> sell-trash trip = any shop; supply run = a shop selling >=1 recovery
  potion (shopSellsAnyPotion, isRecoveryPotion SSOT; a potion shop reliably also stocks ammo).
  findBestShop now takes a Predicate<Shop> so onMapChange (on-arrival visit decision) keeps its
  GRANULAR bag-state-aware shopHasAnythingNeeded (else an ammo-only shop the bot stands at stops
  triggering recharge visits -- 2 BotShopManagerTest failures caught this). Supply search is now
  bag-state-independent -> cached per source map (nearestPotionShopMapCache), like the any-shop cache.
- ADMIN TRADE TARGET: gm "trade cape" on a non-owned bot opened trade with the real OWNER. Fixed:
  startTradeTransfer + startTradeMesoTransfer resolve the partner via BotManager.commanderOrOwner
  (SSOT: fresh debugCommanderId admin else owner; bind set by bindDebugCommander on any gm6 foreign
  name-target). Proactive startScrollReviewTrade left on owner (bot's own initiative, not a command).
- PARTY-AUTOPILOT WHOLE-PARTY (RE)START: command to one bot ran startParty(List.of(entry)) = party
  of one -> solo plan, desync (worse after "follow" cleared autopilotParty). New
  BotManager.partyAutopilotCohort(entry) = live game party (partyBotEntries spans owners; @botparty
  bots self-own so owner's-bots snapshot was just the 1) else owner's bots; NOT filtered on
  autopilotParty so a follow-reset member rejoins. Used by BOTH the per-bot path (BotChatManager
  ~1064) and the owner-broadcast intercept (BotManager ~1231). partyDecider/partyInputs don't filter
  on autopilotParty; applyPartyPlan sets it -> followed-reset bot gets planned + re-synced.

## Autopilot job-advance milestones (2026-06-14)
- BotBuildManager.checkLevelUp + buildJobPrompt now handle autopilot bots at job levels (was
  skipped before). Trigger is SKIP-SAFE: keyed on a milestoneFloor (lvl>=120/70/30/10/8) + the
  jobPromptSent tracker (lvl>=X && jobPromptSent<X), NOT exact lvl== (a kill that jumps 69->71 must
  not miss the advance). prev==-1 first-observe baselines jobPromptSent=passedMilestoneForJob(job)
  so a spawned-high-level bot isn't re-prompted / doesn't spam checkBotStatus.
- 8/10/30 (1st/2nd job = a CHOICE): autopilot bot leaves cohort individually via
  BotManager.parkAutopilotForJobDecision -> online real non-self owner (hasOnlinePlayerOwner, the
  predicate canWalkToOwner now reuses minus its autopilot guard) => issueFollowOwner; else =>
  enterOwnerInactiveSafeMode town SSOT. Owner STILL gets the choice prompt (parkIfAutopilot fires
  alongside the returned JobPrompt). Stays stopped (no auto-resume) per user.
- 70/120 (3rd/4th = DETERMINISTIC): auto-advance in place via BotStarterKitManager.advanceJob
  (SSOT: Character.changeJob awards SP/AP/HP-MP/slots + handleJobAdvance auto-spends; no quest),
  delayed randMs(900,1100), suppresses owner prompt (returns null). AUTOPILOT-ONLY per user;
  supervised bots keep the owner "type 'crusader'" prompt. Successor = id+1 within explorer branch
  (BotStarterKitManager.thirdJobOf/fourthJobOf, explorer ids 100-599; leans on client.Job numbering
  X10->X11->X12 as the progression SSOT). Hero is the one gap: 4th-job SP held until owner picks
  1h/2h via buildSpVariantPrompt (existing design, not changed).
- COMMIT COLLISION (logged for future): production code got swept into a concurrent session's commit
  6fd0512a0 (owner response-overlay/BotPrompt sendHint); my tests committed separately 9220bc969.
  Lesson: concurrent sessions sharing a worktree -> git add -A folds in others' WIP. Files: park in
  BotManager, thirdJobOf/fourthJobOf in BotStarterKitManager, checkLevelUp/buildJobPrompt in
  BotBuildManager.

@BOTME/@BOTPARTY (gm0): BotManager.takeOverAsBot(Client, requireBotParty) - yellowMessage,
c.disconnect(false,false) after ~700ms, poll player storage (700ms x10) until char gone
(disconnect saves synchronously incl. party id column), loadOfflineBot + registerSpawnedBot
(charId, botChar, botChar) = SELF-OWNED (owner==bot: resolveTickOwner happy since BotClient
always isLoggedinWorld; ferryAllowed treats owner==bot as owner-absent), markBotPartyOnline
(PartyCharacter LOG_ONOFF re-online), then startParty when >=2 party bot entries AND all online
members are bots, else solo start. Reversal is FREE: real login -> Character.newClient detects
BotClient->real swap -> cleanupBotRuntimeState. @botparty = same with requireBotParty (refuses
unless every other online party member is a bot).
