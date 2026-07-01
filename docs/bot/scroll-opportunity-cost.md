# Scroll opportunity-cost model (refined)

**Status:** DESIGN (doc-first, owner to review before implementation). Central to bot progression.

## Problem

Auto-scroll (`BotScrollManager.tickAutoScroll` → `buildBestPlan`) burns scrolls on **subpar spare gear**
(e.g. a dark att-60% scroll on spare gloves for an avg→good bump) when those scrolls would be better
**saved** for a better item — either a better roll the bot already could chase, or a better **base** it
could **farm** (pink capes, 7-slot gloves, high-base-stat gloves). Today the planner considers **only
items currently in inventory**; future obtainable gear is invisible to it, so it can't weigh "spend now"
against "save for something better."

## What the owner clarified (don't get these wrong)

- **A dominated item is NOT skippable.** A spare dominated by the worn item can become an **upgrade once
  scrolled**. Example: worn `10DEX/6ATT/0slot`; spare `8DEX/0ATT/5slot` → scrolling DEX can pass the worn
  at ~4 successes. So we must value the **scrolled potential**, not the current state. (My earlier
  "skip dominated" idea was wrong.)
- But not every dominated item is worth it: `4DEX/0ATT/5slot` may not be; `0ATT/7slot` may be worth
  trying. It's an **EV decision per candidate**, against the worn rival AND the scroll's own cost.
- **Auto-abort on bad luck is already handled** — the per-scroll continuation re-evaluates after each
  scroll, so a failed early slot (reduced ceiling / lost slot) aborts the chain. Don't rebuild that.
- **ACTIVE farmable awareness (owner chose this):** the bot should be aware of better **farmable** bases
  and, when one is clearly better, **steer farming toward it** (existing farm-item autopilot) and **hold**
  the relevant scrolls until it has the better base — not just passively decline to scroll.

## SSOTs to reuse (rule #6 — do NOT fork these)

- **Scrolled-potential EV curve:** `BotScrollValuer.reproductionValue(baseScore, tuc, reproSpecs, baseCost)`
  (built in `BotScrollManager.collectCandidates`, `BotScrollManager.java:~411`). This already models the
  expected value of scrolling an item's remaining slots with the obtainable scroll set, vs a clean-base
  cost. VERIFY whether it already nets out the **scroll's own cost** (`reproSpecs` carries scroll market
  price) — if it does, the per-scroll opportunity cost is partly there; if not, add it.
- **Equip value:** `BotScrollManager.offenseValue` / `potentialValue`; `BotEquipManager.rawPhysicalMax`.
- **Worn rival floor:** `wornRivalValue` (already computed per candidate in `collectCandidates`).
- **What gear can the bot FARM (the missing piece):** `BotGrindAdvisor.gearProspects(...)` /
  `gearDropsByMob()` (`BotGrindAdvisor.java:~580`) — gear drops valued as improvement over worn, per
  mob/map, weighted by drop chance. THIS is the SSOT for "better base farmable outside inventory."
- **Active farming:** autopilot farm-item mode (`BotAutopilotManager`, `entry.autopilotFarmItemId` /
  the farm-item advisor `BotGrindAdvisor.recommendFarmItem`).
- **Scroll market value (opportunity cost of one scroll):** `shopPrices()` / the drop-farm cost model
  already in `BotScrollManager` (`cleanBaseCostMeso`, the scroll-side of `reproSpecs`).

## Refined model

Three layers, each building on the last:

### 1. Scrolled-potential valuation (already mostly present — verify + tighten)
Keep valuing each candidate by `reproductionValue` (EV of scrolling its slots) vs `wornRivalValue`; the
DP (`BotScrollPlanner.planBest`) picks the best play. ACTION: confirm dominated candidates flow through
with their scrolled potential (they should — `collectCandidates` builds a `valueFn` for every slotted
equip). Add a regression case: `8DEX/0ATT/5slot` spare vs `10DEX/6ATT/0slot` worn → the spare's scrolled
EV must be considered, not pre-filtered by `betterAvailable`.

### 2. Per-scroll opportunity-cost floor (NEW or made explicit)
A scroll play is worth it only when **EV gain > consumed-scroll value × margin**. The "value" of a scroll
is its market/repro cost (it's reusable on a future better item). If `reproductionValue` already subtracts
the scroll cost internally, this reduces to a small positive-EV margin (don't act on near-zero gains).
Knob: `SCROLL_OPPORTUNITY_MARGIN` (visible constant; start ~1.2× so the gain must beat the scroll cost by
20%). This alone stops "burned a 60% att scroll for a tiny avg→good bump."

### 3. Farmable-better-base discount + ACTIVE farm steering (NEW, the big piece)
For the slot a candidate occupies, compute the **best farmable base** the bot could realistically obtain
(via `gearProspects`/`recommendFarmItem` at the bot's level + reachable maps): its **scrolled potential**
(reproductionValue of a clean copy with full slots). Then:
- **Discount/suppress** scrolling the current mediocre base when a farmable base's scrolled potential is
  meaningfully higher (e.g. `farmablePotential > currentPlayPotential × FARMABLE_SAVE_FACTOR`). Hold the
  scrolls instead. Knob `FARMABLE_SAVE_FACTOR` (~1.5).
- **ACTIVE:** when such a farmable base exists and is worth it, set the bot's farm-item target to that
  base (reuse the farm-item autopilot) so it goes and gets it, then scrolls THAT. Gate so the bot doesn't
  chase a 1-in-10000 drop: require a reasonable expected acquisition time (the grind advisor already
  estimates drop chance/hr; require it to clear a floor). Don't starve normal grinding/exp — the farm
  steering should be a bias, not an obsession (cap how long it diverts before giving up).

## Risks / calibration
- Don't make the bot **never scroll** because something better is always theoretically farmable —
  `FARMABLE_SAVE_FACTOR` must require the farmable base to be CLEARLY better AND realistically obtainable.
- Don't make it **chase phantom drops** — gate active farming on expected-acquisition-time and bound the
  diversion (fall back to normal grind, like the death-loop/give-up patterns).
- Keep all the existing protections (worn-rival floor, per-scroll abort, full-bag guard, `hasItem` equip
  resolution).

## Verify
- WZ-free unit tests on the valuation/decision seams (mirror `BotScrollPlannerTest`): the three corrected
  examples rank as the owner described; the opportunity-cost margin suppresses tiny-gain plays; a clearly
  better farmable base suppresses scrolling the current base and yields a farm-item target.
- Compile; don't run nav/graph suites (rule #4).
