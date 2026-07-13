# Bot documentation

This directory contains current project documentation for `server.bots`. Code and data remain the
source of truth; documents explain architecture, operational invariants, and verified failure modes.

## Start here

- [`ROADMAP.md`](ROADMAP.md): the only canonical list of unfinished bot work.
- [`README_SERVER_BOTS.md`](../../README_SERVER_BOTS.md): operator and player-facing setup/features.
- [`web-endpoints.md`](web-endpoints.md): the HTTP route and JSON contract. The `createContext(...)`
  list in `BotWorldGraphWebServer.start()` is the route source of truth.
- [`economy.md`](economy.md): current market/economy architecture and deliberate gaps.
- [`living-server-design.md`](living-server-design.md): population, sessions, social behavior, and
  unobserved-map LOD as currently implemented.
- [`party-autopilot-redesign.md`](party-autopilot-redesign.md): current party-plan architecture and the
  two deferred migrations still marked in code.
- [`physics-client-audit.md`](physics-client-audit.md): client-verified movement facts and unresolved
  physics questions.
- [`kb/README.md`](kb/README.md): durable incident and subsystem notes.
- [`patch-notes/README.md`](patch-notes/README.md): release history.

## Editing rules

1. Update current-state docs, not a dated handoff or completion checklist.
2. Put reusable debugging knowledge in the relevant KB entry; delete session state, commit ledgers,
   live PIDs, and “resume here” instructions once the work lands.
3. Do not copy source constants, route lists, or long method inventories when a stable source link is
   enough. In particular, never duplicate the navigation `GRAPH_VERSION` commentary.
4. Keep proposals only while they represent an active decision. When implemented, replace the design
   with a short as-built description or remove it.
5. Use ASCII in bot chat text. Repository Markdown may use UTF-8, but do not introduce mojibake.
6. Do not treat a KB limitation or a source comment as backlog. Promote owner-approved future work to
   `ROADMAP.md`; remove its row when completed.

## Specialized guidance

- `.claude/skills/bot-combat/SKILL.md`: combat packets, hitboxes, attack routes, and ammo gates.
- `.claude/skills/bot-nav/SKILL.md`: navigation/physics layers, graph invariants, and live debugging.
- `.claude/skills/bot-perf/SKILL.md`: bot performance CSV interpretation.
- `.claude/skills/wz-data/SKILL.md`: WZ XML layout and safe data access.
