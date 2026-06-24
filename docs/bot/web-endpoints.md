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
   "bots":[{"id","n","l","j","c","p","g"}, ...]
}}}
```
`n`=name, `l`=level, `j`=job, `c`=commandable (1 = managed/RTS-controllable bot), `p`=party id (0=none),
`g`=crew id (0=none).

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
    "skills":{ "<skillId>": <level>, ... }          // every learned skill, live
  }
}, ...]}
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

### `/api/perf[?on=1|0]`
Live performance snapshot from `BotPerformanceMonitor` (per-subsystem timings, including `scroll-scan`,
grind decides, movement, pathfind). Monitoring is opt-in/off by default: `?on=1` enables it, `?on=0`
disables, no param just reports the current aggregate. Enable it, let it run, then read to see what's hot.
```
{"enabled":true,"sections":[{"section":"scroll-scan","count":N,"avgMs":..,"maxMs":..,"slow":..,"slowAvgMs":..}, ...]}
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

## Write API

### `/api/command` (POST)
RTS control. Body `{"cmd","ids":[botCharId,...],"maps":[mapId,...]?,"target":charId?,"x":int?,"y":int?}`.
`cmd` ∈ `idle | fidget | move | moveattack | moveto | follow | resume | dance | jump | cheer`. Applies to each
commandable bot id; `move`/`moveattack` resolve a per-bot destination from `maps[]` (clicked node:
hub-first, else nearest); `moveto` walks each bot to an exact `x`,`y` on its **current** map via the full
nav pipeline (A*/jumps/climbs) and holds there — the live physics-debug + RTS "go exactly here" surface
(exposed in the RTS toolbar as the x/y inputs + **Go xy**); `follow` needs `target`. Returns
`{"ok","applied","skipped":[ids...]}`. Commands persist ~30 min, then revert to autopilot (`resume` ends now).
