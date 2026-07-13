---
name: kb_bot_ranged_spacing_weapons
description: "All 4 ranged ammo weapons (bow/xbow/claw/gun) retreat for spacing. Freeze bug was a missing anti-freeze on the spacing retreat (now unified RetreatGiveUp watchdog) + rope guard, NOT weapon type. Pathlog logs full retreat-decision factors."
metadata: 
  node_type: memory
  type: reference
  originSessionId: afa4030f-6d3d-4574-894d-3b2cd6105093
---

`isDegenerateCapableRangedWeapon` = BOW, CROSSBOW, CLAW, GUN — **all four retreat for spacing** (confirmed by owner). Do NOT restrict it to bow/crossbow; an earlier attempt to do that was wrong and was reverted.

**Freeze bug (fixed 73b088af5, 2026-06-18):** a grinding ranged bot froze next to a mob it was standing on (claw, gun, AND bow all hit it). Root cause was the **retreat logic**, not weapon categorization: a ranged-spacing retreat that can never open distance (mob chases at equal speed, blocked nav, pinned on a rope) looped forever with the attack gate shut. The danger retreat had an anti-freeze give-up; the spacing retreat had none.

Fix, three parts:
1. **Unified anti-freeze**: extracted the give-up state machine into one shared `RetreatGiveUp` class (`forcedFight(active, now, maxStreakMs, fightWindowMs)`). Both `dangerGiveUp` and `spacingGiveUp` are instances on `BotEntry`. Once a retreat runs past its cap without the trigger clearing, it gives up and FIGHTS in place for a window. Merged two near-duplicate timers (the old `applyDangerRetreatGiveUp` still exists as a thin wrapper that adds the danger flip-flop hold `dangerRetreatUntilMs`). Spacing constants `MAX_RANGED_SPACING_RETREAT_MS=1500`, `RANGED_SPACING_RETREAT_SUPPRESS_MS=2500`; danger `MAX_DANGER_RETREAT_MS=3500`, suppress 12000.
2. **Rope guard**: `rangedSpacingCrowded = !entry.climbing && shouldRetreatFromNearbyTarget(...)`. You can't open horizontal distance on a rope; trying sent the bot climbing DOWN away from a mob sitting on top of the rope then back up (oscillation). Danger-retreat still applies while climbing.
3. The streak resets the instant the trigger clears, so healthy kiting (back off → mob out of band → re-engage) never trips it.

**The other retreat layers were deliberately left alone** (assessed as distinct, not redundant): cross-region retreat (`selectCrossRegionRetreatTarget`), surround-breakout (`breakoutDirection`/`isSurrounded`), crowding-swap (`findCloserThreatMob`), retreat-hold hysteresis (`retreatHoldPos`), degenerate close shot (`allowOneDegenerateAttack`). Only the anti-freeze give-up was the actual freeze source and the only thing worth unifying.

**Pathlog combat telemetry** (in `BotPathLogger.appendCombatState` + `cmb=` tick token): CURRENT STATE `Combat:` block lists the full retreat decision — verdict (attackGateOpen/retreatSpacing/dangerRetreat/crossRegion), inputs (crowded + the band thresholds, inDegenBand, degenAttackDone, climbing), anti-freeze (both watchdog streakMs/cap + fightLeftMs + danger holdLeftMs), and position (retreatHoldPos/breakoutDir). Per-tick token: `ATK`/`RETrng`/`RETdgr`/`hold` with `/d` degen-band, `/X` cross-region, `/g` gave-up flags. Backed by `entry.dbg*` fields set in `BotManager.tickGrindMode`.

NOTE: the experimental branch is shared with a concurrent session that periodically resets/rewrites history — commits can vanish from the branch tip (recover via reflog). Stage only your own files.
