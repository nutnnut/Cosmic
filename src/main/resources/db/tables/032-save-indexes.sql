-- Per-character save indexes. Character.saveCharToDB() delete+reinserts these
-- tables filtered by characterid, but the base Cosmic schema only indexed their
-- PKs, so every save full-scanned them - lock-testing every row in the table.
-- Under concurrent saves those full scans convoy on each other's uncommitted
-- inserts (and are the prime suspect for the June-2026 InnoDB deadlock storms
-- that forced saves to serialize, see SAVE_GATE in Character.java). Indexed,
-- each save only touches its own rows. Companion to 028-perf-indexes.sql,
-- which fixed the same disease on the load path.
-- The big one: account-scoped item saves (CashShop.save -> ItemFactory CASH_*
-- factories, run inside EVERY char save) delete by accountid, which had no index
-- - a full scan of all ~200k inventoryitems rows, lock-testing every concurrent
-- save's uncommitted inserts. Two such saves deadlock each other exactly as the
-- June-2026 reports showed ("deadlocked on the inventoryitems DELETE"); confirmed
-- live 2026-07-09 via SHOW ENGINE INNODB STATUS (two accountid DELETEs, hundreds
-- of row locks each, mutual wait on inventoryitems PRIMARY).
CREATE INDEX ACCOUNTID ON inventoryitems (accountid);
CREATE INDEX characterid ON savedlocations (characterid);
CREATE INDEX characterid ON trocklocations (characterid);
CREATE INDEX characterid ON buddies (characterid);
CREATE INDEX characterid ON skillmacros (characterid);
CREATE INDEX charid ON area_info (charid);
