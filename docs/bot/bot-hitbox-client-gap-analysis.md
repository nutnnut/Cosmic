# Bot Hitbox vs Real v83 Client Gap Analysis

Date: 2026-05-12

## Scope

Requested focus: claw, bow, and magic attacks such as Magic Claw; how attack
hitboxes are determined; whether the real client uses cone hitboxes; current bot
gaps where it misses mobs above and hits mobs too close in the vertical band.

This note does not implement behavior. It records client-derived facts and
recommended next steps.

## Tooling Status

No Ghidra or IDA MCP resources were configured in the current Codex session, so
there was no live MCP-backed decompiler access. Existing IDB-derived notes and
logs under `D:\ReverseEngineer` were used.

The local IDB helper command currently fails here with `No installed Python
found!` from `py.exe`, so fresh disassembly could not be generated in this
session. Prior verified notes remain the stronger source for v83 behavior.

IDA MCP options as of this check:

- IDA Pro has public MCP servers such as `mcp-server-ida`, but they are normally
  IDAPython-plugin based and target IDA Pro.
- IDA Free does not ship IDAPython. There is a separate `ida-free-mcp` project
  that ports an IDA MCP plugin to native C++ for IDA Free's SDK, but that is a
  separate project, not stock IDA Free support.
- Ghidra has public MCP options such as `pyghidra-mcp` and `ghidraMCP`.

## Verified Client Facts

The real v83 client attack-side hit test is rectangular, not circular or conic.

`CMob::GetHitPoint` at `0x00664603` is the key verified function. It receives an
attack rectangle, loads the mob body rectangle, clamps/intersects those
rectangles, and returns the midpoint of the intersection. If there is no
overlap, it returns `{0,0}`. Existing IDB notes list callers from melee, shoot,
magic, summon, remote attack, body attack, and touch paths.

Mob body bounds are current animation-frame sprite bounds. v83 Mob.wz has no
separate dedicated hitbox node. The current frame's `lt/rb` is the body hitbox.
Attack/hit animation frames can include weapon swing extents and should not be
used as body bounds.

Ranged projectile base range is stat-derived, not per-weapon hardcoded in the
attack routine. Prior IDB notes verified bow, crossbow, claw, and gun share
`projectilerange=400`; passive WZ `range` skills add to this, such as Eye of
Amazon and Keen Eyes.

The projectile rectangle shape used by the current bot matches the known
client-derived shape:

```text
base: x 5..400 in facing direction, y -50..+50 around character origin
left-facing:  [origin.x - range, origin.x - 5]
right-facing: [origin.x + 5,     origin.x + range]
```

That means there is a 5 px near dead zone for true projectile attacks. Point
blank behavior should be handled through the real client's degenerate/basic
attack path, not by widening projectile hitboxes inward.

Melee/basic close range is WZ-driven through weapon afterimage `lt/rb` per
action. The client does not have a single universal hardcoded melee rectangle.

## Current Bot Model

Relevant files:

- `src/main/java/server/bots/BotCombatManager.java`
- `src/main/java/server/bots/BotAttackExecutionProvider.java`
- `src/main/java/server/bots/combat/BotMobHitboxProvider.java`
- `src/main/java/server/bots/combat/BotAttackDataProvider.java`

Bot attack-vs-mob testing uses rectangle intersection:

- `BotCombatManager.doesHitBoxIntersectMonster` uses `hitBox.intersects(mobBounds)`
  and falls back to `hitBox.contains(monster.getPosition())`.
- `BotMobHitboxProvider` caches only frame `0` from `stand`, `move`, or `fly`.
- `BotCombatManager.clientProjectileHitBox` builds the 5 px inset, 400 px base
  projectile rectangle and adds passive range.

Skill hitboxes:

- If `StatEffect` has WZ `lt/rb`, the bot uses `effect.calculateBoundingBox`.
- If the skill lacks WZ bounds, non-close routes fall back to the projectile
  rectangle.
- Magic route is selected for wand/staff skills.

Ranged degenerate behavior:

- The bot forces bow/crossbow/claw/gun into a close-range route when target
  point distance is within `RANGED_DEGENERATE_RANGE_X/Y` (`50 x 50` by default)
  or when out of ammo.
- That gate is based on mob origin point distance, not on overlap between the
  mob body rectangle and the real near dead zone / close attack rectangle.

## Likely Gaps

### 1. Mob bounds use one cached body frame, while client uses current frame

The bot's `stand/move/fly/0` cache is usually close, but it can be wrong by
several pixels and sometimes more for tall/flying or animated mobs. This can
explain edge cases where the bot cannot hit a mob high enough, especially when
the real client's current frame extends farther upward than frame 0.

Improvement direction: track or approximate the active mob animation frame or
cache a conservative body union across safe body-pose frames. Do not include
`attack*` or `hit*` groups because those can include weapon/swing extents.

### 2. Skill fallback for magic may be too short vertically

For skills without WZ `lt/rb`, the bot falls back to the projectile rectangle
with vertical range `-50..+50`. If the real client uses a skill-specific WZ
rectangle for Magic Claw / similar magic skills, or if the server WZ parsing
misses that rectangle, the bot will under-hit mobs above.

Improvement direction: inspect `wz/Skill.wz/200.img.xml` for Magic Claw level
`lt/rb` and verify `StatEffect.hasBoundingBox()` sees it. If Magic Claw has a
larger upward `lt/rb`, the fix is to make bot skill planning use that exact
skill rectangle, not the fallback projectile band.

### 3. Point-based degenerate ranged selection can make close vertical hits too permissive

The real projectile rectangle excludes the first 5 px in front of the character.
The bot also has a separate "degenerate close" path for ranged weapons at
`dx <= 50 && dy <= 50`. Because that decision uses the mob position point, a tall
mob whose origin is in the 50 px band can trigger close-route behavior even when
only an unrealistic vertical/body slice is reachable. This matches the symptom
"being able to hit mob too close but in up/down hitbox."

Improvement direction: decide degenerate/retreat using rectangle geometry:

- True projectile: require mob body overlap with the forward projectile rect.
- Point-blank fallback: require mob body overlap with the actual close/afterimage
  rect, not just `abs(origin dx/dy) <= 50`.
- If neither overlaps, reposition instead of attacking.

### 4. Close/basic fallback is still hardcoded in places

`BotAttackExecutionProvider.closeRangeBasicHitBox` and
`BotCombatManager.fallbackCloseRangeSkillHitBox` use config rectangles
(`ATTACK_RANGE_X`, `ATTACK_RANGE_Y`, `ATTACK_DOWN_MAX`). The real client uses
weapon afterimage for close/basic attack extents when available.

Improvement direction: prefer `BotAttackDataProvider.NormalAttackProfile`
afterimage bounds for close/basic and degenerate ranged close attacks. Keep
config rectangles only as fallback when no WZ afterimage exists.

### 5. Target ordering differs for multi-target skills

Existing secondary target collection sorts by distance to the primary target.
The client-side model in prior notes chooses eligible mobs from an attack
rectangle, but exact ordering should be verified from v83 IDB before changing.
This is less likely to explain high/close hitbox symptoms than the first three
items.

## Wall / Projectile Collision Notes

No verified v83 client fact in the existing notes proves that player projectiles
are blocked by map walls before hit selection. The verified `CMob::GetHitPoint`
path only proves rectangle-vs-mob intersection after an attack rectangle is
constructed.

Do not implement wall-blocking for bot projectile hit selection from the current
evidence. If this matters, use live IDB/Ghidra to inspect the shoot/magic attack
target enumeration around the `CMob::GetHitPoint` calls and search for foothold
or line-segment collision tests on the path from character origin to mob bounds.

## Answer to Cone-Hitbox Question

No, the verified v83 attack-side hit model is not a cone. It is rectangle vs
current mob-body rectangle. Some visual effects look cone-like or arced, but the
hit decision goes through rectangular WZ/stat-derived boxes and
`CMob::GetHitPoint`.

## Recommended Fix Order

1. Verify Magic Claw and common bow/claw skill WZ `lt/rb` are loaded into
   `StatEffect` and used by bot planning. This likely addresses "cannot hit mob
   high" for magic.
2. Replace point-distance degenerate ranged gates with body-rectangle overlap
   against the real projectile rect and real close/afterimage rect. This likely
   addresses "hits too close in up/down hitbox."
3. Improve mob bounds from single frame 0 to current-frame approximation or safe
   body-pose union. This reduces edge drift against tall/flying/animated mobs.
4. Only after fresh IDB verification, consider wall/projectile obstruction.

