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
   "bots":[{"id","n","l","j","c","p","g","status"}, ...]
}}}
```
`n`=name, `l`=level, `j`=job, `c`=commandable (1 = managed/RTS-controllable bot), `p`=party id (0=none),
`g`=crew id (0=none). `status`=the @botstatus line (bots only; drives the roster-hover tooltip and the
right-panel detail — omitted for players and for bots with no registry entry).

### `/api/mapinfo?id=<mapId>`
On-demand detail for one map.
```
{"mobs":[{"name","level","spawns"}, ...],     // spawns = WZ spawn-point count
 "bots":[{"name","status"}, ...],             // status = the @botstatus line
 "chat":[{"t","n","m"}, ...]}                 // recent map chat: time, name, message
```

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

### `/api/navprobe?id=<botCharId>&x=<>&y=<>[&skills=1]`
Pathfinding probe: runs the bot's own nav planner (`BotNavigationManager.findPath` on the live graph)
from its current position to an arbitrary point on its current map. The "why can't the bot get there"
companion to `/api/bot/pathlog` — answers reachability for a hypothetical target (e.g. a portal's
approach point) without driving the bot there. `targetGroundY=-1` / `targetOnRope` flag the target
surface; `reachable=false` with `path:[]` means no route from `fromRegion` to `toRegion`.
`&skills=1` runs the **skill-enabled** planner — teleport / flash-jump edges the bot is eligible for by
skill possession (no MP/meso gate, no cost-saved threshold) — so the path can include `TELEPORT`/
`FLASH_JUMP`; default is walk-only. Probe a teleport mage / flash-jump hermit to confirm the planner
routes through skill edges. The response echoes `skills`.
```
{"bot","map","from":[x,y],"to":[x,y],
 "fromRegion","toRegion","targetGroundY","targetOnRope","skills":bool,
 "reachable":bool,"hops":n,
 "path":[{"type":"WALK|CLIMB|JUMP|DROP|TELEPORT|FLASH_JUMP","fromR","toR","from":[x,y],"to":[x,y]}, ...]}
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
