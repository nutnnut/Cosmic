---
name: Need-first shop discovery
description: Search local and return-town rings together, then rank dynamic stock coverage before distance.
---

# Need-first shop discovery

Shop maps are often nested below a hub (`town -> market -> department store`). Searching only a fixed
radius from the field can reach the town but miss its better store. `BotShopManager.findNearestShopMap`
therefore unions two bounded rings: six portal hops from the bot's current map and three from the map's
return-town hub. Every candidate is still verified routeable from the bot's actual map.

Rank shops by how many current needs their live `Shop` inventory satisfies (weapon, recharge/fixed ammo,
HP/MP potions, Nearest Town Scroll), then by route hops and map id. Stock coverage outranks which ring
found the shop. This keeps the policy data-driven: moving a store or changing its catalog requires no
search-logic edit. Leafre's Sly department store is the regression example, not a production special case.

Nearest Town Scrolls are part of the normal ten-scroll shopping runway and are protected from junk sale by
`BotInventoryManager.classifyReturnScrollRunway`. Trigger and target are deliberately separate numbers, the
same split pots use (`POT_TRIGGER_THRESHOLD` vs `POT_TARGET_THRESHOLD`): `returnScrollRunwayLow` (under
`RETURN_SCROLL_TRIGGER_QTY`, 3) is the only thing allowed to START a trip or a shop stop, while
`shouldBuyReturnScrollWhileShopping` (under `RETURN_SCROLL_TARGET_QTY`, 10) governs how far to top up once
the bot is already buying, and how shops are ranked by coverage. Collapsing the two makes a bot holding 9
scrolls walk to town to buy one.

Before departing for an off-map autopilot destination, a thin runway starts a resupply errand even when
pots/ammo are fine. That trigger is affordability-gated by `canAffordReturnScrollResupply` exactly like its
pot and ammo neighbours — a bot that cannot cover one scroll keeps grinding instead of peeling to town every
cooldown to buy nothing. The return-trip guard prevents the detour from rearming immediately after shopping.
