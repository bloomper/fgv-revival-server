CREATE TABLE IF NOT EXISTS guide_version
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    status                 VARCHAR(16)           NOT NULL,
    label                  VARCHAR(32)           NULL,
    note                   VARCHAR(2000)         NULL,
    intro_sv               TEXT                  NOT NULL,
    intro_en               TEXT                  NULL,
    based_on_id            BIGINT                NULL,
    published_at           DATETIME(6)           NULL,
    published_by           BIGINT                NULL,
    draft_marker           TINYINT AS (IF(status = 'DRAFT', 1, NULL)) STORED,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_GUIDE_VERSION PRIMARY KEY (id),
    CONSTRAINT FK_GUIDE_VERSION_ON_BASED_ON FOREIGN KEY (based_on_id) REFERENCES guide_version (id) ON DELETE SET NULL,
    CONSTRAINT FK_GUIDE_VERSION_ON_PUBLISHED_BY FOREIGN KEY (published_by) REFERENCES person (id),
    CONSTRAINT UC_GUIDE_VERSION_LABEL UNIQUE (label),
    -- At most one draft
    CONSTRAINT UC_GUIDE_VERSION_DRAFT UNIQUE (draft_marker),
    CONSTRAINT CK_GUIDE_VERSION_STATUS CHECK (status IN ('DRAFT', 'PUBLISHED'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_GUIDE_VERSION_ON_STATUS_PUBLISHED_AT ON guide_version (status, published_at);

CREATE TABLE IF NOT EXISTS guide_chapter
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    version_id             BIGINT                NOT NULL,
    phase                  CHAR(1)               NOT NULL,
    title_sv               VARCHAR(255)          NOT NULL,
    title_en               VARCHAR(255)          NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_GUIDE_CHAPTER PRIMARY KEY (id),
    CONSTRAINT FK_GUIDE_CHAPTER_ON_VERSION FOREIGN KEY (version_id) REFERENCES guide_version (id),
    CONSTRAINT UC_GUIDE_CHAPTER_VERSION_PHASE UNIQUE (version_id, phase)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS guide_section
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    version_id             BIGINT                NOT NULL,
    chapter_id             BIGINT                NOT NULL,
    `key`                  CHAR(36)              NOT NULL,
    position               INT                   NOT NULL,
    title_sv               VARCHAR(255)          NOT NULL,
    title_en               VARCHAR(255)          NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_GUIDE_SECTION PRIMARY KEY (id),
    CONSTRAINT FK_GUIDE_SECTION_ON_VERSION FOREIGN KEY (version_id) REFERENCES guide_version (id),
    CONSTRAINT FK_GUIDE_SECTION_ON_CHAPTER FOREIGN KEY (chapter_id) REFERENCES guide_chapter (id),
    CONSTRAINT UC_GUIDE_SECTION_VERSION_KEY UNIQUE (version_id, `key`)
) DEFAULT CHARSET = utf8mb4;

-- Not unique: positions are renumbered in several statements within one transaction, and MySQL checks unique keys
-- per row. The service keeps them dense and unique.
CREATE INDEX IX_GUIDE_SECTION_ON_CHAPTER_POSITION ON guide_section (chapter_id, position);

CREATE TABLE IF NOT EXISTS guide_item
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    version_id             BIGINT                NOT NULL,
    chapter_id             BIGINT                NOT NULL,
    section_id             BIGINT                NULL,
    `key`                  CHAR(36)              NOT NULL,
    position               INT                   NOT NULL,
    type                   VARCHAR(16)           NOT NULL,
    text_sv                TEXT                  NOT NULL,
    text_en                TEXT                  NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_GUIDE_ITEM PRIMARY KEY (id),
    CONSTRAINT FK_GUIDE_ITEM_ON_VERSION FOREIGN KEY (version_id) REFERENCES guide_version (id),
    CONSTRAINT FK_GUIDE_ITEM_ON_CHAPTER FOREIGN KEY (chapter_id) REFERENCES guide_chapter (id),
    CONSTRAINT FK_GUIDE_ITEM_ON_SECTION FOREIGN KEY (section_id) REFERENCES guide_section (id),
    CONSTRAINT UC_GUIDE_ITEM_VERSION_KEY UNIQUE (version_id, `key`),
    CONSTRAINT CK_GUIDE_ITEM_TYPE CHECK (type IN ('TIP', 'REQUIREMENT', 'QUESTION', 'TEXT'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_GUIDE_ITEM_ON_CHAPTER_SECTION_POSITION ON guide_item (chapter_id, section_id, position);

CREATE TABLE IF NOT EXISTS guide_import_report
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    version_id             BIGINT                NOT NULL,
    file_name              VARCHAR(255)          NOT NULL,
    counts                 JSON                  NOT NULL,
    warnings               JSON                  NOT NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    CONSTRAINT PK_GUIDE_IMPORT_REPORT PRIMARY KEY (id),
    CONSTRAINT FK_GUIDE_IMPORT_REPORT_ON_VERSION FOREIGN KEY (version_id) REFERENCES guide_version (id) ON DELETE CASCADE,
    CONSTRAINT UC_GUIDE_IMPORT_REPORT_VERSION UNIQUE (version_id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS guide_version_audit
(
    id                     BIGINT                NOT NULL,
    status                 VARCHAR(16)           NULL,
    label                  VARCHAR(32)           NULL,
    note                   VARCHAR(2000)         NULL,
    intro_sv               TEXT                  NULL,
    intro_en               TEXT                  NULL,
    based_on_id            BIGINT                NULL,
    published_at           DATETIME(6)           NULL,
    published_by           BIGINT                NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_GUIDE_VERSION_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS guide_chapter_audit
(
    id                     BIGINT                NOT NULL,
    version_id             BIGINT                NULL,
    phase                  CHAR(1)               NULL,
    title_sv               VARCHAR(255)          NULL,
    title_en               VARCHAR(255)          NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_GUIDE_CHAPTER_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS guide_section_audit
(
    id                     BIGINT                NOT NULL,
    version_id             BIGINT                NULL,
    chapter_id             BIGINT                NULL,
    `key`                  CHAR(36)              NULL,
    position               INT                   NULL,
    title_sv               VARCHAR(255)          NULL,
    title_en               VARCHAR(255)          NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_GUIDE_SECTION_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS guide_item_audit
(
    id                     BIGINT                NOT NULL,
    version_id             BIGINT                NULL,
    chapter_id             BIGINT                NULL,
    section_id             BIGINT                NULL,
    `key`                  CHAR(36)              NULL,
    position               INT                   NULL,
    type                   VARCHAR(16)           NULL,
    text_sv                TEXT                  NULL,
    text_en                TEXT                  NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_GUIDE_ITEM_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
