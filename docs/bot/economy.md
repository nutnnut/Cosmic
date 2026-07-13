# Bot economy

Current as-built map for the living economy. This replaces the completed design, build ledger, and
session handoffs that previously described intermediate stages.

## Architecture

- `BotAssetView` is the cross-location inventory view (bags, storage, and merchant holdings).
- `BotMarketBook`, `BotMarketConsensus`, and `BotMarketStore` maintain shared observations and
  persistent price beliefs. `BotMarketLedger` records market events; `BotMarketMath` owns quote math.
- `BotFreeMarketManager` decides when an autonomous bot should visit the Free Market, browse, open or
  service a hired merchant, price surplus, collect Fredrick holdings, and return to its prior town.
- `BotMarketShoutBus`, `BotMarketGrammar`, and `BotShoutTradeManager` implement structured shout-driven
  equip trading: bots emit `S>` asks for surplus and `B>` buy orders (optionally with a stat criterion,
  e.g. `8+ att work glove 500k`) for the one upgrade they want, match heard shouts over the vanilla
  `Trade`, and haggle in-window via plain trade-chat counters bounded by each side's per-roll SSOT
  price.
- `BotScrollManager`/`BotScrollPlanner` remain the scroll and equipment-value source of truth.
  Market code consumes their values instead of maintaining a second equipment scorer.
- `BotMakerManager`/`BotMakerPlanner`, gachapon, shop decisions, and grind selection consume the same
  progression/value substrate.

## Correctness invariants

- A market transfer must be atomic with respect to bot ticks and save operations. Never let a bot
  farm, equip, compact, or scroll the same item while it is staged in a trade or merchant operation.
- Price beliefs influence decisions; they do not mint items or meso. Real inventory, fees, storage,
  merchant, and trade paths perform every transfer.
- Only actual clearings can seed shared price consensus. Stall listings, shout asks and bids, and
  in-window haggle counters remain visible audit data, but advertisements never become the price that
  bots use for valuation.
- Exposed outcomes provide directional supply/demand pressure without turning advertisements into
  prices: surviving bot-stall stock is repriced about hourly and can only push consensus down; a sale
  within 15 minutes of opening/repricing can only push it up. Corrections start small, grow with
  repeated or crowded pressure, retain clearing-volume damping, and ignore a 5% equilibrium band.
- Merchant stock and Fredrick holdings are assets. Do not count a listing as both bag inventory and
  merchant inventory.
- Tick-thread code reads stable snapshots. Database and expensive valuation work belongs off the hot
  path or behind bounded caches.
- Free Market travel is a detour: preserve the bot's prior objective and return location.
- Web market endpoints in [`web-endpoints.md`](web-endpoints.md) are the live observability surface.

## Deliberate gaps

- Shout trading is equip-only. `BotShoutTradeManager` explicitly leaves consumable/stack shout trading
  for a later model with quantity-aware WTP/WTS and safe stack transfer. This is tracked in
  [`ROADMAP.md`](ROADMAP.md).
- Party-wide buyer comparison and fuzzy free-text item matching are not source-backed requirements.
  Add them only with an approved behavior design; do not revive the old speculative checklists.
