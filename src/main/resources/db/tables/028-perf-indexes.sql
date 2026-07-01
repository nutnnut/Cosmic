-- Per-character load indexes. Character.loadCharFromDB() filters each of these
-- tables by characterid, but the base Cosmic schema only indexed their PKs, so
-- every load was a full table scan. Harmless for a handful of human players;
-- crippling at boot when N managed bots are loaded serially and queststatus has
-- grown to hundreds of thousands of rows from bot questing (full scan per bot ->
-- mysqld pegged for minutes). See server.bots cold-start perf audit.
CREATE INDEX characterid ON queststatus (characterid);
CREATE INDEX characterid ON questprogress (characterid);
CREATE INDEX characterid ON keymap (characterid);
