# 02 — Randomize appearance + per-bot config store

**Status:** partially scoped (appearance fields known; **legal-id source NOT yet verified** — do that
first). Read `README.md` first. Two related sub-features; A is small, B is a design decision.

## A) Randomize appearance on bot creation
**Today** `client/creator/BotCreator.java:createCharacter` hardcodes a single look:
- `botChar.setGender(0)` (line ~32)
- `botChar.setSkinColor(SkinColor.getById(0))` (line ~31)
- `botChar.setHair(30020)` (line ~34)
- `botChar.setFace(20100)` (line ~35)

**Goal:** randomize gender, skin, hair, face (eye) — but ONLY from **legal** values a real
character-creation would accept. Randomizing to an invalid id risks a broken/garbage look or client
issues, so the legal sets are load-bearing.

**FIRST: find the legal-id source (do not guess — rule "verify IDs").** Candidates to check:
- The real char-creation path / `CharacterFactory` and the novice creators
  (`client/creator/novice/BeginnerCreator.java` etc.) — what hair/face/skin they pass and any
  validation (`MakeCharInfoValidator` was mentioned as the client-packet validator BotCreator skips —
  read it to learn the allowed ranges).
- WZ/`Etc` makeup data or a beauty/makeover NPC's allowed lists, or an `ItemId`/constants list.
- Typical v83 ranges (VERIFY before relying): hair `30000-3xxxx` (male) / `31xxx` (female), face
  `20000-2xxxx`, skin `SkinColor` enum ids 0-3 (+ a few specials). Gender 0/1.
**Once verified**, build small legal pools (gender-correct hair/face) and pick with `ThreadLocalRandom`.
Keep it humanlike: pick gender first, then a hair/face/skin legal for that gender.

**Wiring:** replace the four hardcoded setters in `BotCreator.createCharacter` with random legal picks.
Mirror whatever real creation does so the look is always valid. Compile; spawn a few bots to eyeball.

## B) Per-bot config / personality store (design decision)
**Today** all bot config is **global**: `BotManager.cfg` (`Config`). Per-bot runtime state lives only
in-memory on `BotEntry` (volatile, lost on restart). Ownership is persisted via `BotOwnershipService`
(check for a `bot`/ownership table).

**Goal:** a per-bot store for future personality/behavior knobs (e.g. aggression, risk tolerance,
chattiness, jitter profile), tunable per bot and surviving restart.

**File vs DB — recommendation:** bots are already DB-backed characters, and personality should persist
across restarts and be **per-character runtime state** (not git-shared repo content). So:
- **Recommend a DB table** `bot_config` keyed by `charid` (PK), columns per knob OR a single JSON/text
  blob column for forward-compat. Load into `BotEntry` on spawn; defaults from `BotManager.cfg` when no
  row. This matches how the project already treats bots as DB entities.
- A repo file (yaml/json) is wrong here: personality is per-bot runtime state, not shared source; and
  keying a file by transient char ids is awkward. (Reserve repo files for shared *defaults/templates*
  if ever wanted.)
- **Confirm before building:** grep for any existing per-bot persistence or config-file loader
  (`*.yaml`/`properties`/`ServerConfig`) and follow the established pattern if one exists.

**Scope for this item:** Sub-feature A (appearance) is the concrete deliverable. Sub-feature B is a
**design + minimal scaffold**: create the `bot_config` table migration + a load/save path that reads
into `BotEntry` with code defaults, but DON'T invent personality knobs yet (no consumer exists). If B
balloons, deliver A and leave B as a written recommendation in this doc.

## Tests / verify
- Appearance: compile; assert random picks are within the verified legal sets (a small unit test over
  the pools). Spawn-and-look is the real check.
- Config: if you add the table, a load-default/round-trip test.

## Out of scope
- Actual personality behaviors that consume the config (separate, once knobs are defined).
