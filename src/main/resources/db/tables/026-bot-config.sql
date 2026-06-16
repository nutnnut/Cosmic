CREATE TABLE bot_config
(
    bot_char_id INT       NOT NULL,
    config      TEXT      NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (bot_char_id),
    CONSTRAINT fk_bot_config_bot FOREIGN KEY (bot_char_id) REFERENCES characters (id) ON DELETE CASCADE
);
