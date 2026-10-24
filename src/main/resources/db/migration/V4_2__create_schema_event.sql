-- A revival's events (M4-FR-3): typed from the timeline or added freely, cancelled rather than deleted. Times are in
-- Swedish time (Europe/Stockholm).
CREATE TABLE IF NOT EXISTS event
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    type_id                BIGINT                NULL,
    title                  VARCHAR(255)          NOT NULL,
    start_date             DATE                  NOT NULL,
    end_date               DATE                  NOT NULL,
    start_time             TIME                  NULL,
    end_time               TIME                  NULL,
    location               VARCHAR(255)          NULL,
    description            VARCHAR(4000)         NULL,
    revival_pays           TINYINT               NOT NULL,
    cancelled_at           DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_EVENT PRIMARY KEY (id),
    CONSTRAINT FK_EVENT_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_EVENT_ON_TYPE FOREIGN KEY (type_id) REFERENCES event_type (id),
    CONSTRAINT CK_EVENT_DATES CHECK (end_date >= start_date),
    CONSTRAINT CK_EVENT_END_TIME CHECK (end_time IS NULL OR start_time IS NOT NULL)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_EVENT_ON_REVIVAL_START ON event (revival_id, start_date);

CREATE TABLE IF NOT EXISTS event_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    type_id                BIGINT                NULL,
    title                  VARCHAR(255)          NULL,
    start_date             DATE                  NULL,
    end_date               DATE                  NULL,
    start_time             TIME                  NULL,
    end_time               TIME                  NULL,
    location               VARCHAR(255)          NULL,
    description            VARCHAR(4000)         NULL,
    revival_pays           TINYINT               NULL,
    cancelled_at           DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_EVENT_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- An event checklist made for an event (M4-FR-4); hand-made event checklists have none
ALTER TABLE checklist
    ADD COLUMN event_id BIGINT NULL AFTER template_id,
    ADD CONSTRAINT UC_CHECKLIST_EVENT UNIQUE (event_id),
    ADD CONSTRAINT FK_CHECKLIST_ON_EVENT FOREIGN KEY (event_id) REFERENCES event (id),
    ADD CONSTRAINT CK_CHECKLIST_EVENT CHECK (event_id IS NULL OR kind = 'EVENT');

ALTER TABLE checklist_audit
    ADD COLUMN event_id BIGINT NULL AFTER template_id;
