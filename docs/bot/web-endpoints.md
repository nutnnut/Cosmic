# Bot web endpoints

Served by `server.bots.BotWorldGraphWebServer` (started from `Server` boot). Bound to all
interfaces on **port 8089**, **no auth** — exposes online player/bot names + locations to anyone on
the LAN (accepted: private game-server LAN). Open `http://<server-lan-ip>:8089/`.

> **Keep this in sync.** The `createContext(...)` list in `BotWorldGraphWebServer.start()` is the
> SSOT for routes. When you add/change/remove a route or its JSON shape, update this file. Exact
> field sets live in the code (`charJson`, `mapInfoJson`, `botDebugJson`, `serveCommand`); this doc is
> the map, not the schema of record.

## Pages

| Route | Method | Purpose |
|-------|--------|---------|
| `/` | GET | Legacy bot world-graph page (`botworld.html`). |
| `/map` | GET | RTS world map (`worldmap.html`): graph over WorldMap continent images, live positions, RTS control, collapsible per-map detail. |
| `/admin` | GET | Admin/settings menu (`admin.html`): edit bot config live, drive the population scheduler + LLM toggle, danger zone (disconnect-all / wipe). Front end for `/api/settings`. |
| `/mapgraph?id=<mapId>` | GET | Single-map nav-graph preview (`mapgraph.html`): regions (footholds/ropes), typed edges, NPCs, portals, live characters. Click a region for its `!pos` report; the **Pathfind** button lets you pick a source then target region to preview + verdict the route (`/api/pathfind`). **`show:` view toggles** (all client-side, all default OFF): `partition` colours regions by reachability component (SCC over the walk-only edge set — the same adjacency `BotNavigationGraph.canReach` uses; one colour = fully connected, multiple = a split map); `downjumps` (DROP edges), `jump-downs` (JUMP landing lower), `fj-downs` (FLASH_JUMP landing lower) show those normally-hidden down-edge classes. Opened from a world-map node's detail-panel button (same tab) or by middle-clicking a node (new tab). |
| `/wm/{worldmapId}.png` | GET | A WorldMap continent background image. |

## Read APIs (JSON, GET)

### `/api/worldmaps`
The world graph laid out over the WorldMap images: per worldmap `{id, x, y, scale, nodes[], edges}`;
each node `{id, maps[], names[], x, y, hub, anchor, dup, danger, leaf, unreachable}`. A node may merge
several maps (`maps[]`). Drives the map page. (See `serveWorldMaps`/`worldmapsJson` for exact fields.)

### `/api/live`
Live occupancy of every online character, bucketed by map. Cached ~750 ms.
```
{"maps":{"<mapId>":{
   "players":[{"id","n","l","j","c","p","g"}, ...],
   "bots":[{"id","n","l","j","c","p","g","a","status"}, ...]
}}}
```
`n`=name, `l`=level, `j`=job, `c`=commandable (1 = managed/RTS-controllable bot), `p`=party id (0=none),
`g`=crew id (0=none). `a`=coarse activity bucket (`"grind"`|`"break"`|`"chill"`; chill = whole-session
chill login, gacha/idle/logging-off → break, resupply/travel/quest → grind) for the roster tally (bots
only). `status`=the
@botstatus line (bots only; drives the roster-hover tooltip and the right-panel detail — omitted for
players and for bots with no registry entry).

### `/api/mapinfo?id=<mapId>`
On-demand detail for one map.
```
{"mobs":[{"name","level","spawns"}, ...],     // spawns = WZ spawn-point count
 "bots":[{"name","status"}, ...],             // status = the @botstatus line
 "chat":[{"t","n","m"}, ...]}                 // recent map chat: time, name, message
```

### `/api/mapgraph?id=<mapId>[&sp=<>&jmp=<>&snow=0|1]`
One map's bot {@code BotNavigationGraph} + live features for the `/mapgraph` canvas. Loads the map on
demand (map factory builds it from WZ — footholds/portals/NPCs included) and blocks once on the cached
graph build, so an empty map still resolves. `sp`/`jmp`/`snow` pick which cached movement-profile graph
to render (default speed100/jump100 `BotMovementProfile.base()`); `active` echoes the served profile and
`profiles` lists the profiles currently cached for this map (the page's graph picker). `portals.k`
classifies each portal: `in` = shortcut whose target is this same map, `cross` = press-up portal to
another map, `coll` = a collision-warp portal type (WZ `pt` ∈ {3,9,12,13}). `edges` are de-duped to one
per (from-region, to-region, type), each carrying its `fromR`/`toR` region ids so the page can list a
selected region's edges (hover a listed edge to highlight it on the canvas). Each edge also carries `cost`,
`lsx` (launchStepX) and `n` = how many parallel launch-x edges collapsed into this one drawn line (`n>1` —
common around ropes — is why the raw `/api/pathfind` `explored` overlay shows more edges than are drawn).
Every drawn edge is **clickable** for its details (type, regions, cost, lsx, endpoints, parallel count).
Each region's `report` is the **same** debug text the `!pos` command prints (SSOT:
`BotNavigationDebugOverlay.describeRegion`); the page shows it when a region is clicked. The page also
renders top-bar composition stats (region counts split fh/rope, edge counts split by type), computed
client-side from `regions`+`edges`.
```
{"map","name","active":{sp,jmp,snow},"profiles":[{sp,jmp,snow},...],
 "bounds":{minX,minY,maxX,maxY},
 "regions":[{"id","kind":"fh","segs":[[x1,y1,x2,y2],...],"report":[lines]}   // foothold region: segment lines
          | {"id","kind":"rope","ladder","x","y1","y2","report":[lines]}],   // rope/ladder region: vertical span
 "edges":[{"t":"WALK|JUMP|DROP|CLIMB|PORTAL|TELEPORT|FLASH_JUMP","fromR","toR","cost","lsx","n","fx","fy","tx","ty"}, ...],
 "npcs":[{"x","y","n"}, ...],
 "portals":[{"x","y","k":"in|cross|coll","tm","n"}, ...],
 "chars":[{"x","y","n","bot"}, ...]}                             // live player+bot positions
```
The client renders regions/edges/npcs/portals once and repaints only `chars` on its 2 s poll. The same
region report backs the GM command **`!pos`** (`PosCommand` → `BotNavigationDebugOverlay.posReport`),
which reports the region you're standing on using your own movement profile.

### `/api/botdebug[?id=<botCharId>]`
Read-only per-bot autopilot internals for live debugging (party cohesion, follow, travel). No cache.
`?id=` filters to one bot and adds a `detail` block (live stats + learned skills). **Read this over a DB
`skills`/`characters` query** — the in-memory `Character` is the SSOT; the DB row lags until the next save.
```
{"bots":[{
  "id","n","map","lvl",
  "party","crew","owner",          // owner: "null" | "self" | <human name>
  "apParty","dst","errand",        // apParty = party-autopilot on; dst = travel target map; errand = resupply map (-1 none)
  "grinding","following","followTo","transit","waiting",
  "op",                            // operator override command name ("" = none)
  "wt","atk","aoe","noAmmo",       // combat-readiness: weapon type; resolved single-target/aoe skill ids (atk=0 => no offensive skill => basic swing only); ammo gate
  "status",                        // the @botstatus line
  "detail":{                       // only when ?id= given
    "job","str","dex","int","luk","watk","matk",   // totals (base+equip)
    "hp","maxHp","mp","maxMp","exp","meso",
    "atkSkill","aoeSkill",         // resolved attack choices (BotCombatManager.rebuildSkillCacheIfNeeded; 0 = none)
    "pos":[x,y],"navEdge",         // live position + committed nav edge summary (e.g. "TELEPORT r3->r7 (..)->(..)")
    "navDecision","edgeBlock",     // last resolveTarget decision ("exec"/"skill-hop"/"reuse"/..) + last edge block reason ("tele-mp"/"fj-pos"/..)
    "canMoveSkill":bool,           // passes the teleport/flash-jump gate right now (has skill && >40% MP && >500k meso)
    "skills":{ "<skillId>": <level>, ... }          // every learned skill, live
  }
}, ...],
"routeCache":{"hits","misses","rate"}}             // region-route cache effectiveness, cumulative since server start (rate = hits/(hits+misses)); A/B vs pathfind count in bot-perf CSV
```

### `/api/bot/pathlog?id=<botCharId>`
On-demand per-bot navigation trace, mirroring the `!botnav pathlog <name>` command. **Toggle:** the
first call attaches a 120-tick (~6 s) ring-buffer recorder (`BotEntry.pathLogger`) — recording is
otherwise OFF, zero per-tick overhead; the second call detaches it, dumps the trace to `logs/bot-nav`,
and returns the report. Use briefly to capture why a bot is stuck.
```
1st call -> {"recording":true,"bot":<name>,"msg":"recording started — call again to dump"}
2nd call -> {"recording":false,"bot":<name>,"file":<path>,"report":<full pathlog text>}
```

### `/api/perf[?on=1|0][&durationMs=5000]`
Live performance snapshot from `BotPerformanceMonitor` (per-subsystem timings, including `scroll-scan`,
grind decides, movement, pathfind). Monitoring is opt-in/off by default: `?on=1` enables it, `?on=0`
disables, no param just reports the current aggregate. `durationMs` resets the monitor, samples that
many milliseconds (capped at 60000), and returns a bounded window sorted by total CPU time; use this for
repeatable live checks instead of comparing cumulative snapshots. Enable it, let it run, then read to see
what's hot, or call `durationMs` for a one-shot sample.
```
{"enabled":true,"sampleMs":5001,"processCpuMs":123,"processCore":0.025,
 "heapUsedBytes":123,"heapTotalBytes":123,"heapMaxBytes":123,"heapDeltaBytes":123,
 "sections":[{"section":"tick-total","count":N,"totalMs":..,"avgMs":..,"maxMs":..,
              "cpuMsPerSec":..,"core":..,"callsPerSec":..,"sharePct":..,
              "slow":..,"slowAvgMs":..}, ...]}
```

### `/api/spawnbot?id=<charId>[,<charId>...]`
Bring offline bot character(s) back online as managed self-owned autopilot bots (reuses
`BotManager.spawnManagedBot`, the `BotScheduler` path). Respawns at the character's saved map/position
with its real level/job/skills/stats — it does **not** synthesize stats. For a controlled repro, respawn a
character that already has the build you want, then `/api/command moveto x y` to drop it on an exact spot
(e.g. a lv10 mage next to a snail). State-changing GET (LAN debug only). `spawned:false` = already online
or load failed.
```
{"results":[{"id":767,"spawned":true},{"id":814,"spawned":false,"note":"already online or load failed"}]}
```

### `/api/navprobe?id=<botCharId>&x=<>&y=<>[&skills=1][&mode=normal|exhaustive]`
Pathfinding probe: runs the bot's own nav search (`BotNavigationManager.runSearch` on the live graph)
from its current position to an arbitrary point on its current map. The "why can't the bot get there"
companion to `/api/bot/pathlog` — answers reachability for a hypothetical target (e.g. a portal's
approach point) without driving the bot there. `targetGroundY=-1` / `targetOnRope` flag the target
surface. `mode=normal` mirrors the live executor's bounded `"committed"` search, including best-effort
redirects for unreachable targets; `mode=exhaustive` runs a strict unbounded proof search.
`&skills=1` runs the **skill-enabled** planner — teleport / flash-jump edges the bot is eligible for by
skill possession (no MP/meso gate, no cost-saved threshold) — so the path can include `TELEPORT`/
`FLASH_JUMP`; default is walk-only. Probe a teleport mage / flash-jump hermit to confirm the planner
routes through skill edges. The response echoes `skills`.
```
{"bot","map","from":[x,y],"to":[x,y],
 "fromRegion","toRegion","targetGroundY","targetOnRope","skills":bool,
 "mode":"normal|exhaustive","canReach":bool,"reachable":bool,"reached":bool,
 "bestEffort":bool,"capped":bool,"finalRegion":n,"cost":n,"expanded":n,"hops":n,
 "path":[{"type":"WALK|CLIMB|JUMP|DROP|TELEPORT|FLASH_JUMP","fromR","toR","cost","lsx","from":[x,y],"to":[x,y]}, ...]}
```
`reachable` is an alias of `reached`: true only when the path actually lands in `toRegion`.
**redirecting** `"committed"` search — for an UNREACHABLE target it returns a best-effort partial path that
stops at the closest useful region instead of the exact target; `bestEffort:true` marks this case.
This is expected for some unreachable NPC/portal pixels where walking near enough is still useful to the
runtime `stuckNear`/interaction fallback.

### `/api/pathfind?id=<mapId>&from=<regionId>&to=<regionId>[&sp=<>&jmp=<>&snow=0|1][&mode=normal|exhaustive][&tp=1][&fj=1]`
Region-to-region pathfind for the `/mapgraph` UI: click **Pathfind**, click a source region, click a target
region — it draws the route and verdicts it. Pathfinds on the SAME cached movement-profile graph the page
renders (`sp`/`jmp`/`snow`; default base sp100/jmp100). Runs the **same `BotNavigationManager.runSearch` the
live bot uses** (SSOT — no parallel pathfinder), in one of two modes:
- `mode=normal` (default) — the live executor's `"committed"` **redirecting best-effort** search with the
  bot's bounded edge-check budget. On an unreachable/too-far target it walks AS CLOSE AS POSSIBLE; the
  `explored` array is the frontier it checked (drawn faint teal, clickable) so you can see where it gave
  up. It is de-duplicated to the **distinct** edges checked (A* re-pops regions, so the raw sink repeats the
  same edge many times); genuine parallel launch-x variants stay separate — those are the redundant edges.
- `mode=exhaustive` — **strict, UNBOUNDED** search: exhausts the graph so an empty path is a definitive "no
  route". `canReach` (a full directed reachability BFS) is the exhaustive proof of (un)reachability.

The tool has no live bot, so skill edges are **off by default (walk-only)**. `tp=1` enables teleport (mage)
edges and `fj=1` enables flash-jump (thief) edges — both `canReach` and the search honour the mask (via
`runSearch`'s `forcedSkillMask`, so no synthetic bot is needed). The response echoes `teleport`/`flashJump`.

Reachability uses the same verdict fields as `navprobe`: `reached:true` = genuine route;
`bestEffort:true` = produced a partial that stops at `redirect`
(`canReach:true` → A* capped; `canReach:false` → real graph gap, the "stuck in a movement loop" target);
`canReach:false, path:[]` (exhaustive) = proven unreachable.
```
{"map","from","to","profile":{sp,jmp,snow},"mode":"normal|exhaustive","teleport":bool,"flashJump":bool,
 "canReach":bool,"reachable":bool,"reached":bool,"bestEffort":bool,"capped":bool,
 "finalRegion":n,"cost":n,"expanded":n,"elapsedMs":n,"hops":n,"redirect":<regionId|-1>,
 "path":[{"type","fromR","toR","cost","lsx","from":[x,y],"to":[x,y]}, ...],
 "explored":[ ...same edge shape; only populated for a best-effort result... ]}
```

## Settings API

### `/api/settings` (GET + POST)
Live admin/tuning surface behind `/admin`. Same SSOT as the GM commands: `BotConfigReflect` (the
`!botcfg` reflection), `BotScheduler` (`@botpop`), `BotLlmConfig` (`!botllm`), `BotAdminOps` (`@botpop wipe`).

**GET** — snapshot of every tunable group:
```
{"manager":[{"name","value","type"}, ...],   // BotManager.cfg public fields (POPULATION_MULTIPLIER, break/loot/autopilot/party knobs)
 "combat":[{"name","value","type"}, ...],     // BotCombatManager.cfg public fields (the !botcfg set)
 "pop":{"enabled":bool,"multiplier":num,"status":[lines...]},
 "llm":{"enabled":bool,"debug":bool}}
```

**POST** — mutate one knob; dispatch on `cmd`:
- `{"cmd":"set","group":"manager|combat","field","value"}` → set a config field (case-insensitive). Returns `{"ok","msg"}` (`msg` starts with `OK` on success, mirrors `!botcfg`).
- `{"cmd":"pop","mult"?,"enabled"?,"sweep"?}` → set multiplier / toggle scheduler / force a sweep. Returns `{"ok","status":[lines...]}`.
- `{"cmd":"llm","enabled"?,"debug"?}` → toggle LLM chat (`debug:true` implies on). Returns `{"ok","enabled","debug"}`.
- `{"cmd":"perflog","seconds":1-300,"html"?}` → enable `BotPerformanceMonitor`, capture one clean window for `seconds`, export `logs/bot-perf/bot-perf-<ts>.csv`; `html:true` also runs `tools/botperf_report.py` to write the `.html` report next to it. Blocks for `seconds`. Returns `{"ok","msg":"CSV: ...|HTML: ..."}`.
- `{"cmd":"disconnectAll","confirm":"DISCONNECT"}` → disconnect every online bot (scheduler may respawn them). Returns `{"ok","disconnected":n}`.
- `{"cmd":"wipe","confirm":"WIPE"}` → **permanently delete** every managed bot (shared with `@botpop wipe`; real/shared accounts skipped). Returns `{"ok","wiped","skipped","lines":[...]}`.

Destructive verbs require the exact `confirm` token (the page prompts for it) — no real auth, LAN-only.

## Write API

### `/api/command` (POST)
RTS control. Body `{"cmd","ids":[botCharId,...],"maps":[mapId,...]?,"target":charId?,"x":int?,"y":int?}`.
`cmd` ∈ `idle | fidget | move | moveattack | moveto | follow | resume | dance | jump | cheer`. Applies to each
commandable bot id; `move`/`moveattack` resolve a per-bot destination from `maps[]` (clicked node:
hub-first, else nearest); `moveto` walks each bot to an exact `x`,`y` on its **current** map via the full
nav pipeline (A*/jumps/climbs) and holds there — the live physics-debug + RTS "go exactly here" surface
(exposed in the RTS toolbar as the x/y inputs + **Go xy**); `follow` needs `target`. Returns
`{"ok","applied","skipped":[ids...]}`. Commands persist ~30 min, then revert to autopilot (`resume` ends now).
