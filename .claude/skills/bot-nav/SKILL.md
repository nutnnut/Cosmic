---
name: bot-nav
description: Use when debugging or modifying bot movement, navigation, pathfinding, physics, or stuck bots — anything in BotNavigationGraph*/BotNavigationManager/BotMovementManager/BotPhysicsEngine/BotFallbackMovementManager. Triggers include "bot stuck", "bot looping", "jumping in place", "walks back and forth", a pathlog-*.txt file, "launch window", "nav graph", "GRAPH_VERSION", "bot won't climb/drop/jump", or graph cache questions.
---

# Bot movement & navigation

Cookbook for the bot nav stack at `server.bots.*`. Distilled from live stuck-bot debugging
sessions (CheatSTanK, TeensDusk, BishopDemo, CabinOpened — all in
`docs/bot/kb/kb_bot_nav_oscillation_rootcauses.md`). Read that ledger's entry list BEFORE
diagnosing a new stuck bot — most "new" stucks are a known class.

## Iron rules (user directives — do not violate)

1. **The graph is baked truth. The runtime does zero physics prediction.**
   The builder (`BotNavigationGraphProvider`) authors every edge by physics simulation at build
   time; the executor only checks the baked data (launch windows, landing points). NEVER add a
   runtime jump/fall simulation, and NEVER "help" an authored launch window at runtime (widening,
   tolerance, nudging). If a window or landing looks wrong, **fix the builder and bump
   GRAPH_VERSION**. (A runtime widening caused the CheatSTanK jump-in-place loop; a runtime jump
   sim was explicitly rejected by the user.)
2. **Author the whole outcome envelope or nothing.** If an edge's live outcome depends on state
   the graph doesn't encode (fractional pixel phase, arrival speed, carryMs), the builder must
   verify the outcome is stable across that whole spread (`BotPhysicsEngine.walkOffLandingVariants`
   precedent) — or not author the edge. One sampled outcome is a planner lie: the executor
   "succeeds" (dismounts, lands), touches down somewhere else, replans through the same edge,
   and loops forever without tripping stuck detection.
3. **Fix the layer that is wrong.** Physics bug → fix `BotPhysicsEngine`, not a workaround in
   navigation. Wrong graph data → fix the builder, not an executor guard. Executor gate too
   loose/tight → fix the gate. Never patch downstream.
4. **Bots move legally.** No position snapping/teleport hacks; movement goes through the same
   physics a player packet would produce. Recovery from unhittable states is the blocked-pos
   watchdog + replan, not a forced reposition.
5. **Profile mismatch = no arcs.** A graph built for one speed/jump profile must not fly
   JUMP/FLASH_JUMP arcs with different live stats (arcs are per-x simulated for THAT profile).
   The planner masks arcs on mismatch (`EXCLUDE_JUMP_ARCS` + clearing `SKILL_FLASH_JUMP` in
   `runSearch` when `graphMatchesLiveProfile` fails). Keep `canReach`/`costToGoal`/
   `nearestReachableRegion` on the same mask so reachability verdicts match executability.

## Layer map

| File | Layer | What it owns |
|------|-------|--------------|
| `BotPhysicsEngine.java` | Physics SSOT | All movement integration + all build-time sims: ground motion (8ms client sub-steps, fractional `physX`, `carryMs`), jumps (`velY` 0,5,10,…cap), falls, ropes, slippery ground, `simulateWalkOffLanding`, `walkOffLandingVariants`, `simulateFallLanding`, `teleportLanding`. Builder and executor MUST use the same functions. |
| `BotNavigationGraphProvider.java` | Graph builder | Region extraction, edge authoring (`addJumpEdges`, `addDirectionalDropEdge`, `addDropEdges`, rope/portal/teleport/FJ edges), launch-window expansion (`expandJumpLaunchWindow` — the authored window IS the maximal physically-valid span), `GRAPH_VERSION`, disk cache `cache/bot-nav/v<N>/`, `peekGraph`/`peekClosestGraph`/`peekBestGraph`, async warmup. |
| `BotNavigationGraph.java` | Graph data | Regions, edges, launch windows, `canReach`, `getOutgoing(regionId, skillMask)`, skill mask bits (`SKILL_TELEPORT`, `SKILL_FLASH_JUMP`, `EXCLUDE_JUMP_ARCS` — the last is an EXCLUSION bit), serialized route buckets. |
| `BotNavigationManager.java` | Planner + executor gates | A* (`runSearch`), committed routes + route buckets, edge execution gates (`canExecuteSelectedJumpFromCurrentPosition` — strict window containment, `isWithinJumpLaunchWindow`, `matchesDirectionalDrop`, `selectDropWaypoint`), precise steering (`preciseNavStopDist` returns 0 for JUMP edges — walk INTO the window), blocked-pos watchdog (`trackBlockedPositionGate`, ~300–500ms give-up → replan). |
| `BotMovementManager.java` | Motor | 50ms ticks, walking/climbing (`tickClimbing` — rope dismount requires being near rope BOTTOM), jumping, formation offsets, `clearNavigationState`. |
| `BotFallbackMovementManager.java` | Heuristic fallback | Movement when NO graph exists yet (`graphWarmupFallback`). Dumb but must not loop; bugs here surface fleet-wide after every GRAPH_VERSION bump (cold cache). |
| `BotMovementProfile.java` | Profile key | (speed, jump, snowShoes) bucketed to 5s, capped 140/123. Graph cache key. `fromCharacter` is the live-side; mismatch handling per iron rule 5. |
| `BotNavigationMapLoader`, `BotMovementSimulationLab` | Test infra | Load real WZ map geometry; step the full bot movement stack headless. Lab CANNOT execute portals (null client NPE) — assert graph-level (`canReach`, edge properties) when the route crosses a portal. |

## Live debugging workflow (server up, port 8089, no auth)

Route list SSOT: `createContext(...)` in `BotWorldGraphWebServer.start()`; docs in
`docs/bot/web-endpoints.md`. Prefer these over log-scraping.

1. **Capture a pathlog**: `GET /api/bot/pathlog?id=<charId>` toggles the recorder; the file lands
   in `logs/bot-nav/pathlog-<name>-<ts>.txt`. Get charId from `/api/live` (search by name) or
   `/api/botdebug` (filters by `?id=` only, NOT `?name=`).
2. **Read the header first**:
   - `Graph:` line — `exact` / `closest ... requestedSpeed=X` / `none/warming` +
     `Fallback: heuristic=yes|no closestGraph=yes|no`. This splits the bug into three classes:
     exact-graph bug (graph data or executor gate), profile-mismatch bug (closest graph flying
     wrong arcs — iron rule 5), or heuristic-fallback bug (no graph at all).
   - `Stuck: no` does NOT mean not stuck — position keeps changing in a loop. Find the cycle
     period in the tick history instead (same edge + same positions repeating).
   - `r=-1` + `AIR(...)` mid-descent is NORMAL airborne, not a bug.
   - `nav=` values: `reuse` (same edge), `route` (next committed edge), `replan` (new A*),
     `exec` (edge fired, e.g. portal), `no-ai` (off-cadence tick).
3. **Reconstruct the loop**: list the repeating edge sequence (e.g. r4 →drop→ r6 →drop→ r7
   →portal→ r4). The edge whose REAL outcome differs from its AUTHORED outcome is the bug.
4. **Compare authored vs live numbers**: edge line gives authored launch/landing/window/stepX;
   tick history gives live launch pixel, `airVelX`, landing. Any disagreement = builder/executor
   mismatch → fix per iron rules 1–3.
5. **Probe hypotheses without redeploying**: `/api/navprobe?id=<botId>&x=&y=` (reachability from
   the bot's stance), `/api/pathfind?id=<mapId>&from=<r>&to=<r>` (A* on the cached graph),
   `/api/mapgraph?id=<mapId>[&sp=&jmp=]` — WARNING: a novel sp/jmp BLOCKS and builds that
   profile's graph on demand.
6. **Triage the live bot** (server still runs old code until restarted): POST `/api/command`
   `{"cmd":"moveto","ids":[id],"x":..,"y":..}` to reroute/park, `"resume"` to end the override.
   Commands persist ~30 min. There is no warp — bots move legally (iron rule 4).

## GRAPH_VERSION rules

- Bump ONLY when serialized graph content or authoring semantics change. Runtime-only changes
  (executor gates, steering, watchdogs) never bump.
- A bump invalidates ~9.6k persisted graphs → fleet-wide cold cache for hours. Expect
  closest-profile and heuristic-fallback code paths to get exercised hard right after a bump —
  latent fallback bugs WILL surface then (TeensDusk, BishopDemo). That is a reason to fix the
  fallbacks, never a reason to skip a semantically-required bump or to revert one whose
  authoring fix is correct.
- Append a numbered note to the version-constant comment describing what changed (see the
  constant in `BotNavigationGraphProvider`).

## Known stuck classes (index — details in `docs/bot/kb/kb_bot_nav_oscillation_rootcauses.md`)

- Jump-in-place at a window boundary → runtime window widening (forbidden; #10/#11).
- Overshooting arcs on `closest` graph → profile mismatch (#12).
- Rope attach→instant dismount loop on heuristic fallback → rope-bottom gate (#13).
- Drop lands wrong region → replan loop through the same edge → knife-edge authoring (#9/#14).
- Walk-in-place against a committed edge from the wrong foothold → live walk-off sim gate
  (`selectDropWaypoint`).
- Region oscillation at joined forks / shared-ground phantom jumps → builder chain rules (#5b/#8).

## Testing

- Synthetic maps: build `Foothold`s + `FootholdTree` by hand, `BotNavigationGraphProvider
  .rebuildGraph(map)` — fast, no WZ. Idioms in `BotDirectionalDropNavigationTest`.
- Real geometry: `assumeTrue(Files.isDirectory(Path.of("wz","Map.wz")))`, then
  `BotNavigationMapLoader.loadMapGeometry(mapId)` + `BotMovementSimulationLab` for end-to-end
  (`spawnBot`/`setMoveTarget`/`step`/`position`), or graph-level asserts when portals are on the
  route. Idioms in `BotHenesysDeptStoreDescentTest`, `BotFreeMarketEntranceDescentTest`.
- Every live stuck fix gets a regression test named for the map/incident, and a KB ledger entry.
- Run only the nav tests you touched (project rule: nav/graph tests dominate wait time):
  `BotNavigationManagerTest`, `BotMovementManagerTest`, `BotDirectionalDropNavigationTest`,
  `BotNavigationGraphFallbackTest`, plus the WZ descent tests when authoring changed.
- Compile/test command (this machine):
  `cmd //c "mvn.cmd test -q -Dtest=<Tests> -DfailIfNoTests=false"` with mvn at
  `C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.14\bin\mvn.cmd`; results in
  `target/surefire-reports/server.bots.<Test>.txt` (console output is mostly Mockito noise).

## Physics cheat-sheet (for reading pathlogs, not for reimplementing)

- Tick = 50ms; ground physics runs in 8ms client sub-steps with fractional `physX` + `carryMs`
  carry — per-tick pixel steps therefore ALTERNATE (e.g. 6/7 at 105% speed). Never assume a
  constant integer step; this variance is exactly what iron rule 2 exists for.
- Fall: `velY` 0,5,10,15,… per tick, capped 33.5; horizontal drift = `airVelX` seeded from the
  last ground step at dismount, constant while airborne (air steering authority is ~negligible).
- Committed JUMP/FJ arcs fly "launch key held" → constant stepX, which is why per-x build-time
  arc sims are exact for the SAME profile and wrong for any other (iron rule 5).
- Launch windows (`window=[a,b]` in pathlogs) are the maximal per-x-simulated valid span. A bot
  1px outside is physically unable to make the arc — steer in, or watchdog out; never accept.
