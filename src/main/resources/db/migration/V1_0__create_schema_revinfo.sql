CREATE TABLE IF NOT EXISTS revinfo
(
    id                     BIGINT                NOT NULL AUTO_INCREMENT,
    modified_at            BIGINT                NOT NULL,
    modified_by            VARCHAR(255)          DEFAULT NULL,
    source                 VARCHAR(32)           DEFAULT NULL,
    operation              VARCHAR(255)          DEFAULT NULL,
    comment                VARCHAR(1000)         DEFAULT NULL,
    CONSTRAINT PK_REV_INFO PRIMARY KEY (id)
);

CREATE INDEX IX_revinfo_modified_at ON revinfo (modified_at);
CREATE INDEX IX_revinfo_modified_by ON revinfo (modified_by);

CREATE TABLE IF NOT EXISTS revchanges
(
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    entityname             VARCHAR(255)          DEFAULT NULL
);
