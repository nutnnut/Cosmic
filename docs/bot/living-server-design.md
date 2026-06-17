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

- **P4 — parties:** persistent crews (`managed_bot.group_id` → log in together and form a party via
  `joinBotToOwnerParty` + `BotAutopilotManager.startParty`) and dynamic ad-hoc party-up by `sociability`.
  Reuses the existing cohort travel/grind cohesion + the level-gap idle-leech catch-up.
- **Aliveness extras:** login/logout chatter (gated by `chattiness`); the scheduled population is also the
  natural substrate for the simulated economy (`docs/bot/economy-design.md`).

## How to turn it on (operator)

1. Generate a pool: `@spawnbot generate confirm` a handful of times (each becomes a managed bot with a
   random personality). 2. `@botpop on`. 3. Watch with `@botpop status` / `@botpop list`; tune
   `POPULATION_CURVE` (tight values for testing). Real player characters are never touched.
