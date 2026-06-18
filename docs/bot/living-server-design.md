# Living-server bot system — design & status

A managed population of server-owned bots that log in/out on believable, varying schedules, each with
a persistent personality (when/how long it plays, farm vs. idle, sociability, career arc), so the world
feels populated at peak hours and quiet at 4am — emergent from per-bot schedules, not scripted.

Plan of record: `~/.claude/plans/plan-how-would-you-serialized-pascal.md`. This doc tracks the design
and what has shipped.

## Hard safety invariant

Only **explicitly server-generated** bots are ever auto-scheduled. The `managed_bot` table is the single
source of truth: a row there ⇔ "disposable, server-owned bot the scheduler may log in/out, retire, and
replace." Rows are created **only** by the generation path (`@spawnbot generate`); a real player's
character (`@registerbot`, `@botme`, or a named `@spawnbot`) never gets a row, so the scheduler can never
touch it. The scheduler also guards against double-spawning a bot already live/online.

## Persistence (storage vs DB)

- **DB** for structured, low-churn, queryable state:
  - `managed_bot` (registry/marker): `bot_char_id` PK, `group_id` (crew), `enabled`, `retired_at`
    (career end), `last_online_at`, `created_at`. (`db/tables/027-managed-bot.sql`, `ManagedBotService`)
  - `bot_config` (personality blob, its scaffolded purpose): the serialized `BotPersonality`.
    (`db/tables/026-bot-config.sql` — now registered in the changelog; `BotConfigService`)
  - The **character row** already persists level/exp/job/inventory/position for free, so bots level up
    across sessions and veterans emerge over time.
- **Disk** for high-churn episodic chat memory — the existing `BotMemoryStore` (JSONL + summary), left
  as-is. A richer relationship/episodic ledger is deferred.

## BotPersonality (`BotPersonality.java`)

Deterministic from a seed (the char id), so a bot is stable even before its blob is first saved.
Serialized as a tolerant flat `key=value` line (no JSON lib in this project): unknown keys ignored,
missing keys default — forward-compatible. Fields:

- **Schedule:** `daysActiveRatio` (plays ~this fraction of days, deterministic per day → sporadic
  players), 24-slot `hourWeights` (a Gaussian peak → morning person / night owl / after-work),
  `sessionLenMeanMin`.
- **Play style:** `farmIdleRatio` (diligence), `breakFreqPerHour`, `breakLenMeanMin`.
- **Social/other:** `sociability`, `chattiness`, `riskTolerance`.
- **Career:** `Archetype` {TOURIST, CASUAL, REGULAR, HARDCORE} + `careerLenDays` (hardcore = forever);
  `engagementMultiplier(level)` tapers daily-online chance as a bot levels, so not every bot maxes.

`loadOrCreate(charId)` parses the saved blob, else rolls+persists one for managed bots, else neutral
`defaults()`. Loaded onto `BotEntry.personality` at spawn. **Non-managed bots get `defaults()` with
`breakFreqPerHour = 0`, so they never random-break or get scheduled** — personality behaviors are a
managed-bot trait.

## In-session breaks (`BotBreakManager`) — SHIPPED

A grinding bot periodically stops and idles (held spot, spread out) instead of farming non-stop —
realizing its farm/idle rhythm. Per-minute start roll from `breakFreqPerHour`, damped by `farmIdleRatio`
(diligent bots break less) and capped; jittered length from `breakLenMeanMin`. Wired as a grind-tick
branch next to idle-leech (the safe integration point); the bot still pots/heals during a break.

## Population scheduler (`BotScheduler` + `BotScheduleMath`) — P3a SHIPPED (default OFF)

A `TimerManager` sweep (every `POPULATION_SWEEP_MS`) reconciles the live managed-bot count toward a
target curve by server-local hour (`POPULATION_CURVE`, with bounded noise), biased by each bot's
`onlineDesire` (hour preference × engagement × play-rate; 0 if not active today). It does session-length
logouts, then brings the most-eager offline bots online (`spawnManagedBot`) or logs out the least-eager
excess (`logoutManagedBot` = save + disconnect). Decision math is pure and unit-tested.

**DEFAULT OFF** (`POPULATION_SCHED_ENABLED`): registered at boot (`Server.init`) but no-ops until enabled,
so a server start never silently spawns a crowd. Enable + inspect via `@botpop` (below).

### Config knobs (`BotManager.cfg`)
`POPULATION_SCHED_ENABLED` (false), `POPULATION_SWEEP_MS` (60s), `POPULATION_WORLD/CHANNEL` (0/1),
`POPULATION_CURVE` (24 hourly targets), `POPULATION_NOISE`, `MANAGED_POOL_MAX`, `HARDCORE_CAP`,
`POPULATION_AUTOGEN`.

## `@botpop` admin command — SHIPPED

GM command to drive/inspect the population: `status` (curve target now vs live count, enabled flag),
`on`/`off` (toggle the scheduler), `list` (managed bots + schedulable/retired), `sweep` (force a
reconcile now). See `client/command/commands/.../BotPopCommand`.

## Career turnover + auto-generation (`BotGenerator`) — P3b SHIPPED (default OFF)

The self-sustaining churn: newcomers join as veterans leave, all inside the same default-OFF reconcile
sweep (nothing happens until `@botpop on`).

- **Turnover:** each sweep, an OFFLINE managed bot whose career age (`managed_bot.created_at` → days)
  has reached its `careerLenDays` is retired (`retired_at` set) — kept as a record, no longer scheduled.
  Hardcore never retire; a live session is never yanked (retire only when offline).
- **Auto-generation:** when the live count is still below the hour's target after waking every eligible
  offline bot, `BotGenerator.generateManaged` creates ONE fresh level-1 managed bot per sweep (gradual
  inflow; gated by `POPULATION_AUTOGEN` + the non-retired `MANAGED_POOL_MAX` ceiling) and spawns it.
- **Hardcore cap:** enforced at generation, not retirement — a fresh HARDCORE roll is re-rolled to a
  finite career while the non-retired hardcore count is already at `HARDCORE_CAP`, so the never-retiring
  veteran set stays small and turnover keeps flowing. Hardcore bots, once created, truly stay forever.
- **Shared creation path (rule #1/#6):** account + character creation was extracted out of
  `SpawnBotCommand` into `BotGenerator.createBotCharacter`; both `@spawnbot`/`generate` and the
  auto-generator now use it. Decision math (`autogenCount`, `hardcoreAllowed`, `careerEnded`) is pure and
  unit-tested in `BotScheduleMath`.

## Planned (not yet shipped)

- **P4 — parties:** see the dedicated design below.
- **Aliveness extras:** login chatter (gated by `chattiness`); the scheduled population is also the
  natural substrate for the simulated economy (`docs/bot/economy-design.md`). **Logout goodbye SHIPPED:**
  a scheduled logout says a chattiness-gated goodbye ("gtg ty cya", many variants), leaves its party, then
  disconnects after a 5-15s human beat (`BotManager.logoutManagedBot`, `loggingOut` guard).

## Parties (`BotSocialManager` + crews) — P4

**SHIPPED — dynamic ad-hoc party-up + invite gating:**
- `BotManager.partyUp(leader, joiner)` is the party-formation SSOT (owner-join delegates to it); it
  never assigns ownership, so a party-mate gets zero owner privileges.
- `BotSocialManager.tick` (common-tick, per-bot cooldown): a SOLO self-owned autopilot bot, co-located
  with another solo bot, occasionally offers to party — trait-gated by `BotSocialMath` (initiate ∝
  sociability×chattiness; accept ∝ sociability + the `EXP_SPLIT_LEECH_INTERVAL` exp-share window, with a
  riskTolerance-scaled "mistake" across too-big a gap; a quiet bot ignores rather than speaks a decline).
  Offer/accept/decline lines are cosmetic ASCII; the party is formed server-side + `startParty` cohort.
  Self-owned only — never touches a player's companion. Knob `cfg.SOCIAL_PARTY_ENABLED` (default on).
- Invite acceptance (`PartyOperationHandler` → `BotManager.acceptsPartyInvite`): a bot no longer blindly
  auto-accepts any invite. An OWNED companion accepts only its registered owner (anti-yank); a self-owned
  bot accepts a real player's invite per sociability/level — so players can party managed bots safely.
- Pure policy unit-tested in `BotSocialMath`.

**STILL PLANNED — persistent crews (`managed_bot.group_id`):** a fixed group that logs in together and
auto-parties on spawn. Needs BOTH a crew-assignment path (nothing sets `group_id` yet) AND scheduler
co-spawn — deferred (the dynamic system above already makes bots group up; crews add only "always the
same people"). Also deferred: a bot proactively *inviting a real player* (vs the player inviting it).

Two layers, both reusing the server-side party SSOT (`Party.createParty`/`joinParty` — bots join
server-side, no invite-packet dance) and the existing cohort grind cohesion (`BotAutopilotManager
.startParty`/`applyPartyPlan`, the `autopilotParty` flag) plus crowd-dispersion + level-gap idle-leech.

### Safety invariant (audited): party membership grants ZERO owner privileges
Ownership is DB-backed (`bot_owners`, written ONLY by `registerOwner` via @registerbot / @spawnbot /
same-account login); no party path ever calls it. Owner perks — loot/NX redirect, potion + ammo supply
share, ferry/follow — are each gated on `entry.owner` (the registered, online owner) and donor-filtered
to the owner's own bot collection, so a party-mate triggers none. Trade-rob is structurally impossible:
a headless bot has no trade UI and its tick halts during an open trade, so it never adds/confirms items.
**Required fix:** `BotChatManager.handleChat` currently parses follow/stop/grind for any speaker — gate it
on `sender == entry.owner` so a party-mate (or peer social chatter) can't command the bot. That gate is
also the seam where non-owner chat branches into the social handler.

### P4a — persistent crews
Bots sharing `managed_bot.group_id` (stored since P0, unused) are brought online together by the
scheduler and auto-formed into a party (mechanical, server-side), then `startParty` as a cohort. One
crew greeting, `chattiness`-gated.

### P4b — chat-driven ad-hoc party-up
`BotSocialManager` runs a trait-gated state machine over MAP chat (bots already receive all map chat via
`GeneralChatHandler` → `BotManager.handleChat`). Interactions: open LFP shout, targeted offer to a
co-located soloist (bot OR real player), ask to join a visible party, accept / decline / ignore,
"sorry, full" at 6, greet-then-ask (chatty bots), leave/disband on logout or interest-decay.

- **Real players included (per owner request):** a bot may offer a nearby player a party — sends a real
  `PacketCreator.partyInvite` through `InviteCoordinator` so the player's client shows accept/decline; a
  player asking "can I join?" in map chat makes the bot leader invite them per `sociability`. Bot↔bot
  uses silent server-side `Party.joinParty`. The player joins as a plain party-mate: NO ownership, NO
  command rights, NO supply/loot priority, NO trade trust (see the safety invariant).
- **Invite-acceptance routed through sociability:** the existing PartyOperationHandler BotClient
  auto-accept becomes a social decision — a managed bot accepts per `sociability`; an OWNED companion bot
  accepts only its registered owner's invite (so a stranger can't yank someone's companion).
- **Trait gating (the spectrum):** `partyInitiateChance ∝ sociability × chattiness × (not partied)`;
  `acceptChance ∝ sociability`; `chattiness` gates whether responses are spoken (sociable+quiet → joins
  with a terse/no reply; unsociable+quiet → just ignores). So some bots never party, some always offer,
  some never talk.
- **Level-gap gate (exp-share aware, SSOT):** a party only shares a kill's exp with members inside the
  server's `EXP_SPLIT_LEECH_INTERVAL` (= 5) level window (`Monster.distributePartyExperience` — outside it
  a member is flagged `underleveled` and gets nothing; this is the same cutoff the idle-leech keys off).
  So the social decision computes the level gap to the prospective partner / party level-range and
  **declines (or won't initiate)** when the gap exceeds that window — partying out of exp range is
  pointless. To stay humanlike, a small personality-scaled MISTAKE chance (≈ base × (0.5 + riskTolerance))
  still occasionally offers/asks across too-big a gap (bots, like people, don't always check first); the
  *other* side then usually declines, which reads naturally.
- **Anti-spam:** a per-bot social cooldown (`nextSocialAtMs`) + pending-offer timeout
  (`pendingPartyOfferUntilMs`) on top of the existing ~5s chat-queue drain; canned ASCII line-pools per
  intent (like `BREAK_MSGS`/`WB_REPLIES`), not LLM.

### Pieces / refactors
Pure tested core in `BotPersonality`: `partyInitiateChance()` / `acceptChance()` + a
`socialDecision(personality, intent, levelGap, shareWindow, roll) → {INITIATE, ACCEPT, DECLINE, IGNORE}`
(levelGap > shareWindow ⇒ DECLINE unless the MISTAKE roll fires; `shareWindow` is passed in from
`EXP_SPLIT_LEECH_INTERVAL`, never hardcoded). Extract a generic
`partyUp(Character leader, Character joiner)` SSOT from the misnamed `joinBotToOwnerParty` (it already
does server-side create/join, no `registerOwner`); add the `sender == owner` command gate; new
`BotSocialManager`; new `BotEntry` social fields.

## How to turn it on (operator)

1. Generate a pool: `@spawnbot generate confirm` a handful of times (each becomes a managed bot with a
   random personality). 2. `@botpop on`. 3. Watch with `@botpop status` / `@botpop list`; tune
   `POPULATION_CURVE` (tight values for testing). Real player characters are never touched.
