---
name: kb_bot_response_overlay
description: "BotPrompt — SSOT for the sendHint \"possible responses\" overlay shown when a bot asks its owner a discrete-choice question"
metadata: 
  node_type: memory
  type: project
  originSessionId: 284c72bf-8151-435f-96f0-fa38f1c14f92
---

`server.bots.BotPrompt.showOptions(BotEntry, String question, List<String> labels)` is the SSOT for
popping a `PacketCreator.sendHint` overlay on the OWNER's screen. The overlay is HEADED by the bot's
actual question (the chat line), then lists the replies the owner can type, when a bot asks a
discrete-choice question (inspired by SoloMapling's social-bot hint menus). Mirrors the
canonical NPC-script idiom `sendHint` + `enableActions` (see `AbstractPlayerInteraction.showInstruction`).

Key design facts (so it isn't reinvented):
- **Presentation only — no reply capture.** Reply parsing stays in the existing per-prompt handlers
  (`BotScrollManager.handleScrollConfirm`, the AP/SP/job-advance regexes in `BotChatManager`). No new
  pending-prompt matcher was added (deliberately — avoids stale-prompt mis-capture + greedy-match
  hijacking the LLM).
- **Labels MUST equal the exact tokens the handler accepts.** The overlay renders tokens VERBATIM with
  no numeric prefixes ("- yes", not "1. yes") because the handlers parse tokens, not list indices —
  a "1." prefix would invite typing "1", which they reject (e.g. scroll "1" -> cancel).
- **Timing:** queued prompts carry options on `BotChatManager.QueuedMessage.overlayOptions`; the overlay
  fires in `drainMsgQueue` exactly when the question is spoken. Direct `botReply` prompts (scroll) fire
  the overlay immediately after. Chat line is source of truth (persists); hint is a transient nudge.
- Offline-safe (no-op if owner null/offline), US-ASCII only (chat charset rule).

Wired (all behavior-preserving — only the overlay was added; reply parsing untouched):
- scroll yes/no/let-me-see (`BotScrollManager.requestScrollPass`)
- AP build (`BotBuildManager.apBuildOptions`), Hero SP variant (`spVariantOptions`)
- job advance (`BotBuildManager.buildJobPrompt` returns a `JobPrompt(text, options)` record — text+options
  co-located, single source)
- craft yes/no (`BotMakerManager` craft_confirm)
- equip offer yes/no — both `BotOfferManager.createOwnerUpgradeRequest` (bot wants owner's gear) and
  `promptLootOfferAfterLoot` (bot offers loot; overlay gated on recipient==owner since it can target a
  sibling bot)
- item_choice trade/drop/nvm (`BotChatManager` applyTransferCommandResult + applyItemQueryResult)
- relog yes/no, logout yes/no, owner_away town|logout / stay|logout (`promptOwnerAway`)

Queue plumbing: `QueuedMessage.overlayOptions` + `queueBotReply(.,.,opts)` / `queueBotSay(.,.,opts)` /
`queueBotSayWithEstimatedDelay(.,.,opts)`; `drainMsgQueue` fires `BotPrompt.showOptions(entry, msg.text, opts)`
for both say and reply branches. Direct (non-queued) `botReply` prompts call `showOptions` inline right after.

NOT wired: `skill_tree_choice` (dynamic per-job tree options + info-only report selector — labels would need
to mirror `resolveSkillTreeChoice`; skipped to avoid mismatched labels). Visually unverified in-client as of
Implemented 2026-06-14.
