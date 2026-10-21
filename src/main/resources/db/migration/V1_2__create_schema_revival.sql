CREATE TABLE IF NOT EXISTS revival
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    spex_id                BIGINT                NOT NULL,
    year                   SMALLINT              NOT NULL,
    working_title          VARCHAR(255)          NOT NULL,
    premiere_date          DATE                  NULL,
    status                 VARCHAR(16)           NOT NULL DEFAULT 'PROPOSED',
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_REVIVAL PRIMARY KEY (id),
    CONSTRAINT CK_REVIVAL_YEAR CHECK (year BETWEEN 1948 AND 2100)
);

CREATE INDEX IX_REVIVAL_ON_STATUS ON revival (status);
CREATE INDEX IX_REVIVAL_ON_SPEX_YEAR ON revival (spex_id, year);

CREATE TABLE IF NOT EXISTS revival_show_date
(
    revival_id             BIGINT                NOT NULL,
    show_date              DATE                  NOT NULL,
    CONSTRAINT PK_REVIVAL_SHOW_DATE PRIMARY KEY (revival_id, show_date),
    CONSTRAINT FK_REVIVAL_SHOW_DATE_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id)
);

CREATE TABLE IF NOT EXISTS revival_venue
(
    revival_id             BIGINT                NOT NULL,
    position               INT                   NOT NULL,
    name                   VARCHAR(255)          NOT NULL,
    CONSTRAINT PK_REVIVAL_VENUE PRIMARY KEY (revival_id, position),
    CONSTRAINT FK_REVIVAL_VENUE_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id)
);

CREATE TABLE IF NOT EXISTS revival_status_change
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    from_status            VARCHAR(16)           NOT NULL,
    to_status              VARCHAR(16)           NOT NULL,
    changed_by             BIGINT                NOT NULL,
    changed_at             DATETIME(6)           NOT NULL,
    CONSTRAINT PK_REVIVAL_STATUS_CHANGE PRIMARY KEY (id),
    CONSTRAINT FK_REVIVAL_STATUS_CHANGE_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_REVIVAL_STATUS_CHANGE_ON_PERSON FOREIGN KEY (changed_by) REFERENCES person (id)
);

CREATE TABLE IF NOT EXISTS revival_audit
(
    id                     BIGINT                NOT NULL,
    spex_id                BIGINT                NULL,
    year                   SMALLINT              NULL,
    working_title          VARCHAR(255)          NULL,
    premiere_date          DATE                  NULL,
    status                 VARCHAR(16)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REVIVAL_AUDIT PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS revival_show_date_audit
(
    revival_id             BIGINT                NOT NULL,
    show_date              DATE                  NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REVIVAL_SHOW_DATE_AUDIT PRIMARY KEY (rev, revtype, revival_id, show_date)
);

CREATE TABLE IF NOT EXISTS revival_venue_audit
(
    revival_id             BIGINT                NOT NULL,
    position               INT                   NOT NULL,
    name                   VARCHAR(255)          NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REVIVAL_VENUE_AUDIT PRIMARY KEY (rev, revtype, revival_id, position, name)
);

CREATE TABLE IF NOT EXISTS revival_status_change_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    from_status            VARCHAR(16)           NULL,
    to_status              VARCHAR(16)           NULL,
    changed_by             BIGINT                NULL,
    changed_at             DATETIME(6)           NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REVIVAL_STATUS_CHANGE_AUDIT PRIMARY KEY (id, rev)
);
