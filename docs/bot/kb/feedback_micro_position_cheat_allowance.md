---
name: feedback-micro-position-cheat-allowance
description: "Scoped exception to the intent-based-only rule: sub-tick micro-positioning may cheat when 50ms input granularity makes a maneuver humanly-legal but bot-impossible"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a05517cc-fbd8-4558-83bc-a6266fec5fbc
---

The HARD rule stands: bots play legally, intent-based input only, never force velocity/teleport.
USER-GRANTED EXCEPTION (2026-06-12, El Nath 2px launch-window deadlock): when a maneuver is
possible for a real player at the client's 8ms step granularity but provably impossible/fragile
at the bot's 50ms tick input granularity (e.g. creeping into a 2px jump launch window on fs=0.2
ice), the bot MAY cheat with sub-fullspeed micro-positioning - but only as a LAST resort after
legal pulse control (one-tick accel + glide stop-out) is shown insufficient.

**Why:** the 50ms/8ms granularity gap is a bot-harness artifact, not a skill gap - a human CAN
tap-walk in ~0.03px increments on ice; denying the bot that fidelity creates deadlocks no
player would have.

**How to apply:** scope any cheat to the smallest displacement that legal input cannot express
(sub-pulse position nudges toward a launch window), keep it off normal locomotion, and prefer
fixing the legal controller first. Reference geometry: fs=0.2, accel 280 px/s^2, glide 80
px/s^2, one 50ms pulse from rest moves ~1.6px total - windows >= ~3px need no cheat at all.

Related: [[kb_bot_slippery_ground_physics]].
