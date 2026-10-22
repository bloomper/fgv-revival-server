CREATE TABLE IF NOT EXISTS guide_suggestion
(
    id                       BIGINT AUTO_INCREMENT NOT NULL,
    revival_id               BIGINT                NOT NULL,
    author_id                BIGINT                NOT NULL,
    version_id               BIGINT                NOT NULL,
    kind                     VARCHAR(16)           NOT NULL,
    item_key                 CHAR(36)              NULL,
    chapter_key              CHAR(36)              NOT NULL,
    section_key              CHAR(36)              NULL,
    proposed_type            VARCHAR(16)           NULL,
    proposed_text_sv         TEXT                  NULL,
    proposed_text_en         TEXT                  NULL,
    reason                   VARCHAR(2000)         NULL,
    status                   VARCHAR(16)           NOT NULL DEFAULT 'OPEN',
    decided_by               BIGINT                NULL,
    decided_at               DATETIME(6)           NULL,
    decision_reason          VARCHAR(2000)         NULL,
    accepted_into_version_id BIGINT                NULL,
    reopened_at              DATETIME(6)           NULL,
    created_by               VARCHAR(255)          NOT NULL,
    created_at               DATETIME(6)           NOT NULL,
    last_modified_by         VARCHAR(255)          NULL,
    last_modified_at         DATETIME(6)           NULL,
    version                  BIGINT                NULL,
    CONSTRAINT PK_GUIDE_SUGGESTION PRIMARY KEY (id),
    CONSTRAINT FK_GUIDE_SUGGESTION_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_GUIDE_SUGGESTION_ON_AUTHOR FOREIGN KEY (author_id) REFERENCES person (id),
    CONSTRAINT FK_GUIDE_SUGGESTION_ON_VERSION FOREIGN KEY (version_id) REFERENCES guide_version (id),
    CONSTRAINT FK_GUIDE_SUGGESTION_ON_DECIDED_BY FOREIGN KEY (decided_by) REFERENCES person (id),
    CONSTRAINT FK_GUIDE_SUGGESTION_ON_ACCEPTED_INTO FOREIGN KEY (accepted_into_version_id) REFERENCES guide_version (id) ON DELETE SET NULL,
    CONSTRAINT CK_GUIDE_SUGGESTION_KIND CHECK (kind IN ('CHANGE', 'REMOVE', 'ADD')),
    CONSTRAINT CK_GUIDE_SUGGESTION_STATUS CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_GUIDE_SUGGESTION_ON_STATUS ON guide_suggestion (status);
CREATE INDEX IX_GUIDE_SUGGESTION_ON_REVIVAL_STATUS ON guide_suggestion (revival_id, status);
CREATE INDEX IX_GUIDE_SUGGESTION_ON_ACCEPTED_INTO ON guide_suggestion (accepted_into_version_id);

CREATE TABLE IF NOT EXISTS guide_suggestion_audit
(
    id                       BIGINT                NOT NULL,
    revival_id               BIGINT                NULL,
    author_id                BIGINT                NULL,
    version_id               BIGINT                NULL,
    kind                     VARCHAR(16)           NULL,
    item_key                 CHAR(36)              NULL,
    chapter_key              CHAR(36)              NULL,
    section_key              CHAR(36)              NULL,
    proposed_type            VARCHAR(16)           NULL,
    proposed_text_sv         TEXT                  NULL,
    proposed_text_en         TEXT                  NULL,
    reason                   VARCHAR(2000)         NULL,
    status                   VARCHAR(16)           NULL,
    decided_by               BIGINT                NULL,
    decided_at               DATETIME(6)           NULL,
    decision_reason          VARCHAR(2000)         NULL,
    accepted_into_version_id BIGINT                NULL,
    reopened_at              DATETIME(6)           NULL,
    version                  BIGINT                NULL,
    rev                      BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                   BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                  TINYINT               DEFAULT NULL,
    revend_tstmp             DATETIME(6)           DEFAULT NULL,

    KEY                      IX_rev                (rev),
    KEY                      IX_revend             (revend),

    CONSTRAINT PK_GUIDE_SUGGESTION_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
