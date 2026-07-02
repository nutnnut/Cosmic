# Living Bot Economy — Design of Record

Status: **DESIGN — build-ready** (2026-07-02, rev 2 same day: consensus layer added per owner
direction — pure per-bot beliefs deemed too unstable; see §4, §16.2). Source brief:
`tmp/nut_economydesign_opusreview.md` (this doc realizes it; where it was internally conflicting,
§16 records the resolution). `docs/bot/economy-design.md` remains the *math reference*
(reproduction DP, LP-duality equilibrium theory, Fish Spear calibration) — **this file wins on any
conflict**, notably: prices are set by bots (the central consensus is a damped *statistic*, not a
price setter), no anti-drain caps, anchor is not a clamp.

Grounding: all `file:line` refs below were verified 2026-07-02 (they drift; treat as anchors, not
gospel). Substrate audit: four exploration passes over valuation, trade/merchant, chat/traits/
lifecycle, and nav/FM (this doc's §2, §13 carry their findings).

---

## 0. Philosophy

Prices are **discovered by bots trading, not computed by the server.** Each bot privately values
goods (use-value), privately believes what the market pays (exchange-value), and acts — farms,
scrolls, lists, shouts, haggles, buys. Market price is whatever those actions clear at. Inflation,
gluts, shortages, and player dumps are absorbed the same way real markets absorb them: individual
agents re-learn and re-price. No intervention, no authored price tables, no rate caps.

**The only two scaffolds** (both data-derived behaviors, not typed-in numbers):

1. **Farming-cost anchor** — `BotFarmingCostModel.rarityMeso` (BotFarmingCostModel.java:51),
   evaluated *per bot* (producer = the asking bot). Used as (a) each bot's **prior belief** when it
   has never observed a price and (b) the seller's cost-plus opening ask. It is **neither a floor
   nor a ceiling**: prices may transiently clear below it (glut) or sit far above it (scarcity).
   The *durable* floor emerges without a clamp: a bot whose believed price for an item drops below
   *its own* farming cost stops farming it for sale (§7), supply exits, price recovers. The
   cheapest producer keeps undercutting until price ≈ frontier cost — the "cheapest capable
   producer sets the floor" property is **discovered by competition, not computed** (we deliberately
   do NOT build the economy-design.md §4.1 frontier-producer machinery).
2. **Self-tuning adaptation** — belief/ask updates are confidence-weighted, gap-proportional steps
   (§4/§5): ignorant bots move fast, informed bots are damped. The step size is a *structure*
   (Bayesian-style weighting), not a tuned constant.

**Legitimate outside options (not caps).** NPC shops are part of the legal game world and bound any
real player's behavior; bots inherit the same bounds *rationally*, not by rule:
- a bot never sells to a player below NPC sell-back (`ItemInformationProvider.getPrice`, full WZ
  price — **no /2 on this server**, Shop.java:285), because the NPC is a standing better bid;
- a bot never buys from a player above the NPC shop ask for NPC-stocked goods (DB `shopitems`),
  because the NPC is a standing better offer.
These fall out of "compare against all known offers" — the NPC is just one more known offer.

**Litmus test for any new constant:** does it encode a decision the system should make itself?
If yes, derive it. Resource bounds (cache sizes, LRU caps, max negotiation rounds) are allowed;
price-policy numbers are not. §13 lists every existing placeholder this build retires.

---

## 1. Architecture at a glance

Agent-based with a shared stabilizer. **No global order book and no price setter** — bots set
their own asks/bids — but a central per-item **consensus statistic** (damped, robust, computed
from the ledger) anchors everyone's "rough idea" of what things cost (§4). Bots never read the
ledger or the raw consensus as an oracle: decision code sees only their own book, which blends
private observations with a *noisy, lagged sample* of consensus.

```
              (per bot)                                   (shared)
  ┌─────────────────────────────────┐        ┌────────────────────────────────┐
  │ BotMarketBook                   │        │ BotMarketLedger (MySQL)        │
  │  perception = private obs       │◄─obs───│  append-only market events      │
  │  ⊕ noisy/lagged consensus sample│  write │  + faucet/sink counters         │
  └───────────────┬─────────────────┘───────►└──────────────┬─────────────────┘
                  │ estimates          ▲ noisy sample        │ robust recency stats
  ┌───────────────▼─────────────────┐  │     ┌──────────────▼─────────────────┐
  │ BotMarketValuer                 │  └─────│ BotMarketConsensus              │
  │  WTP / reservation / upgrade    │        │  damped per-item statistic      │
  │  menu — glue over SSOT valuers  │        │  (periodic sweep, anchor prior) │
  └───────────────┬─────────────────┘        └────────────────────────────────┘
                  │ acts                     ┌────────────────────────────────┐
  ┌───────────────▼─────────────────┐        │ Execution (all legal paths)     │
  │ BotPersonality economic traits  │───────►│  BotFreeMarketManager · Trade   │
  └─────────────────────────────────┘        │  negotiator · shout bus · NPCs  │
                                             └────────────────────────────────┘
```

New classes (all `server.bots.*`): `BotMarketMath` (pure), `BotMarketBook` + `BotMarketStore`
(DAO), `BotMarketLedger` (DAO + counters), `BotMarketConsensus` (shared statistic + sweep),
`BotMarketValuer`, `BotMarketGrammar` (pure parser), `BotMarketShoutBus`, `BotTradeNegotiator`,
`BotFreeMarketManager`, `BotAssetView`. One migration: `db/tables/031-bot-market.sql` (§12).

---

## 2. Reused SSOT surface (extend these; do not parallel)

| Concern | SSOT (verified location) |
|---|---|
| Job-agnostic equip stat worth ("market axis") | `BotScrollManager.marketStatValue` :997 |
| Job-specific offense / keep-value / headroom | `offenseValue` :974 / `equipValue` :1024 / `potentialValue` :1128 |
| Stat-score → meso bridge (convex repro curve) | `BotScrollValuer.reproductionValue` :51 |
| Scroll/stop decision DP (takes meso costs) | `BotScrollPlanner.planBest` :131 (+ dormant profit pass, `SCROLL_FOR_PROFIT_ENABLED` :114) |
| Farming-cost anchor (per-bot) | `BotFarmingCostModel.rarityMeso` :51 (+ inputs via BotScrollManager.farmingCostMeso :1278) |
| Accuracy→effective-DPS, drop EV | `BotGrindAdvisor.accuracyHitFactor` :498, `expectedAcquireGain` :929 (shared by drops/maker/gacha) |
| What to wear | `BotEquipManager.autoEquip` :107 (Pareto DP; reqs are feasibility gates) |
| Sell/keep shelf | `BotInventoryManager.classifyBagUse` :2014 / `classifyBagEquips` :2754 / `useShelfKeepValue` :2215 |
| Trade engine + bot driving | `server.Trade` (+ `BotInventoryManager.tickManualTrade` :306, `startTradeTransfer` :452, `tickTrade` :731) |
| Trade-tick pause (already live) | `BotManager` :3558 (physics-only short-circuit), :5436 (loot gate) |
| Merchant stalls (headless-drivable) | `HiredMerchant.addItem` :560 / `.buy` :285 / `.getItems` :544; handler effects at PlayerInteractionHandler :266-271, :386-391 |
| Stall proceeds / leftovers | `Character.merchantmeso` + `FredrickProcessor.fredrickRetrieveItems` :275 |
| Price tokens in chat | `BotChatManager.MESO_AMOUNT_TOKEN` :469; item-name resolution from the trade/give commands :463-627 |
| Owner choice overlay | `BotPrompt.showOptions` :40 (labels must be verbatim reply tokens) |
| Traits | `BotPersonality` record (bot_config blob; add fields + serialize/parse keys — no schema change) |
| Errand pattern | `DetourErrand` (BotAutopilotManager :623), clone target `BotGachaponManager`; travel cost `BotTravelCost.floodSeconds` :46 |
| Chat sinks + ASCII | `BotManager.sanitizeChat` :6221 through `botSay`/`botReply`/`botSayParty`; ~5s queue spacing (BotChatManager :1511) |
| Meso sinks already live | trade tax `Trade.getFee` :93 (also merchant buys, HiredMerchant.java:305), NPC buys, storage fees, fares |

---

## 3. Value model — one exchange axis (meso), unchanged combat axes

Today four value scales coexist (stat-score, meso, optimizer DPS-int, offense×hit-factor). We do
**not** merge them; we declare their roles:

- **Meso is the exchange axis.** Everything bought/sold/listed is compared in meso.
- **Stat-score stays the combat/quality axis.** `reproductionValue` is the existing bridge
  (stat-score in → meso out); the optimizer's DPS and the grind advisor's hit-factor stay
  *use-value inputs* that get converted to meso per bot (below).

**Price identity.** Beliefs and listings key on `priceKey = (itemId, qualityBand)`:
- non-equips: band 0.
- equips: band = round(scoreDelta / gain), where scoreDelta = `marketStatValue(equip)` −
  `marketStatValue(clean base)` and gain = the marketStatValue delta of one success of the most
  common applicable scroll (WZ-derived). So band ≈ "how many average scroll successes above clean".
  This keys prices to quality without a new scale, is job-magnitude-safe (delta over own clean
  base), and makes §8.1.4 free: badly-scrolled gear lands in low bands priced between clean and
  well-scrolled by the convex curve + observed trades. Precise Equip stats still ride along in the
  listing itself (`PlayerShopItem` holds the real item). Bands are **points on the item's
  reproduction curve, not independent price cells** — quotes for unobserved bands and substitute
  items derive from observed ones through the structured priors in §4.

**Use-value (what it's worth TO ME), all reused:**
- Equip: Δ(optimizer score / effective DPS) from `autoEquip`'s comparison machinery +
  `accuracyHitFactor` for unlocks (the Fish Spear case is the regression fixture: WTP must reflect
  the ~10× effective-DPS swing, NOT the raw stat line).
- Ammo (§8.2): a set functionally grants ATT → Δeffective-DPS vs best owned set, same conversion.
  Extends the existing per-SET valuation (useShelfKeepValue :2219); **retires `AMMO_CEILING_*`**.
- Buff (§8.3): Δincome during duration minus price; trait-gated (`buffSpend`); bossing demand is a
  stub (no bossing exists — do not build it).

**DPS→meso conversion (derived, no constant):** ΔDPS → Δ(exp/hr, meso/hr) via the grind advisor's
own map model, then meso-WTP = Δincome/hr × relevance horizon (hours until the item is outgrown,
from level curve vs req) with the trait `mesoFocus` weighting the exp term. The bot's own recent
observed meso/hr (see §13-P3) is the same rate used to price its *time*.

**The two decision numbers every action uses:**
- `WTP(item)` = min(use-value-in-meso, best-alternative bound). Best-alternative = the
  `UpgradeMenu`: rank all known acquisition options (stall listings seen, live shouts, NPC shop,
  own scroll plays) by Δscore/price at *believed* prices; a bot with cheap upgrades left on the
  menu won't overpay for this one (economy-design.md §3, now priced by beliefs). Whaling emerges
  from a depleted menu.
- `reservation(item)` = what I'd rather keep it than sell for = max(NPC sell-back, use-value if I
  still use it, re-acquisition cost if I'd need it back). Sell iff price ≥ reservation.

**§8.1.1 resolution (higher-req items):** the optimizer treats requirements as hard feasibility
gates, not graded penalties (BotEquipManager validateReqs :1050) — that is correct for *wearing*.
The "worth less" property emerges on the *demand side*: fewer bots can wear the high-req variant →
fewer bids → lower clearing price. No rule to add; verify in the sim (§14 S1).

---

## 4. Beliefs — two layers: shared consensus + private perception

Pure per-bot observation was judged too unstable for a sparse market (few bots, rare trades →
private estimates random-walk). The model is therefore **a shared consensus everyone roughly
knows, perceived noisily, corrected by personal experience** — which is also the more human model:
real players absorb ambient price knowledge (guildmates, forums) they didn't personally witness.

**Layer 1 — consensus (shared; the stabilizer).** `BotMarketConsensus`: per `priceKey`, a damped
public statistic of what the item actually trades at. Computed by a periodic off-hot-path sweep
(DECIDE_POOL / timer, minutes-scale) over the ledger's recent events: **recency-weighted median**
of arms-length clearing prices (trades + stall sales; listing asks as weak evidence when no
clearings exist; farming-cost/repro anchor as the empty-market prior). The consensus *moves*
gap-proportionally toward that median, damped by decayed event volume — one odd trade barely
budges a liquid item but meaningfully informs an illiquid one, and the median makes it robust to
gift-price outliers. This IS the brief §3 "damped move toward the balance of supply vs demand",
realized as a per-item statistic rather than a price setter: it never places orders, never clamps
anyone; bots remain the only price-setting agents.

**Layer 2 — perception (per bot; the humanizer).** `BotMarketBook` never exposes consensus raw.
A bot's working estimate blends two inputs, weighted by its own experience:
- **Noisy, lagged consensus sample** — refreshed only at market touchpoints (FM session, trade,
  shout heard), not continuously. Noise is *stable per (bot, priceKey, day-bucket)* — seeded, not
  re-rolled per read — so a bot holds a consistent slightly-wrong idea ("gloves are ~900k i
  think") for a session and drifts, instead of jittering between ticks. Noise band scales with
  how plugged-in the bot is (derived from `sociability`/`chattiness` — social bots are better
  informed; loners are wronger). 
- **Private observations** — weighted: own completed trades/stall sales 1.0 (clearing prices),
  asks physically browsed on the current map 0.4 (bound from above; never
  `Channel.getHiredMerchants()` world-scans), shouts heard 0.15, gossip lines (phase 1.5,
  co-located ASCII small talk) 0.15, own listing outcomes 0.3 (sold-fast / expired-unsold,
  directional).

**One update rule for both layers (`BotMarketMath.updateBelief`, pure — two owners, one formula):**
```
step     = (evidence − estimate) × w / (w + confidence)
estimate += step;  confidence += w
```
For the private layer, `w` = source weight above and `evidence` = the observed price; the blend
with the consensus sample is the same formula with `w` = a prior weight that shrinks as private
confidence grows — a bot that just sold five gloves trusts its own number; a bot that never
touched gloves runs on its noisy consensus idea. Confidence **decays per elapsed
observation-gap** (half-life ≈ a few × that key's median inter-event gap, wall-clock-floored):
liquid items forget fast and track the market; illiquid items remember. Self-normalizing; no
global rate constant.

**Structured priors — related prices stay coherent (cross-item information flow).** Price keys
are NOT independent cells; most keys will never trade, and a naive per-key table would let a
10-STR hat trade at 1m while its 11-STR sibling rots at a 300k anchor. Any quote (consensus or
perception) for a key with thin direct evidence is **generated from the value structure and
corrected by observations**, falling back through three relations:
1. **Same item, other quality bands — the reproduction curve.** `reproductionValue` already gives
   the *shape* of value across bands (convex, scroll-prices-in). Maintain a per-itemId
   **calibration factor** = weighted avg(observed band price ÷ curve(band)) over that item's
   observed bands; quote unobserved bands as factor × curve(band). A trade at ANY band moves the
   whole line's curve, so the +11 band quotes ≈ +10's price *plus the marginal expected scroll
   cost of the 11th point* (convexity ⇒ never less than the observed band).
2. **Same slot, substitute items — implied stat price.** Maintain per-slot implied
   meso-per-marketStatValue-point from items that DID trade; a never-traded item quotes from its
   own stat score × that implied rate (what a human does: "10-STR hats go 1m, so this one's a bit
   more"). The UpgradeMenu then enforces it behaviorally: an underpriced substitute tops every
   buyer's Δscore/price ranking, sells instantly, and the sold-fast/failed-fill signals close the
   gap — structure gives the fast prior, arbitrage gives the correction.
3. **Anchor** (own farming/repro cost, NPC band) when even comparables are empty.
The consensus sweep maintains the two derived aggregates (per-itemId calibration, per-slot
implied stat price) alongside per-key rows — a few cheap regressions over recent events, off the
hot path. Scrolls couple in both directions through the same structure: scroll consensus prices
are *inputs* to every reproduction curve (a 60% ATT scroll spike re-quotes all scrolled-ATT gear
upward and lowers scrolling EV → less finished-gear supply → §7 re-equilibrates), and scroll WTP
is the curve's marginal effect (the planner DP's shadow price) — the joint equilibrium of
economy-design.md, realized as information flow instead of a solver.

Zero information anywhere: consensus falls back to anchors (§0), a bot with neither consensus nor
observations quotes its own farming/repro cost — heterogeneous by construction. Human fuzz on
top: prices a bot *speaks or reasons with* round to 2 significant figures; stored values stay
exact.

Persistence (§12): `bot_market_belief` (private layer, LRU-capped ~512 keys/bot, flushed with
other bot state) + `bot_market_consensus` (shared, one row per active key, written by the sweep).
Boot: reload both; no ledger replay needed — prices survive restarts.

---

## 5. Pricing — individual behavior on the consensus substrate

**Sellers (ask formation).** Opening ask = perceived price (§4 blend) × (1 + margin), margin from
`haggleStance` trait and confidence (low confidence → wider margin, room to learn). No perception
at all → cost-plus: own farming/reproduction cost × (1 + margin). Floor = reservation (§3).
**Repricing happens when the bot services its stall/listings** (next FM session, §8): unsold →
step ask down toward best evidence — recent sale prices, and when competing stalls are visible,
**just under the cheapest competing ask** (`undercutTarget`: you must cross below it to win the
buyer; a silly competitor never drags you up; reservation still floors the step) — step
gap-proportional and confidence-damped exactly like §4; sold-out-fast → next batch asks higher.
This unsold-pressure repricing is the load-bearing negative feedback: without it a market can
freeze in a structural no-trade zone (every ask a margin above every WTP) — sim-verified. Because repricing
rides each bot's own personality schedule, bots **never reprice in the same tick** — the brief's
damping/staggering requirement falls out of the living-server layer for free.

**Buyers.** See ask ≤ WTP → buy (stall: straight `merchant.buy`; shout: open trade). Ask within a
trait-scaled stretch band above WTP → attempt to haggle (§8.5). Above that → walk away, remember
the ask (observation). Repeated failure to fill a want raises the belief (scarcity evidence) but
**WTP stays bounded by use-value** — unmet demand raises prices only up to what the item is
actually worth to somebody; that cap is the negative feedback that prevents runaway (the brief §3).

**Stability rationale** (why this converges instead of oscillating): demand is price-elastic (WTP
caps), supply is price-responsive with lag (§7 steering), updates are damped by confidence,
repricing is asynchronous across bots — and the consensus layer (§4) bounds collective error:
individual perceptions orbit a slow, robust, volume-damped statistic instead of chasing each
other, so a sparse market cannot random-walk and a single outlier trade cannot cascade.
economy-design.md's LP-duality section is the formal story (individual DP best-responses ≈
tâtonnement on the dual); consensus is that tâtonnement's damped step, perception noise keeps it
lively.

**Inflation is a separate axis, measured not managed.** Faucet (mob meso × rates) vs sink (trade
tax :93, NPC purchases, scroll fails/booms, fares, storage fees, recharge) counters accumulate in
`BotMarketLedger` and surface on the web API (§11). No control loop touches relative prices;
nominal drift is absorbed by beliefs re-learning (recency weighting). If the operator wants to
steer absolute price level they tune real sinks/faucets (server rates), never bot pricing.

---

## 6. Demand (who wants what, and how much)

- The **UpgradeMenu** (§3) is the demand engine: every bot periodically (market session cadence)
  re-ranks its known acquisition options by Δscore/price at believed prices and forms concrete
  *wants* — "buy a Fish Spear up to 240k", "buy 60% glove ATT up to 800k each while my scroll DP
  says EV-positive". Wants drive: stall browsing intent, `B>` shouts, responses to `S>` shouts.
- Scroll demand comes from `BotScrollPlanner.planBest` fed believed scroll prices (§13 retires the
  0.2 margin & 0.9 fraction): DP-positive plays generate scroll *wants*; the DP's own convexity
  makes rich bots chase deep upgrades (whale premiums) while poor bots buy cheap ratio.
- A bot with money and a depleted menu rationally overpays for marginal stat; a poor bot with a
  fat menu underbids everything. **No wealth curve is coded.**
- The bot's wallet is the only spend limit (brief §9): spending down to zero on a genuinely
  DPS-positive purchase is correct behavior, not a bug. No per-player caps, no rate limits, no
  bid-ask spread guardrails (deliberate deviation from economy-design.md §8 — recorded §16).

---

## 7. Supply (what gets produced and listed)

- **Grind steering:** add a market term to grind-map scoring — expected *sale* value/hr of surplus
  drops = Σ dropRate × believedPrice(item) for items the bot would list rather than use, weighted
  by trait `mesoFocus` against the exp term. This is a weighting on `BotGrindAdvisor`'s existing
  output (the brief §5), not a new mode. Effect: over-supplied items (belief < own farm cost) stop
  being farmed for sale; scarce expensive items attract farmers. This closes the §0 emergent floor.
- **Scrolling is the supply atom too:** flip `SCROLL_FOR_PROFIT_ENABLED` (BotScrollPlanner :114)
  once prices are endogenous — the dormant profit pass already values "scroll to sell" with a
  resale haircut; finished gear flows to stalls. Scroll fails/booms remain the organic item sink.
- **Listing pipeline:** the shelf system stays the *what-to-liquidate* SSOT. Extend
  `classifyBagUse`/`classifyBagEquips` decision: for each sellable, **list on stall iff
  believedPrice × (1 − tradeTax) − listing time cost > NPC sell-back**, else NPC-sell as today.
  Worth-less-first ordering already exists; market pricing just re-sorts the shelf.
- Liquidity preference (brief §7 "clean glove is liquid") emerges: high-menu-frequency items get
  observed/traded more → confident beliefs → tighter spreads → bots prefer stocking them. No item
  list.

---

## 8. Execution

### 8.1 FM errand (`BotFreeMarketManager`, clone of `BotGachaponManager`)
- New `DetourErrand` registered in `DETOUR_ERRANDS` (BotAutopilotManager :640) after gacha;
  overrides `yieldForResupply` (ferry-precedent escape valve — permit/bag space, :634/:655).
- **Nav: errand-encapsulated, zero `BotWorldGraph` changes** (explorer verdict): `tickTravel`
  routes to a FM-carrying town (~24 towns have the `market00` portal; entrance↔rooms are already
  normal graph edges); the errand walks the scripted portal via the existing
  `walkToPortalAndEnter` → `portal.enterPortal` path (bots CAN run portal JS,
  BotTravelManager :405/:437); exit via entrance `out00` (script restores `FREE_MARKET` saved
  location). Room hop = normal portals.
- **Trigger** (when): market sessions layer into the existing break/chill system — a town-break
  upgrade: roll FM instead of plain rest-town when the bot has (a) shelf surplus worth listing
  (§7 test), (b) unfilled wants it believes FM can fill, or (c) a stall needing service
  (restock/reprice/expiry). Chill-session bots may spend the whole session there. Cost/benefit
  gate mirrors gacha's netScore with `BotTravelCost.floodSeconds`, priced at the bot's own meso/hr
  (never a constant/hr). Runs identically with zero humans online (§2 of the brief; substrate
  verified: bot ticks are human-independent, `RespawnTask`/`MapleMap.respawn` count bots).
- In-FM session loop: browse stalls on current room map (observe asks → beliefs), buy wants
  (`merchant.buy` direct, tax applies), service own stall, shout (§8.4), gossip, idle/chair (§15).

### 8.2 Stall lifecycle (bot-owned `HiredMerchant`)
- Open: rooms only (`GameConstants.isMerchantLocked` blocks the entrance). Reuse the domain object
  headlessly (BotClient packets are no-ops); replicate the three handler-side effects — create
  registration (PlayerInteractionHandler :266-271), publish (:386-391), post-buy visitor broadcast
  (:759) — by **extracting a `HiredMerchant.openFor(owner,...)` / `publish()` helper** the handler
  also calls (rule 1: extract, don't copy). Verify thread-safety of concurrent `buy` under direct
  calls; marshal onto the channel/map executor if needed.
- Permit 5030000 (CASH tab, not consumed on open): managed bots acquire it through the same
  NX/ticket abstraction gacha uses. One permit lasts a career (matches live server behavior for
  players; noted, not "fixed").
- Stock at asks from §5; cap 16 slots (HiredMerchant :560). Bot may **leave the stall open and go
  grind** (that's the point of hired merchants); proceeds accrue to `MerchantMesos` (separate
  wallet — §10 asset view). Next session: collect/restock/reprice; handle the ~24h `forceClose` →
  retrieve leftovers + meso via `FredrickProcessor.fredrickRetrieveItems` (Fredrick stands in the
  FM entrance — same errand).
- Sale events (server-side hook in `HiredMerchant.buy`) append to the ledger and, when the owner
  bot is online, feed its beliefs; offline sales are learned at collection time (humanlike lag).
- Player stalls and bot stalls are indistinguishable to a browsing bot (brief §7) — it just reads
  `getItems()` of whatever merchants stand on the map. Buying from `PlayerShop` (owner-present
  counter shops needing visitor registration, PlayerShop.buy :262) is **deferred**; hired
  merchants are the v1 surface.

### 8.3 Buying, selling, and players
- Player sells to a bot: shout `S> ...` (grammar below) or open a trade and stage the item; the
  bot evaluates vs WTP exactly as with bots. Player buys from a bot: stall purchase (vanilla UI)
  or answer the bot's `S>` shout with a trade.
- Bots never distinguish counterparty species anywhere in valuation/negotiation. Owner-online
  niceties (BotPrompt hints, LLM flavor) are additive presentation only.

### 8.4 Shout grammar (Phase 1, exact/structured) + the shout bus
Wire format (US-ASCII, one line, greppable):
```
S> <item> [x<qty>] <price>     sell offer        S> brown work glove 1m
B> <item> [x<qty>] <price>     buy offer         B> ilbis 300k
PC> <item>                     price check       PC> fish spear
```
`<item>` = exact item name (case-insensitive; reuse the name resolution the trade/give commands
already use) or `#<itemId>`. `<price>` = existing `MESO_AMOUNT_TOKEN` (`1m`, `900k`, `1500000`).
Parser = pure `BotMarketGrammar` (unit-testable; also reused inside trade chat, §8.5).

**Transport — the one structural addition chat needs:** bots cannot hear chat packets (bot `botSay`
emits packets only, and `BotManager.handleChat` is invoked solely by human packet handlers).
Therefore a `BotMarketShoutBus`: a map-scoped, in-memory feed of parsed shouts
`{speakerId, mapId, offer, expiresAt}`.
- A **player** shout enters via the existing `GeneralChatHandler → BotManager.handleChat` path: a
  new grammar branch parses it and publishes to the bus (plus normal chat display).
- A **bot** shout publishes to the bus at `botSay` time (same parsed object); the spoken line is
  the human-visible narration of the same event. One grammar, one bus, both species symmetric.
- Subscribers: bots on that map poll the bus on their AI tick (cheap; bounded). A matched offer
  (want ∩ `S>`, or stock ∩ `B>`, price within reach) → approach + trade invite after a humanlike
  jittered delay; also always → belief observation. Shouts expire in minutes; the bus is not an
  order book (no resting depth, no matching engine — deliberately: matching happens socially).
Cadence: shouts ride the existing ~5s chat queue + a personality-jittered per-bot shout cooldown
(minutes); mostly during FM sessions, occasionally from the grind map (trait `chattiness`).

### 8.5 Trade negotiation (`BotTradeNegotiator`)
State machine over the existing `Trade` (no parallel engine). All mutation settles **pre-lock**
(completion is a two-sided lock handshake, Trade :299-305 — no counter-offers after lock).
```
seller flow (post S>-match or stall-adjacent ask):
  INVITE/ACCEPT → STAGE (bot adds item, chats "1m pls" variant)
  → READ OFFER: partner staged items (trade.getPartner().getItems(), public) +
                staged meso (NEW small getter, §10) + price tokens in trade chat (grammar)
  → DECIDE:  offer ≥ ask                → accept & complete
             offer ≥ reservation        → trait roll: accept | counter(step toward midpoint)
             offer < reservation        → counter near ask; after R rounds → polite decline/cancel
             rude keywords / timeout    → cancel ("sry no nego" variants)
buyer flow symmetric (stages meso, reads seller's staged item).
```
- `R` (max rounds ≤ 3), counter step size, accept slack: all scaled by `haggleStance` (+ a little
  `riskTolerance` noise) — some bots are pushovers, some are firm, a few are annoying.
- Every transition speaks from canned ASCII pools (like `BREAK_MSGS`); when the counterparty is a
  human AND the LLM is enabled, `BotLlmReplyManager` may garnish the *wording* (never the price
  decision), via a `[Trade]` situation block. Bot↔bot negotiations never invoke the LLM.
- Owner online → `BotPrompt.showOptions` surfaces the legal next moves as verbatim tokens
  (`accept 900k` / `counter 950k` / `decline`) so the owner can steer; silence = bot decides.
- Farming stays paused throughout via the existing `getTrade() != null` short-circuit
  (BotManager :3558) — already live; §10 extends the same gate to stall-service work.

### 8.6 Phase 2 (later, additive): fuzzy matching — typo/partial item resolution (token n-gram
index over item names, optionally LLM-assisted disambiguation), free-text offers ("S> 10 watk
brown glove 100m" → closest inventory item). Phase 1 grammar remains the debuggable fallback.

---

## 9. Traits (extend `BotPersonality`, the bot_config blob — no schema change)

New serialized fields (all 0..1, rolled at generation like existing fields, defaults mid):
- `haggleStance` — negotiation firmness + counter step + decline threshold (§8.5).
- `mesoFocus` — meso-vs-exp grind weighting (§7) + shout/stall frequency appetite.
- `buffSpend` — premium-buff tolerance (§3); rich × high-trait bots buy buffs, others never.
- `collectorTaste` — chair/collectible budget share (§15).
Reused as-is: `riskTolerance` (gamble-scroll appetite, negotiation noise), `chattiness` (shout &
gossip cadence), `sociability` (initiating trades/gossip), schedule fields (market cadence).
Derived, not stored: stall propensity = f(mesoFocus, sociability); price-speech fuzz (§4) is
universal. Everywhere the design says "depends on trait" it reads one of these fields.

---

## 10. Correctness invariants (anti-bug, not economics)

1. **Farming pause during trade — DONE, keep + extend.** Verified live: full tick short-circuit
   at BotManager :3558-3567 and loot gate :5436-5440. Extend both tests to a new transient
   `BotEntry.marketBusy` flag set while staging stall stock / executing merchant buys (a
   `HiredMerchant` is not a `Trade`, so today's gate wouldn't cover stall service).
   *Recommended root-cause hardening (shared code, small):* `Trade.completeTrade` ignores
   `addFromDrop`'s boolean (Trade.java:129) — on false, drop the item to the floor or roll back
   instead of silently voiding. Negotiation multiplies trade volume; fix the loss path, players
   benefit too.
2. **Staged meso must be readable mid-trade.** `Trade.getMeso()` is private (:181);
   `getExchangeMesos()` is 0 pre-completion. Add a public read-only `getStagedMeso()` — the
   minimal expose-for-bots change (rule 2); without it case "player silently puts in 900k" is
   undecidable.
3. **US-ASCII chat only.** All grammar tokens, line pools, and prompt labels ASCII;
   `sanitizeChat` (BotManager :6221) remains the chokepoint. `yellowMessage` is not covered —
   keep those ASCII at source.
4. **Reserved/listed/stored assets stay visible: `BotAssetView`.** Today storage, stall stock,
   `MerchantMesos`, and trade escrow are invisible to every bot decision (grep-verified zero
   references). New read-only aggregator enumerating `{EQUIPPED, BAGS, STALL, STORAGE, FREDRICK,
   TRADE_ESCROW}` with availability tags; wealth = liquid meso + MerchantMesos + Σ believed value.
   Rules: valuation/WTP/menu see everything; *wear/consume/stage* paths see only in-hand items;
   nothing is double-counted (an item is in exactly one location). Wire into shelf decisions,
   resupply (don't re-buy what's stored), and net-worth reporting.
5. **True ownership only.** Every transfer moves real persisted items/meso through
   Trade/HiredMerchant/NPC/Fredrick paths. The ledger *records*; it never *creates*. Bot wallets
   are their real `getMeso()` (+`MerchantMesos` via view); no minting, no backend transfers.
6. **No omniscience in decisions.** Channel-wide merchant registries, raw ledger reads, raw
   consensus reads, and other bots' books are all off-limits to bot decision code (observability
   endpoints only). The shared consensus reaches behavior **only** through the noisy, lagged,
   touchpoint-sampled perception in `BotMarketBook` (§4). Enforced by code review convention:
   `BotMarketBook` is the only price source for behavior.

Deliberately absent (brief §9): purchase caps, per-player rate limits, bid-ask guard spreads,
anti-drain ceilings. A bot that empties its wallet on a genuinely good deal is working as
intended; a player farming a bot's mispriced belief IS price discovery.

---

## 11. Observability (extend the existing web SSOT)

New routes on `BotWorldGraphWebServer` (update `docs/bot/web-endpoints.md` when they land —
rule 8):
- `/api/market` — recent ledger events, per-item stats (consensus + volume, last clearing price,
  rolling median, listing count, spread of current asks), faucet/sink counters (absolute meso
  created/destroyed by category).
- `/api/market/bot?name=` — that bot's beliefs (est/conf/age), wants + WTPs, active listings,
  stall state, MerchantMesos, negotiation log tail. This is the debugging surface for "why did it
  pay that".
Plus: ledger rows are the time-series for convergence verification (§14), and `@botpop`-style ops
verbs can come later. Live behavior questions go through these endpoints, not log scraping.

---

## 12. Persistence (`db/tables/031-bot-market.sql` + Liquibase changelog entry)

```sql
CREATE TABLE bot_market_event (        -- append-only tape; observability + boot stats
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  kind TINYINT NOT NULL,               -- TRADE, STALL_SALE, LIST, DELIST, EXPIRE, SHOUT,
                                       -- NPC_SELL, NPC_BUY, SINK, FAUCET
  item_id INT NOT NULL, quality SMALLINT NOT NULL DEFAULT 0, qty INT NOT NULL,
  unit_price BIGINT NOT NULL,          -- meso
  seller_id INT NULL, buyer_id INT NULL, map_id INT NULL,
  KEY k_item (item_id, quality, at)
);
CREATE TABLE bot_market_belief (       -- per-bot private books
  bot_char_id INT NOT NULL,
  price_key BIGINT NOT NULL,           -- itemId*256 + qualityBand
  estimate BIGINT NOT NULL, confidence FLOAT NOT NULL,
  obs INT NOT NULL, last_seen TIMESTAMP NOT NULL,
  PRIMARY KEY (bot_char_id, price_key)
);
CREATE TABLE bot_market_consensus (    -- shared layer-1 statistic (sweep-owned)
  price_key BIGINT NOT NULL PRIMARY KEY,
  consensus BIGINT NOT NULL,           -- meso
  volume FLOAT NOT NULL,               -- decayed event weight (damping)
  updated_at TIMESTAMP NOT NULL
);
```
Stall state needs no table: `HiredMerchant` persists items via `ItemFactory.MERCHANT` +
Fredrick on close/shutdown; the bot re-plans a stall each session from its shelf + beliefs.
DAO pattern: `ManagedBotService`-style singletons. Belief writes batched on save ticks.

---

## 13. Prerequisite review — fixes & retirements folded into this build

**P0 — correctness (slice 0):**
- `Trade.getStagedMeso()` getter (§10.2). `BotEntry.marketBusy` gate extension (§10.1).
- `BotAssetView` + wiring (§10.4). *(Recommended)* `completeTrade` addFromDrop handling (§10.1).

**P1 — SSOT folds (retire parallel scorers/constants as market pricing lands):**
- `tradeValueScore` (BotInventoryManager :1808, 4th parallel stat-weighting) → replace with
  `marketStatValue`-based keep-ranking; keep behavior tests.
- Shop-buy ranking (BotShopManager :1178) raw Δscore → Δscore/price through the UpgradeMenu.
- Retire: `SCROLL_OPPORTUNITY_MARGIN=0.2` (planner :108; DP already nets scroll cost — gate
  becomes EV>0 + trait jitter), `SCROLL_OPPORTUNITY_FRACTION=0.9` (:83; apply-cost = believed
  resale price), Chaos/White 10M floor (:1341 — belief prior from repro/anchor instead),
  `SCROLL_CEILING_PER_EV=750k` (:78), `AMMO_CEILING_*` (BotInventoryManager :1871) — both replaced
  by §3 ammo/scroll valuation. Each retirement lands only when its endogenous replacement is live.
**P2 — perf:**
- Reproduction curves: add a cross-decision cache keyed (itemId, scroll-price snapshot hash),
  dirty on belief moves > a band (today every scan rebuilds every curve, BotScrollValuer :57).
- All market thinking (menu refresh, repricing, shout matching beyond the cheap bus peek) runs on
  `BotGrindAdvisor.DECIDE_POOL` off the tick thread, mirroring scroll scans; FM-session-gated
  cadence keeps it rare (the town-break gate precedent cut scroll scans 10-100×).
**P3 — anchor quality (upgrades the prior, not load-bearing for the loop):**
- `FARM_MESO_PER_SECOND=1000` (BotScrollManager :100) → the bot's own observed meso/hr EMA
  (grind sessions already know income); keeps time-pricing endogenous per bot. Attack-cycle and
  spawn-density inputs are already real (weaponCycleMs + BotSpawnIndex — done earlier).

---

## 14. Build slices (each independently verifiable; always-on check applies to every slice:
*with zero humans online, the behavior still happens* — run under `@botpop on`)

**S0. Substrate fixes** — P0 list. *Verify:* unit tests for asset-view enumeration (stall/storage
items visible, never wearable); staged-meso readable in a scripted trade; concurrent-farm trade
test stays green (existing gate), stall-service gate covered by a new test.

**S1. Beliefs + valuation plumbing (no FM yet)** — `BotMarketMath`/`BotMarketBook`/`BotMarketStore`
/`BotMarketLedger`/`BotMarketValuer`; ammo/buff meso valuation; scroll DP on believed prices;
P1 retirements that don't need live listings; NPC list-vs-sell shelf decision.
*Verify (pure sim, no server — the `BotScheduleMath` test style):* `BotMarketSimTest` with N
in-memory agents + one good: (a) prices converge from dispersed priors to a band and stay there;
(b) supply glut → clearing price sags toward/below the cheapest agent's cost then recovers as
sellers exit (emergent floor, §0); (c) demand spike → price rises, capped by use-value; (d) no
sustained oscillation (damping); (e) **sparse-market stability**: with ~3 agents and rare trades,
quotes stay in a band around consensus instead of random-walking, while cross-bot dispersion
stays nonzero (perception noise — bots still disagree); (f) an injected outlier trade (gift
price) barely moves consensus on a liquid item; (g) **cross-key coherence**: with trades only at
the 10-STR band, the 11-STR band of the same item quotes ≥ the 10-STR price + marginal repro
cost (never below the observed band), and a never-traded same-slot substitute quotes from the
implied stat price, converging without requiring its own trades. Fish Spear fixture: weak-bot
WTP ≫ its own farm cost; buying beats grinding. Two-bots-and-a-player glove case scripted at
this layer.

**S2. FM errand + stalls** — `BotFreeMarketManager` (nav work items from the explorer: BotEntry
errand fields, DetourErrand registration + statusReport branch, room pick, `HiredMerchant.openFor`
extraction, permit acquisition, Fredrick collection); browsing-observation intake; ledger hooks in
`HiredMerchant.buy`. *Verify (live):* a bot walks town→FM room, opens a stall priced from its
book, returns to grind; a second bot browses, buys with `merchant.buy`, both books update, ledger
shows the sale, seller collects proceeds next session. Skip nav test suites (rule 4) — one FM
route smoke only.

**S3. Shout grammar + bus + direct trades** — `BotMarketGrammar`, `BotMarketShoutBus`, player
chat-handler branch, bot loopback, want/stock matching, minimal negotiation (accept-at-ask /
decline; no counters yet), BotPrompt hints. *Verify:* grammar unit tests (incl. ASCII); live:
player `S> brown work glove 1m` → nearby bot with the want trades within a minute, no item
loss/dupe with farming running (gate test); bot `S>` shout completes bot↔bot with zero humans on
the map.

**S4. Haggling + supply steering + traits** — full §8.5 state machine (counters, rude-cancel,
trait scaling), grind-advisor market term, `SCROLL_FOR_PROFIT` flip, remaining P1 retirements,
new personality fields. *Verify:* the three brief §7 cases (exact 1m; "900k" counter; silent 900k
stage) each produce accept/counter/decline per trait fixtures; sim shows farm-target shift toward
a demand-spiked item and back.

**S5. Diffusion + spend breadth** — gossip lines, ammo purchase behavior (tier chosen by
budget/WTP), buff buying (trait-gated), `/api/market*` routes + web-endpoints.md update.
*Verify:* belief convergence time in sim drops measurably with gossip on; a rich high-`buffSpend`
bot buys a buff a poor one refuses (no wealth constant anywhere).

**S6 (later). Phase-2 fuzzy chat; chairs + collecting; storage NPC use** (deposit via
StorageProcessor extraction — 25 storage NPC ids catalogued in kb_bot_maker_economy — riding
`BotAssetView` so stored ≠ invisible; §10.4 already guarantees the invariant).

Branch note: implementation starts on a fresh dev branch off `experimental` per the established
workflow; slices S0-S1 are pure-testable and land first.

---

## 15. Side quests (deliberately thin here)

Chairs during breaks (random owned chair; `collectorTaste` budget for buying chairs at market —
rarity premium emerges from supply like everything else; skip duplicates via owned-set check).
Storage for far-future gear (S6). Maker crafting as a supply channel and NX/AP-reset economics:
future extensions; both already have SSOT hooks (`expectedAcquireGain`, recipe tables).

---

## 16. Resolutions & deviations (architect log)

1. **Anchor conflict (brief §0 "not a floor" vs §3 "held at/above"):** resolved per §0 — the
   anchor seeds priors and opening asks; the durable floor is *emergent* via supply exit +
   cheapest-producer undercutting (§0, §7). Transient below-cost clearing is allowed and healthy.
2. **Central damped price loop (brief §3, economy-design.md §5):** rev 1 replaced it entirely
   with per-bot beliefs; **rev 2 (owner direction, 2026-07-02) restored a central element as a
   *statistic*, not a setter** — pure private beliefs were judged too unstable for sparse
   markets. Final shape: damped robust consensus per item (§4 layer 1) + noisy lagged per-bot
   perception (layer 2) + private-experience override. Bots still set every price; the ledger and
   raw consensus are still not readable by decision code (§10.6).
3. **economy-design.md §8 anti-exploit caps/spreads:** dropped per brief §9 (rational valuation is
   the only limiter; NPC band is the only hard envelope, and it's game data).
4. **Frontier-producer anchor (economy-design.md §4.1):** not built — competition discovers it.
5. **"startBotTrade()" in the brief:** doesn't exist; the real seams are `startTradeTransfer`/
   `startTradeSequence`/`tickManualTrade` — negotiation extends those.
6. **Bot↔bot chat transport:** bots can't hear chat packets; the shout bus (§8.4) is the designed
   answer — grammar stays the single wire format, chat lines remain the human-visible narration.
7. **§8.1.1 higher-req-worse:** does NOT fall out of the equip optimizer (reqs are hard gates);
   it falls out of *demand breadth* instead. No rule added either way.
8. **Merchant permit:** granted to managed bots via the gacha-style NX abstraction; permits aren't
   consumed on open (existing server behavior, applies to players too) — accepted, not patched.
9. **Imperfect information model (brief §4 "you design"):** chosen = noisy/lagged perception of a
   shared consensus (stable seeded noise per bot+item+day, band scaled by social traits) blended
   with real partial private observation (local-only, source-weighted, confidence-decayed), plus
   speech rounding. Personal experience dominates hearsay as confidence grows.
10. **Inflation:** measured (ledger counters), never managed. Operator steers real faucets/sinks.
11. **Cross-item price coherence (owner question, 2026-07-02):** flat per-key beliefs rejected —
    quotes for thin keys are *generated* from structure (calibrated reproduction curve across
    bands, per-slot implied stat price across substitutes, anchors last) and corrected by
    observations; arbitrage via the UpgradeMenu closes residual gaps. See §4 structured priors.
