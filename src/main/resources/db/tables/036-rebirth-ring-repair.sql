-- Repair migration for the rebirth_ring table.
--
-- On some databases changeSet 32 (032-rebirth-ring.sql) is recorded as applied in
-- DATABASECHANGELOG while the table itself is missing (e.g. after restoring/importing a
-- SQL dump that carried the changelog rows but not this table). Liquibase will not re-run
-- changeSet 32, so player login throws "Table 'cosmic.rebirth_ring' doesn't exist" during
-- the rebirth-ring stat rehydration. This idempotent recreate fixes that and is a no-op on
-- databases where the table already exists. Schema mirrors 032-rebirth-ring.sql.
CREATE TABLE IF NOT EXISTS rebirth_ring
(
    characterid     INT NOT NULL,
    warrior_stacks  INT NOT NULL DEFAULT 0,
    magician_stacks INT NOT NULL DEFAULT 0,
    bowman_stacks   INT NOT NULL DEFAULT 0,
    thief_stacks    INT NOT NULL DEFAULT 0,
    pirate_stacks   INT NOT NULL DEFAULT 0,
    PRIMARY KEY (characterid),
    CONSTRAINT fk_rebirth_ring_char
        FOREIGN KEY (characterid) REFERENCES characters (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
