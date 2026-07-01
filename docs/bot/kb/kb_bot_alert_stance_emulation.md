---
name: Bot Alert Stance Emulation (Client Visual-State Parity)
description: How the bot replicates the 5-second ALERT pose that real clients render locally after attack/skill/damage
type: project
originSessionId: 960c9579-c9e8-4b59-b4a3-fa5032e6268c
---
# Bot Alert Stance Emulation

## Model (client-local visual state)
`CharLook::alerted` is a **client-local** timed boolean. While active, the draw layer substitutes `Stance::ALERT` for `Stance::STAND1/STAND2`. Duration: 5000ms. Triggered when the client renders an attack/skill action or `show_damage` for the displayed character. Verify exact paths against Angel.idb if needed.

**Observer key fact:** the alert timer is started *per observer* based on what they render, not synced via packet. damagePlayer + any attack packet already triggers observers to start their own timers. Pure self-buffs don't — no attack packet goes out — which is why buffs don't show alert otherwise.

## Bot implementation (commit 6a52e22c3 on experimental)
- Field: `BotEntry.alertedUntilMs` (long, absolute deadline, never additive). See `BotEntry.java:~100`.
- Helper: `BotCombatManager.markAlerted(entry)` sets `now + ALERT_DURATION_MS (5000L)`. Called at:
  - `attackMonster` (end)
  - `applyMobHit` (both dmg=0 and dmg>0 branches)
  - `tickSupportHealing` (after `sendHealAttack`)
  - `castSupportSkill` (after dispatchSupportSpecialMove)
- Broadcast override: `BotPhysicsEngine.movementSnapshot` calls `broadcastStance(entry, stance)` which swaps `STAND_RIGHT_STANCE(4)`→`ALERT_RIGHT_STANCE(8)` and `STAND_LEFT_STANCE(5)`→`ALERT_LEFT_STANCE(9)` when `now < alertedUntilMs`. Logical `Character.stance` (set via `bot.setStance(...)` a few lines earlier) is **not** mutated — server-side `isStanding` checks still work.
- Constants: `constants/game/CharacterStance.java` — ALERT_RIGHT_STANCE=8, ALERT_LEFT_STANCE=9 (right is the canonical value, +1 for left-facing — same convention as STAND/WALK/etc.).

## Why timestamp > cooldown
Broadcast dedupe compares stance across packets. A counter ticking in the background would spuriously invalidate the cache each tick. Timestamp only flips the stance value at the enter/exit boundary.

## Related/ruled-out paths
- `0x87` packets after SPECIAL_MOVE in `logs/monitored-packets-buffing.log` are **CHANGE_KEYMAP auto-pot pings** (`KeymapChangeHandler.java`), not skill-cast confirmation. Red herring.
- `showBuffEffect` 4-arg (`PacketCreator.java:3472`, direction=3) vs 5-arg (`:3483`) does NOT drive alert pose on observers; particle effect only.
- Heal already worked pre-fix because `sendHealAttack` broadcasts MAGIC_ATTACK with direction=41 (alert2 bodyActionId) which triggers the observer's own attack() → set_alerted.
