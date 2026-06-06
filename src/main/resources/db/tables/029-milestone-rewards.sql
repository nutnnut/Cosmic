-- Level milestone rewards: tracks which milestone tiers a character has already
-- claimed through the @lumen command. A milestone is "available" to a character
-- when their level is >= the milestone level and no row exists here for the
-- (characterId, milestone) pair, so eligibility is derived from current level and
-- characters who passed a milestone before this feature shipped can still claim.

CREATE TABLE milestone_claims
(
    id          INT       NOT NULL AUTO_INCREMENT,
    characterId INT       NOT NULL,
    milestone   INT       NOT NULL,
    claimedAt   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_char_milestone (characterId, milestone),
    KEY idx_char (characterId),
    CONSTRAINT fk_milestone_char
        FOREIGN KEY (characterId) REFERENCES characters (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
