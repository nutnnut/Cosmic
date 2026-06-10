# Bot-Driven Simulated Economy — Design

Status: design draft. Target audience: solo / small-friend-group servers where **bots dominate**
production and trade, and a handful of humans interact with the same market as first-class
participants. Goal: a self-consistent, fully *dynamic* economy where prices, demand, scrolling
behavior, and "inflation" emerge from the system's own state rather than from hand-tuned tables.

This doc captures the model and the concrete build targets. It deliberately leaves runtime
constants out of the prose — see [§9 Exogenous inputs](#9-the-only-two-exogenous-inputs) for the
*only* two things that are allowed to be set from outside.

---

## 0. Guiding principles

1. **No hardcoded decision thresholds.** Anything that looks like `if (failedSlots >= 2) skip` is
   forbidden. Such behaviors must *emerge* from a value comparison that recomputes from live state.
2. **Derive, don't tabulate.** Where a number is needed, derive it from data the server already has
   (WZ drop tables, scroll probabilities, the equip optimizer's score) or from the market's own
   endogenous prices — not from a constant typed into the source.
3. **Legal means only.** Bots transact exclusively through systems a human can use: NPC shops,
   `HiredMerchant`/`PlayerShop`, face-to-face `Trade`, chat announcements. No privileged backend
   item/meso transfers.
4. **Ignore player↔player trades for pricing.** Friends gift and trade casually; that signal is
   noise. The market *state* is the aggregate of **bot** inventories and **bot** transactions only.
5. **Anchor, don't float free.** A fully self-referential price system can drift to zero, blow up,
   or fail to cold-start. Exactly one value is exogenous-but-data-derived (the farming-cost anchor)
   plus one self-tuning rate. Everything else is endogenous.

---

## 1. Architecture at a glance

```
                       ┌────────────────────────────────────────────┐
                       │            BotMarketManager (tick)          │
                       │  orchestrates: sense → value → decide → act │
                       └───────────────┬────────────────────────────┘
            ┌──────────────────────────┼───────────────────────────────┐
            ▼                          ▼                                 ▼
   ┌─────────────────┐      ┌────────────────────┐            ┌────────────────────┐
   │   Valuation     │      │   Price Discovery  │            │   Execution (legal) │
   │  (this item is  │      │  (what the market  │            │  HiredMerchant /    │
   │   worth V)      │      │   currently asks)  │            │  Trade / chat WTB   │
   └───────┬─────────┘      └─────────┬──────────┘            └─────────┬──────────┘
           │                          │                                 │
           ▼                          ▼                                 ▼
  ┌──────────────────┐     ┌────────────────────┐           ┌────────────────────┐
  │ ScrollMarkovSolver│    │   MarketLedger      │           │  Anti-exploit guard │
  │ RollProbability   │    │ (bot books, MySQL)  │           │  (spread/caps/rate) │
  │ FarmingCostModel  │    │  + MonetaryBalance  │           └────────────────────┘
  └──────────────────┘     └────────────────────┘
```

All new classes live in `server.bots.*` unless noted. Execution reuses existing infra:
`server/maps/HiredMerchant.java`, `PlayerShop.java`, `server/Trade.java`, the Owl handlers, and the
existing `BotShopManager` navigation/step-scheduling patterns (`scheduleShopStep`, `BotManager.after`).

---

## 2. Valuation — one DP, two jobs

The core object is a **value** `V(item)` = the expected meso cost to *reproduce* that item from
scratch via the optimal production policy. The same machinery that prices an item also tells a bot
how to scroll one — they are duals.

### 2.1 The scrolling / stopping DP (`ScrollMarkovSolver`)

State while improving an item: `(slotsLeft, bonusSoFar)` on a known base. Solve by backward
induction:

```
V(slotsLeft, bonus) = max(
    STOP    : marketValue(item as-is),                       // keep / list / use
    SCROLL_k: −price(k) + Σ P(outcome | k) · V(next state)   // best over affordable scrolls k
                outcomes: success      → (slotsLeft−1, bonus + gain_k)
                          fail/no-boom → (slotsLeft−1, bonus)
                          fail/boom    → item destroyed → scrapValue
)

ABANDON ⟺  V(currentItem) < V(freshBase) − price(freshBase) + marketValue(currentItem)
```

Everything the user listed is an **emergent output** of this equation, never a coded rule:

- *Incremental cheap-high-gain upgrades* → the `argmax` over scroll types picks the best marginal-EV
  scroll affordable right now.
- *Quit after a good roll then a failed 60%* → that lands in `(slotsLeft−1, sameBonus)`, whose `V`
  may drop below the ABANDON line.
- *Two failed slots ⇒ not worth it* → fewer slots ⇒ lower reachable ceiling ⇒ `V(continue)` falls
  under the fresh-start value. Falls out automatically.
- *Prefer a good base over a lucky bad base* → a high base has a strictly larger `V(freshBase)`;
  five 10% successes onto a bottom-tier base produce an item whose `V` loses to moderate 60% scrolls
  on a good base. Base acquisition is just "buy/farm the base maximizing `V(base) − price(base)`."

`V` is also the **reproduction price**: the EV cost the DP incurred to reach a given
`(slotsLeft, bonus)` *is* what that finished item is worth on the supply side. Compute once; use for
pricing and for bot decisions.

The DP's inputs are all dynamic/data-derived: scroll & base **prices** are endogenous (from price
discovery); success %, stat gain, and **boom flag** are ground truth read from item/WZ data (facts,
not knobs — verify boom flags against WZ when implementing, since only some v83 scrolls destroy the
item); `marketValue` of the as-is item is endogenous.

### 2.2 Cube / potential
no cube/potential in this version

---

## 3. Demand & willingness-to-pay — derived from opportunity cost

No hardcoded "wealth → spending" curve. A bot's price for a meso is set by its **own upgrade menu**:

`UpgradeMenu(bot)`:
1. Enumerate every available upgrade (gear swaps, scroll plays, cube plays) with
   `Δscore` (from the equip optimizer / DPS model) and `price` (endogenous).
2. Rank by `Δscore / price`; the bot buys greedily down the list within its meso.
3. **Marginal value of one meso** = the `Δscore/price` of the *best upgrade it has not yet bought*.
4. **WTP for a specific item** = `Δscore_item ÷ (marginalValueOfMeso)`.

Consequences, all emergent:
- A poor bot with juicy cheap upgrades still on its menu will **not** overpay.
- As a bot exhausts cheap high-ratio upgrades, its next-best ratio worsens → meso becomes "cheap" to
  it → WTP for what remains climbs → it rationally pays exponential premiums for marginal stat:
  **whaling emerges from a depleted menu, not from a flag.** "Can the bot afford to whale" and "how
  much do scrolls cost" both enter here directly.
- This closes the demand↔price loop: WTP (demand) is defined via the prices of *alternatives*; those
  prices are set by demand. A fixed point — anchored by §9 so it can't run away.

**Substitute goods (many alternative gears for one slot).** Nothing special is needed: the menu
already lists every candidate that fills a slot as a competing row, ranked by `Δscore/price`. Demand
flows to the best stat-per-meso option, which pulls all substitutes into a linked price band
(cross-price elasticity, emergent). The equip optimizer's Pareto front does the heavy lifting of
"which of these many gears is actually better" for use-value; the menu turns that into demand. A
glut of one alternative lowers its price → it climbs the ranking → demand shifts to it → its price
rises back: substitutes self-balance against each other with no per-item rules.

---

## 4. The farming-cost anchor (`FarmingCostModel`)

This is the one exogenous-but-derived value that bootstraps cold-start and floors prices. It answers
"what does it cost to pull this item out of the world?" — and crucially returns **≈∞ when the
content is unreachable by the server's current capability**, exactly as desired (a high-drop item
from a Lv100 mob is correctly "infinitely expensive" when nobody is past Lv30).

```
farmingCost(item, producer) = expectedKills(item) × effortPerKill(mob, producer)

expectedKills(item)        = 1 / dropRate(item)                    // from WZ drop tables
effortPerKill(mob, prod)   = timeCost(mob, prod) + survivalCost(mob, prod)

timeCost(mob, prod)        = timeToKill × opportunityMesoPerSec(prod)
timeToKill                 = mobHP / estimatedDPS(prod, mob)        // reuse bot combat DPS model
survivalCost(mob, prod)    = expectedPotionMeso(damageTaken(mob, prod))
                             where damageTaken folds mob touch damage + attacks vs prod's
                             defense/avoid/heal over the encounter

feasibility gate: if estimatedDPS ≈ 0  (timeToKill → ∞)
               or damageTaken outpaces sustain (can't out-heal / dies)
               → effortPerKill = ∞  → farmingCost = ∞
```

Notes:
- **`opportunityMesoPerSec`** (the time→meso conversion) is itself **endogenous**: it's the best
  meso/sec the producer could earn farming its *best alternative*. So even "how valuable is time" is
  derived, not a constant.
- `survivalCost` reuses the combat/potion estimates the bot already computes for autopot, and the
  potion price is itself an anchored market price — consistent all the way down.
- Because both `timeToKill` and `survivalCost` blow up when content outclasses the farmer, the
  ∞-when-unreachable property is automatic; no level checks.

### 4.1 Whose capability sets the anchor?

Recommendation: **the cheapest capable producer** — i.e., evaluate `farmingCost` against the
**best-equipped bot/character able to farm the source**, not the asker and not the average.

Rationale:
- Economically, the price floor of a good is the **marginal (lowest-cost) producer's** cost. Whoever
  can farm it most efficiently will undercut, so they set the market anchor.
- This yields the right cold-start behavior: if even the best farmer can't sustainably clear the
  source, the anchor is ∞ and the item is correctly "not really obtainable yet." As the server's
  capability frontier advances, the anchor falls smoothly.
- **Average is wrong**: weak bots would drag the average and make obtainable items look
  unobtainable (and vice versa). Average has no economic meaning here.

Two distinct uses, two different "producer" arguments — keep them separate:
- **Market anchor / pricing** → `producer = bestCapableProducer` (frontier).
- **A bot deciding what *it* should farm** → `producer = thatBot` (own capability), because a bot
  acts on its own feasibility, not the server's best.

A cheap reachability index (best-producer DPS & sustain per mob) can be cached and refreshed as gear
changes, so the anchor tracks the frontier without recomputing per query.

### 4.2 Only *active* characters count (last-login filter)

The frontier is "best **active** capable producer", and the same filter applies to **every**
aggregate computed from the `characters` table (population counts, supply, capability frontier).
A god-tier character abandoned months ago is not actually farming, so it must not drag the anchor
down and make items look cheaply obtainable when nobody is producing them.

- Before a character feeds the frontier or any aggregate, check its last-login/last-logout timestamp
  against a freshness window; stale characters are excluded entirely.
- Frontier producers are **bots only** (decided), but the staleness filter is what makes "bots only"
  meaningful in practice — a parked bot that hasn't run recently isn't a producer.
- This ties directly to the **active-bot registry** prerequisite (§11): the economy needs a live
  notion of "which bots are actually running now", not just "which bot rows exist in the DB".

---

## 5. Price discovery (`PriceDiscovery`)

Per item, the live ask/bid is a damped move toward the balance of bot supply vs. bot demand,
**floored/anchored** by `farmingCost`:

- `price ← price + rate · gap`, where `gap` reflects unsold-supply vs. unmet-WTP-demand from the
  ledger, and `price` is held at or above the farming-cost anchor (you cannot durably sell below the
  cost to produce; you *can* sit above it when demand is high).
- Bid–ask **spread** exists so bots don't arbitrage themselves; players profit inside the spread
  (a functioning market) but can't drain a bot.
- `rate` is **self-tuning** (see §9): faster when an item is stale/mispriced, slower when stable —
  a gradient on the order-book gap, not a fixed EMA constant.

Stability: demand is price-elastic (WTP capped by use-value in §3) → negative feedback → the loop
converges instead of running away. Damping prevents oscillation/herding (bots also shouldn't all
re-price in the same tick).

---

## 6. The scrolling behavior engine (`BotScrollManager`)

This is the missing piece that makes the economy *move*: today bots have no scroll behavior, so
there's no scroll demand and no supply of finished gear.

- Each upgrade cycle, a bot runs the §2.1 DP over its candidate bases/items and executes the optimal
  action: pick the scroll, **apply it through the real player scroll code path** (legal means), then
  re-evaluate (continue / stop / abandon).
- This single behavior generates, all at once:
  - **scroll demand** (bots buy scrolls when `EV(Δscore) > price`),
  - **supply of scrolled gear** (finished items listed via HiredMerchant),
  - **meso/item sinks** (failed scrolls, booms) — see §7.
- Base acquisition is part of the same loop: farm or buy the base with the best `V(base) − price`.

---

## 7. Monetary balance (`MonetaryBalance`) — inflation is *not* the price loop

Critical separation the design must preserve:

- **Relative prices** (scroll vs. base vs. finished equip) are set by §5's damped, anchored,
  self-correcting fixed point. This does **not** drive inflation.
- **Absolute price level** (inflation/deflation) is purely **meso faucet vs. sink**:
  - *Faucet*: mob meso drops, NPC sell-backs.
  - *Sinks*: NPC purchases, **scroll failures & booms**, repair, trade tax, recharge costs.
- If faucet > sink, everything inflates regardless of the relative loop. Tune sinks to hit the target
  price level (a small server may want mild deflation). Track faucet/sink flow in the ledger so the
  monetary state is observable, not guessed.

Do not let inflation control leak into the price-discovery loop or vice versa — different modules,
different inputs.

---

## 8. Execution & anti-exploit (legal means)

Reuse existing systems; the real engineering here is guardrails, not packets.

- **Sell side**: bot opens a `HiredMerchant` (FM) listing surplus farm loot and finished scroll
  output, priced from §5. Reuse `BotShopManager`'s navigation + `scheduleShopStep` timing so it
  looks human.
- **Buy side**: bot announces WTB in chat (`BotChatManager.botSay`, **US-ASCII only** per the chat
  charset rule) and accepts a `Trade`. This is the lever that lets players *affect* prices: dump an
  item onto a bot → its stock rises → its ask drops.
- **Guardrails (non-negotiable):**
  - bid–ask spread + sanity floor/ceiling vs. `V` (never buy above, never sell below);
  - per-item / per-player purchase caps + rate limits, so one player can't drain a bot's mesos;
  - bots transact only items they truly own / mesos they truly hold (real, persisted inventory &
    meso accounting);
  - **pause the bot's farming tick during an open trade** — the existing trade dupe/loss race
    (`kb_bot_trade_dupe_loss_audit`) is otherwise hit directly.

---

## 9. The only two exogenous inputs

Everything above is endogenous **except**:

1. **Farming-cost anchor** (§4) — exogenous but *data-derived* (WZ drop tables + combat/potion
   models against the frontier producer). It bootstraps cold-start and floors prices. It is **not** a
   typed-in price.
2. **Adaptation rate** (§5) — the one irreducible tuning knob (how fast prices chase imbalance).
   Make it **self-tuning** (gap-proportional), so it's a behavior, not a magic number.

The litmus test for any future constant: *does it encode a decision the system should be making
itself?* If yes, it's forbidden — derive it. A drop-rate-derived anchor and a volatility-adaptive
rate are scaffolding that keeps a fully dynamic system from eating itself, not hidden heuristics.

---

## 10. Build sequence

Each phase is independently verifiable; later phases assume earlier ones.

1. **Valuation core** — `ScrollMarkovSolver` + `RollProbability` (port/improve ICOG) +
   `FarmingCostModel`. *Verify*: unit tests asserting the +15-all-10% vs +14-mixed example orders
   correctly, and that a Lv100-mob drop returns ∞ for a Lv30-capability producer.
2. **Ledger + monetary accounting** — `MarketLedger` (MySQL: per-item bot-side state, txn log) +
   `MonetaryBalance` faucet/sink flow. *Verify*: a bot NPC-sell increments faucet; a scroll fail
   increments sink.
3. **Price discovery** — `PriceDiscovery` loop, anchored + self-tuning. *Verify*: simulated supply
   glut drives price down toward (not below) the anchor; demand spike drives it up; converges, no
   oscillation.
4. **Demand / WTP** — `UpgradeMenu` + opportunity-cost WTP. *Verify*: a wealthy bot with a depleted
   menu pays a premium a poor bot refuses, with no wealth constant in the code.
5. **Scrolling engine** — `BotScrollManager` executing the DP via the real scroll path. *Verify*:
   bot abandons a 2-failed-slot item in favor of a fresh good base purely from `V` comparison.
6. **Execution + guardrails** — HiredMerchant listing, WTB/Trade, anti-exploit caps, farming-tick
   pause during trade. *Verify*: scripted player cannot drain a bot beyond the per-player cap; no
   item duplication across a trade under concurrent farming.

---

## 11. Prerequisites & substrate (what must exist beneath this)

The market mechanism (§1–§9) is a **capstone**. It assumes a substrate of autonomous economic agents
that, today, only partially exists. Current state of `server.bots.*` (audited):

- Bots are **owner-tethered companions**, not autonomous agents: `BotOwnershipService` binds each bot
  to an owner character (`bot_owners` table); bots are spawned/driven in the owner's context.
- Bots have rich *reactive/commanded* behavior: combat, navigation, NPC-shop resupply & trash-sell
  (`BotShopManager`), chat (+ LLM replies), and **scroll *reactions*** (`BotScrollReactionManager`
  only *comments* "nice"/"rip" on nearby players' scrolls — it does **not** scroll anything).
- **Absent**: any autonomous lifecycle, any independent farm-target selection (no
  `chooseMap`/`farmTarget`/`huntMap` exists), any bot scrolling *action*, any economic bookkeeping.

### Dependency stack (bottom → top)

**Tier 0 — substrate**
- **A1. Autonomous / background bots.** Bots that live and act on the server tick independent of an
  online owner. *Biggest gap; everything economic assumes it.*
- **A2. Active-bot registry + last-login filter (§4.2).** A live set of "bots producing right now",
  with stale DB characters excluded from every aggregate.

**Tier 1 — autonomous agent behaviors**
- **B1. Independent farm-target selection.** Bot picks what/where to farm by profitability —
  literally `opportunityMesoPerSec` per map/mob for *its own* capability (the inverse of the §4
  farming-cost math). Does not exist.
- **B2. Bot scrolling behavior** (the §2.1 / §6 DP-policy executor). Greenfield (only reactions exist).
- **B3. Economic bookkeeping** — treat bot inventory/meso as tracked books. Partially present
  (inventory, meso, resupply) but not recorded as economic state.

**Tier 2 — market mechanism (this doc, §1–§9)**
- C1 valuation core · C2 ledger + monetary balance · C3 price discovery · C4 demand/WTP menu ·
  C5 execution + guardrails.

### Strategic sequencing — what can start *now* vs. what blocks on substrate

The valuation kernel is **pure functions with no substrate dependency** and is fully unit-testable
today against existing bot character stats:

- **Buildable now (no blockers):** `ScrollMarkovSolver`, `RollProbability`, `FarmingCostModel`
  (incl. the combat-feasibility/∞-when-unreachable model and the last-login filter), the
  opportunity-cost WTP math. These are Phase 1 of §10 and need only WZ data + character stats.
- **Blocks on A1/A2:** the live ledger, price discovery dynamics, and bot-driven listing/trading —
  because they need autonomous bots actually producing, consuming, and transacting over time.
- **Blocks on B1/B2:** realistic supply (farm output) and realistic scroll demand/supply.

Recommendation: build and verify the **deterministic kernel (C1)** now while **A1 (autonomous bots)**
is developed in parallel, then wire C2–C5 on top once bots actually run unattended. This avoids
blocking the testable math on the larger behavioral lift, and the kernel immediately doubles as the
brain for B1 (farm selection) and B2 (scrolling) once those land.

---

## Open questions

- **Resolved:** frontier = **bots only**; **scroll-only first** (cubes deferred; `RollProbability`
  built but unwired); ICOG is **reference-only** — model any scroll (incl. chaos = stat-reroll) as a
  generic outcome distribution; ledger persisted to **MySQL**.
- **Autonomous-bot design (A1):** the largest open prerequisite — lifecycle, tick ownership when no
  owner is online, and how many background bots populate the world. Needs its own design pass.
- **Persistence cadence:** how often to checkpoint the ledger to MySQL vs. hold in memory (restart
  resilience vs. write load).
- **Last-login freshness window (§4.2):** how recent counts as "active" for frontier/aggregate
  inclusion — and which timestamp column on `characters` to key on (verify schema).

---

## Validated computational model — scroll reproduction cost & equilibrium (2026-06)

Worked out and validated offline (no in-game testing). This is the concrete math behind the
scroll-pricing parts above.

### Value = reproduction cost, in MESO

An item's value at a given state = the cheapest **expected meso to reproduce** it from scratch.
Scroll cost is also meso: shop-sold → `shopitems.price`; drop-only → effort→meso; a clean base →
its rarity→meso (drop chance × meso/kill, or a market anchor). Value and cost share one unit (meso),
so "scroll iff expected meso-value gained > scroll's meso cost" is apples-to-apples.

### The reproduction DP (cheapest-production, restart-on-ruin)

State `(slots_remaining s, stat_score a)`. Backward induction with an explicit abandon option:
```
valueOf(s,a) = min(  rebuy:  B + D                              (abandon this base, D = value of a fresh one)
                     per scroll i:  cost_i
                        + p_i        · valueOf(s-1, min(a+g_i, T))     (success)
                        + (1-p_i)    · valueOf(s-1, a) )               (fail: slot gone)
```
Maple wrinkle: a regular scroll **consumes the slot win-or-lose**, so the "retry" is at the *item*
level — buy a fresh clean base (a boom OR slot-exhaustion-below-target both force a restart).
`D = valueOf(S,0)` (the restart value) is a 1-D fixed point solved by a few sweeps. The resulting
value-vs-stat curve is **convex** (binomial upper tail under a fixed slot budget) — that convexity is
exactly what makes the online bot snowball winners and abandon losers, and it's denominated in meso.

**Emergent, not hand-coded (verified by dumping the optimal policy):** for a hard target the DP
throws the rarest/biggest gamble on the **first** slot and **abandons immediately** if it fails
(`(5,0)→gamble`, `(4,0)→rebuy`). "Gamble early, cut losses early" falls out of the backward
induction; the abandon frontier rises as slots run down.

Worked example: **Oaker Garner glove 1082089** (reqLevel 60, base STR1/DEX1/WDEF22, `tuc`=5; dropped
by mob 4230126 @chance 1000 ≈ 0.1%). Obtainable non-boom non-GM ATT scrolls (verified vs DB
`shopitems`+`drop_data` and exact-boundary WZ extraction): **2040804 (60%/+2, 550k)**, **2040805
(10%/+3, 1.1M)**. Blacklisted: 2040807 (GM 100%/+3, 11M), dark scrolls 2040808–2040815 (the only
boomers, `cursed=50`, unobtainable here), all other +ATT scrolls (not in shop/drop). Reproduction
table @ B=500k: +1≈1.42M, +5≈3.96M, +8≈7.47M, +10≈22.7M, +11≈124M — convex; recipe is mostly 60%/+2,
with 10%/+3 only in the extreme tail.

### Equilibrium pricing is a convex/LP solve — prices are DUALS, not a tuned loop

Demand is endogenous: a bot's willingness-to-pay for scroll *i* = its marginal effect on reproduction
cost (the DP's shadow price). Aggregate bot demand vs. **faucet supply** (drop rate × kill rate +
NPC stock) clears the market. Formally:

- Put a price/multiplier `λ_i` on each scroll's supply constraint. The per-bot inner problem at prices
  `λ` **is** the reproduction DP (scroll cost = `λ_i`) — the DP is the Lagrangian inner minimization.
- Equilibrium `λ*` maximizes the Lagrangian dual `g(λ)` (concave: pointwise min of linears) ⇒ a single
  convex optimization. By LP duality, `λ*` = shadow prices of scroll supply = market prices.
- Equivalent primal: min total reproduction cost s.t. meet demand + scroll caps; recipes are columns,
  the **DP is the column-generation oracle**, duals on supply rows = prices, complementary slackness =
  the DP's recipe rule. One LP picks recipes AND prices.
- Few scrolls → solve KKT per binding-regime in closed form; many → Newton on the concave dual.
  Tâtonnement (multiplicative price nudging) is just subgradient ascent on `g` — the crude version.
- **Substitution is the clearing force** and reproduces real servers: 60%/+2 sits in most recipes →
  high demand → commands a premium **even when 10%/+3 is rarer to drop**; as 60% gets pricey, bots
  substitute to 10% (then abandon ambitious targets). Demo (toy supplies 900 vs 120): 60% ≈ 3.9M vs
  10% ≈ 2.4M (1.65×), and the demand curve flips toward 10% only once 60% is ~2× equilibrium.
- **Caveat — degeneracy:** a scroll with slack supply at the margin has a dual *range*, not a point
  (the "lumpy" niche price). Intrinsic non-uniqueness; the LP returns the whole optimal dual face.

### Cross-item alternatives — same emergence, one level up

A **slot's** value = the lower envelope over all wearable base variants (e.g. the many Garner gloves)
of their reproduction cost; variants differ in base stats, `tuc`, reqLevel, drop supply, and the
cheapest-to-reproduce one wins each target (this generalizes the bot's existing dominance gate into
reproduction-meso). Scroll demand **pools** across all variants sharing a scroll pool (more liquid
prices, not fragmented). Base-item prices co-emerge from their own supply/demand; dominated variants →
floor, the "meta" base → premium. Together it's one **joint general equilibrium** over the full
goods vector (bases + scrolls) — same dual/LP machinery, higher-dimensional.

### Cost & deployment — tiered; populate-once + cache, never per-decision

- One item's reproduction curve: tiny DP (slots ≤ ~9 × bucketed score × a few scrolls) → sub-ms; one
  solve yields the whole curve.
- All items: build lazily only for items bots own/target; cache, dirty-flag on material price moves.
- Bot's per-scroll decision: O(1) — read cached curve + current prices, run the small online DP over
  its item's ≤9 slot-states. Hot-path safe.
- Price discovery: a periodic **background "market epoch"** (minutes), one convex solve (or a few
  tâtonnement steps). Off the hot path. → The heavy math is precomputed/cached; live decisions are
  lookups.

---

## Why self-scrolling is the prerequisite — the demand atom (2026-06)

This records *why* the bot self-scrolling feature (the thing actually being built now) is the
load-bearing prerequisite for the whole economy above, and what is already implemented vs. still
open. The vision in one sentence:

> **If both demand and supply are produced by agents acting on value, prices don't need to be
> authored.** Everything else — equilibrium, adaptation, player impact — is consequence.

### The chain (and where each link lives)

1. **Supply is already real.** Bots farm legally; loot is dropped, never spawned/voided. (Existing
   `server.bots.*` combat + farming.)
2. **Demand needs an agent that *wants* goods at a price.** That want must come from a *value*, not a
   flag. Self-scrolling is what gives a bot preferences over goods:
   - **Valuation — built.** `BotScrollValuer.reproductionValue` answers "what is this item worth?" as
     the cheapest expected meso to *reproduce* one this good (base stats + slots + scroll prices +
     success odds, convex above base). This is §2's `V` for the scroll case, realized. → vision #1.
   - **Decision — built.** `BotScrollPlanner` makes the 10%-vs-60%, gamble-early / abandon-early,
     push-for-+11 calls *dynamically* from that curve — no scripted thresholds. → vision #2.
   - **The bridge to demand — falls out for free.** A bot "wants" scroll *i* exactly when its
     reproduction value for the result exceeds the scroll's market price. No separate wanting-system
     is needed; it is `EV(Δvalue) > price` read off the same curve. → vision #3 → #4.
3. **Real supply + real demand = a price-discovery loop has both its inputs.** Once demand is an
   agent output rather than a table, §5's damped/anchored loop has something real to clear. → #5 → #6.
4. **Player impact and farm-steering are then consequences**, not new systems: buying up a scroll
   raises bot WTP → bots route to maps that drop it (the §4 inverse). → #7, #8, #9.

So self-scrolling is not a side quest: it is the smallest unit that makes a bot *have a preference
over goods at a price*, which is the atom the entire market is built from.

### Built vs. open (honest status)

| Piece | Status | Where |
|---|---|---|
| Reproduction-cost value curve (convex, meso) | **done, unit-tested** | `BotScrollValuer` |
| Online scroll/stop decision from the curve | **done, unit-tested** | `BotScrollPlanner` |
| Owner-confirmed legal apply via real player path | **done** | `BotScrollManager` (v1: non-boom only) |
| Demand signal (`value > price ⇒ want`) | **implicit, works per-bot** | derived from the curve |
| Clean-base cost (rarity→meso) | **stub** (`cleanBaseCostMeso`, level-scaled) | the curve's floor only |
| WTP → a single *cleared* price across all goods | **open** | §3 + §5, not built |
| Reflexive stability (demand↔price feedback) | **open / unproven at scale** | §5 damping |

### The genuinely hard parts (not yet solved)

- **From private willingness-to-pay to one emergent clearing price.** Equilibrium is the LP/convex
  dual (proven; §"Equilibrium pricing"), and tâtonnement converges in a toy 2-scroll world. Scaling
  that to *every* item/scroll simultaneously, online, as the population shifts is the open problem —
  "solvable" in that the fixed point exists, but maybe not *cheaply enough to run live* without
  approximation. An engineering bound, not a theoretical wall.
- **Reflexivity.** Bot value depends on scroll price; scroll price depends on bot demand. That loop
  is what makes it adaptive (#6, #8) and also what could oscillate or run away. Damping / epoch-
  batching (§5) is the intended answer, unproven here.
- **The clean-base floor is a stub.** Everything *above* the floor is principled; the floor itself
  (`cleanBaseCostMeso`) is hand-set until the §4 `FarmingCostModel` (rarity→meso) lands.

Bottom line: the demand-generation half (value → decision → derived want) is realized end-to-end
today. The leap from "every bot has a private WTP" to "the server has one emergent clearing price" is
the part that is still best-effort, not proven-at-scale — but even the semi-optimal version already
prices gear and can steer farm targets without the full equilibrium.
