-- Registry of SERVER-GENERATED, schedulable bots (the "living server" population).
-- A row here is the single source of truth for "this character is a disposable, server-owned bot
-- that the population scheduler may auto log in/out, retire, and replace." Rows are created ONLY by
-- the bot-generation path (@spawnbot generate / the population auto-generator) — a real player's
-- character (@registerbot, @botme) NEVER gets a row, so the scheduler can never touch it.
CREATE TABLE managed_bot
(
    bot_char_id    INT       NOT NULL,
    group_id       INT       NULL,                 -- persistent crew id (bots that log in together as a party); NULL = soloist
    enabled        TINYINT   NOT NULL DEFAULT 1,    -- 0 = administratively paused (won't be scheduled)
    retired_at     TIMESTAMP NULL DEFAULT NULL,     -- set when the bot's career ends ("left"); kept as a record, no longer scheduled
    last_online_at TIMESTAMP NULL DEFAULT NULL,     -- last time the scheduler brought it online
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bot_char_id),
    CONSTRAINT fk_managed_bot_bot FOREIGN KEY (bot_char_id) REFERENCES characters (id) ON DELETE CASCADE
);
