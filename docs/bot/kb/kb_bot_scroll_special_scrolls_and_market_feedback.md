# Special (req-restricted) scrolls + scroll market feedback loop — audit 2026-07-04

Audit of the auto-scroll system (BotScrollPlanner/Valuer/Manager) + scroll valuation/market
trading feedback, focused on special scrolls ([4yrAnniv], Dragon Stone 2041200, accessory
scrolls 20492xx). Branch dev-economy. Findings not yet implemented — this file is the record.

## Verified WZ/DB facts (don't re-derive)

- **Req-restricted scrolls** carry an `imgdir name="req"` list of exact equip ids in
  `Item.wz/Consume/0204.img.xml`; `ItemInformationProvider.getScrollReqs` reads it and
  `BotScrollManager.applicable()` honors it (req list wins; category match `(sid/100)%100 ==
  (eqId/10000)%100` is only the fallback). So req scrolls need NO special-casing — they already
  flow through planning/valuation emergently.
- **[4yrAnniv] scrolls** (22 ids, e.g. 2043013 1H-sword ATT 40% +3ATT/+2STR, 2041059-62 cape
  20% +3 stat): all `tradeBlock=1`, most `cursed=30` (boom), req = **Maple event gear only**
  (2043013→Maple Glory Sword 1302064, capes→Maple Cape 1102166-68). Obtainable: dropped 20%
  (chance 200000) by event boss **Giant Cake 9400606** only.
- **Dragon Stone 2041200**: success=100, +15 all stats/+140 PDD/+140 MDD/+15 EVA, `only=1`,
  `tradeBlock=1`, `reqRUC=3`, req = Horntail Necklace 1122000 (tuc=3). NOT in drop_data; only
  shop rows are price=1 GM shops (9999998, 1337) which the `shopPrices()` SQL (`price > 1`)
  already excludes → priced at DEFAULT_SCROLL_COST_MESO=1M.
- **`reqRUC` is read NOWHERE server-side** (ScrollHandler, ItemInformationProvider) — only the
  client enforces it. A bot applying Dragon Stone with <3 free slots deviates from client-legal.
- **Accessory scrolls 2049200-2049211** (pendant/ring/belt): NO req list, `cursed=50`,
  widely obtainable (NPC shops 1.54-2.42M, drops 3000/400 chance). Players use them via a
  hardcoded special case `ScrollHandler.canScroll` (sid/100==20492 → ring/pendant/belt cats).

## What's already correct (verified in code)

- Boom (cursed) scroll play is live for OWNED scrolls: `buildOptions` carries boomRate, planner
  gates on `hasFallbackForSlot` + EV prices the destroyed branch; 4yrAnniv boomers are usable.
- Cheap-scroll emergence works: per-apply cost + convex reproduction curve makes 60% dominate
  10% except in tails (validated worked example in [[project_bot_economy_and_self_scrolling]]).
- tradeBlocked items never listed on stalls: `BotFreeMarketManager.tradeable` =
  `!ii.isDropRestricted(id)` which reads WZ `info/tradeBlock`.
- `scrollsByCategory` maps req scrolls into their req items' categories (Dragon Stone lands in
  pendant cat 12); boom scrolls are excluded from this CATALOG index (but not from owned play).
- Chaos/White read live consensus (`max(10M floor, consensus)`).
- `BotMarketConsensus.consensus(key)` returns **0 when no evidence** — safe to branch on.

## Gaps found (ranked) — STATUS 2026-07-04: 1-6 FIXED same day (see commit on dev-economy;
## shared ItemConstants.canScroll, consensus-blended scrollPriceMeso, scroll WTP in
## maybeBargainBuy, applyCostMeso helper, bestRoleWorth in marketStatValue, obtainable-only
## catalog index). 7-8 still open. Caveat on 1: owned accessory scrolls now plan/apply, but all
## obtainable 20492xx are cursed=50 → still excluded from the CATALOG index (boom filter), so
## accessory band curves stay flat until a non-boom accessory scroll exists.

1. **Accessory-scroll applicability hole.** `applicable()`'s category fallback maps 20492xx to
   nonexistent cat 92 → bots can never use/value the ONLY generic pendant/ring/belt scrolls,
   and accessory equips get no scroll set (`bandUnit=0` → flat band curves for those slots).
   Fix: share `ScrollHandler.canScroll` (extract, rule 1 — bots share player code) instead of
   the reimplemented fallback in `applicable()`.
2. **Scroll USE never reads the market → feedback loop is open on the demand side.**
   `scrollPriceMeso` = static min(NPC shop, farm cost) for all regular scrolls; only chaos/white
   blend consensus. A glut (consensus falling) never lowers apply-cost → usage never rises;
   scarcity never raises it → no hoarding signal. Fix shape: shop-sold → min(shop, consensus>0);
   drop-only → consensus>0 ? consensus : farmCost. (Listing/supply side already self-corrects
   via unsold-pressure undercut + repriceAsk.)
3. **No demand-side WTP for scrolls at stalls.** `maybeBargainBuy` non-equip branch needs a
   prior belief (perceived>0) — a fresh scroll market can't clear, the exact problem
   `equipBuyCeilingMeso` solved for equips in S4. `scrollCombatCeilingMeso` (a real WTP) exists
   but is only used as the USE-shelf keep cap. Fix: belief-less scroll listings buyable when
   ask < min(obtain cost, scrollCombatCeilingMeso)-style ceiling.
4. **Untradeable scrolls charged full opportunity cost.** Per-apply cost =
   `SCROLL_OPPORTUNITY_FRACTION(0.9) × scrollPriceMeso` — but a tradeBlocked scroll forgoes NO
   sale (can't be sold/listed/traded). Dragon Stone gets a phantom 900k apply-cost (1M default
   price); 4yrAnniv get 0.9×farm-cost. Biases bots AGAINST the exact scrolls the owner wants
   used emergently. Fix: opportunity fraction ≈ 0 when `ii.isDropRestricted(sid)` (the DP's
   cross-candidate comparison + SCROLL_OPPORTUNITY_MARGIN still prevent waste). Keep FULL
   farm-cost in reproSpecs (remaking one still costs effort).
5. **Unobtainable scrolls pollute market reproduction curves.** `scrollsByCategory` has no
   obtainability filter; a no-source scroll prices at the 1M default in `marketReproSpecs`.
   Dragon Stone (100% success, huge gain, fake 1M cost) makes HTP's band curve near-flat — any
   rolled HTP quotes barely above clean. Fix: catalog index (market curves) = obtainable-only
   (has legit shop row or dropper); owned-scroll options keep using anything in the bag.
6. **Multi-stat scroll market worth sums all four mains.** `marketStatValue(Map)` predates the
   2026-07-04 best-use fix (`bestRoleWorth`) — Dragon Stone's +15×4 scores 60 where its best
   buyer gets ~19.5. Overprices all-stat scrolls in ceiling/band-unit math (~3x). Low impact
   today (Dragon Stone untradeable) but inconsistent with equip pricing; use bestRoleWorth on
   scroll inc-stats too.
7. **reqRUC unenforced for bots** (and server). Client-legality nit: honor `reqRUC` (WZ
   info key, NOT in getEquipStats today) in `applicable()`/eligibility so a bot never applies
   Dragon Stone with fewer free slots than a real client allows.
8. **Pure-defense scrolls never used.** `buildOptions` gain = `offenseValueFromStats` (no
   survival terms), so e.g. [4yrAnniv] Shield DEF / HP scrolls are always "no offense gain"
   even though equip keep/market axes DO count survival (WDEF/MDEF/HP/EVA weights). Dragon
   Stone still qualifies via +15 mains (gain 19.5 any job) but its 280 def/+15 avoid are
   invisible to the self-scroll DP. Known offense-only-axis gap; fixing means moving the DP
   axis to equipValue-style scoring consistently (base score, gain, worn rival all together).

## Answer of record: are 4yrAnniv / Dragon Stone valid for bot use?

YES mechanically (req path works end-to-end, no special-casing needed) — a bot holding the
scroll + its req item will propose it emergently. In practice suppressed by gap 4 (phantom
opportunity cost) and, for defense-only specials, gap 8. Obtainability is event-gated
(Giant Cake / HT quest), which is fine — the machinery, not the spawn schedule, was the question.
