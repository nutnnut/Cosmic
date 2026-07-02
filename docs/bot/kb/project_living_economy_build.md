---
name: project_living_economy_build
description: "Living economy BUILD STATE (branch dev-economy): S0-S2 landed, what each commit holds, implementation insights, pending live-verify + S3-S6 roadmap - resume here"
metadata:
  type: project
---

# Living economy — build state (2026-07-02)

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

## Pending / next

- **S2 LIVE VERIFY round 2 (next action, restart required):** watch stranded bots walk out on
  boot (log line "stranded in FM map ... walking it out"), then a clean stall loop: trip → slot
  spread ≥170px apart (`/api/market/stalls` x/y), listings all premium-worthy (no potions —
  `/api/market/bot` verdicts), browse/bargain, EXIT back to town, party decides working with a
  member mid-market. Design sec 14 S2 criteria on top.
- **S3:** BotMarketGrammar (S>/B>/PC>, reuse MESO_AMOUNT_TOKEN + trade-command name resolution),
  BotMarketShoutBus (map-scoped; player entry via GeneralChatHandler branch, bot loopback at
  botSay), want/stock matching, accept-at-ask trades via tickManualTrade extension, BotPrompt
  hints. Verify: shout→trade with farming running, no dupe (gate already live).
- **S4:** BotTradeNegotiator (counters/rude-cancel; settle pre-lock), UpgradeMenu WTP,
  grind-advisor market term (mesoFocus trait), flip `SCROLL_FOR_PROFIT_ENABLED`
  (BotScrollPlanner:114), retire placeholders (SCROLL_OPPORTUNITY_MARGIN/FRACTION, Chaos/White
  10M, SCROLL_CEILING_PER_EV, AMMO_CEILING_*), equip banding + listing, fold tradeValueScore
  into marketStatValue path, own-income travel gate, new BotPersonality fields
  (haggleStance/mesoFocus/buffSpend/collectorTaste).
- **S2 follow-ups queued:** Fredrick proceeds collection (FredrickProcessor.fredrickRetrieveItems
  is an INSTANCE method — check acquisition), live-stall restock/reprice service visit (currently
  stall-alive → browse-only), stall-name flavor corpus (SoloMapling audit).
- **S5:** gossip diffusion, ammo purchase behavior, buff buying, `/api/market` +
  `/api/market/bot` routes (UPDATE docs/bot/web-endpoints.md when added — rule 8).
- P3: FARM_MESO_PER_SECOND=1000 → own observed meso/hr EMA (design sec 13).
