# SoloMapling v0.3 (2026-07-08) grind-bot audit — borrow-back candidates

Third SoloMapling audit (prior: [project_solomapling_audit.md] economy substrate,
[kb_bot_solomapling_trade_behavior.md] FM/trade behavior). v0.3 "Enhanced Training Bots" is a
128-commit grind-AI rewrite (`BotGrindSystem/` at D:\GameServers\Maplestory\SoloMapling). Primary
sources read in full: their v0.3 changelog + their 2026-07-03 scaling audit doc. Their changelog
credits NutNNut's GCMovementSystem as the base of their movement layer — lineage confirmed.

**Skip their scaling arc** (tick-wheel + virtual threads, 6.5k ambient bots on a handheld): built for
thousands of shallow ambient bots; our fork runs fewer, deeper companions and already has LOD.

## Ranked steal-list (behavioral)

1. **Map-archetype grind strategies** [HIGH value / MED-HIGH effort] — `GrindStylePolicy.natural()`
   classifies maps COMPACT/SPREAD/SPARSE(+stack-dominant) and picks CAMP / ROAM / PATROL /
   STACK (vertical tether, class-gated). We have NO archetype concept — `findGrindTarget` is one
   uniform nearest/best-score loop regardless of map shape. Would live beside BotGrindAdvisor/Planner.
2. **Spot clustering** [substrate for #1] — `SpotFinder` greedy-merges WZ spawn points into scored
   Spots (250-500px radius). Our BotSpawnIndex has only aggregate counts, no spatial clustering.
3. **Rest-spot safety model** [MED / LOW-MED] — `RestSpotFinder`: a ledge bearing a mob spawn is
   categorically hot (mobs patrol their whole foothold); vertical clearance outweighs horizontal;
   optional rope-hang rest. Our BotBreakManager has NO positional safety — direct aggro-avoidance win.
4. **Class-aware engage feel** [HIGH for humanlike rule #3 / MED] — `EngageBeat`/`MovementStylePolicy`/
   `FlashJumpTiers`: per-job grind locomotion (PLANTED/FLASH_JUMP/JUMP_ATTACK/TELEPORT/RANGED),
   thief jump-attack mini-kite (35%), ranged kite-back, AoE step-in, skill-level-scaled FJ dash.
   Mostly gating behaviors we already have by job — little new packet work.
5. **Combat rope-climb stuck recovery** [LOW effort] — `ClimbRecovery`: no vertical progress for 1.2s
   mid-combat -> dismount toward target. We confirmed we have no rope-specific in-combat recovery.
6. **Level-band map selection** [MED / MED] — `TrainingMapChooser`/`TrainingRegions`: two-sided level
   band with a constant look-down floor for lowbies, region allow-list (original v83 content),
   gap-decay fallback. Our level-appropriateness is only emergent (survivability/hit-chance); a
   lightweight band+region pre-filter is portable. Their crowd-spread capacity machinery is N/A.
7. **Loot sweep pattern** [LOW-MED, VERIFY FIRST] — `GrindLoot`: never interrupt a swing, sweep on dry
   beats, 1.5s settle delay on fresh drops. Check overlap with our tickPassiveLoot before building.
8. **SKIP** — their `MobHitboxIndex` == our existing `BotMobHitboxProvider`.

They also deferred DORMANT park+wake and observed-subset combat ticking under a "no complexity the
numbers don't demand" rule — same philosophy as our CLAUDE.md; validation, not action.
