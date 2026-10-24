-- Mailings, the mailing plan and its reminders (product spec 5.8, M6b). Recipients are references to participants or
-- people; email addresses are read at send time and never stored.

-- The super admin's mailing plan: a mailing due relative to each event of a type, or to the premiere
CREATE TABLE IF NOT EXISTS mailing_template
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    name_sv                VARCHAR(100)          NOT NULL,
    name_en                VARCHAR(100)          NULL,
    subject_sv             VARCHAR(200)          NOT NULL,
    subject_en             VARCHAR(200)          NULL,
    body_sv                MEDIUMTEXT            NOT NULL,
    body_en                MEDIUMTEXT            NULL,
    audience_kind          VARCHAR(16)           NOT NULL,
    event_type_id          BIGINT                NULL,
    deadline_kind          VARCHAR(24)           NOT NULL,
    deadline_amount        INT                   NULL,
    deadline_weekday       VARCHAR(9)            NULL,
    position               INT                   NOT NULL,
    retired_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_MAILING_TEMPLATE PRIMARY KEY (id),
    CONSTRAINT FK_MAILING_TEMPLATE_ON_EVENT_TYPE FOREIGN KEY (event_type_id) REFERENCES event_type (id),
    CONSTRAINT CK_MAILING_TEMPLATE_AUDIENCE CHECK (audience_kind IN ('PARTICIPANTS', 'CONFIRMED', 'STEERING_GROUP', 'POSITION',
                                                                     'EVENT_ATTENDEES', 'HELPERS', 'NON_RESPONDERS')),
    CONSTRAINT CK_MAILING_TEMPLATE_DEADLINE CHECK (deadline_kind IN ('DAYS', 'WORKING_DAYS', 'WEEKDAY_WEEK_BEFORE'))
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS mailing_template_audit
(
    id                     BIGINT                NOT NULL,
    name_sv                VARCHAR(100)          NULL,
    name_en                VARCHAR(100)          NULL,
    subject_sv             VARCHAR(200)          NULL,
    subject_en             VARCHAR(200)          NULL,
    body_sv                MEDIUMTEXT            NULL,
    body_en                MEDIUMTEXT            NULL,
    audience_kind          VARCHAR(16)           NULL,
    event_type_id          BIGINT                NULL,
    deadline_kind          VARCHAR(24)           NULL,
    deadline_amount        INT                   NULL,
    deadline_weekday       VARCHAR(9)            NULL,
    position               INT                   NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_MAILING_TEMPLATE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS mailing
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    audience_kind          VARCHAR(16)           NOT NULL,
    -- A Spexregister task id
    audience_task_id       VARCHAR(36)           NULL,
    audience_event_id      BIGINT                NULL,
    subject                VARCHAR(200)          NOT NULL,
    body                   MEDIUMTEXT            NOT NULL,
    status                 VARCHAR(16)           NOT NULL,
    -- The plan entry it was drafted from: its template and event (null for the premiere)
    template_id            BIGINT                NULL,
    plan_event_id          BIGINT                NULL,
    created_by_person      BIGINT                NULL,
    sent_by_person         BIGINT                NULL,
    sent_at                DATETIME(6)           NULL,
    cancelled_at           DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_MAILING PRIMARY KEY (id),
    CONSTRAINT FK_MAILING_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_MAILING_ON_AUDIENCE_EVENT FOREIGN KEY (audience_event_id) REFERENCES event (id),
    CONSTRAINT FK_MAILING_ON_TEMPLATE FOREIGN KEY (template_id) REFERENCES mailing_template (id),
    CONSTRAINT FK_MAILING_ON_PLAN_EVENT FOREIGN KEY (plan_event_id) REFERENCES event (id),
    CONSTRAINT FK_MAILING_ON_CREATED_BY_PERSON FOREIGN KEY (created_by_person) REFERENCES person (id),
    CONSTRAINT FK_MAILING_ON_SENT_BY_PERSON FOREIGN KEY (sent_by_person) REFERENCES person (id),
    CONSTRAINT CK_MAILING_AUDIENCE CHECK (audience_kind IN ('PARTICIPANTS', 'CONFIRMED', 'STEERING_GROUP', 'POSITION', 'EVENT_ATTENDEES',
                                                            'HELPERS', 'NON_RESPONDERS')),
    CONSTRAINT CK_MAILING_STATUS CHECK (status IN ('DRAFT', 'SENT', 'CANCELLED'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_MAILING_ON_REVIVAL ON mailing (revival_id);

CREATE TABLE IF NOT EXISTS mailing_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    audience_kind          VARCHAR(16)           NULL,
    audience_task_id       VARCHAR(36)           NULL,
    audience_event_id      BIGINT                NULL,
    subject                VARCHAR(200)          NULL,
    body                   MEDIUMTEXT            NULL,
    status                 VARCHAR(16)           NULL,
    template_id            BIGINT                NULL,
    plan_event_id          BIGINT                NULL,
    created_by_person      BIGINT                NULL,
    sent_by_person         BIGINT                NULL,
    sent_at                DATETIME(6)           NULL,
    cancelled_at           DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_MAILING_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- One row per recipient: a participant or a person (steering group), never an address
CREATE TABLE IF NOT EXISTS mailing_recipient
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    mailing_id             BIGINT                NOT NULL,
    participant_id         BIGINT                NULL,
    person_id              BIGINT                NULL,
    status                 VARCHAR(16)           NOT NULL,
    attempt                INT                   NOT NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_MAILING_RECIPIENT PRIMARY KEY (id),
    CONSTRAINT FK_MAILING_RECIPIENT_ON_MAILING FOREIGN KEY (mailing_id) REFERENCES mailing (id),
    CONSTRAINT FK_MAILING_RECIPIENT_ON_PARTICIPANT FOREIGN KEY (participant_id) REFERENCES participant (id),
    CONSTRAINT FK_MAILING_RECIPIENT_ON_PERSON FOREIGN KEY (person_id) REFERENCES person (id),
    CONSTRAINT CK_MAILING_RECIPIENT_REFERENCE CHECK ((participant_id IS NULL) <> (person_id IS NULL)),
    CONSTRAINT CK_MAILING_RECIPIENT_STATUS CHECK (status IN ('SENT', 'FAILED', 'NO_ADDRESS'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_MAILING_RECIPIENT_ON_MAILING ON mailing_recipient (mailing_id);

CREATE TABLE IF NOT EXISTS mailing_recipient_audit
(
    id                     BIGINT                NOT NULL,
    mailing_id             BIGINT                NULL,
    participant_id         BIGINT                NULL,
    person_id              BIGINT                NULL,
    status                 VARCHAR(16)           NULL,
    attempt                INT                   NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_MAILING_RECIPIENT_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- Not audited: a record that the owners were reminded of a plan entry, so they are reminded once
CREATE TABLE IF NOT EXISTS mailing_reminder
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    template_id            BIGINT                NOT NULL,
    event_id               BIGINT                NULL,
    -- 0 for the premiere, so the unique key also holds then
    event_key              BIGINT AS (IFNULL(event_id, 0)) STORED,
    sent_on                DATE                  NOT NULL,
    CONSTRAINT PK_MAILING_REMINDER PRIMARY KEY (id),
    CONSTRAINT UC_MAILING_REMINDER UNIQUE (revival_id, template_id, event_key),
    CONSTRAINT FK_MAILING_REMINDER_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_MAILING_REMINDER_ON_TEMPLATE FOREIGN KEY (template_id) REFERENCES mailing_template (id),
    CONSTRAINT FK_MAILING_REMINDER_ON_EVENT FOREIGN KEY (event_id) REFERENCES event (id)
) DEFAULT CHARSET = utf8mb4;
