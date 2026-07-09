# Char-save throughput & the InnoDB deadlock saga (resolved 2026-07-09)

**TL;DR:** char saves used to serialize (SAVE_GATE=1) at 2-4 saves/sec because parallel saves
deadlock-stormed in June 2026. The root cause was never the parallelism — it was **missing DB
indexes turning per-char/per-account DELETEs into full-table lock sweeps**, plus one-row-per-round-trip
inserts making each save ~300 round trips. Fixed: saves are batched, parallel (SAVE_GATE=6), and a
~870-bot shutdown saves in ~14s (~60 saves/sec) with zero deadlocks.

## The June-2026 history (why saves were serialized)

Commit chain: `06a3fe563` (parallel shutdown saves) → deadlock storm → `2693dc7eb` (retry) →
`6014449cc` (SAVE_GATE=4) → still stormed → `aef5866fe` (revert to serial) → `8ef56c1c4`/`c6051a15f`
(SAVE_GATE=1). The revert concluded parallel saves "can't be made deadlock-free". That conclusion was
wrong — the diagnosis stopped at the victim statement instead of the lock cycle.

## Actual root cause (confirmed live via SHOW ENGINE INNODB STATUS)

The June reports said "deadlocked on the inventoryitems DELETE". The real cycle, captured live
2026-07-09 during a 1000-bot spawn wave:

```
DELETE inventoryitems, inventoryequipment FROM ... WHERE type = 3 AND accountid = 978   (477 row locks)
DELETE inventoryitems, inventoryequipment FROM ... WHERE type = 3 AND accountid = 926   (883 row locks)
-> mutual wait on inventoryitems PRIMARY records
```

`type=3` is CASH_EXPLORER — **account-scoped** item saves (`CashShop.save()`, run inside every char
save). `inventoryitems` had an index on `characterid` but **none on `accountid`**, so every cash-item
DELETE full-scanned ~200k rows, lock-testing every concurrent save's uncommitted inserts. Two of
those = deadlock. Five more save-path tables (`savedlocations`, `trocklocations`, `buddies`,
`skillmacros`, `area_info`) had no characterid index either — same disease, worse in June because the
auxiliary-signature skip (`feda7cf31`) didn't exist yet, so every save swept them all.

Same disease class as the load-path fix in `028-perf-indexes.sql`. Fix: `032-save-indexes.sql`.
Empirically: deadlocks during the spawn wave stopped cold the moment the accountid index was created
(60 deadlocks before, 0 after, same workload).

## Why single saves were slow (2-4/sec)

`ItemFactory.saveItemsCommon` did one `executeUpdate` per item plus a freshly prepared statement per
equip (~150 items/bot ≈ 200+ round trips), and quest/aux/monsterbook writes added more. Standalone
JDBC harness numbers (150 items + 50 equips per save, localhost):

| config | serial | 8 threads | 16 threads |
|---|---|---|---|
| per-row, unindexed (June shape) | 68ms/save, 11/s | 56/s (RU) / 15/s + deadlocks (RR) | 45/s |
| batched + indexed | 26ms/save, 20/s | 104/s | **130/s, 0 deadlocks in 1600 saves** |

## The fix set (all 2026-07-09)

1. **`032-save-indexes.sql`** — `inventoryitems(accountid)` (the big one) + characterid indexes on
   the five aux tables. Applied to the live DB same day.
2. **`ItemFactory.saveItemsCommon` batched** — one multi-row INSERT for items, equips' generated ids
   recovered by a select-back on `(inventorytype, position)` (NOT `getGeneratedKeys()`: a rewritten
   batch's keys may interleave under `innodb_autoinc_lock_mode=2`). Slot uniqueness holds for char
   inventories; raw lists (STORAGE/DUEY) fall back to the legacy per-row path on collision.
3. **`rewriteBatchedStatements=true`** in DatabaseConnection. Audited: no caller reads
   getGeneratedKeys off a batch. **Gotcha (cost a debugging round):** batches the driver can't
   VALUES-rewrite (`INSERT..ON DUPLICATE KEY UPDATE` — monsterbook; `REPLACE` — skills) are instead
   semicolon-JOINED into one multi-statement string — but only for batches over a size threshold,
   so small probes pass while real saves fail. If the statement SQL itself ends with `;` (MonsterBook's
   text block did), the join produces an empty `';;'` statement and the whole save fails with
   `SQLSyntaxErrorException ... near ';INSERT INTO monsterbook'`. Fix = strip trailing semicolons
   from any batched SQL (swept: MonsterBook was the only one). `allowMultiQueries` is NOT needed —
   the driver self-enables multi-statements per batch via COM_SET_OPTION (proved by 40-row probe).
4. **SAVE_GATE 1 → 6** (`Character.java`) — the existing 6-attempt jittered retry stays as the
   safety net; the gate stays as the emergency knob (drop to 1 to re-serialize).
5. **`PlayerStorage.disconnectAll` parallel again** — restored `06a3fe563`'s bounded 8-worker pool
   (it was correct all along; the DB was the problem).

## Measured outcome (live)

- Shutdown with ~867 online bots: **14.3s wall, 0 deadlocks, 0 two-minute overruns** (~61 saves/s).
  Previously ~2-4 saves/s serial → would have been 4-7 minutes.
- Equip attachment integrity after batched saves: 0 missing equip rows, 0 equip rows on non-equip
  items (verified by SQL join checks).
- During heavy save concurrency deadlocks may still occasionally appear as
  `WARN Deadlock saving chr X (attempt n), retrying` — that is the design working; only
  `ERROR ... after 6 attempts` means a lost save.

## Failure modes & knobs

- `Deadlock saving chr ... after 6 attempts` storm returns → drop SAVE_GATE to 1 (Character.java),
  investigate with `SHOW ENGINE INNODB STATUS` (LATEST DETECTED DEADLOCK names the cycle — read the
  HOLDS/WAITING sections, not just the victim statement).
- `Equip select-back matched N of M rows` / `found unexpected row` → slot-key collision or a
  concurrent writer to the same owner's rows mid-save; the txn rolls back (no partial write).
- New save-path table with a per-char/per-account DELETE? Index that column or it full-scans.

## Related / still open

- `inventoryequipment` orphan rows (~3.2k) drift upward slowly (+~20/hour under load) — pre-existing
  leak, also grew before this change; some path deletes `inventoryitems` without the equipment join.
  Not chased yet.
- Quest saves (`queststatus` per-row inserts with getGeneratedKeys per quest) still per-row — usually
  signature-skipped, revisit only if shutdown profiling shows them hot.
