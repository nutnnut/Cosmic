---
name: project_bot_web_observability_ssot
description: "Direction — the BotWorldGraphWebServer HTTP endpoints (and a future MCP wrap) become the live observability SSOT for bots, superseding the scattered file loggers / in-game debug chat commands (equip, grind, nav, pathlog, perf, scroll, status) as appropriate"
metadata: 
  node_type: memory
  type: project
  originSessionId: a7e21353-fa1b-4294-8adf-6ab5fff88975
---

User direction (2026-06-23): the bot web endpoints should become the single live debugging/observability surface and "render obsolete the bot equip / grind / nav / pathlog / perf / scroll debugs — all the bot debugs/logging that exists, as appropriate / if possible".

**Why:** today bot observability is scattered across file loggers ([[kb_bot_navigation_architecture]] pathlog, `grind.*` perf instrumentation, equip optimizer dump, scroll planner, [[kb_bot_grind_advisor_perf]]) + in-game chat commands ("grind debug/profile", "inv debug", STATUS classifier) + the Messenger ops console ([[kb_bot_ops_console]]). An agent/operator must scrape logs or sit in-game. A read-only HTTP/JSON surface is queryable live by both the web UI and an agent (proven: diagnosed party-cohesion scatter purely via `/api/live` + `/api/mapinfo`, no log scraping).

**How to apply (staged, lazy — do NOT wholesale-delete contested loggers):**
- Web = live observability SSOT. Surface each subsystem's debug DATA through an endpoint; retire the file logger / chat command per-subsystem only once its coverage is confirmed live. Many of these files are actively edited by a concurrent session — coordinate, stage only your hunks.
- Started: `/api/botdebug` on BotWorldGraphWebServer (commit c1150a8e9) — per-bot owner/party/crew/autopilotParty/dst/errand/grinding/follow/transit/straggler/operator + @botstatus line. Existing read endpoints: `/api/live`, `/api/mapinfo` (mobs + per-bot statusReport + chat).
- Added (2026-06-23): `/api/bot/pathlog?id=` (877187312) — on-demand toggle mirroring `!botnav pathlog`: 1st call starts the 120-tick recorder (off = zero cost), 2nd dumps the trace text (reuses BotPathLogger.dumpToFile, SSOT). `/api/perf?on=1|0` (e87930c4a) — serializes BotPerformanceMonitor.snapshot() per-subsystem timings (incl. "scroll-scan"); `on` toggles the opt-in monitor. All routes documented in docs/bot/web-endpoints.md (CLAUDE.md rule 8).
- High-frequency per-tick traces (pathlog give-up reasons, retreat factors, nav step logs) don't map to a poll snapshot — surface as an on-demand per-bot "recent trace" buffer/endpoint rather than a firehose, or keep as opt-in.
- **TODO (deferred, beneficial): MCP wrap.** Wrap the read endpoints (and selected write ops) in a small MCP server so the tools appear natively in the agent toolset and persist across sessions/agents without hand-written urllib. The HTTP endpoints are the 90%; MCP is the thin native-tool layer on top. Mirror the existing read-only-first posture (LAN, no auth, consistent with the viewer).
