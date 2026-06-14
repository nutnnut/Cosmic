# SoloMapling audit — what's worth stealing for Cosmic's companion fork

> Audit date: 2026-06-14. Sibling repo: `D:\GameServers\Maplestory\SoloMapling`
> (same Cosmic/HeavenMS upstream, different bot project by a mapledev-community friend).
> Purpose: identify features/architecture worth merging into this fork.

## TL;DR

The two forks are **near-complementary, not competing**. On every axis where they
overlap, **Cosmic is deeper** — so the naive "adopt their framework" recommendations do
not survive contact with our existing code. SoloMapling's real value to us is the **one
axis we deliberately don't build: populating the world** — and that axis turns out to be
the *exact substrate our own `economy-design.md` says it still needs* (line 25 / 325 / 381).

| | This fork (Cosmic) | SoloMapling |
|---|---|---|
| Thesis | **Depth** — one AI companion that plays *legally* (shares player code) | **Breadth** — ~650 ambient bots populate a solo world |
| Movement | Intent-based A* physics graph (legal jumps/ropes/slip, `GRAPH_VERSION 57`) | Packet **replay** of ~440 recorded paths + JGraphT Dijkstra |
| Chat | LLM-driven (`server.bots.llm.*`, memory store) | YAML scripted dialogue (15 packs) |
| Scroll economics | Restart-on-ruin stochastic DP, convex reproduction-cost curve (`BotScrollValuer`/`BotScrollPlanner`) | `UpgradeSimulator`: naive `cost / successRate` |
| Gear | Pareto-DP equip optimizer | Tier-randomized cosmetic *decoration* only |
| Valuation | SSOT (`economy-design.md`, one value unit = meso) | Fragmented across 3 price sources (YAML/WZ/DP) |
| Engine footprint | shares player code | own package, 33 files / +1,104 −146 (model citizen of "minimal upstream diff") |

## The headline steal: world-population substrate for our economy capstone

Our `docs/bot/economy-design.md` is explicit that the dynamic market it designs is
**bot-liquidity-driven**:

- *"The market state is the aggregate of **bot** inventories and **bot** transactions only"* (§ line 25)
- *"The market mechanism (§1–§9) is a **capstone**. It assumes a substrate of autonomous economic agents"* (line 325)
- The open question it parks: *"how many background bots populate the world. Needs its own design pass"* (line 381)

SoloMapling **has already built that substrate**, using *legal means* (real `HiredMerchant` /
Player Shop objects — compatible with economy-design.md §"Legal means only", line 21):

- **`FreeMarket/ArtificialFreeMarket` + `FMEconomyManager`** — bots run Hired Merchants /
  Player Shops across 4 FM regions with regional + temporal price variance.
- **Procedural shop inventory** (`ArtificialShopGenerator`, `EquipListGenerator`,
  `ItemSelector`) — tier/version-weighted item selection.
- **Content corpus** (the genuinely expensive-to-reproduce part): ~1,636 player-style IGNs
  + ~2,950 FM name/description entries (`FMShopDescGen`, `FMShopDescriptionManager`).

**How to take it:** lift the *population + listing mechanism + content corpus* as the
"background economic agents" liquidity layer — but **drive every price from our SSOT**
(`BotScrollValuer` reproduction-cost + the planned `MarketLedger`/`PriceDiscovery`), **not**
from SoloMapling's fragmented YAML/WZ/sine-wave pricing. Their pricing is the part to drop;
their world-population plumbing and name/description data are the part to keep.

This stays inside our "sole focus is companion bots" charter: the companion remains the one
deep AI; the substrate bots are *shallow market-makers that exist to give the companion's
autopilot economy real counterparties* — depth (1) + breadth (N) serving the same mission.

## Tactical borrows (small, real)

- **In-game operator console (MMC)** — `server/MapleMessengerConsole.java`: a hidden bot +
  messenger window as an ops console to inspect/drive bots without chat spam. Cleaner than
  our scattered debug chat commands + `BotPathLogger`. Low effort, nice for dev.
- **`EquipMetadataCache`** — one-time WZ scan at init → O(1) fluent queries
  (`.query(type).cashOnly().maxLevel(70)`), zero WZ reads at spawn. Only worth adopting *if*
  our equip optimizer / decoration has a measured WZ-read hotspot; otherwise our
  `ItemInformationProvider` path already covers it. Verify before acting.
- **Event bus** — confirmed **absent** in our fork (we wire reactions directly, e.g.
  `BotScrollReactionManager`). SoloMapling's `BotMessagingSystem` pub/sub filters subscribers
  by world/channel/map. Genuinely net-new, but the payoff is proportional to subscriber
  count — modest for a single companion. File as optional cleanup, not a feature.

## Movement replay — format interesting, mechanism conflicts (downgraded)

Tempting to map onto our `feedback_micro_position_cheat_allowance` exception, but the
**granularity is wrong**: SoloMapling does *whole-path packet replay across a map*; our
exception permits only *sub-tick micro-positioning* in tight launch windows, last-resort.
Whole-path replay is a far larger legal-play violation than the exception allows, and its
value is conditional on our A* graph *actually failing* to produce a legal maneuver
somewhere — unverified that the companion even hits that. **Verdict:** the recording CSV
format (`movementDataPackets/map{ID}/*.csv`: ts, cmd, x, y, wobble, fh, stance, dur) is
mildly interesting as a data shape for a future micro-positioning helper; the replay
*mechanism* is not adoptable. Don't port it.

## Explicitly skip (we already do it better)

- Scroll-cost model — our restart-on-ruin DP ≫ their `cost/successRate`.
- Valuation SSOT — we have one; theirs is fragmented across 3 sources.
- Movement — our legal A* physics ≫ their replay.
- Dialogue — our LLM chat ≫ their YAML scripts.
- Equip optimization — we have Pareto-DP; they have none (decoration ≠ optimization).
- Minigame / social-ambiance / tutorial / OPQ-host bots, adaptive tick, 7-wave mass-spawn
  choreography, deferred decoration queue — all scale-driven for ~650 ambient bots;
  irrelevant for one companion.

## Architectural validation (not a steal, but worth noting)

SoloMapling lives almost entirely in its own `soloMapling/` package and touches the Cosmic
base in only 33 files (+1,104 / −146). That's a clean proof that a large bot layer *can* be
kept to a surgical upstream diff — validating our CLAUDE.md rule 2. (We make the opposite
trade by design — rule 1, share player code — so our diff is more invasive on purpose; but
their layout is a useful reference for anything we *do* want to keep self-contained, like a
future population-substrate module.)
