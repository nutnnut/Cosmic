---
name: kb_bot_use_value_shelf
description: "USE-inventory pressure-driven value-shelf sell model (runway/junk/shelf), per-set rechargeable ammo valuation, inv debug tiers; replaced the old keep-all-potions + party-arrow-reserve logic"
metadata: 
  node_type: memory
  type: project
  originSessionId: 3642999b-0ea3-48de-a127-d7a7b288cf9e
---

USE-bag hoarding (Preston: 20+ slots cheap recovery pots; Clawer: 28 slots / 191k throwing stars, 2070004 alone = 20 slots) fixed by replacing `collectSellTrashUseItems`'s keep-all-potions/keep-all-own-ammo logic with a **pressure-driven + value-shelf model** in `BotInventoryManager` (mirrors the equip kept-valuables shelf — SSOT, no parallel impl).

**Follow-up (2nd commit) — runways made resupply-loop-safe, buffs get a runway, autopilot auto-buff:**
- **Buff runway:** buffs were all SHELF (a cramped trip could dump att/matt combat buffs). Now a BUFF bucket in `classifyBagUse`; `BotBuffManager.runwayBuffItems(bot)` = union of `buildSelection(bot,false)`+`(…,true)` (best per relevant stat-key, WATK/MATK incl., covers cheap+max) → RUNWAY, rest SHELF. `BotBuffManager.fxCache` now routes through `BotInventoryManager.useEffect` (shares test seam).
- **Resupply-loop fix (critical):** runway must sit >= the resupply BUY target or the bot buys→shelf→sells→rebuys, bleeding meso. Recovery runway now keeps strongest-heal stacks until cumulative HP qty AND MP qty each >= `BotShopManager.potResupplyTarget()` (=`POT_LOW_WARN(100)*POT_TARGET_THRESHOLD(5)`=500); non-rechargeable own ammo until cumulative >= `ammoResupplyTarget()` (=`AMMO_LOW_WARN(500)*10`=5000); rechargeable ammo still 1 set. Dropped the flat `consumableRunwaySlots`. HP/MP split via new `BotPotionManager.healsHp/healsMp` (SSOT, reused by countPotions).
- **Autopilot auto-buff:** `BotBuffManager.autoEngageForToughMobs(entry,bot)` (called atop `tick`, throttled 5s, autopilot-only): hardest live mob (`max getMaxHp`), `shots = maxHp / BotCombatManager.estimateBestSkillHitDamage(entry,bot,mob)` (SSOT, same as `BotGrindAdvisor.killSeconds`); shots>=3 → enable cheap buffs (`buffCheapMode=true`, `autoBuffEngaged=true`); shots<2.5 (hysteresis) and `autoBuffEngaged` → turn its own enable off. Manual `buff on/off` clears `autoBuffEngaged` so owner wins. New `BotEntry.autoBuffEngaged/lastAutoBuffEvalMs`.
- Tests: `BotInventoryManagerTest` buff-runway + recovery-resupply-floor; `BotBuffManagerTest` auto-buff enable/disable/manual-wins. 50 tests green.

**Model — `classifyBagUse(bot)` → `Map<Item, UseClass>` with tier RUNWAY/JUNK/SHELF:**
- RUNWAY (never auto-sold): best own-ammo set (rechargeable=1 set since it recharges free; consumed arrows = `consumableRunwaySlots` = clamp(3+lvl/20,3,8) slots), recovery runway (top-N by `recoveryHealScore`), `ALL_CURE_RESERVE_SLOTS` all-cure.
- JUNK (always sold on any trip, `collectSellTrashUseItems`): single-ailment cures (antidote/eyedrop/tonic/holy water = `isSingleCurePotion`), irrelevant equip scrolls, stale quest items. All-cure is reserved, NOT junk.
- SHELF (sold ONLY when USE tab cramped): everything else — extra recovery, other-class & surplus ammo, buffs, misc. Ranked by value-per-slot ascending (`keepValue`); `collectCrampedUseSales(bot, slotsToFree, exclude)` sells worst-first down to `USE_HEALTHY_FREE_SLOTS=6` (in BotShopManager). Stacks ≥ `USE_NEVER_SELL_MESO=50000` are protected even under pressure.

**Valuation:** meso SSOT = `sellPrice`=`getPrice(id,qty)`. **Rechargeable ammo (stars/bullets) is quantity-INDEPENDENT** — value = `ammoSetValue`=`getWholePrice(id)` per set; the 1st shelf slot of a tier carries set value, duplicate same-tier slots = 0 (sell first). So Clawer's 19 redundant 2070004 sets lead the sale, best set + distinct tiers kept.

**Removed:** `PARTY_ARROW_RESERVE`/`isPartyArrowReserveItem`, `sellTrashQuantity` party branch (now sells whole slot), unconditional own-ammo keep. All ammo (own + other-class) now governed by cost model (user: "let the cost model decide fully", "ammo valuable for trading").

**Cure accessors (only non-bot edit):** `StatEffect.curesAnyDebuff()` / `curesAllAbnormalStatus()` — the latter combines `isCureAllAbnormalStatus()` (WHITE_ELIXIR only) with `cureDebuffs` covering all 5 ailments (the regular All Cure Potion 2050004 sets the flags, not the sourceid).

**Q1 debug:** `inv debug` (`inventoryDebug`) USE section now shows tier (RUNWAY / SHELF#rank / SELL) + unit/stack meso + reason; derived from `classifyBagUse` (no second decision tree). It's the USE/ETC analog of `autoequip debug`.

**Test seams** (BotInventoryManagerTest): `useEffect` (ItemEffectLookup), `projectileWatk`, `ammoSetValue`, `sellPrice` — all swappable to avoid the unrunnable ItemInformationProvider static init. `isRecoveryPotion`/`isBuffConsumable` route through `useEffect`. Skipped the optional chat tuning verb (kept diff surgical; inv debug already gives dry-run visibility). Related: [[kb_bot_independence_infra]], [[kb_bot_maker_economy]], [[kb_bot_grind_gear_valuation]].
