# Temple of Time bot questline (3500-3521)

## 1. The problem

Temple of Time (map 270000000 hub + corridor 270010000-270040100) is a linear, quest-gated grind
corridor for level 90+. `scripts/portal/timeQuest.js` gates every forward corridor portal
(`in00` on each lane-end map) on the stepping player's quest/item state — a fresh WZ scan sees only
unconditional portals, so `BotWorldGraph`'s baked graph was quest-blind and every bot capped out at
Memory Lane 1 (270010100), unable to route past the first gate regardless of quest progress.

## 2. Reachability overlay ("1A")

`BotWorldGraph.QuestGatedEntrance(fromMap, portalName, destMap, gateKey)` — 16 rows in
`QUEST_GATED_ENTRANCES` (`BotWorldGraph.java`), one per `timeQuest.js` gate, mirrored 1:1 against the
script. Unlike `SCRIPTED_ENTRANCES` these edges are **not** baked into the shared `Index` — they are
resolved per-bot, per-query:

- `BotWorldGraph.RouteOptions` carries an 8th field `Set<Integer> unlockedGates`. All shorter
  constructors default it to `Set.of()`, so every existing call site (web view, cost probes, tests)
  stays quest-blind with zero changes.
- `weightedNeighbors(...)` emits a `QuestGatedEntrance`'s edge only when
  `options.unlockedGates().contains(g.gateKey())` — same per-bot-conditional shape as the
  Mushroom Shrine / Free Market return edges already in that function.
- `BotAutopilotManager.unlockedTempleGates(Character bot)` is the SSOT resolver: for each row, unlocked
  = `BotQuestManager.gate.isCompleted(bot, gateKey)`, except the final Ruins row which uses
  `bot.haveItem(4032002) || gate.isCompleted(bot, 3522)` (mirrors `timeQuest.js`'s
  `haveItem(...) OR isQuestCompleted(3522)` check exactly). Called from
  `BotAutopilotManager.travelOptions(bot, withFerry)`, the single canonical `RouteOptions` constructor
  site all per-bot travel queries (route/reachability/cost-flood) go through.
- `scriptedEntrancePortal(fromMap, destMap)` also scans `QUEST_GATED_ENTRANCES` so the travel executor
  can resolve the physical portal name (`"in00"` for all 16 rows) once routing has already decided the
  hop is legal; the portal's own script re-checks and performs the real warp.
- Reachability is **inductive**: as a bot completes gate quests, `unlockedTempleGates` returns a larger
  set on the very next query, so the next corridor segment becomes routable automatically — no cache
  invalidation or graph rebuild needed.
- Non-bot / bot-less callers (`BotWorldGraphWebServer` web view, `BotScrollManager.farmableMaps`'s
  global cached flood, party-straggler hop-distance, break-spot picking) stay quest-blind by
  construction — the Temple corridor never appears in the web graph or any other RouteOptions built
  without a `Character` in scope, same as the FM/shrine per-bot returns already don't.

## 3. Gate table

`fromMap` -> portal `"in00"` -> `destMap`, gated on `gateKey` (a quest id, or `TEMPLE_ITEM_GATE` =
sentinel `999999` for the item-or-quest final gate):

| fromMap | destMap | gateKey |
|---|---|---|
| 270010100 (Memory Lane1) | 270010110 | 3501 |
| 270010200 (Memory Lane2) | 270010210 | 3502 |
| 270010300 (Memory Lane3) | 270010310 | 3503 |
| 270010400 (Memory Lane4) | 270010410 | 3504 |
| 270010500 (Memory Lane5) | 270020000 | 3507 |
| 270020100 (Road of Regrets1) | 270020110 | 3508 |
| 270020200 (Road of Regrets2) | 270020210 | 3509 |
| 270020300 (Road of Regrets3) | 270020310 | 3510 |
| 270020400 (Road of Regrets4) | 270020410 | 3511 |
| 270020500 (Road of Regrets5) | 270030000 | 3514 |
| 270030100 (Road to Oblivion1) | 270030110 | 3515 |
| 270030200 (Road to Oblivion2) | 270030210 | 3516 |
| 270030300 (Road to Oblivion3) | 270030310 | 3517 |
| 270030400 (Road to Oblivion4) | 270030410 | 3518 |
| 270030500 (Road to Oblivion5) | 270040000 | 3519 |
| 270040000 (Ruins hub) | 270040100 | `TEMPLE_ITEM_GATE` (`haveItem(4032002)` OR quest 3522 complete) |

A failed gate check bounces the player back to the nearest lane entrance — a hard position reset, not
a soft retry, so mistimed portal use loses map position (quest progress is unaffected).

## 4. Quest chain and NPCs

Mainline (strict linear prereq, each requires the previous): 3500-3521. Turn-in hub NPC 2140000
(Temple Keeper) at map 270000000 handles every quest except the three branch hand-offs:

- 3506 completes at / 3507 both run at NPC 2140001 (Memory Keeper), map 270010111.
- 3513 completes at / 3514 both run at NPC 2140002 (Sorcerer), map 270020211.
- 3520 completes at NPC 2140003 (Record Keeper), map 270030411.

3505/3512/3519 are lane-5 miniboss kill-once quests (Dodo/Lilynouch/Lyka, mob ids 8220004/8220005/
8220006).

Two script-only specials:

- **3507** ("Memory Keeper") cannot complete until one of the class-branch sub-quests
  3523/3524/3525/3526/3527/3529/3539 has been done first (`scripts/quest/3507.js` checks
  `infoNumber 7081`). Each sub-quest is a single yes/no talk dialog at a different home-town job
  instructor: Warrior 3523 @ Dances with Balrog (1022000, Perion 102000003); Magician 3524 @ Grendel
  the Really Old (1032001, Ellinia 101000003); Bowman 3525 @ Athena Pierce (1012100, Henesys
  100000201); Thief 3526 @ Dark Lord (1052001, Kerning City 103000003); Pirate 3527 @ Kyrin (1090000,
  Nautilus 120000101); Cygnus 3529 @ Neinheart (1101002, Ereve 130000000); Aran 3539 @ Lilin (1201000,
  Rien 140000000). This is a long-range cross-world travel requirement, not a scripting blocker — see
  [[bot-nav]] for the cross-world sub-quest travel path.
- **3514** ("The Sorcerer Who Sells Emotions") requires buying `2022337` Sorcerer's Potion for
  1,000,000 meso and then actually **drinking** it (the end script checks
  `getBuffSource(BuffStat.HPREC) == 2022337`, not mere possession) before the Sorcerer will complete
  the quest. This was the one mandatory blocker in the mainline corridor before the driver's item-use
  primitive was added (see 5.).

**3521** ("Force Field") is a 6-item turn-in (masks 4000446/4000451/4000456 x10 each, helmets/horn
4000460/4000461/4000462 x1 each) granting item 4032002 Marble of Chaos — the practical unlock past
3519. **3522** ("Beyond the Ruins", solo Pink Bean 8820001) is skipped: `timeQuest.js`'s final gate
accepts `haveItem(4032002) OR isQuestCompleted(3522)`, and 3521 already grants the Marble directly, so
3522 (a raid-superboss solo, not bot-feasible) is never needed. 3530-3538 are orphaned WZ quest data
(a Cygnus-restricted duplicate corridor) with zero script references anywhere outside `Check.img.xml`/
`Act.img.xml` — dead content, not part of the reachable questline.

## 5. The driver (BotTempleProgressionManager)

Registered as a `BotAutopilotManager.DetourErrand` in `DETOUR_ERRANDS`, positioned after job-change and
before quest-piggyback. Each tick recomputes `Q` = the lowest incomplete quest in `MAINLINE_CHAIN`
(3500..3521, strict order) and resolves one step for it:

- **Opt-in / stagger**: `maybeStart` only arms the errand once `bot.getLevel() >= 
  BotPersonality.templeAmbitionLevel()` — a per-bot stable roll in [105,160] derived from a splitmix64
  avalanche over `seed ^ TEMPLE_SALT` (not a plain `new Random(seed).nextDouble()` draw, because that
  first-draw shape correlates hard for consecutive seeds/char ids and would un-stagger adjacent bots;
  the splitmix64 finalizer gives full band spread even for consecutive ids). No stored field — pure
  function of the bot's identity seed, like `sitAppetite`/`gachaAppetite`/`haggleTemper`. Per-quest
  `lvmin` gating then defers higher quests until the bot has leveled into them (it grinds normally
  meanwhile). Crew gate (`crewReadyForTemple`, mirroring the Zakum errand): a bot in a persistent
  all-bot party arms only once every online member still needing the chain has reached its own
  ambition roll, so the whole crew works the questline side by side instead of members peeling off
  solo for hours; a party containing a human never arms. Lane pins are mirrored to the party plan by
  the plan leader (`BotAutopilotManager.publishLeaderPin`) so unarmed crewmates grind the same lane,
  and the errand is in `detachedFromPartyCohesion` so cohesion neither chases nor waits on it.
- **GRIND_LANE pin + crowd-defer**: for lane quests (x999 kill quotas), the driver pins
  `entry.autopilotMapId` to the lane map so kills accrue through the normal grind/combat flow (a bot is
  a `Character`; its own started-quest kill counters advance automatically). Before pinning it reads
  `BotOccupancy.extraCompetitors(bot, CROWD_PENALTY_FACTOR)` for that lane; at or above
  `CROWD_DEFER_THRESHOLD` (2) it steps aside for a jittered cooldown (`CROWD_DEFER_MIN_MS`..
  `CROWD_DEFER_MAX_MS`, 60s-180s) and forces an immediate re-decide (`autopilotNextDecisionAtMs = 0`) so
  the advisor — applying the same crowd surcharge — sends it elsewhere meanwhile. This is tick-safe: it
  reads the occupancy signal directly rather than running a full `BotGrindAdvisor` pass on the bot tick
  thread.
- **Specials** (`handle3507`, `handle3514`, `handle3521`) only run their NPC-script reproduction after
  the bot has physically arrived at the NPC and passed the humanlike read-dwell gate
  (`BotManager.npcDwellReady`) — legality requirement, not a shortcut around the interaction.
  - `handle3507` detours to the class-branch sub-quest NPC (`subQuestFor(bot)`, keyed on job id) and
    reproduces its script via `Quest.forceStart`/`bot.setQuestProgress(3507, 7081, "1")`/
    `Quest.forceComplete` — the sub-quest's own WZ Act is empty, so `gate.complete` would not set the
    infoNumber 3507 needs; `forceStart`/`forceComplete` are the same calls the NPC dialog itself runs.
  - `handle3514` reproduces `q3514s`/`q3514e`: buys+adds the potion, charges the meso, force-starts the
    quest; on arrival at completion it drinks the potion (item-effect apply + `InventoryManipulator`
    remove, the same three-call shape as `ScrollHandler`/`tryUseReturnScroll` — a targeted item-use
    helper for this one item id, not a new generic primitive) and force-completes once
    `getBuffSource(HPREC) == 2022337` is confirmed, granting the exp itself since Act.img is empty.
  - `handle3521` re-farms the lane-5 map of the first missing helmet (the masks accrue passively
    through the mainline grind; the helmets' single required miniboss kill leaves a 40% miss each),
    then does a normal data-driven turn-in via `BotQuestManager.gate` — no custom script needed,
    since 3521 is Act.img-driven.
- **Kill switch**: `BotManager.cfg.TEMPLE_PROGRESSION` (default `true`); `maybeStart` also refuses to
  arm for a supervised bot (owner online).

## 6. Key facts / gotchas

- Mask items (4000446/4000451/4000456) drop at 1% from the exact pack mobs the mainline quests already
  require killing 999x each (8200001/2, 8200005/6, 8200009/10) — statistically guaranteed well before
  the 999th kill, no extra farming.
- Helmet/horn items (4000460/4000461/4000462) drop at 60% from the lane-5 minibosses
  (Dodo/Lilynouch/Lyka, 8220004/5/6), but each lane-5 quest only requires killing its miniboss once —
  a 40% chance per boss of not getting the item on that single required kill. `handle3521` therefore
  re-farms the lane-5 map of the first missing helmet via the same `grindLane` pin. The minibosses
  RESPAWN on `mobTime` 3600s (verified in Map.wz life data), so a re-farm attempt is one kill per
  hour per channel — slow but bounded, and the crowd-defer interleaves normal grinding meanwhile.
- The six turn-in items carry no WZ `info/quest` flag, so the generic quest-item sell guard cannot
  see them; `BotInventoryManager.collectSellTrashEtcItems` consults
  `BotTempleProgressionManager.isQuestCriticalItem` (keep until 3521 completes) so an ETC sell trip
  can't NPC the masks/helmets mid-questline. (This was a latent gap for a while — only 4032002
  survived, incidentally, via its tradeBlock.) Same seam protects the Zakum trial items, see
  [[kb_bot_zakum_prequest]].
- Drop rates and item-to-mob mapping for masks/helmets are **not in WZ** — confirmed only via the live
  `cosmic.drop_data` DB table, not `Mob.wz`. Cross-reference `[[wz-data]]`-style tooling accordingly if
  re-verifying.
- `BotQuestManager.gate` is the shared quest-state SSOT (start/complete/isCompleted/canStart/
  canComplete) reused throughout the driver — no parallel quest-state tracking was introduced.
