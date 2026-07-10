# Market item icons (sprite seam)

The `/market` page requests each item's sprite from `/api/market/icon?item=<itemId>`
(`BotWorldGraphWebServer.serveMarketIcon`). That handler serves `web/item-icons/<itemId>.png` from the
classpath when the file exists, and returns 404 otherwise — in which case the page falls back to a
coloured placeholder tile (WPN / ARM / USE / ...).

To light up real sprites, drop client-extracted PNGs here named by item id, e.g. `01332031.png`? No —
name them by the exact numeric id with no leading zeros: `1332031.png`, `2040002.png`. Rebuild so the
resource is copied onto the classpath; no page or code change is needed.

The repo's WZ dump has icon `canvas` nodes stripped of their `basedata` (base64 PNG), so icons cannot
be pulled from `wz/` today. Once real client WZ with `basedata` is loaded, an alternative to dropping
files here is to repoint `serveMarketIcon` at a WZ canvas reader (`info/icon` for equips) — the page
seam stays the same.
