# bot_market_event tape time: read epochs via UNIX_TIMESTAMP, not getTimestamp

`bot_market_event.at` is a MySQL `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`. Reading it with
`rs.getTimestamp("at").getTime()` yields an epoch skewed by the offset between the **JVM default
timezone** and the **MySQL session timezone** (`@@session.time_zone`, here `SYSTEM`). On this box the
JVM runs UTC while the DB SYSTEM tz is UTC+7, so the JVM read came out ~7h in the *future*: the web
market tape (`/api/market/items`, `/api/market/history`) showed events with negative "ago"
(`-23776s ago`) because the browser's real `Date.now()` was hours *behind* the server-emitted
`lastAt`/point `t`. The JDBC URL sets no `connectionTimeZone`/`serverTimezone`, and fixing it there
would touch the shared `DatabaseConnection` used by all non-bot code (out of scope).

**Fix (SSOT for this tape):** `BotMarketLedger` reads the instant server-side with
`UNIX_TIMESTAMP(at)` (× 1000 for ms) and filters windows with `WHERE UNIX_TIMESTAMP(at) >= ?`
(param = `sinceMs / 1000`). `UNIX_TIMESTAMP` resolves the stored value to a true Unix epoch entirely
in the DB, tz-agnostic and directly `Date.now()`-comparable. This covers `recentEvents` (consensus
sweep window), `itemHistory` (chart series), and `tradedItems` (`UNIX_TIMESTAMP(MAX(at))`). Never
reintroduce `getTimestamp().getTime()` on this column.

Any *new* bot TIMESTAMP column compared against wall-clock epoch (e.g. `bot_market_belief.last_seen`,
`bot_market_consensus.updated_at`) has the same latent skew — read it the same way.

Provenance: web market "events in the future" report, dev branch (post b00401fda).
