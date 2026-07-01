---
name: Bot Equip Optimizer Architecture
description: Pareto-DP equip optimizer file:line map (autoEquip + trade-offer share one DP oracle, rings included, relaxation fallback, infeasible-equipped sweep)
type: project
originSessionId: 6ed0a34e-4345-4dee-8ff6-8b102d66fcfd
---
# Bot Equip Optimizer (Pareto-DP) — Architecture & file:line Map

## Algorithm — at a glance
- **Single oracle**: `solveForWeapon` runs a Pareto-frontier DP across non-weapon slots wrapped in an outer loop over each viable weapon. autoEquip, autoEquipDebug, AND trade request/offer (`findRecommendedEquips`, `findRecommendationForItem`) all go through this same DP — no parallel greedy scorer remains.
- **Rings live in the DP** (since 4029170c2). Four ring slots draw from a shared pool keyed at `-12`; `solveForWeapon` does cross-slot dedupe via `candLoop` continue when the same ring instance was already picked at an earlier ring slot. Slot-specific assignment is arbitrary (rings give stats regardless of position).
- **Final-stat req model (Option A)**: each chosen item must satisfy reqs against the FINAL state, capturing cross-slot stat chains.
- **Best-effort relaxation**: if no frontier node validates (Pareto pruning may drop the all-empty state in favor of states with infeasible picks), `relaxToFeasible` iteratively drops infeasible picks (cascading until stable). Returns null only when the weapon itself fails reqs against the bare snapshot.
- **Feasibility-guarded Pareto pruning** (2026-05-04): `paretoPruneNodes` now takes `OptimizerHooks + dpSlots`. A node B can only dominate A when ALL picks in B satisfy `meetsReqs` against `B.snap` at this DP step. Prevents stat-gated picks (e.g., dex70 glove for a dex=50 thief) from pruning unconditionally-equippable fallbacks that they vec-dominate on stat sums. Optimality preserved under monotonic-stat-growth: if B feasible at slot i, B feasible at final slot, so direct scoring (not relaxation) finds B's score, and vec-dominance is preserved under same-suffix extension. Approach A in `tmp/equip-optimizer-pareto-fix-proposal.md`.
- **Null-weapon fallback**: if every weapon branch returns null, callers retry with `weapon=null` so an armor-only plan still emerges.
- **Score (lex, 2-tuple since some prior simplification)**: `EquipScore(damage, statSum)`. Defense was dropped from score because wdef/mdef are inert in this game.
- **Mob benchmark** = highest-avoid mob (`MapDamageProfile.snapshotByAvoid`).
- **Mage damage objective** = `round(int*1.1) + magic`.
- **`usefulStatSum` weights** (since 67c62e781): HP/MP ×0.1, mage tier `int*5 + matk*4` (INT > MATK), all other stats unweighted.
- **Hard cap `MAX_PARETO_STATES = 2000`** per step; overflow falls back to admissible-bound truncation and surfaces via `DpResult.paretoCapHit` → chat warning "inventory's too cluttered…".
- **Pareto vec (8 dims, since 2026-05-14)**: `[str, dex, int_, luk, watk, magic, totalAcc, statSum]`. Dropped hp/mp (folded into statSum at ×0.1 anyway). statSum is back in vec — now safe because score is 2-tuple `(damage, statSum)` with no defense dim, so dominance preserves both lex levels. Tightens dominance vs the brief 7-dim version: ties on damage-stats now resolve via statSum instead of leaving both states on frontier.
- **`allPicksMeetReqs` cached per node** (since 2026-05-14): hoisted out of the O(N²) inner dominance loop. Was O(N² × slots × WZ) — now O(N × slots × WZ) for the feasibility check + O(N² × vecLen) for vec comparisons. ~N× speedup on the dominant cost.
- **Pre-DP dedup vec is job-aware** (since 2026-05-14): `pruneDominatedWithReqs(ii, items, job, reqRel)` replaces the 14-dim raw `statVec`. Dims now: `[job_primary_stat, watk-or-magic, eff_acc (warrior/brawler only), req-relevant str/dex/int/luk]`. eff_acc = `acc + dex + luk*0.5`. When raw dims tie, tiebreak = `eff_primary + watk*4`, where `eff_primary = primary + sum(secondaries)*0.25`. Non-req-relevant secondaries collapse into the tiebreak so they don't bloat the dominance vec. Helpers: `jobStatPriority`, `isAccRelevantJob`, `scanReqRelevantDims`, `dedupStatVec`, `dedupTiebreak`, `dedupDominatesPre`. Other prune fns (`pruneDominated`, `pruneDominatedSameTrackWithReqs`) untouched — they serve trade/lookahead flows with different semantics.
- Slot-collision constraints inline: 2H weapon → shield empty; overall at -5 → pants empty.

## Core file:line map (`src/main/java/server/bots/BotEquipManager.java`)

| Component | Approx line |
|---|---|
| `EQUIP_LOG_DIR = logs/bot-equip` | 50 |
| `RING_SLOTS = {-12, -13, -15, -16}` | 55 |
| `MAX_PARETO_STATES = 1000` | 57 |
| `record EquipScore(damage, defense, statSum)` (lex compare) | 83 |
| `static void autoEquip(bot, owner, pendingOffer)` | 100 |
| `buildDpSlots(bySlot, currentBySlot)` — adds all 4 ring slots if any ring exists | ~170 |
| `static List<String> autoEquipDebug(bot)` — chat + file dump | ~195 |
| `writeAutoEquipDumpFile(...)` | ~280 |
| `collectAutoEquipCandidates(...)` — pool builder, rings keyed at -12 | ~460 |
| `record DpResult(picks, score, paretoCapHit)` | ~495 |
| `record OptimizerResult(weapon, picks)` | ~501 |
| `runOptimizerWithExtras(bot, extras)` — DP oracle for trade flow | ~510 |
| `interface OptimizerHooks` (testability seam) | ~570 |
| `solveForWeapon(... ItemInformationProvider ...)` legacy overload | ~605 |
| `solveForWeapon(... OptimizerHooks ...)` core DP w/ ring dedupe | ~615 |
| `paretoPruneNodes(nodes, capHit[], hooks, dpSlots)` — feasibility-guarded since 2026-05-04 | ~712 |
| `vecDominates(b[], a[])` | ~755 |
| `allPicksMeetReqs(node, hooks, dpSlots)` — Pareto dominance guard | ~764 |
| `validateReqs(...)` | ~776 |
| `relaxToFeasible(hooks, node, dpSlots, weapon)` — cascade-drop infeasible picks | ~795 |
| `scoreNode(node, weapon, wt, mob)` | ~823 |
| `nakedBase(bot, ii, eqdInv)` | ~785 |
| `applyEquipPlan(...)` — order: weapon, -5, -6, others incl. rings | ~795 |
| `unequipInfeasibleEquipped(bot, ii)` — post-apply sweep using `canWearEquipment` | ~825 |
| `findRecommendedEquips(receiver, holder)` — runs DP with holder items merged | ~875 |
| `findRecommendationForItem(receiver, holder, item)` — runs DP with single extra | ~915 |
| `usefulStatSum(equip, job)` — hp/mp ×0.1, mage int*5+matk*4 | ~1450 |
| `damageWith(sim, ii, wt, mob)` | ~1340 |
| `expectedDamageAfterDef(rawMax, wdef)` — uniform-roll integral | ~1385 |
| `weaponCycleMs(itemId)` — wrapped in try/catch | ~1410 |
| `record StatSnapshot` (package-private) | ~1300 |
| `record MapDamageProfile` (`snapshot` + `snapshotByAvoid`) | ~1500 |

## Hooks — what the DP needs from `ItemInformationProvider`
`OptimizerHooks` exposes only 4 calls so unit tests can stub via lambdas (Mockito **cannot** instrument II's static WZ initializer):
- `boolean isTwoHanded(int itemId)`
- `WeaponType getWeaponType(int itemId)`
- `boolean isOverall(int itemId)` — wraps `"MaPn".equals(ii.getEquipmentSlot(...))`
- `boolean meetsReqs(Equip, Job, level, str, dex, int, luk, fame)`

## Trade request/offer — DP oracle path
- `findRecommendedEquips`: collects holder's tradeable equips (incl. rings), calls `runOptimizerWithExtras`, recommends only picks whose value came from holder. No more legacy greedy scorer or per-slot `scoreEquipFull` / lookahead.
- `findRecommendationForItem`: runs `runOptimizerWithExtras(receiver, [candidate])` and recommends iff the optimizer picks that exact instance. Identity-equality check (`p.getValue() == candidate`) since holder items are distinct Equip instances from receiver's.
- `runOptimizerWithExtras` re-runs `pruneDominatedWithReqs` after merging extras so newly-added items can knock out dominated incumbents.

## Chat surface (`BotChatManager.java`)
| Pattern | Action |
|---|---|
| `AUTOEQUIP_DEBUG_PATTERN` | Calls `autoEquipDebug`, sends each line as bot chat, also writes file dump. **Must match before** plain pattern. |
| `AUTOEQUIP_PATTERN` | Calls `autoEquip(bot, owner, pendingOffer)` then "ok, gear optimized" |

## Trade-decline restore safety net (`BotInventoryManager.java`)
- **Bug it fixes**: bot temporarily unequips an item to make it tradeable; if owner declines/cancels/timeouts the trade, `restoreTemporarilyUnequippedItems` may silently skip restore. Bot ends up wearing pants without a top.
- **Fix**: `resetTradeState` snapshots `hadRestores` BEFORE calling restore, then if true, calls `BotEquipManager.autoEquip(bot, entry.owner, null)` AFTER state reset.

## Deleted in 4029170c2 (rings-in-DP refactor)
The following legacy non-DP code is gone — do not try to call or revive:
- `autoEquipRings`, `findRecommendedRings`, `findRecommendedRingForItem`
- `findBestWithLookahead`, `scoreEquipFull`, `scoreEquipCombo`, `bestTopPantsCombo`, `TopPantsCombo` record
- `buildLookaheadBySlot`, `pruneDominated`, `unlockObjective`, `primarySlotOf`, `currentWeaponType`, `isOverall(Item, ii)` overload

## Pareto stat-gated dominance bug (fixed 2026-05-04)
- **Symptom**: Clawer Assassin equipped INT glove (Red Marker) over LUK glove (Purple Work Gloves). Log: `equiplog-Clawer-2026-05-03T153709.txt`.
- **Cause**: Orihalcon Arbion (req dex70 luk95) vec-dominated Purple Work Gloves on every nodeVec dim (luk, def, hp, mp, statSum). PWG pruned. Arbion later failed validateReqs (bot dex=51), got relaxed to "no glove". Red Marker survived (its INT prevented Arbion dominance) and won on def tie-breaker.
- **Fix**: dominance now requires `allPicksMeetReqs(B, hooks, dpSlots)` — node with stat-gated picks the partial snapshot can't satisfy is "speculative" and can't prune fallbacks.
- **Why monotonic stats make this safe**: DP only adds stats; empty option always carried forward. If B feasible at slot i, B feasible at final slot. Vec-dominance preserved under same-suffix extension → `scoreNode(B+suffix) ≥ scoreNode(A+suffix)`.
- **Alternative considered**: Requirement-as-Vector (add 4 negative-cost dims for max req str/dex/int/luk to nodeVec). Rejected because it (a) explodes frontier under cross-stat-req incomparability, (b) penalizes feasible high-req items, (c) raises cap-hit risk where `damagePotential` truncation isn't optimality-preserving. Full proposal: `tmp/equip-optimizer-pareto-fix-proposal.md`.

## Knowledge that's load-bearing for future work
- **`snapshot()` (level-priority) vs `snapshotByAvoid()`**: only `snapshotByAvoid` is used now.
- **EquipSlot text codes**: `Cp` hat, `Af` face, `Ay` eye, `Ae` ear, `Ma` top, `MaPn` overall, `Pn` pants, `So` shoes, `GlGw` gloves, `Sr` cape, `Si` shield, `Wp` weapon. Overall-blocks-pants logic depends on the `MaPn` text code via `OptimizerHooks.isOverall`.
- **Lex score order**: `damage > defense > statSum`. If hit-chance isn't dominating in high-avoid maps, raise the multiplier inside `damageWith` (currently `expectedAfterDef * hitChance * 1000`).
- **`damageWith` still takes II** (param) but doesn't use it post-refactor — `scoreNode` passes null. Cleanup deferred.
- **Ring slot moves**: `applyEquipPlan` won't reshuffle rings between equipped slots (skips when target's `getPosition() <= 0`). Stats unaffected; only cosmetic. If ever needed, would require explicit unequip→re-equip dance.
- **Tests**: `BotEquipOptimizerTest` (3 DP tests) + `BotEquipManagerTest` (13 mob-profile / damage tests). Tests stub `OptimizerHooks` with lambdas; **never** mock `ItemInformationProvider` directly.
