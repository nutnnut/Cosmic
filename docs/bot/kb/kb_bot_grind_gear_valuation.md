---
name: kb-bot-grind-gear-valuation
description: "Grind advisor gear-farm valuation — cross-slot exclusivity bars (2H/shield, overall/top/pants), weapon speed DPS normalization, where the SSOTs live"
metadata: 
  node_type: memory
  type: project
  originSessionId: a05517cc-fbd8-4558-83bc-a6266fec5fbc
---

How BotGrindAdvisor decides an equip drop is "worth farming" (commit df9ec64f8):

- **Wearability gate** = `BotScrollManager.levelsUntilWearable` (job/level/stat via `ii.meetsEquipRequirements`, weapon-type via `BotEquipManager.isWeaponCompatible` for slot -11, **shield slot -10 returns -1 when the worn weapon is 2H** — a bowman's weapons are all 2H so shields are never farmable/scrollable for it). The autoEquip optimizer does NOT use this gate; it builds pools via `canWearEquipment` and enforces exclusivity inside the DP (`slot == -10 && is2H` skip, `isOverall` blocks pants -6).
- **Cross-slot bars**: `equipGain` calls `gearBar(...)` → `crossSlotBar(combinedBest, pieceBest, partnerBest, candidateIsCombined)` = `max(combined, piece+partner) - keptPartner`. Families: overall(-5, `ItemConstants.isOverall` = id/10000==105) vs top(-5)+pants(-6); 2H vs 1H(-11)+shield(-10). Ensemble pieces cached in `wornScoreBySlot` under synthetic positive keys (105 top, 106 overall, 111 1H, 112 2H; real slots are negative).
- **Weapon speed**: `weaponSpeedFactor(itemId)` = 720ms / `BotEquipManager.weaponCycleMs(itemId)` (WZ attack animation × speed tier — the same number `scoreNode` uses for the optimizer's true DPS benchmark; returns 0 → factor 1.0 when WZ unavailable). Applied to BOTH `sampleRollScores` and `bestOwnedScore` for slot -11 so a slow high-watk spear must out-roll a fast one by the cycle ratio.
- `offenseValue` (BotScrollManager) stays speed-blind by design — it's called from unit-test contexts where ItemInformationProvider can't load; never add II lookups inside it.
- UPDATE (commit 9c6a5e6a5, 2026-06-19): equip valuation is NO LONGER offense-only. Added `survivalValue`/`survivalValueFromStats` (WDEF/MDEF/HP/MP/avoid/move, small weights; ACC deliberately excluded — owned by the aspirational-accuracy model) and `equipValue = offenseValue + survivalValue`. `potentialValue` now bases on `equipValue`; the grind Monte Carlo roll score + worn-baseline normalizer (`totalWornValue`) use `equipValue` too (keeps marginal E[max(0,roll-worn)] in one unit). `offenseValue` itself is UNCHANGED — still the pure-DPS sub-metric for scroll EV (`bestScrollEvPerSlot`). So defensive/utility gear (capes, shields, accessories) now has nonzero farm/keep/reward worth. See [[kb_bot_fetch_quests]].
- UPDATE (commit f23ac9b18, 2026-06-19): QUEST gear rewards now ride the SAME SSOT as grind/maker/gacha. `BotQuestManager.computeRewardGain` (replaced computeUniqueRewardValue / the `uniqueRewardValue` seam → new `rewardGain` seam returning record `RewardGain(dpsGainFraction, consumableMeso)`) values a reward equip via `BotGrindAdvisor.expectedAcquireGain(bot, ii, itemId, cleanSampler, 1, barCache)` (cleanSampler = `sampleEquipScores(...,(base)->base)`), scrolls via `BotGrindAdvisor.scrollGains.gain`, divided by `BotGrindAdvisor.totalWornValue` (now package-visible) → a %DPS fraction exactly like GearProspect.dpsGainFraction. `scoreQuest` converts: `uniqueBonus = dpsGainFraction * baseline * GEAR_PAYBACK_HORIZON_MINUTES(240=4h) + consumableMeso*REWARD_EXP_PER_MESO`. baseline CANCELS in value/cost → gear-quest worth is grind-rate-invariant (only exp reward scales by the quest/mob expRateRatio). Removed flat UNIQUE_REWARD_EXP_PER_OFFENSE=50 and BotScrollManager.scrollRewardEv. Cape (naked lv25) ~225→~5400 exp-equiv.

Related: [[kb_bot_equip_optimizer]], [[project_bot_independence_infra]].
