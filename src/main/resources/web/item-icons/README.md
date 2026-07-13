# Market item icons

PNG sprites for the `/market` web view, named by item id (`1302000.png`, `2000000.png`, ...).

**These files are generated and gitignored** — they are extracted from a real v83 client's binary
WZ, whose canvas `basedata` (the PNG bytes) the repo's XML `wz/` dump has stripped. Regenerate with:

```
powershell -ExecutionPolicy Bypass -File tools\extract_item_icons.ps1
```

which reads `wz-client\Character.wz` (equip icons) + `wz-client\Item.wz` (use/setup/etc/cash icons)
via HaRepacker's MapleLib and writes `<itemId>.png` here for every id the v83 server knows. Because
they ride the jar classpath, run `mvn package` + restart after extracting.

**Endpoint contract:** `GET /api/market/icon?item=<id>` (`BotWorldGraphWebServer.serveMarketIcon`)
serves `web/item-icons/<id>.png` from the classpath, or 404 when absent — the market page then falls
back to a placeholder tile. So a missing icon (Hair/Face/pet-appearance ids with no inventory icon,
or an un-extracted install) degrades gracefully; no page or code change is needed to add icons.
