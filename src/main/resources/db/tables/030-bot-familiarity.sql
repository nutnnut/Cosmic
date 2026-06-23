CREATE TABLE bot_player_familiarity
(
    bot_char_id      INT       NOT NULL,
    player_char_id   INT       NOT NULL,
    party_count      INT       NOT NULL DEFAULT 0,
    total_ms         BIGINT    NOT NULL DEFAULT 0,
    last_together_at TIMESTAMP NULL,
    PRIMARY KEY (bot_char_id, player_char_id),
    CONSTRAINT fk_familiarity_bot FOREIGN KEY (bot_char_id) REFERENCES characters (id) ON DELETE CASCADE,
    CONSTRAINT fk_familiarity_player FOREIGN KEY (player_char_id) REFERENCES characters (id) ON DELETE CASCADE
);
