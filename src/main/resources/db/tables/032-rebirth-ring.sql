-- Per-character rebirth ring state. Each rebirth engraves one stack of the class
-- the character is LEAVING, tracked here as separate columns so we can apply
-- diminishing-returns stat scaling per branch (see service.RebirthRingService).
--
-- The ring's visible equip stats are written into `inventoryequipment` by
-- RebirthRingService each time we engrave; this table is the source of truth
-- for "how many of each class have I been", independent of where the equip
-- itself lives (inventory, equipped, storage). If the equip is ever lost,
-- these stacks let an NPC restore it.
CREATE TABLE rebirth_ring
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
