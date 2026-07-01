---
name: kb_bot_coldstart_queststatus_fullscan
description: 5-min server boot w/ mysqld pegged = queststatus full table scan per bot char-load (no characterid index); NOT the WZ cache warm
metadata: 
  node_type: memory
  type: project
  originSessionId: c0691803-6821-4051-8527-88662348de92
---

Cold-start "5 min boot, mysqld.exe top CPU, bots frozen" is a DB problem, NOT the WZ cache warm (that's JVM CPU, ~16s, already latch-gated — see [[kb_bot_cold_decide_gc_storm]]).

Root cause: `Character.loadCharFromDB()` runs `SELECT * FROM queststatus WHERE characterid=?` per character, but base Cosmic schema indexed queststatus only on its PK `queststatusid` — **no index on characterid**. EXPLAIN = `type:ALL, key:null, rows:253915`. queststatus had grown to ~254k rows (~1670/char from bot questing). Managed bots load serially at boot (BotScheduler.reconcile -> BotManager.spawnManagedBot -> loadOfflineBot -> loadCharFromDB), so 15-21 bots = 15-21 full scans of 254k rows = mysqld pegged for minutes.

Fix: `028-perf-indexes.sql` (Liquibase changeSet 28) adds `characterid` index to queststatus, questprogress, keymap (all per-character-filtered, scale with bot count). Liquibase auto-applies on next boot. Live one-shot: `CREATE INDEX characterid ON queststatus(characterid);` (+ questprogress, keymap).

Deferred (YAGNI): parallelizing serial bot char-loads (unnecessary once scan is gone), pruning queststatus row growth, pet-ignore N+1 (18 rows, trivial).

**How to apply:** if boot is slow + mysqld hot, check `information_schema.STATISTICS` for a missing characterid index on any per-character table before assuming it's the bot AI/WZ warm.
