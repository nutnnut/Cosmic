# Inert autopilot recovery

An autonomous bot with no active rest, errand, logout, or party reason can leak into an inactive
state (`autopilotMapId == -1`) and report `idle rn`. This is a bug state, not a break.

`BotManager.maybeRecoverInertAutopilot(...)` is the recovery seam. It re-runs the grind decision for
self-owned bots with bounded backoff; `BotAutopilotManager.decide(...)` logs failures instead of
silently converting exceptions into permanent idleness. A null/no-reachable recommendation remains a
real failure and may invoke trapped-region recovery.

`BotAutopilotManager.activityCategory(...)` keeps legitimate breaks, chill sessions, errands,
idle-leech, and logout distinct from inert idle. `stuckReason(...)` powers the web roster's
`possibly stuck` filter and reports persistent decision failure or leaked-off autopilot; transient
travel cooldowns do not establish a wedge.

When diagnosing a growing idle population, inspect `/api/live` and `/api/botdebug`, then the warning
from `decide(...)`. A stackless fast-throw exception may require a restart with
`-XX:-OmitStackTraceInFastThrow` to identify the original call site.
