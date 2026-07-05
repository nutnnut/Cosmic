---
name: kb_bot_solomapling_trade_behavior
description: "SoloMapling's FM/trade bot BEHAVIOR (state machine, haggle, buy decision, browse-as-visitor, stand selection, chatter, merchant skins/placement) + a ranked steal-list for our economy update. Behavior only — NOT their pricing (we keep SSOT valuation). Read before building shout-trade timing/haggle/browse/skin work."
metadata:
  node_type: memory
  type: project
---

Read-only extraction of the sibling fork `D:\GameServers\Maplestory\SoloMapling` (same Cosmic
upstream) trade/FM bot system, 2026-07-04. Their trading is well-tested and worth borrowing on the
BEHAVIOR axis; their PRICING is not (fragmented YAML/WZ/sine-wave — we keep our SSOT valuation, see
[[project_living_economy_build]]). Companion context differs: they run ~650 ambient bots populating a
solo world; we run ONE legal companion, so crowd-illusion machinery mostly doesn't apply. High-level
verdict in [[project_solomapling_audit]]; this file is the trade-behavior deep dive + steal-list.

All file:line cite the SoloMapling repo.

## What they have

### 1. Trade state machine (`BotTradeSystem/BotTradeSM.java`)
- Human invite → vanilla `Trade` enqueues to a static `BotTradeQueue` (`Trade.java:532`), NOT consumed
  inline; the bot's outer FSM drains one per tick (`BotTradeLogic.checkTradeQueue:10-21`, auto-accept
  unless block-listed).
- **Cadence is the human-feel engine:** the whole `BotSM` runs on `scheduleWithFixedDelay` at a random
  **2000-6000 ms** period (`BotSM.getRandomDelay:337-339`); ONE state transition per tick. A few states
  add a flat ~2s beat (`sleepAmountSeconds`, ms despite the name). This wheel — not per-step sleeps — is
  what makes it read human instead of robotic.
- States (`:107-219`): `INITIALIZE → RESPONDING/WAITING_RESPONSE → CONFIRMING → CONFIRMED_LOCKED →
  CLEANUP → COMPLETED`, with `DECLINE`/`TIMED_OUT` exits. `CONFIRMING` speaks "trade looks good to go!"
  then `Trade.completeTrade`. `CLEANUP` = emote + line + 2s beat.
- **Cancel/decline:** `DECLINE` (partner locked something insufficient) declines AND permanently
  block-lists the partner (`BotBlockList:370`). Walk-away/logout caught generically by
  `verifyTradePartner()` in the outer loop (`BotSM.java:227-233`) — one circuit-breaker, not per-state
  guards. Global 60s timeout from any state.
- Accept tolerates a **meso shortfall ladder** (1%→20% by magnitude, `BotTradeWants.verifySufficientMeso
  :99-131`) — an implicit auto-haggle-down.

### 2. Haggling (`FreeMarket/ShopOfferSystem/`, `HaggleSession.java`)
Fires when a HUMAN types a price into a bot's shop chat. Separate from `BotTradeSM`.
- Per-shop sticky mode on first query: **PRESENT 60% / AFK 40%** (`ShopOfferSystem:44-49`).
- PRESENT: schedule a **2000-6000 ms** "reading/typing" reply delay → `HaggleSession` (attempts capped
  **3**, 60s expiry) → `OfferEvaluator.evaluate(offer, listing)` → ACCEPT/COUNTER/DECLINE; 3 strikes →
  kick + `banPlayer()`. AFK: single evaluate, accept locks item now but price update + whisper deferred
  **5-30 min** ("owner checks back later").
- Price logic is fully behind `OfferEvaluator.evaluate` / counter-price calc — **the two plug-in points**
  where our own WTP/WTS would substitute.
- All lines externalized to YAML (`ShopOfferDialogue.yaml`): Accept/Decline/Counter/Kick/Welcome pools.

### 3. Buy decision — neither model does true best-of-N
- `BuyingMerchantBot`: advertise-and-wait, no comparison. Picks one list item, offers fixed 0.80-0.90×
  market, shouts, waits; accept = exact-item + tolerant-meso.
- `FMBot.shouldPurchaseItem:366-383`: probabilistic ratio ladder `ratio = ask/marketValue`, hard-reject
  >1.2×, then roll `{0.7→100%, 0.85→95%, 0.95→85%, 1.0→75%, 1.05→55%, 1.1→35%, 1.15→15%, 1.2→5%}`.
  Evaluated **per-shop, first-item-hit** (`purchaseShopItems:282-312`) — buys on first success, no
  cross-stall comparison. **Genuine cheapest-of-N comparison does not exist in either model.**

### 4. Browsing as a merchant visitor (`FMBot`)
- States `NAV_TO_FM_ROOM → PROCESS_ROOM → BROWSING_ROOM → ENTER_SHOP → PROCESS_SHOP → EXIT_SHOP`.
- **Explicit visitor registration:** `enterShop → visitShop → HiredMerchant.addVisitor:142-158` — a
  **3-slot** `visitors[]`, broadcasts `hiredMerchantVisitorAdd`/`updateHiredMerchantBox` (genuinely
  visible to others), `exitShop → removeVisitor` frees slot + logs visit duration.
- **Dwell** ~1s flat after buying, plus the outer 2-6s tick spacing. Purchase is inline while a
  registered visitor.
- Stall walk order: shops sorted by same-row "effective distance" then deck-cut-shuffled into segments;
  **walk-vs-click pity timer** (`BASE_WALK_CHANCE=0.40`, +0.10 per skip → guaranteed eventual visible walk).

### 5. Stand-spot selection
- `FMMovementCommands` is mostly dead code (only door/portal nav is live).
- Real logic: advertise reposition tables (`tryPlatformShuffleWhileAdvertising`), unoccupied-spot pick
  (`EnvironmentManager.findUnoccupiedPoint:1103-1112`, real collision avoidance), overlap nudge
  (`nudgeAwayFromOverlap:657-677`, one step away if a bot lands too close), and a **±50px randomized
  approach point** to a stall (`BotHelpers.getRandomizedPointXAxis:178-187`) so bots don't stack on the
  exact marker.

### 6. Chatter
- Trade-window lines hardcoded in `BotTradeSM` ("Here is what I've got. check it out!", "trade looks good
  to go!", "Thank you!", "Nah I'm good. Good bye.").
- Ad lines composed from prefix pools (`B>/BUY>/Buying>`) + item(+price) + suffix pools ("no lowball",
  "Pros only", "PM me") + 0-3 `@@@@` filler tokens + 15% full-uppercase.
- FM flavor: "ty!", "not a bad price", "gotta compare all the prices before i commit", "someone beat me to
  it again".

### 7. Merchant skin variety & placement
- **Skins (directly portable):** HiredMerchant pool `{5030000,5030001,5030002,5030004,5030008,5030010}`
  uniform-random per spawn (`ArtificialFreeMarket:56,126`; `5030012` tiki torch excluded). Bot player-shop
  permits weighted `{5140000 w85, 5140001-4/6 w3}` (~85/15, `BotCustomization.getRandomStorePermitId:101`).
- **Placement:** a fully hardcoded per-region `Point` table walked in order (`FMShopInfoManager:18-74`,
  henesys 24 / ludi 28 / perion 26 / elnath 27 spots), overlap avoided by hand-authoring, not runtime
  checks. Only randomness is a skip-chance for empty-slot realism. `modifyXCoordVariance` (±5px) is dead
  code. Assumes populating MANY concurrent stalls — overkill for one bot.

## Steal-list for our economy update (ranked)

**STATUS 2026-07-04: items 1, 3, 4, 5, 6, 8 LANDED** (commits on dev-economy; see
[[project_living_economy_build]] DONE entries). Still open: #2 buy-comparison (build fresh — neither
fork has true best-of-N), #7 haggle (S4 `BotTradeNegotiator`).


Maps onto our code: `BotShoutTradeManager` (shout emit/match/trade SM), `BotFreeMarketManager`
(browse/stand/stall), a future `BotTradeNegotiator` (S4 haggle), `BotPersonality` (traits).

1. **[HIGH] Delayed, considered buying — kill "instant".** Our `tryMatchHeardShout`/`maybeBargainBuy`
   act the same tick a match is seen. Steal their **2-6s deliberation delay** before inviting/replying
   (a per-entry "decide at" timestamp banked when a candidate is first seen), and gate the actual buy on
   a re-check. Directly answers the owner's "bot buys a shout too quickly". Their tick-wheel is the
   pattern; we don't need a full FSM, just a delay + re-validate.
2. **[HIGH] Buy COMPARISON (build fresh — neither fork has it).** Owner wants "compare with other
   options". Neither SoloMapling model compares across stalls; port their `FMBot` **probabilistic ratio
   ladder** shape (accept-prob as a function of ask/ourValue) for "considered" acceptance, but the actual
   cheapest-of-N (hold candidates across a browse, pick the best deal before committing) must be built
   new. Feed it our SSOT `equipBuyCeilingMeso`/consensus, not their pricing.
3. **[HIGH-easy] Merchant SKIN variety.** We open every stall with fixed `5030000`. Steal the
   small-pool-weighted-random-per-open pattern → pick from `{5030000,5030001,5030002,5030004,5030008,
   5030010}` (verify each exists in our WZ Commodity/permit data first). Keyed off `botId` for per-bot
   coherence (like `humanizeAsk` styles), or rerolled per open. Cheap, high visual payoff.
4. **[MED] Trade human-pacing beats + robust cancel.** Our `driveTrade` already locks-after-verify; add
   a ~2s pause-with-chat-line before finalize, and confirm our outer walk-away/logout abort matches their
   single `verifyTradePartner` circuit-breaker (we have deadline + getTrade()==null handling; audit it's
   as robust). Skip their meso-tolerance ladder (we're exact-ask) and permanent block-list (too
   aggressive for a companion with few partners).
5. **[MED] Browse dwell + ±50px approach jitter + walk pity timer.** We just added random stand + 12-30s
   reposition; add their **±50px approach jitter** (don't walk to the exact stall pixel) and a per-stall
   **~1s dwell** so browsing isn't a single instant tick that reads+buys everything. The walk-vs-click
   pity timer guarantees eventual visible movement.
6. **[MED] Visitor presence while browsing** (owner asked "browsing should show up in merchant"). Port
   `addVisitor`/`removeVisitor` around a stall browse so the bot occupies a visible 3-slot visitor
   slot and others see a customer. TRADEOFF: only 3 slots — a companion shouldn't hog them from real
   buyers; gate to short dwells and release promptly. Pairs with #5's dwell.
7. **[MED, S4] Haggle/counter (`BotTradeNegotiator`).** Owner wants haggle+cancel. Steal the
   `ShopOfferSystem` shape: parse human price in shop chat → 2-6s reply delay → `HaggleSession`
   (attempt-cap 3, 60s expiry) → ACCEPT/COUNTER/DECLINE with our WTS at the `OfferEvaluator` seam →
   3-strikes decline. Skip the 60/40 PRESENT/AFK + 5-30min AFK-accept (assumes a shopkeeper fielding
   many strangers over time). This is the S4 negotiator slice.
8. **[LOW-MED] Chatter pools.** Externalize our shout/trade lines to small per-situation pools (5-10
   variants — not their 100+, no crowd illusion needed); optional filler-token/occasional-uppercase for
   shout variety. Natural `BotPersonality` field later.

**Do NOT steal (ambient-population-specific):** the hardcoded per-region placement coordinate table
(we place on the live floor strip already); the visit-queue deck-cut/segment walk (many-shop sessions);
60/40 AFK split + 5-30min AFK-accept; the crowd-staggering platform-shuffle idle loops. And never their
pricing — every value plugs into our SSOT valuation.

Related: [[project_solomapling_audit]] (framework-level verdict), [[project_living_economy_build]]
(our build ledger + S4 pending), [[kb_bot_break_and_session_state_machine]] (where market trips ride).
