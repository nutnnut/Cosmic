---
name: Bot nav self-loop portal route unfollowable
description: ROOT CAUSE job-advance/travel stuck = committed route needs an intra-region PORTAL self-loop the follower can't traverse; fix re-searches a portal-free walk route.
type: project
---
# Self-loop portal routes froze the bot at `no-path`

**Symptom (live 2026-06-26):** bot `RougHWealtH` (thief, lv30) stuck in Kerning City (103000000)
job-advancing to BANDIT. `tickApproachNpc` gave up with `deadline`, `bestDist=3902` never improving
(bot frozen at the far-west platform x=-1180, never moved). Pathlog: `nav=no-path edge=none` every AI
tick toward the east00 portal (region R104). Yet `/api/pathfind R136→R104` returned a clean reachable
route. The bot just stood there and retried forever (`JOB_CHANGE_FALLBACK_ANYWHERE=off`).

## Root cause

The cheapest route R136→R104 teleports through **Kerning's east/west in-map shortcut portal** — an
**intra-region PORTAL self-loop** (`fromRegionId == toRegionId == R148`, cost 0, e.g. x=-855 → x=1313).
The committed-route follower **cannot traverse a self-loop portal** (matching the loop by region would
re-select it forever — see the GearArrow ping-pong in [[kb_bot_nav_oscillation_rootcauses]]). So:

1. `computeCommittedRoute` finds the optimal route, sees the self-loop PORTAL, and **returns `null`**
   (deliberate — won't commit an unfollowable loop).
2. Fallback `findNextEdge`: baked `portalRoute`/`portalNextHop` also **can't represent a self-loop**
   (the chain's `seen`-set guard in `BotNavigationGraph.portalRoute` terminates it as empty), and R104
   isn't a baked portal-target. So it lands on `findPath`.
3. `findPath` runs the per-bucket seeded weighted A*; the goal heuristic (`costToGoal`) counts the free
   portal so it mis-prunes the long walk-around, **caps at `MAX_EDGE_CHECKS` (160k) → empty → no-path**.
   With the route cache re-enabled (commit `00baf6c5b`) the empty result is cached as `NO_EDGE`, wedging
   it permanently.

**Why `/api/pathfind` disagreed (debug pitfall):** the tool runs `runSearch` from the region *center*
with `routeSeed=0` (pure Dijkstra) and **with** the portal, so it finds the cheap self-loop route the
live per-hop planner can't follow. The tool is NOT a faithful mirror of the committed-route follower for
self-loop-portal maps.

## Key fact

The target **is reachable without the shortcut portal** — a plain walk/jump/climb route to R104 exists
(region-BFS: 180/184 regions reachable walk-only). The walk-around is just long: ~300k edge checks,
which is why the default 160k budget caps short of it.

## Fix (commit on `experimental`)

`BotNavigationManager`:
- `runSearch` gains a `boolean excludeSelfLoopPortals` (last param) that skips
  `PORTAL && fromRegionId==toRegionId` edges during expansion. All existing callers pass `false`.
- `computeCommittedRoute`: when the optimal route contains a self-loop portal, **re-search with
  self-loop portals excluded** (exact h=0 Dijkstra, seed 0, budget `PORTAL_FREE_EDGE_CHECKS=640k`) and
  commit that portal-free walk route. Only if it still can't reach (target genuinely needs the portal)
  do we return `null` → per-hop planner as before.
- `PORTAL_FREE_EDGE_CHECKS = 640_000` (4× standard): the portal-free walk-around is position-state-heavy
  (Kerning needs ~300k; 160k caps). Runs only on the rare replan whose optimal route used a self-loop
  portal, so the one-off cost is fine.

Net: the committed route is always self-loop-free and directly followable; the bot walks the long way to
east00 instead of freezing. Verified: `BotNavigationManagerTest#excludeSelfLoopPortalsStillReachesViaWalk`
(WZ-backed, real Kerning) — optimal route uses the self-loop portal; the excluded re-search reaches R104
with no self-loop in the path.

## Debugging surface that nailed it

`/api/bot/pathlog?id=<botId>` (toggle record → dump) showed `nav=no-path` live — the decisive signal.
`/api/mapgraph?id=&sp=&jmp=` + `/api/pathfind?from=&to=` show the route *as the tool searches it*
(seed 0, portal allowed) — useful but remember it diverges from the live follower on self-loop maps.

Related: [[kb_bot_nav_search_scaling]] (cache re-enable + portal route index), [[kb_bot_errand_dwell_clobber]]
(the other job-advance-stuck root cause), [[kb_bot_nav_oscillation_rootcauses]] (why self-loop commits are banned).
