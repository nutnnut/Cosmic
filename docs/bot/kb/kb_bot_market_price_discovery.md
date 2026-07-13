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

Poisoned-consensus persistence: `bot_market_consensus` rows written under the old ask-echo /
repro-floored regime carry clearing volume in the hundreds (bots really did buy from each other at
reproduction prices), and the fixed pricing code still reads them as high-confidence market truth —
asks re-emerge at the Integer.MAX clamp (rendered as 2.0b/2.1b/2,099,999,xxx by per-bot ask
styling). They cannot heal on their own: nobody can afford a ~2.1b ask, so the key never clears
again, and hourly UNSOLD pressure (W_OUTCOME 0.3) against hundreds of units of standing volume
moves the consensus by well under a percent per sweep. Overpriced keys starve of exactly the
clearing evidence that would correct them — the asymmetry that makes seeding LOW safer than seeding
high (a cheap ask clears fast and SOLD_FAST probes upward on real W_TRADE evidence). To reset the
persisted layers, the server must be STOPPED first: the sweep holds all consensus rows in memory
and re-upserts any key it later moves (preserving nothing of a live-table clear), and bot books
likewise re-save private beliefs. Clear `bot_market_consensus` and `bot_market_belief` together.

Price styling (`BotMarketMath.humanizeAsk`) applies to the buyer-facing figure: the whole-bundle
slot total (`BotFreeMarketManager.styledBundlePrice`, also the reprice pass) and the spoken shout
price — never the per-unit quotient, which multiplied back out reads like calculator output.
Styling is presentation only and is discarded when rounding would cross an economic bound the plan
honored (NPC sell-back floor, NPC counter / recharge-set ceiling, reprice reservation).

Cold-start seeding (no clearing evidence at a key) is demand-anchored and supply-clamped
(`BotScrollManager.cleanMarketWorthMeso`):

- The effort→meso anchor is LIVE: `BotScrollManager.farmMesoPerSecond()` samples grinding bots'
  modeled sustained kill rates × their mob's per-kill yield (meso EV + NPC-salvage EV of drops,
  `mobKillValueMeso`), median-aggregated, 5-min TTL. The old flat 1,000/s scaffold overpriced
  farmed items severalfold; `FARM_MESO_PER_SECOND_FALLBACK` (250/s) serves only boot/tests.
- Acquisition cost is net of byproduct: chasing a drop also banks the dropper's ordinary yield
  (`BotFarmingCostModel.rarityMeso(input, byproductPerKill)`), so on-grind-path drops net to ~0
  and salvage floors the ask. Behavior decisions (scroll planning, shelf protection) keep the
  GROSS targeted-farm cost — only market seeds use the net.
- A clean equip seeds at its stat LEAD over the cheapest obtainable same-slot, same-level-decade
  alternative (`cleanUtilityPremiumMeso`, baseline from the shop+drop catalogs), priced at half
  the best-buyer per-EV ceiling (`SCROLL_CEILING_PER_EV` × slot durability) — a lv50 +2 ATT cape
  (1102041) seeds millions above a statless lv50 cape despite identical farm effort. Clamped
  between net acquisition (floor) and gross acquisition (abundance cap: nobody pays above
  farm-it-yourself).
- Scroll seeds cap at `scrollCombatCeilingMeso` (stat EV × success × durability at the same
  per-EV anchor) instead of raw targeted-farm cost.

Roll-rarity pricing of clean drops (`BotScrollManager.equipMarketQuote` band curve,
`BotRollDistribution`): a clean piece's stats are a TWO-STAGE drop roll — `getRandStat` rolls each
nonzero catalog stat uniformly in `[c−r1, c+r1]` (`r1 = min(ceil(c*0.1), 5)`), then with 20%
(`GODLY_STATS_DROP_CHANCE`) the result is upgraded by `getRandUpgradedStat` (+`Uniform{0..maxBonus}`).
Top rolls are exponentially rare (plain tail × 20% gate × godly tail), so `cleanMarketWorthMeso` is
the FLOOR-roll price (cost per drop) and a threshold roll is priced at `cleanCost × expected drops to
hit it = cleanCost / P(surplus ≥ threshold)` — the same expected-attempts logic `rarityMeso` applies
to drop CHANCE, now applied to ROLL rarity on the dominant rollable stat. This is scale-aware for free
(a base-2 dex reaching 8 via godly costs ~90× its floor; a base-20 str's plain 18–22 barely moves) and
MONOTONE: bands reachable by clean rolls are rarity-priced, and above the clean-roll ceiling the curve
joins the scrolled-piece reproduction DP via `max(rarity ceiling at roll max, reproduction DP)` — no
band-0/band-1 inversion. The quote reads the curve at the piece's FRACTIONAL band so pieces sharing an
integer band still resolve by roll; the integer band stays the provenance-blind price key.

The structural rarity curve is only a PRIOR. Clearings reshape it: `BotMarketMath.calibratedCurve`
fits `price ≈ A·curve(band)^B` in log space over a bot's banded beliefs, so evidence moves both the
LEVEL (`A`) and the worst-vs-best SPREAD (`B`, clamped `[0,2]` to stay monotone) — a market that pays
less for rare rolls than the prior implies flattens the curve. With one observed band it collapses to
pure scaling. Direct banded belief (`perceivedPrice`) still overrides the curve outright where it
exists (clearing-evidence-dominates).

Seeding errs LOW on purpose: the correction asymmetry (see poisoned-consensus above) means cheap
seeds heal upward through real clearings + SOLD_FAST probes, while expensive seeds starve. A top-roll
rarity price above every bot's combat-anchored WTP (`equipBuyCeilingMeso`, kept LINEAR) simply doesn't
clear bot-to-bot — realistic, not a defect to fix.

`/api/economyreset?confirm=1` (test-server tool, `docs/bot/web-endpoints.md`) wipes the tape,
consensus, and beliefs (memory + DB), drops per-bot pressure state, and force-closes bot stalls so
the whole market reseeds under the current model.

Design lineage (reviewed 2026-07-11 against the rev-2 design of record, git `fa5f59405`): the
mechanism matches the design's seller-repricing and stability structure (gap-proportional
confidence-damped steps, asynchronous per-bot service cadence, reservation floor, W_OUTCOME 0.3 as
planned). Two deliberate deviations, both tightenings: (1) the design allowed "listing asks as weak
evidence when no clearings exist" — dropped, because the ask echo let reproduction-cost asks seed
the consensus that justified the next generation of high asks; (2) the design kept sold-fast/unsold
outcomes private to each bot's book — they now also reach the shared consensus as bounded
directional pressure, because a persisted bad consensus at a key with no clearings could never heal
through private books that keep re-sampling it.
