---
name: kb_bot_ops_console
description: In-game GM ops console (Messenger-window-as-console) for inspecting/driving bots + live decision streaming; borrowed from SoloMapling MMC
metadata: 
  node_type: memory
  type: project
  originSessionId: c7600c77-4efe-4e2f-a2aa-af537d2356e2
---

Tactical borrow of SoloMapling's `MapleMessengerConsole` (MMC). A GM opens the Maple
Messenger window and types `Console: <verb> <args>`; output renders back into the same
window. Purpose: a quiet, scrollable dev pane separate from party/map chat. Full audit/plan
rationale in `docs/bot/solomapling-audit.md` (line 56 tactical-borrow entry).

**Key finding:** the messenger window CANNOT replace our file-based debug. A 3-line chat pane
is a low-bandwidth interactive channel; high-bandwidth structured dumps (`BotPathLogger` nav
traces -> `logs/bot-nav/`, inv/scroll dumps) stay file-bound. The console is additive: a
remote-control + live-summary layer that TRIGGERS file dumps and surfaces their paths. See
[[kb_bot_navigation_architecture]].

**Files (all new under server.bots):**
- `BotOpsConsole.java` (public singleton) — `isConsoleLine()` prefix gate, `handle()` verb
  dispatch, `onMessengerClosed()` teardown, name-resolution by exact/unique-prefix. Every verb
  DELEGATES; no reimplemented parsing.
- `BotConsoleTap.java` — live decision stream. Decorator installed over the mutable static
  `BotAutopilotManager.reply` BiConsumer (the chokepoint every autopilot announcement flows
  through). subscribe/unsubscribe by botCharId; default off = zero hot-path overhead; calls
  original first so normal party/whisper routing is never disturbed. Single-operator global swap.

**Non-bot hooks (minimal, same diff SoloMapling made):**
- `MessengerHandler` case `0x06`: `if (player.isGM() && BotOpsConsole.isConsoleLine(input))
  -> handle() else world.messengerChat(...)`. Console lines are swallowed, not broadcast.
- `client/Character.closePlayerMessenger()`: one teardown line (covers manual close + logout
  via `closePlayerInteractions`).
- `BotManager.allEntries()` added (pkg-private snapshot of all entries) for name resolution.

**v1 verbs:** `help`, `list`, `status [name]` (-> `mapBotStatusLines` / `statusReport`),
`log <name>`/`unlog` (tap stream), `grind <name>` (-> `BotAutopilotDebug.exportPartyDecision`,
writes `ap-debug-*` file, path chats via botReply NOT the window), `say <name> <text>` (->
`BotChatManager.handleChat` — drives existing regex grammar), `cmd <@command>` (->
`CommandsExecutor.handle`; nested client-lock OK: `actionsSemaphore` has 7 permits +
ReentrantLock). Bot targeting is by NAME (exact case-insensitive, then unique prefix).

**v1 limits:** `cmd`/`grind` output goes to normal chat, not the window; `log` streams
decision ANNOUNCEMENTS only (per-tick nav trace + full grind scoring stay file-based). Single
console / single operator / one world. No dedicated `nav` verb — use `cmd !botnav`.

Build verified BUILD SUCCESS (commit on branch `experimental`). Owner-feature gating philosophy
unaffected (this is GM-gated dev tooling). Related: [[project_grind_advisor_perf]],
[[kb_bot_response_overlay]].
