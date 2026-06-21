# Bot System — Quick Start Guide

[Patch Notes](docs/bot/patch-notes/README.md)

## Features

- bot autonomy / autopilot - send a bot or party off to travel, grind, resupply, and play on its own
- fully ownerless bots/living-server population (`!botpop`)
- follow, trade, loot
- auto fight/grind, use skill, auto assign ap/sp, buffs (Only 1st jobs + select 2nd job configured/tested)
- auto buy/resupply potions/ammo if shop available within the same map
- auto share potions/ammo/rocks among themselves and to owner(if requested) when running low while farming
- auto equip and optimize own gear loadout - including chaining stats bonus to unlock higher requirement equips and weapons, allowing dexless/strless/lukless builds
- auto gear progression, grind mob for drop, grind scroll, apply scroll
- auto share equipment upgrades with owner and other sibling bots (auto compare stats directly against recipient's inventory)
- auto sort equipment from junk equip so selling trash equips become manageable. (sell trash command available)
- automation command for maker skill disassemble gear / craft monster crystals
- auto questing (basic quests like kill/fetch/talk quests)
- optional AI LLM support (reply to chat, no effect on gameplay whatsoever)
- Party Quest Automation(currently only KPQ 1st stage + auto accept rewards 5th stage)
- Each bot is a real character you can log in as, can spawn your alts as bots

## Bot Maintenance Guide

1. [Give them mesos, take them shopping to resupply pots/ammo - can also manually give a bot some excess and they will auto distribute among themselves.](docs/bot/consumable-automation-audit.html)
2. use `inv?` command to check inventory spaces, `trade <equip/use/etc/...>` or `trade trash` to get those items, or `sell trash` if you like to live dangerously (bot might sell something expensive)
3. use `brb` or `logout` command to send bot to town safely or despawn

## Creating a Bot

### Option 1: create default 
`@spawnbot <characterName> confirm`

### Option 2: register existing character
log in on the target character and run: `@registerbot <characterName>`

### Option 3: auto-generate a brand-new bot
`@spawnbot generate confirm` invents a procedural MMO name and auto-creates the account + character for it (password `botbot`). Add `autopilot` to have it start playing on its own (see below).

## Spawning a Bot

`@spawnbot <name|generate> [confirm] [autopilot]`

- `<name>` — spawn an existing/authorized character as a follower bot.
- `generate` — pick a procedural name instead of typing one (creates it with `confirm`).
- `confirm` — create the character if it doesn't exist yet.
- `autopilot` — spawn it **self-owned / ownerless**: it plays independently from the moment it spawns instead of following you. Fresh ownerless bots start at level 1 in Mushroom Town.

**Spawning many bots:** there's no single "spawn N" command — each `@spawnbot` makes one bot. To stand up a crowd, run `@spawnbot generate confirm autopilot` repeatedly (each gets its own name and plays on its own), then optionally group your own alts with `@botparty`.

### Buddy invite shortcut
Add bots as friends. Bots will always show up as online. Invite a bot to party or chatroom through buddy menu to spawn.

### Take over the character you're on
`@botme` turns your current character into a bot. `@botparty` does it for a full party of your alts (everyone else in the party must be a bot) and runs party autopilot.

## Bot commands

All commands are sent via **general map chat** (normal typing).
* Currently bot will also accept command across map even if you do normal chat (using follow command will teleport all bots you have spawned from other map to your location)

With multiple bots: target one specifically with a unique name-start prefix, full name, or slot number

```
stop - party wide stop
jason follow - only Jason will follow
jas come - only the unique bot whose name starts with "jas" will follow
1 stop - only bot slot 1 will stop
grind - party wide auto grind
jason stop - only jason will stop
```

---

## Management Commands


| Command | Effect |
|---|---|
| `transfer <botName> to <newOwner>` | Hand bot ownership to another player
| `!takebotowner <botName> [newOwner]` | GM force-reassign bot ownership
| `dismiss <botName>` | Leave bot idle AND ignore commands (make it afk)
| `recruit <botName>` | Get dismissed bot back to follow you

## Commands Available (chat with the bot)

### Movement & Combat

| Say | Effect |
|---|---|
| `follow (me) (here)` / `come (here)` | Bot follows you |
| `follow <name>` | follows party member or sibling bot |
| `stop` / `stay` / `wait` / `hold on` / `idle` / `park here` | Bot stops moving |
| `move here` / `go here` / `move` / `here` | Bot moves to your exact position and stops (Handy for party quest puzzles) |
| `grind` / `farm` / `hunt` / `kill mobs` / `auto on` / `go get exp` | Seek and kill mobs automatically map wide |
| `patrol` / `roam` / `wander`| Prioritize farming at specified platform and nearby platforms only |
| `sentry` / `camp` / `guard mode` / `post up` / `anchor here` / `farm here` / `grind here` /  | Stand at exact position, never chase, only attacking anything in range |
| `fidget` | Trigger a small idle/social fidget |

### Autonomy (Autopilot)

Send bots off to play independently - they travel across maps on their own, pick where to grind, farm, resupply when low, and keep going.

| Say | Effect |
|---|---|
| `go grind somewhere` / `farm wherever` / `autopilot` / `go solo` | Bot plays independently - picks a map, travels there, farms and resupplies on its own |
| `grind together` / `farm together` / `party grind` / `go together` | Whole bot party runs autopilot together |
| `farm <item>` | Autopilot toward whatever map best drops that item |
| `where should we grind` / `where to grind` | Bot suggests a good grind spot (weighs EXP and gear gains) |
| `autopilot debug` / `ap debug` / `why autopilot` | Explain the current autopilot decision |

### Movement Formation

Follow mode behavior

| Say | Effect |
|---|---|
| `formation` / `form` | Prints formation help/available options, default 60 px |
| `form split` / `form stagger` | Default alternating left/right follow formation (you stand in the center) |
| `form spread` | Like split, but first bot stand directly on your position(first bot in center) |
| `form stack` | All bots stand directly on your position |
| `form random` | Randomizes each bot's horizontal follow offset |
| `form left` | Lines every bot up to the left side |
| `form right` | Lines every bot up to the right side |
| `form tight` | Shortcut for stagger formation with tight spacing (`30px`) |
| `form loose` | Shortcut for stagger formation with loose spacing (`120px`) |
| `form <mode> <px>` / `form <mode> <px>` | set both mode and spacing ex `form split 60`, `form left tight` |
| `form snap` | Vertical snap setting, default on |
| `form snap on` | Bots find suitable platform to keep formation |
| `form snap off` | Bots alway stay on owner's platform |
| `form snap <px>` | Sets vertical snap range in pixels, default 200 px |

### Info

| Say | Effect |
|---|---|
| `inventory` / `inv` / `items` / `equips` | Inventory summary |
| `slots` | Free inventory slot count |
| `stats` / `str` / `dex` / `int` / `luk` / `level` | Level and stat summary |
| `range` / `damage` / `dmg` / `watk` | Damage range and hardest-mob hit chance |
| `build` / `ap` / `sp` | AP/SP allocation |
| `skills` | Skill tree summary |
| `mesos` / `cash` | Meso count |
| `speed` / `jump` / `movement stats` | Movement stat breakdown |
| `exp` / `xp` / `experience` | EXP progress |
| `scrolls` | Scrolls on hand |
| `pots` / `potions` | HP/MP pot count |
| `buffs?` / `buff list` | Buff-pot mode and active/available buff summary |
| `debug stats` / `attack cooldown` | Attack timing internals |
| `crit` / `crit debug` | Crit rate / multiplier breakdown |
| `pot debug` / `autopot debug` | Auto-pot selection/debug info |
| `buff debug` / `active buffs` | Buff consumable debug state |
| `skill buff debug` | Skill-buff debug state |
| `help` / `commands` | Prints command list(super spammy) |

### Support (buffs & heals for the party)

| Say | Effect |
|---|---|
| `support on` / `support off` (= `skill buffs on` / `skill buffs off`) | Toggle skill buff casting / rebuffing only |
| `heals on` / `heals off` | Toggle HP heal casting on party members |
| `buff on` / `buff off` | Toggle buff pot usage |
| `buff cheap` | Use worst available buff pots |
| `buff max` / `buff best` | Use best available buff pots |
| `buffs?` / `what buffs` / `buff list` | List active and available buffs |

### Supplies

| Say | Effect |
|---|---|
| `need hp pot` / `need health pot` | Ask bots/owner flow for HP pot help |
| `need mp pot` / `need mana pot` | Ask bots/owner flow for MP pot help |
| `need pot` / `running low on pots` | Ask for whichever pot type is needed more |
| `need ammo` / `low on arrows` / `low on bolts` | Ask for ammo help when using bow/crossbow |

### Social

| Say | Effect |
|---|---|
| `fame me` | Bot fames you (subject to daily & monthly limits) |
| `fame <name>` | Bot fames the named player or bot on the map |

### Quests

Bots auto-run worthwhile mob quests in the background while grinding.

| Say | Effect |
|---|---|
| `quests` / `quest status` / `quest progress` | Active quest progress summary |
| `recommend quest` / `quest rec` / `best quest` / `suggest quest` | Suggest a worthwhile quest to start |

### Maker / Crafting

| Say | Effect |
|---|---|
| `maker plan` / `craft plan` / `what can I craft` | Preview gear upgrades the bot could craft (read-only) |

### Gear

Bots auto-equip the best available gear they can use. They can also recommend gear for you, request current upgrades from you, and trade away non-reserved spare gear.

| Say | Effect |
|---|---|
| `any upgrades?` / `better gear` / `recommended gear` | Check if bot has better gear available for you |
| `request?` / `need anything?` / `what do you need` | Check if you have better gear available for the bot |
| `trade recommended gear` / `trade upgrades` | Bot trades its recommended gear to you |
| `autoequip` / `optimize gear` | Force a gear optimization pass |
| `autoequip debug` / `optimize gear debug` | Explain optimizer picks and dump verbose output |
| `unequip everything` | Unequip all non-cash gear |
| `unequip hat` / `unequip weapon` / `unequip ring` / etc | Unequip a specific slot |

### Trading & Dropping Items

Verbs: `trade [me] <type/name>`, `give [me] <type/name>`, `drop <type/name>`, `pass me <type/name>`

| Say | Effect |
|---|---|
| `trade` | Open a trade window (you want to give them something) |
| `trade me scrolls` / `give me scrolls` | Trade all scrolls |
| `trade pots` | Trade all potions |
| `trade buff` | Trade buff potions |
| `trade ammo` | Trade ammo |
| `trade equips` | Trade all equips |
| `trade trash` / `trade junk` / `show junk` | Trade only unreserved equips |
| `trade use` | Trade all use-tab items |
| `trade etc` | Trade etc/junk items |
| `trade 100k` / `give mesos` | Trade mesos |
| `trade <item name>` | Trade a named item |
| `trade <slot>` | Unequip then trade the item ex. `trade hat`, `trade gloves` |
| `drop <category>` | Drop category to ground (same syntax as trading, but drop instead, requires confirmation) |
| `show (me) (your) <slot>` / `can I see your <slot>` | Unequip then trade the item ex. `show me your hat` 
| `give me <item name>` / `pass me <item name>` | Same named-item trade flow with alternate verbs |
| `sell trash` / `sell junk` | Sell unreserved equips, use with caution\* |
\* sell trash command automatically excludes items with good stats and scrolled items (be careful as brown work glove and potentially many low stats but rare accessories will still be sold if not scrolled, who knows what other edge cases exists, when bot hold expensive accessories, manually trading and selling yourself will always be safer

### Build & Job

| Say                                       | Effect                  |
|-------------------------------------------|-------------------------|
| `respec sp` / `reset skills` / `reset sp` | Refund and re-assign SP |
| `respec ap` / `reset ap`                  | Refund and re-assign AP |
| `change build`                            | Re-prompt AP build selection |
| job name in chat while prompted           | Pick the requested advancement/build option |

### Debug Commands
| Say                                       | Effect                  |
|-------------------------------------------|-------------------------|
| `inv debug` | Detailed inventory dump with the keep/sell classification |
| `scroll debug` | Dump the bot's self-scrolling decision to a file |
| `autopilot debug` / `ap debug` | Full autopilot grind-decision dump |
| `!botperfdebug` | Toggle console spam on bot performance |
| `!botnav`                  | Navigation debug command |
| `grind profile` | Measure the real party grind-decision cost under live load (perf) |
| `@botstatus` | (GM) Private listing of every bot on the map |
| `@autosell` | (GM) Preview/run the bot sell pipeline on your own character |

### GM Ops Console (Maple Messenger)

A private GM console for driving/inspecting bots without spamming map chat. Open a **Maple Messenger** window and type `mmc connect` — a `Console` member joins and from then on every line you type is a command (not chat). Type `mmc disconnect` (or close the window) to leave.

| Type | Effect |
|---|---|
| `mmc connect` / `mmc disconnect` | Enter / leave console mode |
| `help` | List console verbs |
| `list` | All spawned bots (name, map, job/lv) |
| `status [name]` | Bot status (one bot, or all on your map) |
| `log <name>` / `unlog` | Live-stream that bot's autopilot decisions here / stop |
| `grind <name>` | Write the bot's autopilot decision dump (path -> chat) |
| `gachapon <name> [npcId]` | Force a gacha trip now (watch it navigate + roll) |
| `say <name> <text>` | Drive the bot via its own chat commands |
| `cmd <@command ...>` | Run a GM command |

### Living-server population (`@botpop`)

A background scheduler can keep a population of **server-generated** bots logging in and out on their
own, on varying per-bot schedules, so the world feels alive. It is **OFF by default**.

**IMPORTANT — only `@spawnbot generate` bots and auto-generated bots are managed.** The scheduler will only ever spawn/retire. Bots you made with a name (`@spawnbot <name>`), `@botme`, or
`@registerbot` are **never** auto-scheduled — this is the safety rule that stops it from ever spawning a
real player's character. (So spawning 2 named bots and logging them out, then `@botpop on`, does nothing —
they aren't in the managed pool.)

| Command | Effect |
|---|---|
| `@botpop` / `@botpop status` | Status |
| `@botpop on` / `@botpop off` | Toggle auto spawning bots |
| `@botpop list` | List managed bots (id, group, active/retired/disabled, online) |
| `@botpop sweep` | Force one reconcile pass now (instead of waiting for the next tick) |
| `@botpop clear` | Disconnect **all** online bots |
| `@botpop add <name>` / `@botpop remove <name>` | Mark/unmark an existing character as a managed (schedulable) bot |
| `@botpop crew <id\|none> <name...>` | Assign managed bots to a persistent crew (they co-spawn, party, and share gear/ammo/supplies), or clear with `none` |
| `@botpop wipe [confirm]` | delete all managed bots(so new bots lv1 spawns) |

Even then, a given bot only logs in when (a) it's "active today" (each bot plays only a fraction of days),
(b) the current hour is one it likes, and (c) the hourly target exceeds the live count — so at an off-hour
or for a sporadic bot it may stay offline. Tune the 24-hour target curve and knobs in `BotManager.cfg`
(`POPULATION_CURVE`, `POPULATION_MULTIPLIER`, `POPULATION_SCHED_ENABLED`, etc.). Design notes:
`docs/bot/living-server-design.md`.

**Auto-generation & turnover (shipped):** with `POPULATION_AUTOGEN` on (default), you don't need to
pre-build a pool at all — when the live count is under target the scheduler **generates fresh bots to
fill the gap** (a deficit-proportional batch per sweep, `POPULATION_AUTOGEN_FILL`/`POPULATION_AUTOGEN_MAX`),
and bots "retire" after a personality-set career length so the population turns over. Combined with the
`@botpop on` fast-start ramp, a fresh/wiped world fills toward the curve within ~30s. `POPULATION_MULTIPLIER`
scales the whole online target (and fill speed) up/down. Some autogen events arrive as a **crew** (a friend
group that spawns, parties, and shares supplies together) rather than a lone newcomer.

## Notes
- Bot characters can be logged into as normal accounts (user = bot name, password = `botbot`) to manually equip or manage inventory.

## Optional: LLM chat replies

Bots can hold short casual conversations with the owner using a tiny local model via [Ollama](https://ollama.com/). Purely cosmetic/for fun, doesn't affect any existing command or serve any gameplay purpose whatsoever.

### Setup
1. Install [Ollama](https://ollama.com/download).
2. Download + Run a model (in PowerShell):

| Requirements | Command to run | RAM Usage | Expected Intelligence |
|---|---|---|---|
| Light CPU / low RAM | `ollama run qwen3.5:0.8b` | ~2 GB | Good enough for short chatter |
| Decent CPU | `ollama run gemma4:e2b` | ~7 GB | Decent |
| Beefy CPU or Has GPU | `ollama run gemma4:e4b` | ~10+ GB | The more RAM the merrier |
3. Edit `src/main/java/server/bots/llm/BotLlmConfig.java`, set `enabled = true` and `model = xxxx` matching your selected model. Rebuild/Restart server.

### Behavior
- LLM only fires when a message is **directly addressed** to a specific bot by name (`Jason hi`, `Leroy how are you`).
- Bots remember the last few recent chat turns in memory for short context. Persistent disk memory is off by default for speed; set `BotLlmConfig.memoryEnabled = true` if bots should remember conversations across restarts.

