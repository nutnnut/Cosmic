CREATE TABLE IF NOT EXISTS beautyunlock
(
    characterid INT     NOT NULL,
    slots       TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (characterid),
    FOREIGN KEY (characterid) REFERENCES characters (id) ON DELETE CASCADE
);
