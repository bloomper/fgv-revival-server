-- Availability per participant and event day, and staffing with shift slots (product spec 5.7, M6b). The comment on an
-- answer is the person's own free text and is not audited.

CREATE TABLE IF NOT EXISTS availability
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    participant_id         BIGINT                NOT NULL,
    event_id               BIGINT                NOT NULL,
    day                    DATE                  NOT NULL,
    answer                 VARCHAR(8)            NOT NULL,
    comment                VARCHAR(255)          NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_AVAILABILITY PRIMARY KEY (id),
    CONSTRAINT UC_AVAILABILITY_DAY UNIQUE (participant_id, event_id, day),
    CONSTRAINT FK_AVAILABILITY_ON_PARTICIPANT FOREIGN KEY (participant_id) REFERENCES participant (id),
    CONSTRAINT FK_AVAILABILITY_ON_EVENT FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT CK_AVAILABILITY_ANSWER CHECK (answer IN ('YES', 'NO', 'MAYBE'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_AVAILABILITY_ON_EVENT ON availability (event_id);

CREATE TABLE IF NOT EXISTS availability_audit
(
    id                     BIGINT                NOT NULL,
    participant_id         BIGINT                NULL,
    event_id               BIGINT                NULL,
    day                    DATE                  NULL,
    answer                 VARCHAR(8)            NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_AVAILABILITY_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- A time block on an event day that needs people for a task. Removed only while nobody is assigned: it is then an
-- empty planning row.
CREATE TABLE IF NOT EXISTS shift_slot
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    event_id               BIGINT                NOT NULL,
    day                    DATE                  NOT NULL,
    start_time             TIME                  NOT NULL,
    end_time               TIME                  NOT NULL,
    task                   VARCHAR(100)          NOT NULL,
    needed                 INT                   NOT NULL,
    comment                VARCHAR(1000)         NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_SHIFT_SLOT PRIMARY KEY (id),
    CONSTRAINT FK_SHIFT_SLOT_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_SHIFT_SLOT_ON_EVENT FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT CK_SHIFT_SLOT_TIMES CHECK (end_time > start_time),
    CONSTRAINT CK_SHIFT_SLOT_NEEDED CHECK (needed BETWEEN 1 AND 100)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_SHIFT_SLOT_ON_REVIVAL_DAY ON shift_slot (revival_id, day);
CREATE INDEX IX_SHIFT_SLOT_ON_EVENT ON shift_slot (event_id);

CREATE TABLE IF NOT EXISTS shift_slot_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    event_id               BIGINT                NULL,
    day                    DATE                  NULL,
    start_time             TIME                  NULL,
    end_time               TIME                  NULL,
    task                   VARCHAR(100)          NULL,
    needed                 INT                   NULL,
    comment                VARCHAR(1000)         NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_SHIFT_SLOT_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- Who holds a slot: signed up themselves, or assigned by a writer of staffing
CREATE TABLE IF NOT EXISTS shift_assignment
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    slot_id                BIGINT                NOT NULL,
    participant_id         BIGINT                NOT NULL,
    self_signed_up         BOOLEAN               NOT NULL,
    assigned_by            BIGINT                NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_SHIFT_ASSIGNMENT PRIMARY KEY (id),
    CONSTRAINT UC_SHIFT_ASSIGNMENT UNIQUE (slot_id, participant_id),
    CONSTRAINT FK_SHIFT_ASSIGNMENT_ON_SLOT FOREIGN KEY (slot_id) REFERENCES shift_slot (id),
    CONSTRAINT FK_SHIFT_ASSIGNMENT_ON_PARTICIPANT FOREIGN KEY (participant_id) REFERENCES participant (id),
    CONSTRAINT FK_SHIFT_ASSIGNMENT_ON_ASSIGNED_BY FOREIGN KEY (assigned_by) REFERENCES person (id)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_SHIFT_ASSIGNMENT_ON_PARTICIPANT ON shift_assignment (participant_id);

CREATE TABLE IF NOT EXISTS shift_assignment_audit
(
    id                     BIGINT                NOT NULL,
    slot_id                BIGINT                NULL,
    participant_id         BIGINT                NULL,
    self_signed_up         BOOLEAN               NULL,
    assigned_by            BIGINT                NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_SHIFT_ASSIGNMENT_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
