CREATE TABLE orestorages
(
    storageid INT UNSIGNED NOT NULL AUTO_INCREMENT,
    accountid INT          NOT NULL DEFAULT '0',
    world     INT          NOT NULL,
    slots     INT          NOT NULL DEFAULT '48',
    meso      INT          NOT NULL DEFAULT '0',
    PRIMARY KEY (storageid)
);
