# Grind doctrine: spot clustering + map-archetype positioning (SoloMapling borrow-backs, 2026-07-09)

All 7 borrow-backs from the SoloMapling v0.3 audit ([kb_bot_solomapling_grind_v03.md]) implemented
in one pass, each behind a live-flippable toggle (`/api/settings` POST or `!botcfg`; ON by default,
off = bit-for-bit previous behavior).

## SSOT placement (why companion grind gets everything for free)

Solo autopilot, party cohorts, AND `@botme`/`@botparty` takeover all converge on
`tickGrindMode -> findGrindTarget` (the grind-vs-follow fork is `entry.grinding` vs
`entry.following`; solo-vs-party only differs in who picks the destination map, behind
`decidesAsGroupMember`). All doctrine/engage/loot/climb upgrades were built into that shared path,
so every grind mode is upgraded uniformly. Follow-mode local attacks (`findFollowAttackTarget`,
local-only, no chase) were deliberately left untouched. The level band lives in
`BotGrindAdvisor.buildCandidates`, which both the solo `recommend` and the party `candidatesFor`
paths flow through.

## The pieces

| Toggle (group) | Feature | Where |
|---|---|---|
| `GRIND_DOCTRINE_ENABLED` (combat) | Spot clustering + CAMP/PATROL/STACK/ROAM archetypes | `BotGrindSpots`, `BotGrindDoctrine`, hooks in `tickGrindMode`/`findGrindTarget`/`resolveNoGrindTargetPosition` |
| `ENGAGE_STYLE_ENABLED` (combat) | Thief engage hop-attack + 35% away-hop mini-kite | `BotCombatManager.shouldEngageHop`/`engageHopDx`, hook at the in-range attack site |
| `REST_SPOT_SAFETY_ENABLED` (manager) | Spawn-ledge hard-reject + axis-aware clearance for idle/break/HP-rest spots | `resolveSafeIdleRegion` (`pickSafestClearance`) |
| `CLIMB_COMBAT_RECOVERY_ENABLED` (manager) | Rope-stall (1.2s no vertical progress) -> dismount toward mob | `BotManager.tickClimbCombatRecovery` |
| `GRIND_LEVEL_BAND_ENABLED` (manager) | Advisor pre-filter: mob level in [lvl-25, lvl+12], never-strand fallback | `BotGrindAdvisor.levelBandAllows` in `buildCandidates` |
| `LOOT_SWEEP_CHAIN_ENABLED` (manager) | Walk to FAR end of same-ledge drop chain (vacuum grabs the chain en route) | `BotInventoryManager.sweepChainEnd` |

## Doctrine mechanics (BotGrindSpots + BotGrindDoctrine)

- **Profile** (cached per `mapId|movementProfile`, built once from `MapleMap.getMonsterSpawn()` +
  nav-graph region ids): anisotropic union-find clustering (merge 350px, dy x2.5), ledge partition
  by region id, Spots (radius 250-500, anchor = walkable member nearest centroid, p90 spread),
  regime COMPACT/SPREAD/SPARSE, roam flag (best ledge < 4 same-ledge spawns), vertical SpotStacks
  (dy 40-180, X-overlap >= 80; hopTraversable when every gap <= 110).
- **Style per (bot, map)**: dominant traversable stack (feed >= 8 and >= 1.5x best ledge; walkers
  need hop gaps, mages blink via `botSkillMask & SKILL_TELEPORT`) -> STACK; roam flag -> ROAM
  (= doctrine off, previous behavior); SPREAD with >= 3 spots -> PATROL (x-sorted top-4 ring);
  else CAMP. Owner `patrol`/`farm here` commands (patrolRegionId) always win over doctrine.
- **Claims** (`BotGrindSpots.Claims`): TTL-renewed (30s) per (mapId, anchor-key) — self-healing, a
  dead/warped bot can never leak a claim. Selection score = SoloMapling pickBest (feed, tightness,
  room, live-mob start-hot, distance, crowding, 100k over-cap penalty, 12pt jitter).
- **In combat**: `findGrindTarget` filters candidates to the spot (anchor +-radius+130 x +-80y;
  STACK: tether box +-130/+-60 around the column). Empty spot = wait beat — the bot holds/drifts
  near its anchor (`idleAnchorTarget` overrides random wander), which IS the natural camping look.
  Relocation needs BOTH regime patience (COMPACT 8s / SPREAD 4s / SPARSE 2.5s / STACK 5s dry) AND
  unproductive-since-last-landed-hit (35/18/10/20s); relocated-from spot excluded 30s.
- **LOD**: doctrine runs inside `tickGrindMode`, so LOD1 abstract grinders (which skip the live
  combat slice) never pay for it.

## Tuning knobs that turned out load-bearing in SoloMapling (kept verbatim)

Merge 350/x2.5 anisotropy (splits stacked platforms, keeps long floors whole); p90 spread (one
stray spawn must not balloon a radius); claims exclude-self + fewest-holders-first; patience is
regime-scaled or sparse maps starve; away-hop must land-check same-level ground or thieves leap
into pits.

## Verify / debug

- Style + spot visible in behavior: bots on Henesys-style flat maps camp one cluster; Kerning
  subway-style columns get STACK layering; sparse fields roam as before.
- `!botcfg GRIND_DOCTRINE_ENABLED false` (or POST `/api/settings {"cmd":"set","group":"combat",
  "field":"GRIND_DOCTRINE_ENABLED","value":"false"}`) reverts live, per-feature.
- Tests: `BotGrindDoctrineTest` (13: clustering, regime, stacks, style selection, claims TTL,
  candidate filtering, level band edges, sweep chain, thief-job gate).
