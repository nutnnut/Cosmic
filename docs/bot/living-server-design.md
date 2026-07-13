# Living-server bot system

Current as-built overview for population, sessions, social behavior, and simulation LOD.

## Safety boundary

Population automation operates only on characters registered through `ManagedBotService`. It never
logs out, retires, or mutates ordinary player characters. Party membership does not grant ownership:
owner-only commands and inventory actions still use the ownership service and online-owner checks.

## Population and sessions

- `BotScheduler` reconciles online managed bots to the hourly curve and multiplier, chooses by
  `BotPersonality`, retires expired offline careers, and asks `BotGenerator` to refill deficits.
- Population scheduling and auto-generation are currently enabled by default in `BotManager.Config`.
  The live values are editable through `/admin`, `/api/settings`, and `@botpop`; do not copy defaults
  into operational runbooks.
- `BotBreakManager` handles in-session rests. `BotManager`/`BotScheduler` handle login sessions,
  logout loiter, and chill sessions. Crew decisions are coordinated so a crew does not split merely
  because independently sampled timers disagree.
- `BotSocialManager` owns bot-to-bot party offers and optional invites to real players.
  `BotFamiliarityManager` records time together. Social membership never bypasses owner gates.

## Unobserved-map LOD

LOD is implemented, despite the removed preimplementation design saying otherwise.

- Observation state is maintained by the map/player tracking path and exposed on the live web APIs.
- After hysteresis, unobserved bots may use motion-plan physics, timed cross-map travel, abstract grind
  events, and a coarser tick cadence.
- The four `SIMPLIFY_UNOBSERVED_BOTS_*` flags independently gate physics, travel, grind, and cadence.
  `LOD1_TICK_MS` and `LOD_DOWNGRADE_HYSTERESIS_MS` control cadence and transition delay.
- Abstract grinding is allowed only through `BotManager.abstractGrindEligible(...)`; the cadence gate
  shares that eligibility so a bot cannot receive slow ticks while still relying on real combat.
- Returning observers promote the map back to full simulation. LOD must preserve real progression
  outcomes and may simplify presentation/physics only while nobody can see them.

## Operations

Use [`web-endpoints.md`](web-endpoints.md) for live roster, bot debug, performance, market, settings,
and RTS controls. Source defaults live in `BotManager.Config`; population decision math lives in
`BotScheduleMath` and IO in `BotScheduler`.
