-- Areas of responsibility (capabilities) and guide item types become lists the super admin keeps, instead of fixed
-- enums. The four of each that existed keep their names as ids, so every row that names one stays valid. A guide item
-- type has a kind, fixed when it is created, that decides what the application does with its items.
CREATE TABLE IF NOT EXISTS capability
(
    id                     VARCHAR(36)           NOT NULL,
    name_sv                VARCHAR(100)          NOT NULL,
    name_en                VARCHAR(100)          NULL,
    position               INT                   NOT NULL,
    retired_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_CAPABILITY PRIMARY KEY (id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS capability_audit
(
    id                     VARCHAR(36)           NOT NULL,
    name_sv                VARCHAR(100)          NULL,
    name_en                VARCHAR(100)          NULL,
    position               INT                   NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_CAPABILITY_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

INSERT INTO capability (id, name_sv, name_en, position, created_by, created_at, version)
VALUES ('FINANCE', 'Ekonomi', 'Finance', 0, 'system', NOW(6), 0),
       ('CATERING', 'Mat', 'Catering', 1, 'system', NOW(6), 0),
       ('STAFFING', 'Bemanning', 'Staffing', 2, 'system', NOW(6), 0),
       ('COMMS', 'Kommunikation', 'Communications', 3, 'system', NOW(6), 0);

CREATE TABLE IF NOT EXISTS guide_item_type
(
    id                     VARCHAR(36)           NOT NULL,
    kind                   VARCHAR(16)           NOT NULL,
    name_sv                VARCHAR(100)          NOT NULL,
    name_en                VARCHAR(100)          NULL,
    position               INT                   NOT NULL,
    retired_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_GUIDE_ITEM_TYPE PRIMARY KEY (id),
    CONSTRAINT CK_GUIDE_ITEM_TYPE_KIND CHECK (kind IN ('TIP', 'REQUIREMENT', 'QUESTION', 'TEXT'))
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS guide_item_type_audit
(
    id                     VARCHAR(36)           NOT NULL,
    kind                   VARCHAR(16)           NULL,
    name_sv                VARCHAR(100)          NULL,
    name_en                VARCHAR(100)          NULL,
    position               INT                   NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_GUIDE_ITEM_TYPE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

INSERT INTO guide_item_type (id, kind, name_sv, name_en, position, created_by, created_at, version)
VALUES ('TIP', 'TIP', 'Praktiska tips', 'Tip', 0, 'system', NOW(6), 0),
       ('REQUIREMENT', 'REQUIREMENT', 'Krav', 'Requirement', 1, 'system', NOW(6), 0),
       ('QUESTION', 'QUESTION', 'Att ta reda på', 'To find out', 2, 'system', NOW(6), 0),
       ('TEXT', 'TEXT', 'Text', 'Text', 3, 'system', NOW(6), 0);

ALTER TABLE revival_member_capability
    MODIFY COLUMN capability VARCHAR(36) NOT NULL,
    ADD CONSTRAINT FK_REVIVAL_MEMBER_CAPABILITY_ON_CAPABILITY FOREIGN KEY (capability) REFERENCES capability (id);

ALTER TABLE revival_member_capability_audit
    MODIFY COLUMN capability VARCHAR(36) NOT NULL;

ALTER TABLE checklist_template_item
    DROP CHECK CK_CHECKLIST_TEMPLATE_ITEM_CAPABILITY;

ALTER TABLE checklist_template_item
    MODIFY COLUMN default_capability VARCHAR(36) NULL,
    ADD CONSTRAINT FK_CHECKLIST_TEMPLATE_ITEM_ON_CAPABILITY FOREIGN KEY (default_capability) REFERENCES capability (id);

ALTER TABLE checklist_template_item_audit
    MODIFY COLUMN default_capability VARCHAR(36) NULL;

ALTER TABLE checklist_item
    DROP CHECK CK_CHECKLIST_ITEM_CAPABILITY;

ALTER TABLE checklist_item
    MODIFY COLUMN default_capability VARCHAR(36) NULL,
    ADD CONSTRAINT FK_CHECKLIST_ITEM_ON_CAPABILITY FOREIGN KEY (default_capability) REFERENCES capability (id);

ALTER TABLE checklist_item_audit
    MODIFY COLUMN default_capability VARCHAR(36) NULL;

ALTER TABLE guide_item
    DROP CHECK CK_GUIDE_ITEM_TYPE;

ALTER TABLE guide_item
    MODIFY COLUMN type VARCHAR(36) NOT NULL,
    ADD CONSTRAINT FK_GUIDE_ITEM_ON_TYPE FOREIGN KEY (type) REFERENCES guide_item_type (id);

ALTER TABLE guide_item_audit
    MODIFY COLUMN type VARCHAR(36) NULL;

ALTER TABLE guide_suggestion
    MODIFY COLUMN proposed_type VARCHAR(36) NULL,
    ADD CONSTRAINT FK_GUIDE_SUGGESTION_ON_PROPOSED_TYPE FOREIGN KEY (proposed_type) REFERENCES guide_item_type (id);

ALTER TABLE guide_suggestion_audit
    MODIFY COLUMN proposed_type VARCHAR(36) NULL;
