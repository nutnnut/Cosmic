# Bot roadmap

This is the only repository-level list of unfinished bot work. An item belongs here only when the
owner wants it preserved as future work. KB notes and source comments may describe limitations, but
they are not tasks unless linked from this file.

## Pending

| ID | State | Objective | Source | Done when |
|---|---|---|---|---|
| `party-autopilot-stage4` | Decision needed | Replace scattered party/autopilot activity flags with a tested state model. | [`party-autopilot-redesign.md`](party-autopilot-redesign.md#deferred-work), `TODO(bot-party-autopilot-stage4)` in `BotEntry` | Legal transitions and reset/persistence rules are characterized; the replacement passes focused mode-interaction tests. |
| `player-led-party` | Decision needed | Define when autonomous party bots follow a real player versus continue or resume their own objective. | [`party-autopilot-redesign.md`](party-autopilot-redesign.md#deferred-work), `TODO(bot-player-led-party)` in `BotAutopilotManager` | Authority and transition behavior are approved, implemented through the existing decision seam, and covered by party-policy tests. |
| `stack-shout-trade` | Deferred | Extend structured shout trading from equips to quantity-bearing consumable stacks. | [`economy.md`](economy.md#deliberate-gaps), `TODO(bot-stack-shout-trade)` in `BotShoutTradeManager` | Quantity-aware WTP/WTS and atomic partial-stack transfer are implemented and tested without duplicating market valuation. |

## Maintenance rule

1. Add an item only after the owner explicitly asks to retain it as future work.
2. Use a stable short ID, one objective, one source anchor, and a verifiable completion condition.
3. Keep investigation details in the relevant KB/architecture document; do not turn this into a
   handoff, diary, implementation plan, or commit ledger.
4. Source markers use `TODO(bot-<id>)` and must correspond to a row here. Incidental limitations use
   ordinary comments without `TODO`.
5. When work completes, update the durable architecture/KB if needed, then delete the roadmap row.
   Git history is the completion record.
6. Review this file whenever a bot task changes scope. If an item is no longer intended, delete it.
