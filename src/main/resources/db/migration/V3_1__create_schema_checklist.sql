CREATE TABLE IF NOT EXISTS checklist
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    kind                   VARCHAR(8)            NOT NULL,
    phase                  CHAR(1)               NULL,
    name                   VARCHAR(255)          NULL,
    event_date             DATE                  NULL,
    template_id            BIGINT                NULL,
    cancelled_at           DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_CHECKLIST PRIMARY KEY (id),
    CONSTRAINT FK_CHECKLIST_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_CHECKLIST_ON_TEMPLATE FOREIGN KEY (template_id) REFERENCES checklist_template (id),
    -- One phase checklist per phase; event checklists have no phase, and nulls do not collide
    CONSTRAINT UC_CHECKLIST_REVIVAL_PHASE UNIQUE (revival_id, phase),
    CONSTRAINT CK_CHECKLIST_KIND CHECK (kind IN ('PHASE', 'EVENT')),
    CONSTRAINT CK_CHECKLIST_SHAPE CHECK ((kind = 'PHASE' AND phase IS NOT NULL AND name IS NULL AND event_date IS NULL AND cancelled_at IS NULL)
        OR (kind = 'EVENT' AND phase IS NULL AND name IS NOT NULL))
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS checklist_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    kind                   VARCHAR(8)            NULL,
    phase                  CHAR(1)               NULL,
    name                   VARCHAR(255)          NULL,
    event_date             DATE                  NULL,
    template_id            BIGINT                NULL,
    cancelled_at           DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_CHECKLIST_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS checklist_item
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    checklist_id           BIGINT                NOT NULL,
    revival_id             BIGINT                NOT NULL,
    position               INT                   NOT NULL,
    required               TINYINT               NOT NULL,
    guide_item_key         CHAR(36)              NULL,
    text                   VARCHAR(1000)         NULL,
    state                  VARCHAR(16)           NOT NULL DEFAULT 'OPEN',
    state_changed_by       BIGINT                NULL,
    state_changed_at       DATETIME(6)           NULL,
    reason                 VARCHAR(1000)         NULL,
    reason_key             VARCHAR(64)           NULL,
    reason_arg             VARCHAR(32)           NULL,
    owner_person_id        BIGINT                NULL,
    owner_name             VARCHAR(100)          NULL,
    deadline               DATE                  NULL,
    deadline_manual        TINYINT               NOT NULL DEFAULT 0,
    comment                VARCHAR(2000)         NULL,
    template_item_id       BIGINT                NULL,
    default_capability     VARCHAR(16)           NULL,
    required_key           CHAR(36) AS (IF(required, guide_item_key, NULL)) STORED,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_CHECKLIST_ITEM PRIMARY KEY (id),
    CONSTRAINT FK_CHECKLIST_ITEM_ON_CHECKLIST FOREIGN KEY (checklist_id) REFERENCES checklist (id),
    CONSTRAINT FK_CHECKLIST_ITEM_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_CHECKLIST_ITEM_ON_STATE_CHANGED_BY FOREIGN KEY (state_changed_by) REFERENCES person (id),
    CONSTRAINT FK_CHECKLIST_ITEM_ON_OWNER FOREIGN KEY (owner_person_id) REFERENCES person (id),
    CONSTRAINT FK_CHECKLIST_ITEM_ON_TEMPLATE_ITEM FOREIGN KEY (template_item_id) REFERENCES checklist_template_item (id),
    -- A requirement has exactly one required item per revival, wherever it moves (SC-004)
    CONSTRAINT UC_CHECKLIST_ITEM_REQUIRED UNIQUE (revival_id, required_key),
    CONSTRAINT CK_CHECKLIST_ITEM_STATE CHECK (state IN ('OPEN', 'DONE', 'NOT_APPLICABLE')),
    CONSTRAINT CK_CHECKLIST_ITEM_OWNER CHECK (owner_person_id IS NULL OR owner_name IS NULL),
    CONSTRAINT CK_CHECKLIST_ITEM_TEXT CHECK ((required = 1 AND guide_item_key IS NOT NULL AND text IS NULL) OR (required = 0 AND text IS NOT NULL)),
    CONSTRAINT CK_CHECKLIST_ITEM_CAPABILITY CHECK (default_capability IN ('FINANCE', 'CATERING', 'STAFFING', 'COMMS'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_CHECKLIST_ITEM_ON_CHECKLIST ON checklist_item (checklist_id, position);
CREATE INDEX IX_CHECKLIST_ITEM_ON_REVIVAL_STATE ON checklist_item (revival_id, state);
CREATE INDEX IX_CHECKLIST_ITEM_ON_REVIVAL_OWNER ON checklist_item (revival_id, owner_person_id);
CREATE INDEX IX_CHECKLIST_ITEM_ON_REVIVAL_DEADLINE ON checklist_item (revival_id, deadline);

CREATE TABLE IF NOT EXISTS checklist_item_audit
(
    id                     BIGINT                NOT NULL,
    checklist_id           BIGINT                NULL,
    revival_id             BIGINT                NULL,
    position               INT                   NULL,
    required               TINYINT               NULL,
    guide_item_key         CHAR(36)              NULL,
    text                   VARCHAR(1000)         NULL,
    state                  VARCHAR(16)           NULL,
    state_changed_by       BIGINT                NULL,
    state_changed_at       DATETIME(6)           NULL,
    reason                 VARCHAR(1000)         NULL,
    reason_key             VARCHAR(64)           NULL,
    reason_arg             VARCHAR(32)           NULL,
    owner_person_id        BIGINT                NULL,
    owner_name             VARCHAR(100)          NULL,
    deadline               DATE                  NULL,
    deadline_manual        TINYINT               NULL,
    comment                VARCHAR(2000)         NULL,
    template_item_id       BIGINT                NULL,
    default_capability     VARCHAR(16)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_CHECKLIST_ITEM_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
