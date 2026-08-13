# WZ XML Data races: deferred DOM corrupts long-lived trees (2026-08-13)

Symptom: bot statuses "occasionally" report raw ids — "im at map 280010000" — for maps whose
names clearly exist in `String.wz/Map.img.xml` (`ossyria/280010000` = "Unknown Dead Mine"),
while other lookups on the same server resolve fine. Once broken, an id stayed broken until
restart.

Root cause chain:
- `MapFactory` holds ONE `Data` tree for `String.wz Map.img` (`nameData`) for the whole server
  lifetime and reads it from many threads (`loadPlaceName` ← every bot `statusReport`).
- `XMLDomMapleData` wraps shared `org.w3c.dom.Node`s; its `synchronized` methods are per-WRAPPER
  (each `getChildByPath`/`getChildren` returns a NEW wrapper), so they serialize nothing.
- Xerces parses into a DEFERRED DOM by default: nodes are inflated lazily ON FIRST READ, which
  mutates shared parser state. Two threads inflating the same region concurrently can corrupt a
  subtree permanently → those ids return null forever, everything else keeps working. (The
  upstream source even carried the comment "seems susceptible to give nulls on strenuous read
  scenarios" on `getChildByPath`.)
- Why the web map view still showed correct names: `BotWorldGraphWebServer.mapNames()` parses
  its OWN fresh document per call — single-threaded use, no shared deferred tree.

Fix: `XMLDomMapleData` now sets `http://apache.org/xml/features/dom/defer-node-expansion=false`
at parse, materializing the whole document up front so later reads are pure, mutation-free DOM
traversals (safe to share). Parse cost moves to load time; files are parsed per `getData` call
anyway.

Audit hint: any OTHER long-lived shared `Data` tree (skill/item caches hold loaded objects, so
they're mostly safe) had the same exposure before this fix; flaky "null from WZ that clearly
exists" reports predating it can be closed as this class.
