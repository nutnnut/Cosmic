-- Banks the skills a character has carried through @rebirth. Every rebirth keeps the
-- full set here automatically (the player never re-picks them) plus two new skills
-- chosen from their current job, which then get added to this set. Used to keep the
-- "choose two skills" list limited to current-job skills only.
CREATE TABLE character_rebirth_skills
(
    characterid INT NOT NULL,
    skillid     INT NOT NULL,
    PRIMARY KEY (characterid, skillid),
    CONSTRAINT fk_rebirth_skills_char
        FOREIGN KEY (characterid) REFERENCES characters (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
