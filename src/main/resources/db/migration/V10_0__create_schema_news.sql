-- News for everyone with access, written by the super admin and shown on the start page. Not revival data: deleted
-- for real. Whether a piece is published follows from its dates, so nothing is stored for it.
CREATE TABLE IF NOT EXISTS news
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    subject                VARCHAR(255)          NOT NULL,
    text                   TEXT                  NOT NULL,
    visible_from           DATE                  NULL,
    visible_to             DATE                  NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_NEWS PRIMARY KEY (id)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_NEWS_ON_VISIBLE_FROM ON news (visible_from);

CREATE TABLE IF NOT EXISTS news_audit
(
    id                     BIGINT                NOT NULL,
    subject                VARCHAR(255)          NULL,
    text                   TEXT                  NULL,
    visible_from           DATE                  NULL,
    visible_to             DATE                  NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_NEWS_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
