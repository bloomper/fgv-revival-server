-- The reference library the super admin keeps: venues, ticket services, role descriptions and roles to contact.
-- Retired, never deleted. The texts are at most 4000 characters, checked by the application.
CREATE TABLE IF NOT EXISTS library_entry
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    kind                   VARCHAR(16)           NOT NULL,
    name_sv                VARCHAR(100)          NOT NULL,
    name_en                VARCHAR(100)          NULL,
    description_sv         TEXT                  NULL,
    description_en         TEXT                  NULL,
    pros_sv                TEXT                  NULL,
    pros_en                TEXT                  NULL,
    cons_sv                TEXT                  NULL,
    cons_en                TEXT                  NULL,
    booking_sv             TEXT                  NULL,
    booking_en             TEXT                  NULL,
    prices_sv              TEXT                  NULL,
    prices_en              TEXT                  NULL,
    contact_sv             TEXT                  NULL,
    contact_en             TEXT                  NULL,
    url                    VARCHAR(500)          NULL,
    retired_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_LIBRARY_ENTRY PRIMARY KEY (id),
    CONSTRAINT CK_LIBRARY_ENTRY_KIND CHECK (kind IN ('VENUE', 'TICKET_SERVICE', 'ROLE_DESCRIPTION', 'CONTACT_ROLE'))
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS library_entry_audit
(
    id                     BIGINT                NOT NULL,
    kind                   VARCHAR(16)           NULL,
    name_sv                VARCHAR(100)          NULL,
    name_en                VARCHAR(100)          NULL,
    description_sv         TEXT                  NULL,
    description_en         TEXT                  NULL,
    pros_sv                TEXT                  NULL,
    pros_en                TEXT                  NULL,
    cons_sv                TEXT                  NULL,
    cons_en                TEXT                  NULL,
    booking_sv             TEXT                  NULL,
    booking_en             TEXT                  NULL,
    prices_sv              TEXT                  NULL,
    prices_en              TEXT                  NULL,
    contact_sv             TEXT                  NULL,
    contact_en             TEXT                  NULL,
    url                    VARCHAR(500)          NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_LIBRARY_ENTRY_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- A guide item's links to library entries. Copied with the item into a new draft.
CREATE TABLE IF NOT EXISTS guide_item_library_entry
(
    item_id                BIGINT                NOT NULL,
    library_entry_id       BIGINT                NOT NULL,
    CONSTRAINT PK_GUIDE_ITEM_LIBRARY_ENTRY PRIMARY KEY (item_id, library_entry_id),
    CONSTRAINT FK_GUIDE_ITEM_LIBRARY_ENTRY_ON_ITEM FOREIGN KEY (item_id) REFERENCES guide_item (id),
    CONSTRAINT FK_GUIDE_ITEM_LIBRARY_ENTRY_ON_ENTRY FOREIGN KEY (library_entry_id) REFERENCES library_entry (id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS guide_item_library_entry_audit
(
    item_id                BIGINT                NOT NULL,
    library_entry_id       BIGINT                NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_GUIDE_ITEM_LIBRARY_ENTRY_AUDIT PRIMARY KEY (rev, revtype, item_id, library_entry_id)
) DEFAULT CHARSET = utf8mb4;
