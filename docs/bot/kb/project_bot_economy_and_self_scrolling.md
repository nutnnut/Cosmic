---
name: project_bot_economy_and_self_scrolling
description: "Bot simulated-economy vision + the bot self-scrolling feature (first slice) — design doc, scope decisions, build slices"
metadata: 
  node_type: memory
  type: project
  originSessionId: 2c7261c3-01c2-4a68-b411-45803ca2acda
---

Long-term goal: a bot-driven **simulated economy** (bots dominate; humans interact as first-class
participants). **BUILD DESIGN OF RECORD (2026-07-02): `docs/bot/living-economy-design.md`** —
decentralized belief-based pricing (no central price loop), FM stalls + shout grammar + haggling,
slice plan S0-S6; it wins on conflicts. `docs/bot/economy-design.md` stays as the math reference
(reproduction-cost scroll DP, opportunity-cost WTP, farming-cost anchor gated by combat feasibility,
LP-dual equilibrium theory, Fish Spear calibration).

Key decisions locked in conversation (also in the doc):
- Frontier producer = **bots only**, filtered by **last-login freshness** (stale chars excluded from
  anchor + all aggregates).
- **Scroll-only first** (cubes deferred). v232 `ICOGProbabilityCalculator` is reference-only.
- Never feed **player↔player trades** into pricing — market state = bots' own books. Ledger = MySQL.
- Economy is a capstone; prerequisites (autonomous/background bots, independent farm-target
  selection) mostly DON'T exist yet — bots are currently owner-tethered companions.

First concrete feature being built: **bot self-scrolls its own equip** (companion scope, no new
substrate). Confirmed scope:
- Per-item **owner confirmation** (toggle to arm, then propose one equip at a time, yes/no).
- **Boom-capable scrolls** only when EV strongly positive AND a fallback gear exists for the slot.
- **Moderate conservatism**: skip if a better item is already available (about to replace) or the
  equip will be out-leveled soon; model slot-burn cost so low-% scrolls aren't fired freely.
- Deferred/optional "fun" feature: player hands the bot an item + scrolls and the bot scrolls *that*
  given item — NOT the bot scrolling the player's worn gear.

Build slices ALL DONE (compiles, BotScrollPlannerTest 9/9 green):
(1) `BotScrollPlanner` pure decision core + test.
(2) `BotScrollManager.buildBestPlan` — scans worn (EQUIPPED) **+ bag (EQUIP)** equips + owned scrolls
    (USE), job-weighted offense value (ATT*5/main*1/secondary*0.3) as v1 stand-in for the optimizer;
    applicability via getScrollReqs/category match.
(3) `BotChatManager`: SELF_SCROLL_ON/OFF/NOW patterns + `BotEntry.selfScrollEnabled` toggle. Commands:
    "scroll on"/"scroll my gear" (arm+pass), "scroll off" (disarm), "scroll now" (one-shot).
(4) Confirm via existing `pendingAction="scroll_confirm"` (handleScrollConfirm); execute in applyScroll
    mirroring ScrollHandler (scrollEquipWithId mutates in place, null=boom; consume scroll,
    ModifyInventory, broadcast getScrollEffect, equipChanged; notify nearby bots). Resolves the exact
    Equip INSTANCE (IdentityHashMap), not by itemId — bot can own 2 copies of an item w/ diff stats.

DECISION MODEL — now a BOUNDED STOCHASTIC DP (replaces the greedy worth/slotReserve pass):
- `BotScrollPlanner` is `valueOf(v,s)` backward induction (memoized HashMap keyed s*1_000_003 +
  round(v*1000)): valueOf(v,s)=max(stop=rawValue(v), per scroll a: -cost_a + p*valueOf(v+statGain,s-1)
  + (1-p)(1-boom)*valueOf(v,s-1) + (1-p)boom*0). planBest(candidates, DoubleUnaryOperator rawValue)
  returns the best FIRST scroll to apply now (improvement = pickEV - stop), across candidates; bot
  applies ONE then RESCANS (adaptive: success snowballs, fail lowers ceiling → may switch piece).
- Records changed: ScrollOption(id,name,success,boom,statGain,cost); EquipCandidate(id,name,
  currentStatScore,slotsRemaining,betterItemAvailable,hasFallbackForSlot,options). slotReserve
  REMOVED (DP lookahead subsumes it). Boom gate: allowed() requires fallback; EV prices the rest
  (dropped the old STRONG_EV_MARGIN 2x margin).
- KEY INSIGHT: with LINEAR rawValue + unlimited scrolls the DP === greedy best-worth-first (proven).
  DP only picks non-greedily under a CONVEX rawValue (rarer/harder-to-reproduce score worth >linear):
  then snowball-the-winner / abandon-the-loser emerges, and "cheap 11-base+cheap 30/+3 beats pricey
  12-base+60/+2" becomes computable.
- SLICE 1 DONE (behaviour-preserving): wiring supplies rawValue=identity (score->score) + cost=0, so
  decisions match the old greedy. BotScrollPlannerTest 11/11 green, BUILD SUCCESS. Scope = owned
  inventory only (user picked; no market prices yet).
- SLICE 2 (NEXT, needs user nod on value shape): swap rawValue to CONVEX reproduction-cost built from
  the equip RANDOMIZER + scroll binomial, and cost = scroll farm-difficulty. RANDOMIZER FACT
  (ItemInformationProvider.getRandStat): base stat D rolls UNIFORM over [D-r, D+r], r=min(ceil(0.1*D),
  maxRange); maxRange=5 for str/dex/int/luk/w/m-atk/acc/avoid/jump/speed, 10 for wdef/mdef/hp/mp. So
  top base roll rarity=1/(2r+1). Base spread is small (±10%); dominant rarity is the scroll-success
  binomial over slots. randomizeUpgradeStats (+0..2 / +0..5) used by Maker/godly, separate path.
  DOUBLE-ROLL (user-flagged, important): base dist is a MIXTURE, not uniform/normal. randomizeStats
  does the vanilla uniform roll THEN with prob GODLY_STATS_DROP_CHANCE (config default 20%) calls
  randomizeGodlyStats which OVERWRITES each stat with vanilla + Uniform{0..maxBonus},
  maxBonus=max(round(reqLevel*GODLY_STATS_BONUS_SCALING=0.1), GODLY_STATS_MIN_BONUS=5) (≈+10 at lv100).
  Godly only boosts stats the item ALREADY has (getRandUpgradedStat returns 0 if base 0), except HP/MP
  always added. So P(base score) = 0.8*vanilla ⊕ 0.2*(vanilla+godly) → fat upper tail = the rare
  valuable rolls (reinforces convex rarity value). TUC (orig total slots) = getEquipStats(id).get("tuc");
  Equip.getUpgradeSlots() = REMAINING. Drop cost source: drop_data(dropperid,itemid,chance) +
  drop_data_global(continent,...); chance denom ~1,000,000; NPC-shop-sold scrolls are ~free (clamp).

SLICE-2 VALUE DECISION (user-locked): rawValue = REPRODUCTION COST IN MESO (not abstract units).
Cost in meso too: shop-sold scroll → shopitems.price; drop-only scroll → effort→meso; clean base →
rarity→meso (the one input still to derive). Method = DP fill exactly like the user's spreadsheet:
cell=(slots remaining, stat achieved), value=cheapest EXPECTED meso to PRODUCE that, edge=apply a
scroll (cost as fn of price & success), keep cheapest + remember recipe. Maple wrinkle: regular
scroll consumes slot win-or-lose, so the "retry" is RESTART = rebuy a fresh clean base (boom or
slot-exhaustion-below-target both force restart); add explicit early-abandon option. Fixed-point on
D=E[S,0] (restart value), iterate to converge.

WORKED EXAMPLE (validated offline, no in-game test) — Oaker Garner glove 1082089 (reqLevel 60,
base STR1/DEX1/WDEF22, tuc=5, dropped by mob 4230126 @chance1000≈0.1%). Obtainable non-boom non-GM
ATT scrolls (verified vs DB shopitems+drop_data and exact-boundary WZ extract): 2040804 60%/+2ATT
550k, 2040805 10%/+3ATT 1.1M. BLACKLISTED: 2040807 (GM 100%/+3 11M), dark 2040808–2040815 (the ONLY
boomers, cursed=50, unobtainable here), all other +ATT scrolls (2040803/21/22/26/30/34 not in
shop/drop). Reproduction table (B=500k base): +1≈1.42M, +5≈3.96M, +8≈7.47M, +10≈22.7M, +11≈124M —
CONVEX in meso (binomial tail under fixed 5 slots). Recipe: 60%/+2 dominates; 10%/+3 only in extreme
tail. Higher base cost lifts column + abandons ruined bases less. See [[feedback_wz_extract_exact_boundaries]].
NEXT: wire this meso reproduction-cost curve as the per-candidate rawValue in BotScrollPlanner
(engine already built, slice 1); derive clean-base rarity→meso; scroll cost from shopitems/drop.

EQUILIBRIUM + CROSS-ITEM + COMPUTE (designed & validated 2026-06; full writeup appended to in-repo
docs/bot/economy-design.md §"Validated computational model"):
- EMERGENT "gamble early / cut losses early" is automatic from the reproduction DP (verified by
  dumping policy: (5,0)→throw 10%/+3, (4,0)→abandon+rebuy). No special-casing.
- EQUILIBRIUM PRICING is NOT a heuristic loop — it's a one-shot CONVEX/LP solve and prices = DUAL
  variables (shadow prices of scroll supply). The reproduction DP = the Lagrangian inner min /
  column-generation oracle; tâtonnement = subgradient ascent on the concave dual g(λ) (crude). Few
  scrolls → closed-form KKT per binding regime; many → Newton. Substitution is the clearing force →
  reproduces real-server inversion (60% commands premium even though 10% rarer to drop). Demo
  (Node, toy supplies 900 vs 120): 60%≈3.9M vs 10%≈2.4M (1.65x); demand curve flips to 10% at ~2x.
  Degeneracy: slack-supply scroll has a dual RANGE not a point (lumpy niche price; intrinsic).
- CROSS-ITEM (e.g. many Garner variants): slot value = LOWER ENVELOPE over wearable base variants of
  reproduction cost (generalizes the dominance gate into meso). Scroll demand POOLS across variants;
  base prices co-emerge; whole thing = one joint general equilibrium over bases+scrolls, same duals.
- COMPUTE: tiered, POPULATE-ONCE + CACHE (dirty-flag on price moves), never per-decision. Per-item
  curve sub-ms; bot per-scroll decision O(1) lookup + tiny online DP (<=9 slot states); price
  discovery = periodic background "market epoch" (minutes). Hot-path safe.
- PowerShell gotcha: multidim arrays + inline if-expression broke (op_Subtraction:String); used Node
  for the numeric sim instead. node IS available; python is NOT on this box.

SLICE 2 DONE (built, compiles, 16/16 green = BotScrollPlannerTest 11 + BotScrollValuerTest 5):
- NEW src/main/java/server/bots/BotScrollValuer.java — pure reproduction-cost DP. reproductionValue(
  baseScore, tuc, List<ScrollSpec(p,statGain,mesoCost)>, baseCostMeso) -> DoubleUnaryOperator value(score)
  = cheapest expected meso to reproduce an item >= score (restart-on-ruin DP; D = restart value, 1-D
  fixed point, RESTART_ITERS=120; lazily memoized per queried score). Flat=baseCost at/below base,
  convex above. Closed-form test anchor: 1 slot 50%/+1 cost c base B -> value(1)=2B+2c.
- BotScrollPlanner REFACTORED: rawValue moved OFF planBest(...) ONTO EquipCandidate (new last field
  DoubleUnaryOperator value); planBest(List) signature now. DP uses eq.value(). Per-candidate value.
- BotScrollManager WIRED: each candidate's value = BotScrollValuer.reproductionValue(baseOffenseValue
  (clean catalog stats via getEquipStats — strips "inc" prefix so keys are PAD/MAD/STR/DEX/..), tuc=
  getEquipStats."tuc", reproSpecs(owned options), cleanBaseCostMeso). Scroll meso cost now from
  shopitems: cached scrollShopPrices() = "SELECT itemid,MIN(price) FROM shopitems WHERE price>1 GROUP
  BY itemid" (lazy, populate-once volatile map) via DatabaseConnection; fallback DEFAULT_SCROLL_COST
  _MESO=1M for drop-only owned scrolls. Clean-base cost STUB = max(100k, reqLevel*10k) (placeholder
  for rarity->meso). ScrollOption.cost = scrollPriceMeso(sid) (was 0).
- CALIBRATION RESOLVED (user): owned-scroll per-apply cost = HYBRID = SCROLL_OPPORTUNITY_FRACTION(0.9)
  * market price. Rationale: scrolls are liquid/valuable so using one forgoes ~full sale value (stay
  near 1.0); may later become a per-bot PERSONALITY knob. Split cleanly: ScrollOption.cost =
  0.9*price (online evApply action cost); reproSpecs ScrollSpec.mesoCost = FULL price (value curve =
  what it costs to remake). 16/16 still green.
- STILL OPEN: clean-base cost is a reqLevel*10k stub (needs rarity->meso); cross-decision value cache
  per item type not added (curve memoized within one decision only); full market scroll catalog (vs
  owned-only) for the value curve is a 2b refinement; periodic equilibrium "market epoch" is capstone.

DEBUG COMMAND (committed). Chat the bot "scroll debug" (or "debug scroll") -> BotScrollManager
.exportScrollDecision writes scroll-debug-<botname>.txt in the SERVER CWD and the bot replies the
abs path. Dump = every candidate (name, current score, slots, value@now meso, [DOMINATED], its
applicable scrolls w/ p/+score/apply-cost) + the chosen (piece, scroll, expected meso gain, proposal)
+ the reproduction-cost table for the chosen piece (target score -> cheapest meso -> optimal first
scroll, via BotScrollValuer.explain). buildBestPlan refactored to share collectCandidates(). Deep-tail
high targets may show "(abandon+rebuy)" as first move (fp under-convergence at 120 iters; informative,
not a bug). To TEST: need a running server + a bot with scrollable worn/bag gear AND owned applicable
non-boom scrolls; say "scroll debug", read the txt. COMMITS on branch experimental: 2a94c9de0 (slice 2
reproduction-cost value), e7906460b (scroll debug command). BotScrollPlannerTest 11 + BotScrollValuerTest
6 = 17/17 green.
- Removed `withinLevelBand` gate (level-band false-blocked still-best low-req gear). Replaced by
  DOMINANCE: `betterItemAvailable` = >= `capacity` same-slot items are strictly better as-is AND have
  >= upgrade slots (capacity from EquipSlot.getSlotCount(): 4 for rings, 1 for most). A weaker base
  with MORE slots is NOT dominated (its scroll potential can surpass a maxed rival — the Q1 case).
- Bag candidates filtered by `wearable()` = ii.meetsEquipRequirements (job/level/stat) + (weapons)
  BotEquipManager.isWeaponCompatible — never burns scrolls on gear the bot can't use.
- `explainNoPlan` gives cheap chat reasons when nothing qualifies (no slots / no fitting scrolls /
  only boom / useless stats / out-classed gear / odds not worth).

v1 SAFETY CUT: auto-scan skips ALL destroy-capable scrolls (cursed>0) — no boom risk. Planner boom
gate is tested+ready; wiring boom scrolls + real fallback-slot detection is a NEXT increment.
REMAINING GAPS (see chat 2026-06): offense-only valuation (HP/DEF/avoid/acc/utility scored 0 → never
scrolled; needs real optimizer/DPS value); periodic auto-trigger when armed (only chains after a
confirmed scroll); persist toggle across relog; white-scroll slot protection; forward-looking
slotReserve from ledger.

Integration facts found: scroll apply = `ii.scrollEquipWithId(equip, scrollId, whiteScroll,
vegaItemId, isGM)` (null return = boom/curse); scroll stats via `getEquipStats(scrollId)`
(`success`/`cursed`/`inc*`); `getScrollReqs`; bots level via normal combat exp (so "save for higher
gear" is real). See also [[kb_bot_equip_optimizer]].

## 2026-06-08 session: config-aware odds, min-over-sources, GM filter, REAL rarity→meso
Commits on `experimental` (in order): 41da2c97e (debug dumps full scroll inventory + doc demand-atom
rationale), 49475c3da (SCROLL_SUCCESS_BONUS effective odds + initial godly-into-floor — later reverted;
log to logs/bot-scroll/), 22ce82fbc (base cost = MIN over sources, clean floor, removed godly
averaging, scrollShopPrices→shopPrices), 2b0bc2122 (GM/junk shop filter: drop shopitems listing if
buyPrice ≤ getWholePrice sell-back), 0a5a1abc5 (REAL rarity→meso BotFarmingCostModel), bd281e6d2 +
4fa5a421b (self-scrolling.html KB refresh to reproduction-cost + rarity).

DECISIONS baked in:
- Cost = MIN over sources (NPC shop price, farming cost); level/flat stubs = last-resort fallback only.
- GM-shop guard: exclude `shopitems` rows with buyPrice ≤ NPC sell-back (`ii.getWholePrice`).
- SCROLL_SUCCESS_BONUS folded into effective success (`effectiveSuccessPct`, mirrors scrollEquipWithId).
- GODLY_STATS_DROP_CHANCE: NOT averaged into clean floor (cheapest source = clean stats). Godly bases =
  higher-score products valued by drop source — deferred (needs rarity + roll distribution).
- Producer for kill-time = THE ASKING BOT (user choice).

BotFarmingCostModel (NEW, pure, 8 tests): rarity_meso = expectedKills(1/baseDropRate, capped
MAX_EXPECTED_KILLS=1e6) × (secondsPerKill + seekOverhead) × mesoPerSecond. secondsPerKill =
max(attackCycle, mobHp/dps) — FLOORED at one swing (user: "not 1000 kills/sec, cap it"). 0 dps / no
dropper → +∞ (un-farmable → caller falls back). Data sources: drop_data (chance/1,000,000, best dropper
per item, cached `bestDropperByItem`); mob HP+WDEF via `LifeFactory.getMonster(id).getMaxHp()/.getStats()
.getPDDamage()`; producer DPS = `bot.calculateMaxBaseDamage(bot.getTotalWatk())` reduced by mob def via
`BotEquipManager.expectedDamageAfterDef` (reused). Anchors in BotScrollManager: FARM_MESO_PER_SECOND=1000,
FARM_SEEK_OVERHEAD_SECONDS=3.0, FARM_ATTACK_CYCLE_SECONDS=0.72.

DATA-AVAILABILITY findings (DB=`cosmic`): drop_data has dropperid/itemid/chance ✓. SPAWN data NOT in DB
(`plife` empty for type='m') — mob spawn points (count/map, #maps, respawn mobtime) live in Map WZ;
user wants them cached to indexed files. Drop roll: `MapleMap.java:675` dropChance = chance×chRate×cardRate,
roll < nextInt(999999) → base rate = chance/1e6.

NEXT SLICES (deferred, user-named): (1) per-weapon attack timing (replace flat 0.72s w/
BotEquipManager.weaponCycleMs); (2) Map-WZ spawn-density cache → real mob-commonness term (replace flat
seekOverhead); (3) mage/ranged producer DPS (physical-only now); (4) godly-base drop-source product path.
Tests now 25 (Planner 11 + Valuer 6 + Farming 8). Always run targeted -Dtest=, skip nav [[feedback_skip_nav_tests]].

## 2026-06-23 session: population self-scroll TRIGGERING + town-break behavior (not valuation)
This session was about WHEN population bots self-scroll, not the value model above. Commits on
`experimental`: a468255de, cd3c6bd7d, dd7c151fc, b01531f48, e87930c4a. Needs a server RESTART to activate.
- **Default-on for population**: `BotManager.spawnManagedBot` sets `entry.selfScrollEnabled = true` (not
  persisted; re-set each spawn). DB `bot_prefs.self_scroll` still default 0 / opt-in for non-population.
- **Scan cadence 90-180s → 300-600s** (`BotScrollManager.AUTO_SCAN_MIN/MAX_MS`).
- **Gate (BotScrollManager.tickAutoScroll)**: managed/self-owned bots only self-scroll while
  `BotBreakManager.onBreak && bot.getMap().isTown()`; companions w/ online owner unchanged. Scan holds
  "due" (timer not re-armed) until the next town-break, then fires — so scrolling = ~once per town-break.
- **Town-break behavior**: self-scroll bots on autopilot take their break IN a town (vs in place):
  `BotBreakManager.maybeStartBreak` → `startTownBreak` sets `BotEntry.restErrand` (or in-town breakUntilMs);
  `BotAutopilotManager.resolveTownRestDestination` picks a shop-town (same picker as resupply, so arrival
  shop visit sells trash + resupplies via BotShopManager.onMapChange), arrival branch lingers
  `townBreakDurationMs()` = 10-30min (rest clock reuses breakUntilMs, started on arrival), then returns.
  Modeled as an errand so travel/return + cohesion peel-off are reused. clear() resets restErrand.
- **Group-synced break** (`BotAutopilotManager.maybeStartGroupBreak`, called from BotManager grind tick):
  party cohort breaks together — only the leader (cohort.get(0)) rolls, once/min, on the AVG of members'
  breakFreqPerHour/farmIdleRatio; on fire every member town-breaks (own independent errand). Solo bots
  keep the per-member `BotBreakManager.maybeStartBreak`.
- **Level-gap split** (`BotBreakManager.catchUpSplit`, reuses `cfg.PARTY_LEECH_GAP_TRIGGER`): cohort
  members in the low cluster (at/below the first >= trigger jump in sorted cohort levels) SKIP the group
  break and keep grinding solo to catch up — the break-phase complement to idle-leech (grinding phase,
  slows highs). Unit-tested in BotBreakManagerTest (catchUpSplit cases).
- **Grind-between-breaks math** (BotBreakManager.startsBreak): per-min p = (breakFreqPerHour/60)*(1-0.5*
  farmIdleRatio), capped 0.5; mean grind ≈ 1/p min. Population (bfreq 0.3-2, farm 0.45-0.95): ~40min
  (lazy) to ~6hr (diligent), median ~1-1.5hr. = how often a population bot self-scrolls. User kept this.
- **PERF**: scroll scan (`buildBestPlan`) is heavy (full inventory + WZ) and shares the single
  `BotGrindAdvisor.DECIDE_POOL` with grind/party decides = the "slow/expensive" — now mitigated ~10-100x
  by the town-break gate. Instrumented as "scroll-scan" (BotPerformanceMonitor). Live read via new
  `/api/perf?on=1` endpoint (see [[project_bot_web_observability_ssot]]).
