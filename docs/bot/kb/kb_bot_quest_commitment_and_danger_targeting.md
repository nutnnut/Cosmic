---
name: kb_bot_quest_commitment_and_danger_targeting
description: "Quest-commitment + self-preservation biases — where map-bias and the per-mob target-score terms (aoe / quest / touch-danger) compose, and the SSOTs behind them"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 3ba2e094-4330-46cc-ad73-ebfae4332dae
---

Three independent biases now steer an autopilot bot's grind. Touch them together — they compose at shared sites.

**Map selection (off-thread, DECIDE_POOL).** `BotAutopilotManager.travelWeight` closure returns the per-map score multiplier. It is `BotTravelCost.scoreWeight(seconds, mapId, level) * BotQuestManager.questMapScoreBias(mapId, questMobs)`. `questMobs = BotQuestManager.activeQuestMobIds(bot)` computed once per pass. `questMapScoreBias` = `QUEST_GRIND_MAP_BIAS` (1.6) when the map spawns a still-needed started-quest mob, else 1.0. Add new map-level biases here (multiply the weight).

**Per-mob target selection (bot tick thread).** `BotCombatManager.scoreLocalTargets` and `scoreTargetRegions` both build `localScore` the same way — lower wins:
`grindTargetScore(...) - aoeClusterBonus(...) - questTargetBonus(entry,cand) + touchDangerPenalty(...)`.
- `aoeClusterBonus` (subtract, prefer): cluster anchor when bot has aoeSkill. See [[kb_bot_aoe_cluster_target_bias]].
- `questTargetBonus` (subtract, prefer): `QUEST_TARGET_BONUS` 600 when `entry.activeQuestMobIds.contains(cand.getId())`.
- `touchDangerPenalty` (ADD, avoid): soft penalty for touch-dangerous mobs when the bot is fragile.
Any new per-mob bias goes at these two sites; subtract to prefer, add to avoid.

**SSOT `BotQuestManager.activeQuestMobIds(bot)`** = mob ids the bot still needs for any STARTED indexed quest (unmet counts). The single source for "what mobs is this bot committed to." Cached on `BotEntry.activeQuestMobIds` (volatile, O(1) read on tick); refreshed by `refreshActiveQuestMobs` on the quest scan + on quest start/complete + opportunistic grab.

**Opportunistic quest grab** = `BotQuestManager.tickOpportunisticGrab`: autopilot bot within `NPC_TRIGGER_RADIUS_PX` of an INDEXED quest's start NPC on the current map starts it for free (no detour/overlap/worthwhile gate). Indexed-only so the bot only takes quests it can finish. Called from `tickScan` autopilot branch.

**Self-preservation combat (commit b0fa7f49b + fixes)** — `server.bots.combat.BotDangerAssessment`: `isTouchDangerous(bot,mob,hitsToKill)` wraps the SSOT `BotDefenseDataProvider.rollPhysicalTouchDamage` (do NOT write a parallel touch-dmg calc — rule #6). Damage cache key is `(botId,mobId,botLevel,botWdef)` — WDEF is in the key because bots re-equip without leveling (keying on level alone went stale); size-capped at 20k. Knobs in `BotCombatManager.cfg`: `TOUCH_HITS_TO_KILL` 3, `TOUCH_DANGER_PENALTY` 1500, `TOUCH_FRAGILE_MAXHP` 600. Fragile = maxHp≤600 OR hpPots<POT_STOP; `touchDangerPenalty(fragile, bot, target)` takes the fragile flag precomputed ONCE per scoring pass (don't call isFragile per candidate — it scans the USE bag).

Proactive retreat (`BotManager.computeProactiveDangerRetreat`) disengages a touch-dangerous mob when HP is healthy (≥AUTOPOT_HP_THRESH). CRITICAL: it is gated on `isFragile` AND bounded by an anti-freeze give-up (`BotManager.applyDangerRetreatGiveUp`, `MAX_DANGER_RETREAT_MS`/`DANGER_RETREAT_SUPPRESS_MS`, `BotEntry.dangerRetreatStreakStartMs`/`dangerRetreatSuppressUntilMs`) — WITHOUT the give-up, a fragile bot on a map where every mob 3-hits it (normal early game) approach-flee-loops forever and never attacks (froze solo bots). The give-up suppresses retreat after a streak so the bot FIGHTS (reactive heal/pots + death-loop breaker are the nets). Travel-side self-preservation (level-scaled travel penalty, Sleepywood<15 block) already shipped — see [[kb_bot_town_nav_airborne_target]]. Known tuning gaps (deferred): proactive retreat off in the 40–70% HP band (reactive pots fire <40%); no proactive relocate for a solo bot stuck on an all-dangerous map (relies on death-loop breaker).

Pre-existing unrelated failures: `BotCombatManagerTest` has 6 WZ-data-dependent failures (arrow-bomb %, summon/teleport/2nd-job/DK classification, mob-touch-sweep) present on a pristine tree — not from these changes.
