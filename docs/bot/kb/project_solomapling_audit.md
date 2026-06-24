---
name: project_solomapling_audit
description: "Audit of sibling bot fork SoloMapling — what's worth stealing for Cosmic's companion fork"
metadata: 
  node_type: memory
  type: project
  originSessionId: 284c72bf-8151-435f-96f0-fa38f1c14f92
---

Audited sibling repo `D:\GameServers\Maplestory\SoloMapling` (same Cosmic upstream, friend's
project). Full report: `docs/bot/solomapling-audit.md` (repo-scoped, git-shared).

**Verdict:** near-complementary. SoloMapling = breadth (~650 ambient/NPC/merchant/minigame bots
populating a solo world, packet-replay movement, YAML chat, fragmented pricing). Cosmic = depth
(one legal companion). On every overlapping axis Cosmic is **already deeper** — A* physics movement,
restart-on-ruin scroll DP (`BotScrollValuer`/`BotScrollPlanner`), Pareto-DP optimizer, LLM chat,
SSOT valuation. So their framework's "adopt" recs mostly die against our existing code.

**The one real steal:** their world-population FM-merchant substrate + content corpus (~1,636
player-style IGNs, `FMShopDescGen`, `ArtificialFreeMarket`/`FMEconomyManager`, procedural shop gen)
= the "background economic agents" liquidity layer that our own `docs/bot/economy-design.md` says it
still needs (line 25 "market state = aggregate of bot inventories/transactions only"; line 325
"capstone assumes a substrate of autonomous economic agents"; line 381 "how many background bots
populate the world — needs its own design pass"). Take their population plumbing + name/desc data,
but price everything from our SSOT, NOT their fragmented YAML/WZ/sine-wave pricing. See
[[project_bot_economy_and_self_scrolling.md]].

**Minor borrows:** in-game operator console (MMC messenger window), maybe `EquipMetadataCache` (only
if WZ-read hotspot), event bus (absent here, low payoff for 1 companion). **Skip:** movement replay
(whole-path replay ≠ our sub-tick micro-position exception — wrong granularity).
