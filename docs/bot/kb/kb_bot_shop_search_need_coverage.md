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
`BotInventoryManager.classifyReturnScrollRunway`. Before departing for an off-map autopilot destination, a
missing runway starts a resupply errand even when pots/ammo are fine; the return-trip guard prevents that
detour from rearming immediately after shopping.
