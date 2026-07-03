---
name: project_living_economy_build
description: "Living economy BUILD STATE (branch dev-economy): S0-S4 + S2 follow-ups + S3 landed, what each commit holds, implementation insights, pending live-verify + S5-S6 roadmap - resume here"
metadata:
  type: project
---

# Living economy — build state (2026-07-03)

**Design of record: `docs/bot/living-economy-design.md`** (rev 2: consensus statistic + noisy
per-bot perception; structured priors for cross-item coherence; travel-frugality owner rules in
sec 8.1). economy-design.md = math reference only. Read the design doc FIRST; this file is the
build ledger + resume pointer.

## Branch / commits (dev-economy, branched off `dev` which contains experimental)

- `fa5f59405` docs: design of record (rev 2)
- `f6df2976c` S0: `Trade.getStagedMeso()` (staged meso was private — haggling blocker);
  `Trade.completeTrade` addFromDrop-false now FLOOR-DROPS instead of silently voiding;
  `BotEntry.marketBusy` extends both trade tick-gates (BotManager ~:3560/:5440) to non-Trade
  market staging; `BotAssetView` (EQUIPPED/BAG/STALL/STORAGE/TRADE_ESCROW/FREDRICK enumeration +
  wallet split incl. MerchantMesos — everything was invisible to valuation before).
- `50d4ec0cd` S1a: `BotMarketMath` — ONE update rule (confidence-weighted gap step) for private
  books AND consensus; gap-EMA half-life decay; weighted-median + volume damping; seeded
  perception noise (bot+key+day stable); structured priors (curveCalibration / impliedStatPrice /
  structuralQuote); qualityBand + priceKey(itemId*256+band); openingMargin / repriceAsk /
  undercutTarget / counterPrice.
- `c0a4de0f2` S1b: `db/tables/031-bot-market.sql` (bot_market_event tape no-FK, bot_market_belief,
  bot_market_consensus) + changelog id=31; `BotMarketLedger` (append/recentEvents/flow tallies),
  `BotMarketStore` (batched upserts, BELIEFS_PER_BOT_CAP=512 at load).
- `3b2bee433` S1c: `BotMarketBook` (layer 2: private obs ⊕ noise-sampled consensus, LRU, dirty
  drain) + `BotMarketConsensus` (layer 1: pure sweep over event windows, listing asks only when a
  key has no clearings) + `BotMarketSimTest` (4 multi-agent scenarios).
- `8439155c0` S2a+b: book lifecycle (`BotMarketBook.of/maybeFlush` on the bot tick; ~4-5min
  staggered flush) + consensus sweep timer registered in `Server.init` (3min); extracted
  `HiredMerchant.createFor/publish` from PlayerInteractionHandler (shared path);
  `HiredMerchant.buy` → `BotManager.notifyStallSale` (STALL_SALE tape + fee sink + participant
  book observes — fires for PLAYER stalls too, by design).
- `d320fc868` S2c: `BotFreeMarketManager` DetourErrand (registered BEFORE gacha; scan next to
  gacha scan in BotManager runCommonTickSystems; wait-anchor/activity/status wiring) + travel
  frugality + `canPlaceStore` made public + permit purchase.

Tests: 25 economy (BotMarketMathTest 15+1, BotMarketSimTest 4, BotAssetViewTest 3,
BotFreeMarketManagerTest 3) + 203 adjacent regression green. Run:
`mvn test -Dtest=BotMarketMathTest,BotMarketSimTest,BotAssetViewTest,BotFreeMarketManagerTest`.

## Implementation insights (cost real debugging — don't relearn)

- **Unsold-pressure undercut repricing is LOAD-BEARING.** First sim froze: every ask sits a
  margin above every WTP = structural no-trade zone, and glut entrants priced AT the going rate
  (they perceive consensus!) so gluts RAISED prices. Fix = `undercutTarget` (step to just under
  the cheapest visible competing ask, never above own perception, reservation floors) driven by
  unsold rounds. Design sec 5 records it.
- **Books are tick-thread-owned, not thread-safe** — flush happens on the bot's own tick
  (maybeFlush), never from timers. Consensus is the only cross-thread reader surface
  (ConcurrentHashMap).
- **Bots cannot hear chat packets** (handleChat = human packet handlers only; botSay is
  packet-out only) → S3 shout bus is REQUIRED, chat lines are narration (design sec 8.4, res 6).
- **Equip quality band is 0 everywhere for now** — banding needs the S4 valuer; scrolls/stacks
  price fine on band 0, equips are coarse until then.
- Permit 5030000: CASH tab, NOT consumed on open; Commodity SN 10000562 @ 5900 NX (WZ-verified);
  purchase = gacha NX abstraction (`BotGachaponManager.NxBalance/NxCharge` seams reused).
- `UseClass.keepValue` (classifyBagUse) is PER-STACK meso — divide by qty for unit cost basis.
- FM nav needs ZERO BotWorldGraph changes: town via graph, `market00` scripted portal walked by
  the errand (bots run portal JS via walkToPortalAndEnter), entrance→rooms probed by portal name
  `in00..in19` + isFreeMarketRoom(target), exit `out00`. FM_TOWNS = 24 ids in the manager.
- `BotMarketConsensus` is public + startSweeping public ONLY because Server.init calls it.
- travelSeconds/travelOptions seams are SHARED with BotGachaponManager — a test swapping them
  affects both managers.

## Travel-frugality owner rules (2026-07-02, sec 8.1 — structural, keep on any refactor)

Market NEVER interrupts grinding: (1) `decideBreakDestination` upgrades a rolled town break to an
FM town within `FM_BREAK_DETOUR_BUDGET_SECONDS=45` when `hasMarketIntent`; (2) hard
`MAX_ONE_WAY_TRAVEL_SECONDS=90` cap from rest spots (deep-dungeon bots skip until a townside
break); (3) chill session = market day (8x browse dwell).

## S2 live round 1 (2026-07-02) — found + fixed, needs restart to verify

First live run stranded DOZENS of bots inside FM maps. Root causes + fixes (one commit after
d320fc868):

- **Stranding root cause:** tickErrand's timeout branch called finishErrand IN PLACE. FM maps
  (910000000 entrance + rooms) were OFF BotWorldGraph, so a bot released there couldn't route
  anywhere: solo decide spams "no reachable grind spot", party decide fails for the WHOLE party.
  **Final fix (owner-directed, supersedes the first-round bespoke recovery): the FM exit is IN
  the world graph now, shrine-pattern.** Room<->entrance legs were already real portal edges
  from the WZ scan; the missing link was the dynamic exit (entrance out00, script market00,
  warps to the SAVED town). `RouteOptions.fmReturn` (mirrors `worldTourReturn`) carries the
  peeked FREE_MARKET town ONLY while the bot stands inside FM maps — so the FM can be routed
  OUT of but never THROUGH (no 24-town wormhole); `scriptedEntrancePortal` maps any
  entrance->non-room hop to "out00". With that, decide/travel/party all work from inside FM
  with ZERO special cases (the first-round `maybeStartExitRecovery`/`tickStrandedExit`/
  `routeAnchorMapId` layer was deleted). The errand still owns its own exit: timeout inside FM
  pivots to PHASE_EXIT (wedged exit warps out mirroring market00.js), and
  BotAutopilotManager.clear() clears the FM errand (parity with quest/gacha). GOTCHA:
  `Character.getSavedLocation` is a DESTRUCTIVE read (clears) — `peekSavedLocation` for
  planning, destructive read only in the actual warp-out.
- **SETUP fizzle root cause:** stand spots anchored off out00 (+260px…) often sat on another
  floor level → walk never converged → 60s phase deadline → fizzle (live: permit bought, fizzle
  60s later, every trip). Fix: slot columns every `STALL_SPACING_PX=170` along the bot's CURRENT
  floor strip (BotPhysicsEngine.pointBelowIndexed ground snap, |dy|<=60 same-level guard),
  pre-screened with canPlaceStore's own rules (152px merchant spacing = 23000 distanceSq, 120px
  portal buffer), 12s per-slot no-progress watchdog → next slot; slots/tries exhausted →
  browse-only, NEVER a fizzled errand. Entrance room portals are `in01..in22` (no in00).
- **Junk listings:** NPC-shop staples (potions) listed at silly asks. Fix in evaluateListings:
  ask CAPPED under the NPC shop counter price (`npcShopPrice` seam backed by
  `BotScrollManager.marketBuyPriceMeso` — the EXISTING cached shopitems reverse index with the
  GM/junk-listing filter; do NOT re-query shopitems), stacks list only when after-fee premium
  over NPC-selling covers ~30s of FARM_MESO_PER_SECOND (scaffold now package-visible in
  BotScrollManager; P3 retires both together), premium-ranked to fill 16 slots, and
  MIN_LISTINGS_TO_TRIP counts only non-NPC-shop stacks (staples tag along, never cause a trip).
- **Debug surface (owner request):** `/api/market/stalls` (every open merchant: pos + stock) and
  `/api/market/bot?name=` (fm state, wallet split, bag classification, listing verdicts, beliefs
  via a DETACHED store-loaded replica — never the tick-owned book). docs/bot/web-endpoints.md
  updated (rule 8).
- Verified en route: bots DO already buy potions from NPC shops (BotShopManager resupply,
  `ShopFactory.getShopForNPC` + `shop.buyDirect`), so capping potion listings loses nothing.

## Scroll value calibration (2026-07-02, owner-raised: glove-ATT too cheap vs weapon scrolls)

`BotScrollManager.slotDurabilityFactor(cat)` — WZ-derived investment-durability per equip
category ((id/10000)%100, same code `applicable()` matches scrolls by): least-squares growth of
`marketStatValue` per reqLevel within the category, durability = SCROLL_JOB_WORTH /
(SCROLL_JOB_WORTH + slope × REPLACEMENT_HORIZON_LEVELS=30) where SCROLL_JOB_WORTH = a canonical
+10 ATT job (50 statValue units), normalized to weapon-average = 1.0 (keeps the Attack-60% 4.5M
anchor), clamped [0.5, 4], min 8 samples else 1.0, 1.0 on WZ-less test runs.
**v1 bug (fixed 1f6f24f6d, first live round printed it):** growth was measured RELATIVE to the
category's own mean worth (slope/meanY) — gloves' tiny base worth made negligible drift read as
fast growth → factor 0.79, CHEAPER than weapons, opposite of reality. Durability must compare the
slot's ABSOLUTE base-stat growth against the fixed worth a scroll job adds. Post-fix WZ-backed
check (`BotSlotDurabilityPrintTest`, needs local DB+wz): glove 2.62 / claw 1.17 = 2.24x — inside
the SoloMapling 2.0-2.4x reference band; shoes 2.63, capes 2.38, 1h-swords 0.84. Applied in
`scrollCombatCeilingMeso` — flat slots (gloves/shoes/capes) scale UP vs weapons. Behavior follows
price for free: the planner's per-apply cost = SCROLL_OPPORTUNITY_FRACTION × price, so pricier
flat-slot scrolls are burned only for bigger gains. Factors print at boot
("scroll slot-durability factors"). Deferred to S4: explicit keeper-gating in the planner
(only precious-scroll keepers) + use-vs-sell mesoFocus tilt.

**SoloMapling calibration reference (hand-curated YAML, extracted 2026-07-02 — RELATIVE ratios
only):** glove-ATT ≈ 2.0x claw/dagger-ATT at 60% (3.0M vs 1.5-1.6M), ≈ 2.2-2.4x at 10%; shield-ATK
dark 70% is their priciest armor scroll (6M); Chaos AND White ≈ 50M each (our hardcoded floor is
10M — the live consensus already trades Chaos ~28M, let the market keep finding it; revisit the
floor at S4 retirement). Their scrolled-EQUIP price = DP "cheapest expected scroll-craft cost to
reach the rolled bonus" x 0.6 secondhand discount (UpgradeSimulator.getEquipMarketValue) — same
shape as our planned S4 reproduction-value curve calibration, good precedent. Verify our
boot-log glove factor lands ≈2x weapon avg; if far off, tune REPLACEMENT_HORIZON_LEVELS.

## Pending / next

- **S2 live-verified (2026-07-03):** 8 stalls × 16 slots clustered, Fredrick collect→publish
  working, 31 banded equip listings + 3 real banded equip STALL_SALEs on the tape (prior session).
- **DONE (2026-07-03): S2 follow-ups — live-stall restock/reprice service + stall-name corpus**
  (commit e6c6c1b67). On a re-visit with the stall still open the bot TENDS it instead of going
  browse-only: `HiredMerchant.botServiceReprice(ToIntFunction<PlayerShopItem>)` purges sold-out
  slots + rebuilds repriced survivors under the `items` monitor (mutually exclusive with buys;
  price is FINAL so a changed slot is a fresh PlayerShopItem), returns free-slot count, no save;
  `serviceLiveStall` then restocks free slots via the SAME addItem + removeFromSlot + LIST-tape
  loop as opening, one saveItems at the end, under `marketBusy`. Reprice = `BotMarketMath.repriceAsk`
  toward the banded-key belief (pressure 0.5), floored at max(½ ask, NPC sell-back per unit) — no
  parallel pricing. Service cadence 20-26h → 3-6h so sold slots refill + stale asks track belief
  across a market day (rides break/satiation cadence, not a dedicated trek). Headless world-scoped
  stall lookup, so it works from any room. Stall signs → ~40-sign ASCII corpus (headline × tag,
  two hashes). Verified: 28 economy tests green.
- **DONE (2026-07-03): S3 — shout grammar + bus + accept-at-ask direct equip trades**
  (commits 722719702, b7901797e, 727daf1b3).
  - **`BotMarketGrammar`** (pure, WZ-free structure): parses S>/B>/PC> → `Offer(kind,itemId,qty,
    priceMeso)`; name via `getItemDataByName` exact-match-wins or `#<id>`; meso via
    `BotChatManager.parseMesoAmount`/`MESO_AMOUNT_TOKEN` (both made package-visible, SSOT). Also
    `format(Offer)` for the spoken line. Swappable `nameResolver` seam → `BotMarketGrammarTest`
    (10) injects a fake catalog, no WZ. Players PARSE inbound, bots FORMAT outbound — both converge
    on the bus as the same `Offer`.
  - **`BotMarketShoutBus`** (map-scoped bulletin, NOT an order book): `{speakerId,offer,expiresAt}`
    per map, synchronized list, ~3min TTL swept lazily, bounded 32/map, re-shout replaces the
    speaker's prior same-item shout. Player shouts publish via a grammar branch in
    `BotManager.handleChat` (MAP channel). `BotMarketShoutBusTest` (6).
  - **`BotShoutTradeManager`** (matching + priced trade): polls the bus each AI tick, matches a
    heard S> (buy) / B> (sell), invites the speaker MAP-WIDE (inviteTrade has no distance check),
    one side stages the equip + the other the meso over vanilla `Trade`, each LOCKS only after
    verifying the counter-stage. Bots also EMIT an S> for their top surplus equip on a 4-9min
    cadence when idling in an FM room with an audience → bot↔bot with no human. **v1 EQUIP-scoped:**
    WTP = `equipBuyCeilingMeso` on the ACTUAL staged piece, WTS = `equipMarketQuote` curve value
    (SSOT — all design examples are equips). Cross-bot recognition via a `dealsByResponder`
    ConcurrentHashMap the initiator registers keyed by the speaker (responder) id; responder claims
    it on the incoming invite. tickTrade/tickManualTrade stand down while `entry.shoutTradeActive()`.
- **KEY TRADE-SAFETY INSIGHT (don't relearn):** the existing gift auto-confirm (`recipientIsBot`
  / partner-confirmed in tickManualTrade/tickTrade) is UNSAFE for a PRICED trade — neither side may
  lock before seeing the counter-stage, or it could pay/hand over against a wrong or short offer.
  So the shout trade uses its OWN price-aware confirm. Completion vs cancel are BOTH `getTrade()==
  null` (ambiguous); resolved by only calling `finish` when THIS side actually LOCKED
  (`shoutTradeLocked`) — a symmetric deal where I locked with terms met is guaranteed to clear, so
  a window that vanishes before I locked was a partner cancel, never a phantom clearing (no spurious
  belief/tape). Every move rides Trade's staged-debit (`setMeso` debits now) + refund-on-cancel.
- **S3 LIVE VERIFY (restart required):** (1) a human `S> <equip> <price>` near a bot with that
  slot want → the bot invites + buys within ~a minute, farming pauses then resumes, no dupe/loss;
  (2) bot↔bot: watch FM rooms for `S> ...` chat lines (emission) → a second bot completes the swap
  (tape `bot_market_event kind=0` TRADE rows, and `kind=5` SHOUT ads). `/api/market/bot?name=` for
  the buy/sell books moving on W_SHOUT/W_TRADE. Not yet observed live.
- **Live-round fixes (2026-07-02/03, post-S4):** three deadlock-class bugs found by watching the
  restart funnel, each invisible without live checks:
  1. Login market-day seed gated on `BotAutopilotManager.isActive` — always false at session
     begin (autopilotMapId set by the FIRST decide after login) → seed silently dead (e330f616a).
  2. `selectListings` on the bot tick thread — heavy since equips joined the shelf (repro DP +
     farm costs per piece); walks stuttered, trips fizzled. The scheduleScrollPlan lesson,
     RE-learned: heavy valuation NEVER on the tick thread. Plan now on DECIDE_POOL ("fm-plan"
     tag), carried on the entry, staged with a cheap hasItem re-validate (40f0de623). Fizzled
     trips (never reached the entrance) also no longer burn the 2-8h satiation — 10-25 min
     retry instead ('holy' was locked out 7h by one failed walk).
  3. **Room-roulette per-tick reroll (0a01115c6): the market deadlock.** pickRoomPortal runs
     every TO_ROOM tick; the roulette (5e5c0d48f) rolled a fresh room each call → walk target
     flip-flopped between 22 portals → every bot oscillated at the entrance till the watchdog
     fizzled the trip. Two restarts: zero rooms entered, zero stalls, zero tape. RULE: a
     function called per-tick must return a per-tick-STABLE decision — roulettes/randomness
     bank their winner at commit time (fmRoomMapId), never re-roll in the walk loop.
  Plus: FM trip trace behind MARKET_TX_CONSOLE (5f605f7d6 — phase transitions, setup-gate
  reason, Fredrick outcome); LifeFactory negative-caches failed mob loads (3c094c453 — ghost
  drop_data droppers 2230112/9101000/9410019 spammed SEVERE per equip quote; skipping verified
  lossless, only 2 items affected, one ghost-only).
- **DONE (2026-07-02): S4 equip market block** (commits 69f48528d..ab5c55736). Key discovery:
  the convex reproduction-cost DP already existed — `BotScrollValuer.reproductionValue`
  (restart-on-ruin, abandon-and-rebuy option; STRONGER than SoloMapling's infinite-retry
  `cost/(p)` model) — and the banding schema was pre-built in S1 (`priceKey = itemId*256+band`,
  `quality SMALLINT` column, curveCalibration/quoteFromCurve). S4 was wiring, not modeling:
  - **Valuer:** `BotScrollManager.equipMarketQuote(entry,bot,eq)` — job-neutral
    `marketStatValueOf(Equip)`, catalog-wide `marketReproSpecs` (reuses scrollsByCategory) at
    FULL market scroll prices, `SECONDHAND_DISCOUNT = 0.6` above clean (clean = fungible at base
    cost, never discounted; SoloMapling parity). Band = surplus over clean in median-catalog-gain
    units, provenance-blind, capped at the slot budget's reachable ceiling (past it the restart
    DP diverges). `equipQualityBand(ii,eq)` is price-free/deterministic so every bot computes
    identical bands (price keys must match across trades).
  - **Listing:** `evaluateEquipListings` — supply = `collectMarketableEquips` (the valuables
    shelf: NORMAL-group above-base rolls bots already hoard). One verdict/plan PER PIECE
    (HiredMerchant.buy hard-rejects equip qty>1; same-id pieces differ in rolls). Ask = book
    belief at the BANDED key over the curve quote, curve pinned to traded bands via
    curveCalibration when evidence exists. NPC counter caps CLEAN pieces only.
  - **Banded tape:** LIST + STALL_SALE appends and browse observations now carry real bands
    (`notifyStallSale` takes the sold Item; one-line HiredMerchant diff).
  - **Demand:** `maybeBargainBuy` buys a rolled piece as a combat UPGRADE within
    `equipBuyCeilingMeso` = potentialValue gain × SCROLL_CEILING_PER_EV × slotDurabilityFactor —
    needs NO prior price belief, which is what lets a fresh equip market clear at all.
  - **Chaos (owner-specced):** auto-scroll scan with no regular play falls through to
    `bestChaosPlay` — Monte-Carlo (128 samples) over the server's real reroll semantics (every
    positive stat ±CHSCROLL_STAT_RANGE, floored 0) read off the piece's own band curve;
    band ≥ 2 pieces only; deterministic per-bot gambler appetite accepts EV in
    [−0.3, +0.3]×cost. Runs on DECIDE_POOL ("chaos-scan" perf tag), applies via the normal
    pending/confirm flow.
  - **White (owner-specced):** `shouldUseWhiteScroll` in executeConfirmed — protect when
    failRate × p × marginal-band value > white market cost; `applyScroll` grew the ws
    flag/consume flow mirroring ScrollHandler (bots hardcoded `false` before — they literally
    COULD NOT use whites; real functional gap found by exploration, not just missing logic).
  - **10M floor retired to backstop:** chaos/white price = max(10M, live consensus at their key).
  - Deferred: comparable-implied quotes (impliedStatPrice) still unused; clean-rare bases (fail
    shouldKeepForSellTrash) still NPC-dump instead of listing; slots-consumed not in bands;
    mesoFocus/haggle traits still pending (S4 second half below).
- **S4:** BotTradeNegotiator (counters/rude-cancel; settle pre-lock), UpgradeMenu WTP,
  grind-advisor market term (mesoFocus trait), flip `SCROLL_FOR_PROFIT_ENABLED`
  (BotScrollPlanner:114), retire placeholders (SCROLL_OPPORTUNITY_MARGIN/FRACTION, Chaos/White
  10M, SCROLL_CEILING_PER_EV, AMMO_CEILING_*), equip banding + listing, fold tradeValueScore
  into marketStatValue path, own-income travel gate, new BotPersonality fields
  (haggleStance/mesoFocus/buffSpend/collectorTaste).
- **DONE (2026-07-02): Fredrick proceeds collection.** PHASE_FREDRICK in the FM errand: on any
  entrance arrival (inbound AND exit-leg) with `hasFredrickHoldings` (merchantMeso field cheap
  check + ItemFactory.MERCHANT one DB read; a LIVE stall owns its rows — always gate on
  stall-dead), walk to NPC 9030000 (gacha-style approach, 500px trigger) and run the SHARED
  player op `FredrickProcessor.fredrickRetrieveItems` via
  `Server.getChannelDependencies().fredrickProcessor()` (accessor added). The op is
  ALL-OR-NOTHING (canRetrieveFromFredrick: every item must fit + meso headroom) — inbound
  failure retries on the exit leg (bag emptiest right after stall stocking), exit failure waits
  for the next trip; Fredrick keeps holding, nothing is ever lost. State: entry.fmFredrickState
  (0/1 retry/2 done per trip) + fmFredrickOnExit.
- **S3 deferred (next slice):** consumable/stack shout-trading (v1 is equip-only — needs a WTP/WTS
  for non-equips: buyer use-value, seller cost basis); PC> price-check replies (parsed + fed to
  belief but bots don't answer yet); B> emission (bots only emit S> now); an approach-walk to the
  counterparty before inviting (v1 invites map-wide, no walk); BotPrompt hints surfacing shout
  offers to an online owner; auto-equip a shout-bought upgrade (sits in bag until normal equip pass).
- **S5:** gossip diffusion, ammo purchase behavior, buff buying, `/api/market` +
  `/api/market/bot` routes (UPDATE docs/bot/web-endpoints.md when added — rule 8).
- P3: FARM_MESO_PER_SECOND=1000 → own observed meso/hr EMA (design sec 13).
