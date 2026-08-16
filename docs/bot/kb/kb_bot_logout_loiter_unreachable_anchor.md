# Logout-loiter stuck on a cross-region anchor

**Symptom (pathlog):** a `loggingOut=true` bot stands at one spot for minutes
(`Stuck: YES (256200ms)`), every tick `nav=no-ai` / `Last nav decision: no-ai
(AI step suppressed: logging out / lingering)` — even though the AI cadence is firing.
The bot has a far `Move target` labeled `[set by: script-task]` (e.g. r41 bot, target
r51 across a portal), and the A* path shows a full multi-hop route
(DROP→JUMP…→PORTAL→JUMP) it never advances on.

**Root cause:** `tickLogout` was the ONLY `loiterAtAnchor(...)` caller that hardcoded
`runAiTick=false` (the other three — party-wait, farm, operator-follow — all pass the
live `runAiTick`). `runAiTick=false` doesn't just stop fighting; it hard-blocks
navigation: `BotNavigationManager.tryExecuteEdge` returns null immediately when
`!runAiTick`, so no JUMP/DROP/PORTAL/skill-hop ever fires and the bot can only
plain-ground-walk inside its current region. `pickTownLoiterAnchor` picks a random
map-wide NPC, so the logout anchor can sit a portal/jump away — graph-reachable but
untraversable with movement AI off. Hence stuck until the linger deadline, and the bug
appears ONLY on logging-out bots.

**Fix:** `tickLogout` now loiters with the live `runAiTick` like every other caller, so
the bot navigates (marches across town) to its anchor. The loiter's opportunity-attack
is a no-op because this branch only runs once the bot is in a safe, monster-free town
(`canReturnToDifferentMap && anyLiveMonster` sends monster maps down the return-scroll
branch first). One-line change at the `loiterAtAnchor(entry, bot, botPos,
entry.logoutAnchor, ...)` call.

**Rejected alternative:** constraining `pickTownLoiterAnchor` to a same-region
(ground-walkable) anchor. Owner wants the march-across-town behavior, and the anchor
picker is shared with the operator idle-at-spot path — better to fix the one odd caller
than reshape the shared SSOT.

Related: [[kb_bot_town_nav_airborne_target]] (other town-nav stranding root causes).

## Mobless non-town logout retreat

The Door to Zakum (`211042300`) exposed a second logout failure mode. Its WZ data
marks it `town=0`, gives it return map `211000000`, and defines no monster spawns.
The old logout guard required a live monster before returning, so a bot with
`loggingOut=true` stayed in the lobby and `pickTownLoiterAnchor` could select a
far-right ground anchor. Multiple bots therefore converged at the map's right
edge even though the navigation graph was healthy (`same-region`, no edge or
fallback activity).

`tickLogout` now retreats from every non-town map that has a distinct return map,
including mobless quest lobbies, before selecting a logout loiter anchor. The
regression is covered by `BotManagerTest.logoutRetreatsFromMoblessNonTownDoorToZakum`.
