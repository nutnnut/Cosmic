# Idle chair sitting + obnoxious shout padding

Two social-flavor behaviors keyed off new `BotPersonality` traits (both seed-salted, stable across
restarts, uncorrelated with the other rolls — same trick as `gachaAppetite`/`haggleTemper`):

- `obnoxiousness()` — 0..1 (seed-0 default = 0.0). Drives shout-bubble padding AND biases the chair
  pick toward the biggest seat.
- `sitAppetite()` — 0..1 (seed-0 default = 0.3). The per-idle-window sit probability. A low roll is a
  bot that essentially never sits; a high roll sits at almost every idle chance.

## Chair sitting (`BotChairManager`)

- Bots sit only on chairs they OWN in their SETUP inventory. There is **no hand-out** — chairs arrive
  organically from gachapon pulls (`3010xxx` are in the pools), so this fires emergently for bots that
  gamble. Fishing chairs are excluded (sitting one starts the fishing minigame).
- Chair "size" for the obnoxious-hogs-the-biggest bias = the `effect/0` canvas area (w*h px) of the
  chair's Install graphic, via `ItemInformationProvider.getChairSpriteArea` (a bot-only WZ exposure).
  `chooseChair` weights by `area^k`, `k = 3*obnoxiousness` (k=0 → uniform random).
- Entered from every parked-idle context: **rest breaks** (grind-tick break branch — grind maps
  included, not just towns), **idle-leech** (out-levelled party member parked so lowbies get the exp),
  **chill / inert town-or-safe idle** (`tickTownIdleDestack`, gated safe + self-driving), and the
  **FM shout-stand** (`tickShout`; the bot keeps shouting from the chair, fidget/reposition suppressed
  while seated).
- **Sitting does NOT change the touch hitbox, verified.** `BotCombatManager.getBotTouchBounds` is the
  swept FOOT position + a fixed height — no stance/chair/sprite term — so a seated bot is exactly as
  (in)vulnerable as a standing one. That's what makes grind-map/leech sitting safe. A real hit
  (`BotCombatManager.applyDamage`, dmg>0) calls `BotChairManager.knockOutOfChair`: stands the bot so
  knockback/physics resume normally, and cools down re-sitting (20-60s) so it doesn't replop mid-fight.
  The leech branch's existing HP-drop check then relocates it to a fresh safe spot.
- **NOT gated on observation, on purpose.** Sitting is a persistent state carried in the spawn packet
  (`PacketCreator.spawnPlayerMapObject` writes the SETUP chair id), and it survives the LOD1 freeze / LOD0
  materialize (neither clears `Character.chair`). So bots roll to sit even on an empty map; when a player
  walks in they find the steady-state fraction already seated instead of every bot standing and only
  trickling into chairs afterward. Sit/stand broadcasts are no-ops on an unobserved map, so it's nearly
  free there.
- **Trades run from the chair.** Nothing server-side gates a trade on stance (`getChair()` is read only
  by the accessor + this manager; `Trade.java`/`PlayerInteractionHandler` have no chair check), and the
  shout-trade state machine (`BotShoutTradeManager.tick`) is driven from `runCommonTickSystems`, which
  runs before the open-trade short-circuit — so a seated bot completes a walk-up sale without standing.
  `tickTradePhysicsOnly` early-returns when seated so it doesn't broadcast an idle-move over the pose.
- **Invariant: a bot must never travel/act while parked in a chair.** The one central guard is
  `standIfSeated` at the top of `BotManager.stepMovementCore` (every real locomotion path funnels through
  it; the sit branches all `return` before reaching it). Belt-and-suspenders stands also sit at the
  transition owners — `BotBreakManager.endBreak`, the autopilot errand loop (except the FM shout-stand
  sub-state, which owns its sitting), `tickLogout`, and the FM shout-stand's exit/trade branches. Seated
  state lives on `Character.getChair()` (>=0 = seated); timers `chairSitUntilMs`/`nextChairRollAtMs` on
  `BotEntry`.

## Shout padding (`BotMarketChatter`)

- Only bots with `obnoxiousness >= 0.5` pad; per-utterance roll and how close to the length cap they
  push both scale with obnoxiousness. Padding is a trailing run of `@` (ASCII, invariant 3) after a
  space, capped at `Byte.MAX_VALUE` (127) — the general-chat length ceiling `GeneralChatHandler`
  enforces. Cosmetic only: bots match off the structured `BotMarketShoutBus`, never the spoken line,
  so a padded line that no longer parses as a grammar `Offer` is fine.

See [[kb_bot_market_shout_haggle]] for the shout wire format and [[kb_bot_player_party_social]] for the
broader social layer.
