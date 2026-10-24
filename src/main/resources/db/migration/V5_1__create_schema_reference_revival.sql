-- Reference figures from earlier revivals (product spec 5.10, section 7, M5-FR-2): kept by the super admin, or made
-- automatically from a revival that is deleted or anonymized. They hold no personal data and can be deleted.
CREATE TABLE IF NOT EXISTS reference_revival
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    label                  VARCHAR(100)          NOT NULL,
    year                   INT                   NOT NULL,
    participants           INT                   NULL,
    comment                VARCHAR(1000)         NULL,
    automatic              TINYINT               NOT NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_REFERENCE_REVIVAL PRIMARY KEY (id),
    CONSTRAINT CK_REFERENCE_REVIVAL_PARTICIPANTS CHECK (participants IS NULL OR participants BETWEEN 0 AND 2000)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS reference_revival_audit
(
    id                     BIGINT                NOT NULL,
    label                  VARCHAR(100)          NULL,
    year                   INT                   NULL,
    participants           INT                   NULL,
    comment                VARCHAR(1000)         NULL,
    automatic              TINYINT               NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REFERENCE_REVIVAL_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS reference_figure
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    reference_revival_id   BIGINT                NOT NULL,
    category_id            VARCHAR(36)           NOT NULL,
    budgeted               DECIMAL(12, 2)        NULL,
    actual                 DECIMAL(12, 2)        NULL,
    CONSTRAINT PK_REFERENCE_FIGURE PRIMARY KEY (id),
    CONSTRAINT UC_REFERENCE_FIGURE_CATEGORY UNIQUE (reference_revival_id, category_id),
    CONSTRAINT FK_REFERENCE_FIGURE_ON_REFERENCE_REVIVAL FOREIGN KEY (reference_revival_id) REFERENCES reference_revival (id),
    CONSTRAINT FK_REFERENCE_FIGURE_ON_CATEGORY FOREIGN KEY (category_id) REFERENCES budget_category (id),
    CONSTRAINT CK_REFERENCE_FIGURE_AMOUNTS CHECK (budgeted IS NOT NULL OR actual IS NOT NULL)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS reference_figure_audit
(
    id                     BIGINT                NOT NULL,
    reference_revival_id   BIGINT                NULL,
    category_id            VARCHAR(36)           NULL,
    budgeted               DECIMAL(12, 2)        NULL,
    actual                 DECIMAL(12, 2)        NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REFERENCE_FIGURE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
