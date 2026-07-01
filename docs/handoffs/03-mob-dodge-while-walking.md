# 03 — Dodge mobs while walking (wire existing jump-apex dodge into travel)

**Status:** investigation-first (its scoper didn't return). Read `README.md` first. Touches
combat/movement physics — do NOT run in parallel with `05-self-preservation` in the same worktree, and
do NOT run nav/graph tests (rule #4).

## Goal
While **walking/traveling** (autopilot travel, path-follow, follow-owner), a bot should dodge mobs it
would otherwise walk through — by timing a jump so it clears the mob at the jump apex. The user reports
this dodge behavior **already exists** but appears wired only for combat, not for plain travel/move
movement. So this is mostly **wiring an existing feature into the travel movement path**, NOT building a
new dodge.

## Investigate first (file:line everything)
1. **Find the existing apex dodge.** Search `server/bots` for the code that times a jump to clear a mob
   at apex / detects a mob overlapping the bot's horizontal walk path. Likely in `BotPhysicsEngine` or
   `BotMovementManager` (or an attack/reposition provider). Identify the method and **when it's
   currently invoked** (combat reposition only? attack approach?). Confirm the premise.
2. **Map the movement execution paths** and find which one does plain walking that does NOT consult the
   dodge: combat reposition vs autopilot TRAVEL (`BotTravelManager`/`BotMovementManager.movementStep`)
   vs follow-owner formation. The travel/path-follow stepper is the suspect that lacks the dodge call.
3. **Find why** dodge isn't applied in travel: a missing call, a mode gate (e.g. only when
   `entry.grinding`/in-combat), or the travel stepper not knowing about nearby mobs (does it have access
   to `bot.getMap().getMonsters()` / the mob list at step time?).

## Implement (reuse, don't reinvent — rule #1)
- Call the **existing** apex-dodge decision from the travel/walk stepper when a live mob lies ahead on
  the bot's current foothold within a short horizontal lookahead, and the jump is physically legal
  (reuse the existing jump-eligibility/launch logic — see memory of jump apex + `forbidFallDown`/down-jump
  rules; don't re-derive launch math).
- Keep it humanlike: small reaction jitter, don't dodge every tick, don't dodge if it would derail the
  path (e.g. jump off a ledge). Prefer riding the recently-fixed landing momentum (the bot no longer
  brakes/flips on landing — see `BotPhysicsEngine.landOnGround`).
- Surgical: ideally one call site added in the travel stepper + maybe a small guard. Avoid changing the
  dodge algorithm itself.

## Risks / notes
- Physics is packet-fitted and `GRAPH_VERSION`-sensitive. A dodge that's pure runtime *decision* (when
  to invoke an already-cached jump) needs **no** GRAPH_VERSION bump. If you change cached pathing/edges,
  bump `GRAPH_VERSION` (see memory `kb_bot_nav_costs_and_anchors`).
- Don't let travel-dodge fight the autopilot give-up/stuck-loop logic (recently fixed) — verify it
  doesn't cause oscillation.

## Verify
- Compile. Reason through (or add a unit test for) the decision predicate: "mob ahead on path within
  lookahead + legal jump ⇒ dodge; else walk." Do NOT run nav/graph suites.
