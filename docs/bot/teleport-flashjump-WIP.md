# Bot Teleport + Flash Jump — WIP handoff

Resume doc for the bot movement-skills feature (teleport for mages, flash jump for thieves at the
nav level). **State: compiles (BUILD SUCCESS), GRAPH_VERSION 61. Teleport verified working live.
Flash jump still broken (4 known bugs, fixes not yet applied). Several user-requested refinements
partially done.** Companion: `kb_bot_movement_skills_teleport_flashjump` memory + `web-endpoints.md`.

## Live test environment (RESTART the server after every code change to load the new build)
- LAN web server `http://localhost:8089` (no auth). **HTTP from the agent env: use the PowerShell tool
  `Invoke-RestMethod`** — Bash `curl` is hook-blocked and the ctx sandbox can't reach localhost.
- Spawn test chars as managed bots via `GET /api/spawnbot?id=<id>` (respawns at saved map/pos with real
  skills). Drive with `POST /api/command {"cmd":"moveto","ids":[id],"x":..,"y":..}`. Stop with `cmd:"idle"`.
- Read state: `GET /api/botdebug?id=<id>` → `detail` has `pos, navEdge, navDecision, edgeBlock,
  canMoveSkill, mp, maxMp, meso, skills{}`. Probe the planner: `GET /api/navprobe?id=&x=&y=&skills=1`
  → path shows `TELEPORT`/`FLASH_JUMP` edges the bot is eligible for.
- Test chars (in the `cosmic` DB; all pass/fail the gate as noted):
  - **id 31 "Mage"** — I/L Wizard, teleport (2201002) L20, 79M meso → `canMoveSkill=true`. map 101030110.
  - **id 1 "Admin"** — Hermit, flash jump (4111006) L20, 189M meso → `canMoveSkill=true`. map 100000000.
  - **id 6 "wherebugs"** — Night Lord, has ALL skills but 192k meso → `canMoveSkill=false` (meso-gate demo).
- **Verified live:** gate (canMoveSkill correct incl. wherebugs blocked by meso), planner (navprobe
  skills=1 → TELEPORT/FLASH_JUMP routes), distance-threshold (close target → walk JUMP/DROP, far → teleport),
  teleport execution (pos jumps ~150px, −13 MP per cast = the L20 mpCon).

## DONE this session (compiles; needs restart to verify)
1. **Physics SSOT snap** — `BotPhysicsEngine.teleportLanding(map, origin, dirX, dirY, range, ySnap)` +
   `closestPlatformWithin`. Horizontal = closest platform **vertically** within ±ySnap (same-level beats a
   higher/lower diagonal — fixes the illegal `(154,274)→(-15,218)` teleport; should now land `(-15,274)`);
   up = furthest within range; down = nearest within range; `null` = blocked.
2. **Provider** `addTeleportEdges` → intent-based (asks `teleportLanding` for L/R/U/D). GRAPH_VERSION 60→61.
3. **`BotMovementManager.broadcastTeleport(entry, origin, dest)`** + `putTeleportFrag` — emits the real
   teleport packet (`cmd 4 appear@origin` + `cmd 3 disappear@dest`, matches captured
   `logs/monitored-packets-teleport*`). **NOT yet called from execution** (see REMAINING A).

## REMAINING — all in `BotNavigationManager.java` unless noted. Grep method names (lines may shift).
**A. Wire teleport visual.** In `tryExecuteTeleport`: capture `Point origin = bot.getPosition()` BEFORE
`teleportTo`, then after it call `BotMovementManager.broadcastTeleport(entry, origin, edge.endPoint)`.
(There's a `ponytail:` comment marking the spot.) Without this the client glides instead of blinking —
the user's "not seeing teleport effects".

**B. Cooldown / cadence** (user: "too rapid teleport"). Share `entry.skillHopReadyAtMs` across all skill
casts. Rename `INTRA_EXPRESS_COOLDOWN_MS`→`SKILL_CAST_COOLDOWN_MS = 300`. In `tryExecuteTeleport` AND
`tryExecuteFlashJump`: top → `if (System.currentTimeMillis() < entry.skillHopReadyAtMs) { lastEdgeBlockReason
= "tele-cd"/"fj-cd"; return null; }`; after firing → `entry.skillHopReadyAtMs = now + SKILL_CAST_COOLDOWN_MS`.
`tryIntraRegionSkillHop` already sets it.

**C. Long-stretch express** (user: "not teleporting on long walk stretch"). The intra-express only fires
when `edge==null`; while walking to a committed cross-region edge's launch point it never blinks. In
`resolveTarget`, AFTER `tryExecuteEdge` returns null, add: if `botCanUseMovementSkill(bot) && startRegionId
== edge.fromRegionId && Math.abs(botPos.x - edge.startPoint.x) > INTRA_EXPRESS_MIN_PX`, call
`tryIntraRegionSkillHop(entry, bot, graph, botPos, edge.startPoint, startRegionId)` and return the hop if
non-null. **Also remove `clearNavigation(entry)` from `tryIntraRegionSkillHop`** so it doesn't drop the
committed edge (re-plan storm) — the same-region call path has no committed edge anyway.

**D. Intra-express uses the SSOT snap.** In `tryIntraRegionSkillHop`, replace the `findGroundPoint`
horizontal probe with `BotPhysicsEngine.teleportLanding(map, botPos, dir, 0, BotNavigationGraphProvider.
TELEPORT_RANGE_PX, BotNavigationGraphProvider.TELEPORT_Y_SNAP_PX)` (same-level priority). Emit
`broadcastTeleport(entry, botPos, dest)` for the visual.

**E. FLASH_JUMP — 4 bugs (this is why the Hermit sits stuck on `fj-pos`):**
1. `canExecuteJumpFromCurrentPosition`: `if (edge.type != JUMP)` → `if (edge.type != JUMP && edge.type !=
   FLASH_JUMP)`.
2. `isWithinJumpLaunchWindow`: accept FLASH_JUMP AND use a launch tolerance — `edge.type != JUMP` →
   `(edge.type != JUMP && edge.type != FLASH_JUMP)`, and `edge.containsLaunchX(botPos.x)` →
   `edge.containsLaunchX(botPos.x, edge.type == FLASH_JUMP ? FLASH_JUMP_LAUNCH_TOL : 0)`. FJ edges have a
   single-point window `[anchor.x,anchor.x]`; without tolerance the bot can never stand exactly on it.
   Add `FLASH_JUMP_LAUNCH_TOL = 12` near the other tolerances.
3. `tryExecuteFlashJump`: move `entry.pendingFlashJump = true;` to AFTER `BotMovementManager.initiateJump(...)`
   — `launchAirborne` clears the flag, so setting it before launch wipes it (FJ becomes a plain jump, no dash).
4. `reuseCommittedEdge` mid-air keep (`if (entry.inAir && ... && (edge.type == DROP || edge.type == JUMP))`):
   add `|| edge.type == FLASH_JUMP` — else the committed FJ edge drops mid-arc and a re-plan disrupts the flight.

**F. Down-teleport prone** (user: "teleporting downward requires holding downkey prone like downjump").
In `tryExecuteTeleport`, if `edge.endPoint.y > edge.startPoint.y` set the prone intent (`entry.crouching =
true`) before `teleportTo`, mirroring `beginDownJump`. Minor; generation already only allows down via the
`dirY>0` intent.

## After A–F: recompile, **restart server**, re-verify
- Teleport visual: `moveto` Mage(31) far → blink not glide (watch web map or have NuTNNuT visit map 101030110),
  cadence ~300ms not rapid. Same-level: a horizontal teleport lands same-level, not diagonal-up.
- Long stretch: Mage on a long platform → blinks along it (`navDecision` shows skill-hop / the long-stretch hop).
- Flash jump: `navprobe id=1 ...&skills=1` to find a FLASH_JUMP route on Henesys (e.g. ~`(1200,220)`),
  `moveto` there → mid-air dash (pos arc + −13 MP), no longer stuck on `fj-pos`.

## Build / test (paths per `reference_build_tools` memory)
- Compile: `cmd //c "cd /d D:\GameServers\Maplestory\Cosmic && C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.14\bin\mvn.cmd compile 2>&1" | grep -E "ERROR|BUILD"`
- Offline: `... mvn.cmd test -Dtest=BotNavigationGraphProviderTest` → 42 pass (incl. 3 new teleport/FJ-gen tests).
  Pre-existing UNRELATED fails (confirmed not ours via `git stash`): `BotPhysicsEngineTest` counter-strafe-landing
  (0.48) + down-jump-too-far. GRAPH_VERSION 61 → first map load rebuilds the nav cache.

## Files touched this session
`BotNavigationGraph.java` (EdgeType += TELEPORT, FLASH_JUMP), `BotEntry.java` (pendingFlashJump,
skillHopReadyAtMs), `BotPhysicsEngine.java` (FJ consts/helpers, apex injection, simulateFlashJumpLanding,
launchAirborne clears flag, **teleportLanding SSOT**), `BotNavigationGraphProvider.java` (GRAPH_VERSION 61,
teleport/FJ gen, recordEdge), `BotNavigationManager.java` (skill helpers, isEdgeUsable filter, runSearch
threading, two-pass gate, execution dispatch, tryExecuteTeleport/FlashJump, intra-express, findPathWithSkills,
switches), `BotMovementManager.java` (**broadcastTeleport**), `BotWorldGraphWebServer.java` (navprobe `skills`,
botdebug nav-state), `BotNavigationDebugOverlay.java` + `BotMovementSimulationLab.java` (switch cases),
`BotNavigationGraphProviderTest.java` (3 tests), `docs/bot/web-endpoints.md`.
