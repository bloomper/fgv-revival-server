-- Event types: the guide's example timeline as a list the super admin keeps (product spec 5.3, M4-FR-1). A type's
-- offsets are days from the premiere, both or neither; a type without them is never proposed. Retired, never deleted.
-- The texts are at most 4000 characters, checked by the application; as TEXT they keep the row within MySQL's limit.
CREATE TABLE IF NOT EXISTS event_type
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    name_sv                VARCHAR(100)          NOT NULL,
    name_en                VARCHAR(100)          NULL,
    description_sv         TEXT                  NULL,
    description_en         TEXT                  NULL,
    rooms_sv               TEXT                  NULL,
    rooms_en               TEXT                  NULL,
    catering_sv            TEXT                  NULL,
    catering_en            TEXT                  NULL,
    offset_min_days        INT                   NULL,
    offset_max_days        INT                   NULL,
    days                   INT                   NOT NULL,
    revival_pays           TINYINT               NOT NULL,
    checklist_template_id  BIGINT                NULL,
    position               INT                   NOT NULL,
    retired_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_EVENT_TYPE PRIMARY KEY (id),
    CONSTRAINT UC_EVENT_TYPE_NAME_SV UNIQUE (name_sv),
    CONSTRAINT FK_EVENT_TYPE_ON_CHECKLIST_TEMPLATE FOREIGN KEY (checklist_template_id) REFERENCES checklist_template (id),
    CONSTRAINT CK_EVENT_TYPE_OFFSETS CHECK ((offset_min_days IS NULL AND offset_max_days IS NULL)
        OR (offset_min_days IS NOT NULL AND offset_max_days IS NOT NULL AND offset_min_days <= offset_max_days
            AND offset_min_days >= -730 AND offset_max_days <= 730)),
    CONSTRAINT CK_EVENT_TYPE_DAYS CHECK (days BETWEEN 1 AND 14)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS event_type_audit
(
    id                     BIGINT                NOT NULL,
    name_sv                VARCHAR(100)          NULL,
    name_en                VARCHAR(100)          NULL,
    description_sv         TEXT                  NULL,
    description_en         TEXT                  NULL,
    rooms_sv               TEXT                  NULL,
    rooms_en               TEXT                  NULL,
    catering_sv            TEXT                  NULL,
    catering_en            TEXT                  NULL,
    offset_min_days        INT                   NULL,
    offset_max_days        INT                   NULL,
    days                   INT                   NULL,
    revival_pays           TINYINT               NULL,
    checklist_template_id  BIGINT                NULL,
    position               INT                   NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_EVENT_TYPE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
