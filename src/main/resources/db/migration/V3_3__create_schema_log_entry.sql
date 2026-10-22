CREATE TABLE IF NOT EXISTS log_entry
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    number                 INT                   NOT NULL,
    entry_date             DATE                  NOT NULL,
    type                   VARCHAR(16)           NOT NULL,
    text                   TEXT                  NOT NULL,
    status                 VARCHAR(16)           NOT NULL DEFAULT 'OPEN',
    owner_person_id        BIGINT                NULL,
    owner_name             VARCHAR(100)          NULL,
    deadline               DATE                  NULL,
    comment                TEXT                  NULL,
    meeting_id             BIGINT                NULL,
    guide_item_key         CHAR(36)              NULL,
    checklist_item_id      BIGINT                NULL,
    answers_entry_id       BIGINT                NULL,
    origin                 VARCHAR(16)           NOT NULL,
    guide_question_key     CHAR(36) AS (IF(origin = 'GUIDE', guide_item_key, NULL)) STORED,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_LOG_ENTRY PRIMARY KEY (id),
    CONSTRAINT FK_LOG_ENTRY_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_LOG_ENTRY_ON_OWNER FOREIGN KEY (owner_person_id) REFERENCES person (id),
    CONSTRAINT FK_LOG_ENTRY_ON_MEETING FOREIGN KEY (meeting_id) REFERENCES meeting (id),
    CONSTRAINT FK_LOG_ENTRY_ON_CHECKLIST_ITEM FOREIGN KEY (checklist_item_id) REFERENCES checklist_item (id),
    CONSTRAINT FK_LOG_ENTRY_ON_ANSWERS FOREIGN KEY (answers_entry_id) REFERENCES log_entry (id),
    CONSTRAINT UC_LOG_ENTRY_NUMBER UNIQUE (revival_id, number),
    CONSTRAINT UC_LOG_ENTRY_ANSWERS UNIQUE (answers_entry_id),
    -- The guide sync cannot create a question twice
    CONSTRAINT UC_LOG_ENTRY_GUIDE_QUESTION UNIQUE (revival_id, guide_question_key),
    CONSTRAINT CK_LOG_ENTRY_TYPE CHECK (type IN ('DECISION', 'TODO', 'QUESTION')),
    CONSTRAINT CK_LOG_ENTRY_STATUS CHECK (status IN ('OPEN', 'DONE', 'WAITING', 'CANCELLED')),
    CONSTRAINT CK_LOG_ENTRY_ORIGIN CHECK (origin IN ('MANUAL', 'IMPORT', 'GUIDE')),
    CONSTRAINT CK_LOG_ENTRY_OWNER CHECK (owner_person_id IS NULL OR owner_name IS NULL)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_LOG_ENTRY_ON_REVIVAL_STATUS ON log_entry (revival_id, status);
CREATE INDEX IX_LOG_ENTRY_ON_REVIVAL_OWNER ON log_entry (revival_id, owner_person_id);
CREATE INDEX IX_LOG_ENTRY_ON_REVIVAL_DEADLINE ON log_entry (revival_id, deadline);

CREATE TABLE IF NOT EXISTS log_entry_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    number                 INT                   NULL,
    entry_date             DATE                  NULL,
    type                   VARCHAR(16)           NULL,
    text                   TEXT                  NULL,
    status                 VARCHAR(16)           NULL,
    owner_person_id        BIGINT                NULL,
    owner_name             VARCHAR(100)          NULL,
    deadline               DATE                  NULL,
    comment                TEXT                  NULL,
    meeting_id             BIGINT                NULL,
    guide_item_key         CHAR(36)              NULL,
    checklist_item_id      BIGINT                NULL,
    answers_entry_id       BIGINT                NULL,
    origin                 VARCHAR(16)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_LOG_ENTRY_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS log_entry_tag
(
    entry_id               BIGINT                NOT NULL,
    tag                    VARCHAR(50)           NOT NULL,
    CONSTRAINT PK_LOG_ENTRY_TAG PRIMARY KEY (entry_id, tag),
    CONSTRAINT FK_LOG_ENTRY_TAG_ON_ENTRY FOREIGN KEY (entry_id) REFERENCES log_entry (id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS log_entry_tag_audit
(
    entry_id               BIGINT                NOT NULL,
    tag                    VARCHAR(50)           NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_LOG_ENTRY_TAG_AUDIT PRIMARY KEY (rev, revtype, entry_id, tag)
) DEFAULT CHARSET = utf8mb4;

-- A technical counter, not audited (research R3)
CREATE TABLE IF NOT EXISTS log_entry_counter
(
    revival_id             BIGINT                NOT NULL,
    last_number            INT                   NOT NULL DEFAULT 0,
    CONSTRAINT PK_LOG_ENTRY_COUNTER PRIMARY KEY (revival_id),
    CONSTRAINT FK_LOG_ENTRY_COUNTER_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id)
);

INSERT INTO log_entry_counter (revival_id, last_number)
SELECT id, 0
FROM revival;

CREATE TABLE IF NOT EXISTS log_import
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    file_name              VARCHAR(255)          NOT NULL,
    layout                 VARCHAR(16)           NOT NULL,
    row_count              INT                   NOT NULL,
    warnings               JSON                  NOT NULL,
    imported_by            BIGINT                NOT NULL,
    imported_at            DATETIME(6)           NOT NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_LOG_IMPORT PRIMARY KEY (id),
    CONSTRAINT FK_LOG_IMPORT_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_LOG_IMPORT_ON_IMPORTED_BY FOREIGN KEY (imported_by) REFERENCES person (id),
    CONSTRAINT UC_LOG_IMPORT_REVIVAL UNIQUE (revival_id),
    CONSTRAINT CK_LOG_IMPORT_LAYOUT CHECK (layout IN ('ALI_BABA', 'NOBEL'))
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS log_import_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    file_name              VARCHAR(255)          NULL,
    layout                 VARCHAR(16)           NULL,
    row_count              INT                   NULL,
    warnings               JSON                  NULL,
    imported_by            BIGINT                NULL,
    imported_at            DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_LOG_IMPORT_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- What was done to a row in a meeting; append-only and itself a record, so not audited (research R8)
CREATE TABLE IF NOT EXISTS meeting_entry_change
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    meeting_id             BIGINT                NOT NULL,
    entry_id               BIGINT                NOT NULL,
    kind                   VARCHAR(16)           NOT NULL,
    changed_by             BIGINT                NOT NULL,
    changed_at             DATETIME(6)           NOT NULL,
    CONSTRAINT PK_MEETING_ENTRY_CHANGE PRIMARY KEY (id),
    CONSTRAINT FK_MEETING_ENTRY_CHANGE_ON_MEETING FOREIGN KEY (meeting_id) REFERENCES meeting (id),
    CONSTRAINT FK_MEETING_ENTRY_CHANGE_ON_ENTRY FOREIGN KEY (entry_id) REFERENCES log_entry (id),
    CONSTRAINT FK_MEETING_ENTRY_CHANGE_ON_CHANGED_BY FOREIGN KEY (changed_by) REFERENCES person (id),
    CONSTRAINT CK_MEETING_ENTRY_CHANGE_KIND CHECK (kind IN ('CREATED', 'ASSIGNED', 'CLOSED', 'ANSWERED', 'UPDATED'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_MEETING_ENTRY_CHANGE_ON_MEETING ON meeting_entry_change (meeting_id);
