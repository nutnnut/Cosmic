-- Damage Skin feature: adds per-character active skin, owned catalog, and
-- the shop price list. Skin ID 0 is the "default/no skin" and is implicitly
-- owned by every character (no inventory row needed); the client renders
-- the stock digits when active = 0.

-- Per-character active skin id (0 = default).
ALTER TABLE characters
    ADD COLUMN activeDamageSkin INT NOT NULL DEFAULT '0';

-- Shop catalog: skinId -> price in mesos.
CREATE TABLE damageskin_catalog
(
    skinId     INT         NOT NULL,
    priceMesos BIGINT      NOT NULL DEFAULT '10000000',
    PRIMARY KEY (skinId)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Per-character owned skins.
CREATE TABLE damageskin_inventory
(
    id          INT       NOT NULL AUTO_INCREMENT,
    characterId INT       NOT NULL,
    skinId      INT       NOT NULL,
    acquiredAt  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_char_skin (characterId, skinId),
    KEY idx_char (characterId),
    CONSTRAINT fk_damageskin_char
        FOREIGN KEY (characterId) REFERENCES characters (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
