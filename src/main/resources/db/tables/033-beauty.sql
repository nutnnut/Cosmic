CREATE TABLE IF NOT EXISTS beautystorage
(
    id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
    characterid INT          NOT NULL,
    slot        TINYINT      NOT NULL,
    slottype    TINYINT      NOT NULL,
    gender      TINYINT      NOT NULL DEFAULT 0,
    skin        INT          NOT NULL DEFAULT 0,
    hair        INT          NOT NULL DEFAULT 0,
    face        INT          NOT NULL DEFAULT 0,
    hat         INT          NOT NULL DEFAULT 0,
    top         INT          NOT NULL DEFAULT 0,
    bottom      INT          NOT NULL DEFAULT 0,
    shoes       INT          NOT NULL DEFAULT 0,
    weapon      INT          NOT NULL DEFAULT 0,
    cashweapon  INT          NOT NULL DEFAULT 0,
    savedat     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_char_slot_type (characterid, slot, slottype),
    KEY characterid (characterid),
    FOREIGN KEY (characterid) REFERENCES characters (id) ON DELETE CASCADE
);
