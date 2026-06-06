-- Tracks how many times a character has used @rebirth. Each rebirth resets the
-- character to Level 10 (keeping two chosen skills) and grants (rebirths * 750) AP,
-- so the running count must persist across sessions.
CREATE TABLE character_rebirths
(
    characterid INT NOT NULL,
    rebirths    INT NOT NULL DEFAULT 0,
    PRIMARY KEY (characterid),
    CONSTRAINT fk_rebirths_char
        FOREIGN KEY (characterid) REFERENCES characters (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
