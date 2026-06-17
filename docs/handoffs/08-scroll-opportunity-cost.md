# 08 — Scroll opportunity cost + farmable-base awareness (ACTIVE)

**Status:** DESIGN DONE → implement. Read `README.md` first, then the full design doc:
**`docs/bot/scroll-opportunity-cost.md`** (owner reviews that doc before/at implementation).
**Central to progression — take the effort.** Owner chose **ACTIVE** farmable steering.

## One-paragraph summary
Auto-scroll burns scrolls on subpar spare gear when they'd be better saved for a better roll or a better
**farmable base** (pink capes, 7-slot gloves, high-base-stat gloves). Fix in three layers: (1) value a
candidate's **scrolled potential** (a dominated item CAN scroll into an upgrade — do NOT pre-skip
dominated); (2) a **per-scroll opportunity-cost** floor (EV gain must beat the consumed scroll's own value
× margin); (3) **farmable-better-base awareness** — discount scrolling a mediocre base when a clearly
better base is realistically farmable, AND **actively steer farming** toward that base (reuse the
farm-item autopilot), holding the scrolls until acquired. Full model, SSOTs, knobs, and verify steps are
in `docs/bot/scroll-opportunity-cost.md`.

## Key SSOTs (rule #6 — reuse, see the design doc for file:line)
- `BotScrollValuer.reproductionValue` — scrolled-potential EV curve (already exists; verify it nets scroll cost).
- `BotGrindAdvisor.gearProspects` / `gearDropsByMob` (`BotGrindAdvisor.java:~580`) — **what gear is farmable** (the missing input).
- `BotAutopilotManager` farm-item mode / `BotGrindAdvisor.recommendFarmItem` — **active farming** of the better base.
- `BotScrollManager.offenseValue`/`potentialValue`, `wornRivalValue`, `cleanBaseCostMeso`/`shopPrices`.

## Owner's corrections to honor
- Dominated ≠ skip (scrolled potential can beat the worn item — `8DEX/0ATT/5slot` vs `10DEX/6ATT/0slot`).
- Per-scroll abort on bad luck is already handled (per-scroll continuation) — don't rebuild it.
- ACTIVE: steer farming toward a clearly-better farmable base and hold scrolls for it (not just decline).
- Don't over-correct into never-scroll or phantom-drop chasing — gate farmable steering on "clearly
  better AND realistically obtainable," bound the diversion (fall back to grind).

## Verify
- WZ-free tests on the valuation/decision seams (the three corrected examples; opportunity-cost margin
  suppresses tiny gains; a clearly-better farmable base suppresses the current scroll and yields a
  farm-item target). Compile. No nav/graph suites (rule #4).
