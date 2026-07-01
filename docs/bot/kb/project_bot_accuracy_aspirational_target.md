---
name: project_bot_accuracy_aspirational_target
description: "Aspirational-grind-mob SSOT that breaks the low-DEX vicious cycle — accuracy now valued as effective-DPS for gear farming + AP DEX floor, both aimed at the map the bot WANTS not the one it's stuck on"
metadata: 
  node_type: memory
  type: project
  originSessionId: 7995dc2d-0bb9-42de-a41a-d225ab3399e6
---

Problem (found via live bot "alone": lv18 Warrior, STR93/DEX9, ~9 accuracy → ~1% hit even on lv15 avoid-5 Bubbling): a low-DEX warrior is hit-locked, so the planner discounts harder maps by hit-chance and never moves there, so the AP floor (which sampled the CURRENT map) never asks for DEX, so it stays hit-locked. Also `BotScrollManager.offenseValue` (farm-desirability SSOT) is `att+mainStat+secondaryStat` — **accuracy/incACC is invisible to it**, so the bot would never farm a Fish Spear (1432008, +5 ACC, dropped by Bubbling 1210103) — its defining stat scored as 0. (autoEquip Pareto path DID value ACC at BotEquipManager:1504 → the two valuations disagreed.)

Fix (SSOT, grind-advisor-local, low blast radius):
1. `BotGrindAdvisor.pickAspirational` — best **accuracy-blind** (raw) exp/sec among mobs the bot does NOT one-/two-shot (frontier filter `rawKillSeconds >= ATTACK_CYCLE * ASPIRATION_MIN_HITS=1.75`; falls back to global-best if it one-shots everything). Biasing off trivial mobs is what makes it point at content the bot grows INTO — otherwise raw exp/hr picks the densest low-level farm (a one-shot's kill time floors at the attack cycle), e.g. lv12 mob for a lv22 bot → 95% hit → accuracy never fires. IMPORTANT (user asked "filter vs multiply bot damage — which is SSOT?"): real damage is the SSOT; the frontier is a SELECTION policy layered on top, NOT a faked dps multiplier (multiplying damage is wrong-direction AND corrupts the kill-time SSOT). Cached on `BotEntry.aspirationalMobLevel/Avoid` (avoid<0 = unset) by the off-thread grind pass.
2. `accuracyHitFactor` = hit(acc with item)/hit(acc now) vs the aspirational mob, capped `MAX_ACCURACY_HIT_FACTOR=10`. Folded into `expectedAcquireGain`'s new `sampleScaleExtra` param (Maker/gacha pass 1.0, unchanged). This is the "accuracy as effective DPS" the user wanted — Fish Spear leaps up the farm shortlist when hit-starved, ~1.0 once hitting. `accContribution(eq) = acc + 0.8*dex + 0.5*luk`.
3. `BotBuildManager.accuracyDexFloor(entry,bot)` now reads the cached aspirational mob (avoid+levelDelta-aware inversion) instead of `bot.getMap().getAllMonsters()`; falls back to current-map median until first grind pass. Test asserts floor=12 for the "alone" scenario (was effectively 0/parked at 9).

Structural change: `buildCandidates` is now two passes (profile-all → pick aspirational + cache → gear-with-hit-factor → blend); `profileFor` no longer computes gear (moved out so it can be keyed to the aspirational mob); `MobProfile` gained `avoid` + `rawKillSeconds`; `killSeconds`→`killProfile` returns `[discounted, raw]`. Discounted kill-time still drives the planner's actual ranking (unchanged) — raw only feeds the aspirational pick.

Verify: offline `BotAccuracyAspirationTest` (4 tests, green; 110 bot tests no regression). In-game: owner tells bot **"grind debug"** → `logs/bot-grind/grind-debug-<name>.txt`; header now shows `acc=`, aspirational mob lv/avoid, current hit% — look for Fish Spear as a wanted prospect on Bubbling's map. (Live run still pending — needs server up.)

Calibration scenario documented in docs/bot/economy-design.md (Fish Spear = high-value + hard-to-self-farm → bot should BUY not grind; market WTP must consume this same effective-DPS model, not re-derive). Relates to [[project_bot_economy_and_self_scrolling.md]], [[kb_bot_grind_gear_valuation]], [[kb_bot_equip_optimizer]], [[project_grind_advisor_perf]].
