CREATE TABLE IF NOT EXISTS checklist_template
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    kind                   VARCHAR(8)            NOT NULL,
    phase                  CHAR(1)               NULL,
    name_sv                VARCHAR(255)          NULL,
    name_en                VARCHAR(255)          NULL,
    retired_at             DATETIME(6)           NULL,
    phase_marker           CHAR(1) AS (IF(retired_at IS NULL, phase, NULL)) STORED,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_CHECKLIST_TEMPLATE PRIMARY KEY (id),
    -- One active template per phase
    CONSTRAINT UC_CHECKLIST_TEMPLATE_PHASE UNIQUE (phase_marker),
    CONSTRAINT CK_CHECKLIST_TEMPLATE_KIND CHECK (kind IN ('PHASE', 'EVENT')),
    CONSTRAINT CK_CHECKLIST_TEMPLATE_PHASE CHECK ((kind = 'PHASE' AND phase IS NOT NULL) OR (kind = 'EVENT' AND phase IS NULL)),
    CONSTRAINT CK_CHECKLIST_TEMPLATE_NAME CHECK (kind = 'PHASE' OR name_sv IS NOT NULL)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS checklist_template_audit
(
    id                     BIGINT                NOT NULL,
    kind                   VARCHAR(8)            NULL,
    phase                  CHAR(1)               NULL,
    name_sv                VARCHAR(255)          NULL,
    name_en                VARCHAR(255)          NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_CHECKLIST_TEMPLATE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS checklist_template_item
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    template_id            BIGINT                NOT NULL,
    position               INT                   NOT NULL,
    text_sv                VARCHAR(1000)         NOT NULL,
    text_en                VARCHAR(1000)         NULL,
    guide_item_key         CHAR(36)              NULL,
    default_capability     VARCHAR(16)           NULL,
    deadline_kind          VARCHAR(24)           NULL,
    deadline_amount        INT                   NULL,
    deadline_weekday       VARCHAR(9)            NULL,
    retired_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_CHECKLIST_TEMPLATE_ITEM PRIMARY KEY (id),
    CONSTRAINT FK_CHECKLIST_TEMPLATE_ITEM_ON_TEMPLATE FOREIGN KEY (template_id) REFERENCES checklist_template (id),
    CONSTRAINT CK_CHECKLIST_TEMPLATE_ITEM_CAPABILITY CHECK (default_capability IN ('FINANCE', 'CATERING', 'STAFFING', 'COMMS')),
    CONSTRAINT CK_CHECKLIST_TEMPLATE_ITEM_DEADLINE CHECK (deadline_kind IN ('DAYS', 'WORKING_DAYS', 'WEEKDAY_WEEK_BEFORE'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_CHECKLIST_TEMPLATE_ITEM_ON_TEMPLATE ON checklist_template_item (template_id, position);

CREATE TABLE IF NOT EXISTS checklist_template_item_audit
(
    id                     BIGINT                NOT NULL,
    template_id            BIGINT                NULL,
    position               INT                   NULL,
    text_sv                VARCHAR(1000)         NULL,
    text_en                VARCHAR(1000)         NULL,
    guide_item_key         CHAR(36)              NULL,
    default_capability     VARCHAR(16)           NULL,
    deadline_kind          VARCHAR(24)           NULL,
    deadline_amount        INT                   NULL,
    deadline_weekday       VARCHAR(9)            NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_CHECKLIST_TEMPLATE_ITEM_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
