---
name: kb_bot_sleepywood_trap_return_scroll
description: Low bot stuck in Sleepywood (walled region) escape + prevention — donated return-scroll rescue + returnMap-aware grind prune; graph cache bumped to v3 to persist returnMap
metadata: 
  node_type: memory
  type: project
  originSessionId: 87125234-4074-4dfe-ad3b-e4f41dc58631
---

A lv12 bot stranded in Sleepywood town (105040300) spamming "nowhere walkable looks good rn".
Sleepywood (every 105######) is hard-blocked for bots <15 (`isDangerRegionBlocked`), and the only
walk-out (Ant Tunnel, also 105######) is blocked too → trapped. Two gaps fixed:

**Recovery (legal-only, by design — even real players need a rescue here):** when the planner
returns a null rec AND the bot stands in a danger-blocked region, `BotAutopilotManager.escapeTrappedRegion`
tries a donated return scroll, else begs and waits (no free teleport). Wired into all 3 null-rec
sites (start, farm-start, periodic redecide). `BotManager.tryUseReturnScroll` was generalized from
only 2030000 → any town scroll 2030000-2030006: resolves each scroll's destination via the new
`StatEffect.getMoveTo()` (−1 none, MapId.NONE = current map's returnMap, else fixed town) and SKIPS
any whose dest is `isDangerRegionBlocked` (so a donated "Return to Sleepywood" 2030006 never re-traps).
No donate/ask UI — a passing player just drops a scroll in the bot's USE bag; next decide self-rescues.

**Prevention (SSOT route gate):** the gate predicate is `BotAutopilotManager.routeBlockFor(bot)` =
`isDangerRegionBlocked(bot,m) || isDangerRegionBlocked(bot, BotWorldGraph.returnMapOf(m))`, funnelled
through two wrappers `reachableForBot` / `routeForBot` (BotWorldGraph stays generic — only gained a
`route()` blocked overload). ALL bot-aware destination-selection now routes through the gate: advisor +
farmAdvisor (deduped to `m -> isAvoided(entry,m) || gate.test(m)`), party `partyInputs` reachable,
shop search (`BotShopManager:433`). Quest errands gate at their 3 eligibility sites (`worthwhile`,
the two startable/auto-suggest loops) with region-only `isDangerRegionBlocked(bot, npcMap)`. NOT gated:
the `tickTravel` walker + `hopCount`/`routeLookup` seams (bot-less, test-injected by ~13 tests) — they
never need it because the walker is never *told* to go to Sleepywood once selection is gated. Job
instructors are town-hub NPCs (never 105######), so job-advance needs no gate. Respawn stays LEGAL (no
guard — user decision 2026-06-20): if an edge case still strands a bot, Part 1 begs for a scroll.
So a <15 bot won't grind/route/shop/quest into a map that dumps it into Sleepywood on death. Data: only **107000500 (19 mobs) /
107000501 (14 mobs)** are realistically-grindable non-105 maps with a Sleepywood returnMap; the other
~17 are zero-mob/event/PQ maps (450/677/910) the advisor never autonomously picks — so prevention's
real benefit is small (flagged the tradeoff; did it anyway for SSOT correctness + no oscillation).

To support `returnMapOf`, `BotWorldGraph.Index` now carries `returnMaps` and the disk cache gained a
4th TSV column (returnMap, omitted when == self). **GRAPH_VERSION 2 → 3** — forces one WZ rescan on
next boot. Entry vector note: the bot most likely reached Sleepywood via placement/quest-errand (which
bypass the danger block), NOT by grind-selection, so recovery is the load-bearing fix here.

Related: [[kb_bot_inert_autopilot_recovery]] (other stranded-bot self-heal), [[kb_bot_town_nav_airborne_target]]
(travel give-up loop), [[feedback_client_side_formulas_in_bot]] (StatEffect stays generic; bot reuses applyTo).
