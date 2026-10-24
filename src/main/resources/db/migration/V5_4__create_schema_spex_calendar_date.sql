-- The current Chalmersspex's show dates (product spec 5.1), kept by the super admin so that the cross-revival
-- timeline can flag clashes. Not revival data: deleted for real.
CREATE TABLE IF NOT EXISTS spex_calendar_date
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    date                   DATE                  NOT NULL,
    label                  VARCHAR(100)          NOT NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_SPEX_CALENDAR_DATE PRIMARY KEY (id)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_SPEX_CALENDAR_DATE_ON_DATE ON spex_calendar_date (date);

CREATE TABLE IF NOT EXISTS spex_calendar_date_audit
(
    id                     BIGINT                NOT NULL,
    date                   DATE                  NULL,
    label                  VARCHAR(100)          NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_SPEX_CALENDAR_DATE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
