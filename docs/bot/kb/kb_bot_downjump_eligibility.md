---
name: kb-bot-downjump-eligibility
description: "Down-jump drop-distance is NOT capped (300px was empirically wrong, removed); eligibility rule still unknown"
metadata: 
  node_type: memory
  type: reference
  originSessionId: a05517cc-fbd8-4558-83bc-a6266fec5fbc
---

The bot down-jump (jump+down through a platform) has **no drop-distance cap**. An earlier
`DOWN_JUMP_MAX_DROP_PX = 300` (commit 48bcab404, claimed disasm-confirmed from
`CUserLocal::FallDown` @ 0x94c4f8's `0x12c` probe) was **empirically wrong**: the owner can
down-jump in-client where the capped bot refused, and the cap deleted the only graph link to
the Orbis station (200000000) arrival platform — a ~780px straight-down drop — islanding it and
stranding follower bots ("no path found"). Bisected, cap **removed** (`simulateDownJumpLanding`
no longer caps distance), GRAPH_VERSION 56→57.

**The true client eligibility rule is still UNKNOWN — it is neither a 300px probe nor purely the
`forbidFallDown` flag. Do NOT re-cap on either without in-client + disasm proof.** Current policy:
generate a down-jump edge wherever a real landing exists below (any distance); `canStartDownJump`
still refuses a `forbidFallDown` source foothold; execution abandons edges that prove unwalkable.

Authoritative writeup + retraction: `docs/bot/physics-client-audit.md` §(b) (in git).
Regression test: `BotNavigationGraphProviderTest#shouldConnectOrbisStationLowerLedgesToUpperPlatform`.
Related: [[kb_bot_nav_costs_and_anchors]], [[kb_bot_navigation_architecture]], [[reference_reverse_engineering_toolkit]].

RESOLVED separate gap (GRAPH_VERSION 58): mid-rope jump-grabs were missing. `canReachRopeFromGround`
horizontal reach used the jump arc back to launch height only; a rope/ladder whose climbable span
hangs BELOW the ledge lets the bot drift sideways through the descent and catch it farther out. Fixed
with `BotPhysicsEngine.maxHorizontalTravelWithDrop` (airtime = rise + fall to dropPx below launch).
General "stand by a ladder, jump and catch it" fix; long-standing bug (not the graphgen rewrite).
