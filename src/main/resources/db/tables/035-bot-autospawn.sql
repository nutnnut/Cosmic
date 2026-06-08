-- Per-owner toggle for the @spawnbots command: when enabled, the owner's registered
-- bots are spawned automatically on login.
CREATE TABLE IF NOT EXISTS bot_autospawn
(
    owner_char_id INT     NOT NULL,
    enabled       TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_char_id),
    FOREIGN KEY (owner_char_id) REFERENCES characters (id) ON DELETE CASCADE
);
