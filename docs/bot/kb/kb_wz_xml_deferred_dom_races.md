# WZ XML Data races: shared DOM reads corrupt lookups (2026-08-13)

Symptom: bot statuses report raw ids — "im at map 100000100" — for maps whose names clearly
exist in `String.wz/Map.img.xml`, across dozens of ordinary maps, worst under load (cold boot,
many bots resolving concurrently). Reproduced deterministically by
`MapFactoryNameLookupTest.concurrentColdLookupsAllResolve` (16 threads x all ~5k map ids over
the real WZ): hundreds of blank lookups before the fix, zero after.

Root cause chain:
- `MapFactory` holds ONE `Data` tree for `String.wz Map.img` (`nameData`) for the whole server
  lifetime and reads it from many threads (`loadPlaceName` ← every bot `statusReport`).
- `XMLDomMapleData` wraps shared `org.w3c.dom.Node`s. Its old `synchronized` methods guarded
  NOTHING: every `getChildByPath`/`getChildren` call returns a NEW wrapper, so no two callers
  ever shared the monitor.
- Xerces DOM reads are not thread-safe even on a fully-materialized document:
  `ParentNode.nodeListItem` keeps a per-node NodeList cache (last index/position) that is
  MUTATED ON READ. Two threads iterating the same node's children corrupt each other's
  traversal → `getChildByPath` transiently returns null for entries that exist. (Deferred node
  expansion — lazy inflation on first read — is a second, permanent-corruption variant of the
  same class; the upstream source carried the comment "seems susceptible to give nulls on
  strenuous read scenarios".)
- Why the web map view still showed correct names: `BotWorldGraphWebServer.mapNames()` parses
  its OWN fresh document per call — single-threaded use, no shared tree.

Fix (both in `XMLDomMapleData`):
- Every DOM-touching method now synchronizes on the one object all wrappers over a file share —
  the owning `Document` (`node.getOwnerDocument()`), so traversals are mutually exclusive.
- Documents parse with `defer-node-expansion=false` (fully materialized up front), removing the
  lazy-inflation variant and shrinking the locked work.

Audit hint: this made ALL long-lived shared `Data` trees safe (item/skill providers cache
derived objects, but their raw-tree reads had the same exposure). Flaky "null from WZ that
clearly exists" reports predating this fix can be closed as this class. Keep per-tick WZ reads
out of hot paths regardless — the lock serializes readers of the same file
(kb_bot_population_cpu_hotspots).
