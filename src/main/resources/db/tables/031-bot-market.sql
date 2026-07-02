-- Living bot economy (docs/bot/living-economy-design.md sec 12).
--
-- bot_market_event: append-only tape of observable market activity. Observability + the
-- consensus sweep's input window + faucet/sink accounting. NOT an oracle: bot decision code
-- never reads it (sec 10.6) — bots know only their own book. No FKs: history outlives characters.
CREATE TABLE bot_market_event
(
    id         BIGINT    NOT NULL AUTO_INCREMENT,
    at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    kind       TINYINT   NOT NULL,               -- BotMarketLedger.EventKind: 0=TRADE 1=STALL_SALE 2=LIST
                                                 -- 3=DELIST 4=EXPIRE 5=SHOUT 6=NPC_SELL 7=NPC_BUY 8=SINK 9=FAUCET
    item_id    INT       NOT NULL,               -- 0 for pure meso flows (SINK/FAUCET)
    quality    SMALLINT  NOT NULL DEFAULT 0,     -- equip quality band (sec 3); 0 for non-equips
    qty        INT       NOT NULL DEFAULT 1,
    unit_price BIGINT    NOT NULL,               -- meso per unit (flow amount for SINK/FAUCET)
    seller_id  INT       NULL,                   -- char id (bot or player), NULL when n/a
    buyer_id   INT       NULL,
    map_id     INT       NULL,
    PRIMARY KEY (id),
    KEY k_item (item_id, quality, at),
    KEY k_at (at)
);

-- bot_market_belief: layer-2 private books, one row per (bot, priceKey) the bot has an opinion
-- on. priceKey = item_id * 256 + quality band (BotMarketMath.priceKey). LRU-capped per bot at
-- load time; estimate in meso, confidence in accumulated evidence weight.
CREATE TABLE bot_market_belief
(
    bot_char_id INT       NOT NULL,
    price_key   BIGINT    NOT NULL,
    estimate    BIGINT    NOT NULL,
    confidence  FLOAT     NOT NULL,
    obs         INT       NOT NULL DEFAULT 0,
    last_seen   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bot_char_id, price_key),
    CONSTRAINT fk_bot_market_belief_bot FOREIGN KEY (bot_char_id) REFERENCES characters (id) ON DELETE CASCADE
);

-- bot_market_consensus: layer-1 shared statistic, one row per active priceKey. Written only by
-- the BotMarketConsensus sweep; read by bots exclusively through the noisy perception sample.
-- volume = decayed event weight backing the number (its damping mass).
CREATE TABLE bot_market_consensus
(
    price_key  BIGINT    NOT NULL,
    consensus  BIGINT    NOT NULL,
    volume     FLOAT     NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (price_key)
);
