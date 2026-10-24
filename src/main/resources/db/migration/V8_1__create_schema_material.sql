-- A revival's materials and inventory (product spec 5.9, M8). A material is a link to where a file is kept, never the
-- file: https only, recognised as one of the link services by its host unless one is chosen. Its visibility is the
-- steering group, every participant, or participants with one of its positions (Spexregister task ids). Materials and
-- inventory items are removed, never deleted (constitution IV).
CREATE TABLE IF NOT EXISTS material
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    title                  VARCHAR(200)          NOT NULL,
    url                    VARCHAR(2000)         NOT NULL,
    service_id             VARCHAR(36)           NULL,
    category               VARCHAR(16)           NOT NULL,
    visibility             VARCHAR(16)           NOT NULL,
    comment                VARCHAR(1000)         NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_MATERIAL PRIMARY KEY (id),
    CONSTRAINT FK_MATERIAL_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_MATERIAL_ON_SERVICE FOREIGN KEY (service_id) REFERENCES link_service (id),
    CONSTRAINT CK_MATERIAL_URL CHECK (url LIKE 'https://%'),
    CONSTRAINT CK_MATERIAL_CATEGORY CHECK (category IN ('SCRIPT', 'SHEET_MUSIC', 'RECORDING', 'VIDEO', 'PROGRAMME', 'POSTER', 'PHOTO', 'OTHER')),
    CONSTRAINT CK_MATERIAL_VISIBILITY CHECK (visibility IN ('STEERING_GROUP', 'PARTICIPANTS', 'POSITIONS'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_MATERIAL_ON_REVIVAL ON material (revival_id);

CREATE TABLE IF NOT EXISTS material_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    title                  VARCHAR(200)          NULL,
    url                    VARCHAR(2000)         NULL,
    service_id             VARCHAR(36)           NULL,
    category               VARCHAR(16)           NULL,
    visibility             VARCHAR(16)           NULL,
    comment                VARCHAR(1000)         NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_MATERIAL_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- The positions a POSITIONS material is for: Spexregister task ids, as a participant's position
CREATE TABLE IF NOT EXISTS material_position
(
    material_id            BIGINT                NOT NULL,
    task_id                VARCHAR(36)           NOT NULL,
    CONSTRAINT PK_MATERIAL_POSITION PRIMARY KEY (material_id, task_id),
    CONSTRAINT FK_MATERIAL_POSITION_ON_MATERIAL FOREIGN KEY (material_id) REFERENCES material (id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS material_position_audit
(
    material_id            BIGINT                NOT NULL,
    task_id                VARCHAR(36)           NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_MATERIAL_POSITION_AUDIT PRIMARY KEY (rev, revtype, material_id, task_id)
) DEFAULT CHARSET = utf8mb4;

-- Things the revival has, needs or borrows. The owner is a member of the steering group or a free-text name, as for
-- decision log rows; anonymization clears the names (M7).
CREATE TABLE IF NOT EXISTS inventory_item
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    name                   VARCHAR(200)          NOT NULL,
    kind                   VARCHAR(16)           NOT NULL,
    quantity               INT                   NOT NULL,
    status                 VARCHAR(16)           NOT NULL,
    location               VARCHAR(16)           NULL,
    location_note          VARCHAR(255)          NULL,
    owner_person_id        BIGINT                NULL,
    owner_name             VARCHAR(100)          NULL,
    comment                VARCHAR(1000)         NULL,
    material_id            BIGINT                NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_INVENTORY_ITEM PRIMARY KEY (id),
    CONSTRAINT FK_INVENTORY_ITEM_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_INVENTORY_ITEM_ON_OWNER FOREIGN KEY (owner_person_id) REFERENCES person (id),
    CONSTRAINT FK_INVENTORY_ITEM_ON_MATERIAL FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT CK_INVENTORY_ITEM_OWNER CHECK (owner_person_id IS NULL OR owner_name IS NULL),
    CONSTRAINT CK_INVENTORY_ITEM_QUANTITY CHECK (quantity BETWEEN 1 AND 100000),
    CONSTRAINT CK_INVENTORY_ITEM_KIND CHECK (kind IN ('SCRIPT', 'SHEET_MUSIC', 'PROPS', 'COSTUME', 'WIG', 'BACKDROP', 'TECHNICAL', 'OTHER')),
    CONSTRAINT CK_INVENTORY_ITEM_STATUS CHECK (status IN ('HAVE', 'MISSING', 'BORROW', 'BUY', 'BUILD')),
    CONSTRAINT CK_INVENTORY_ITEM_LOCATION CHECK (location IS NULL
        OR location IN ('KEMIFORRADET', 'V_FORRADET', 'RUNAN', 'PRIVATE', 'OTHER'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_INVENTORY_ITEM_ON_REVIVAL ON inventory_item (revival_id);

CREATE TABLE IF NOT EXISTS inventory_item_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    name                   VARCHAR(200)          NULL,
    kind                   VARCHAR(16)           NULL,
    quantity               INT                   NULL,
    status                 VARCHAR(16)           NULL,
    location               VARCHAR(16)           NULL,
    location_note          VARCHAR(255)          NULL,
    owner_person_id        BIGINT                NULL,
    owner_name             VARCHAR(100)          NULL,
    comment                VARCHAR(1000)         NULL,
    material_id            BIGINT                NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_INVENTORY_ITEM_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
