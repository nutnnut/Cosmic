---
name: kb_bot_chat_charset_ascii_only
description: "Bot chat is US-ASCII only — non-ASCII chars render as '?'; sanitizeChat chokepoint + use plain ASCII in reply strings"
metadata: 
  node_type: memory
  type: project
  originSessionId: 5d15e37b-e6fa-47b8-80c7-5085de3ecffd
---

Bot chat (and most v83 client text) is encoded with `CharsetConstants.CHARSET`, which on this server is **US-ASCII**. Any character > 0x7F — em dash `—`, en dash `–`, curly quotes `’ ‘ “ ”`, ellipsis `…`, non-breaking space — encodes to a literal `?` on the client. This caused corrupted bot messages like `"done ? 35 trash equips"` (was an em dash).

**Rule for writing bot reply/say strings: use plain ASCII only.** Use `-` not `—`, `'` not `’`, `"` not `”`, `...` not `…`. This applies to every `botSay`/`botReply`/`botSayParty` arg, `yellowMessage`, and LLM-prompt-derived output.

**Runtime safety net:** `BotManager.sanitizeChat(String)` is routed through all three chat sinks (map `botSay`, whisper `botReply`, party `botSayParty`). It maps common typographic chars → ASCII, replaces other non-encodables with `?`, and **`log.warn`s the original→cleaned string** so corruption is auto-fixed and flagged in console. `yellowMessage` is NOT a chat sink and is not covered — keep its strings ASCII at the source.

Gotcha: `BotManager` imports `client.Character`, so a `Map<Character,String>` field resolves to the wrong type — qualify as `java.lang.Character`.

Source encoding is UTF-8 (`pom.xml` `project.build.sourceEncoding`), so non-ASCII literals compile, but player-visible bot text must still be sanitized to US-ASCII.
