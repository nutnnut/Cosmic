---
name: kb_bot_market_price_discovery
description: "Bot-market consensus price discovery: clearings seed prices; hourly unsold supply lowers them; unusually fast sales raise them; volume and a deadband prevent oscillation"
metadata:
  node_type: memory
  type: project
---

# Bot market price discovery

`BotMarketConsensus` must never treat LIST or SHOUT advertisements as prices. That echo previously
let reproduction-cost asks seed consensus, which then justified the next generation of equally high
asks. Only realized `TRADE` / `STALL_SALE` clearings may seed a price key.

Clearing-only seeding is not sufficient for autonomous recovery: a persisted bad consensus at a
quality band with no clearing would otherwise remain forever. Bot stalls therefore emit censored
outcomes from real exposure:

- A live slot that survives its roughly hourly service interval is repriced downward and records
  `UNSOLD`. Repeated intervals and more same-key stock accumulate pressure, so corrections grow.
- A sale within 15 minutes of opening or repricing records `SOLD_FAST`; repeated quick purchases add
  demand pressure at that offered price.
- `BotMarketConsensus` applies these as directional bounds only: UNSOLD cannot raise and SOLD_FAST
  cannot lower. They cannot seed a never-cleared key.

Stability comes from the same confidence SSOT as pricing: clearing volume damps both consensus moves
and visible stall markdowns; outcome evidence has lower source weight than a trade; quantity scales
sublinearly; and outcome targets inside 5% of consensus are ignored. A real clearing resets the
seller's accumulated unsold pressure. This lets stale persisted prices heal without a table clear,
while liquid or near-equilibrium items resist isolated noise.

The behavioral seams are `BotMarketConsensus.sweep` (`BotMarketSimTest`) and
`BotFreeMarketManager.repriceUnsold` (`BotFreeMarketManagerTest`). The event tape remains the audit
trail; bot decisions continue to read only `BotMarketBook`.

Design lineage (reviewed 2026-07-11 against the rev-2 design of record, git `fa5f59405`): the
mechanism matches the design's seller-repricing and stability structure (gap-proportional
confidence-damped steps, asynchronous per-bot service cadence, reservation floor, W_OUTCOME 0.3 as
planned). Two deliberate deviations, both tightenings: (1) the design allowed "listing asks as weak
evidence when no clearings exist" — dropped, because the ask echo let reproduction-cost asks seed
the consensus that justified the next generation of high asks; (2) the design kept sold-fast/unsold
outcomes private to each bot's book — they now also reach the shared consensus as bounded
directional pressure, because a persisted bad consensus at a key with no clearings could never heal
through private books that keep re-sampling it.
