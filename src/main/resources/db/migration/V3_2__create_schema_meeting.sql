CREATE TABLE IF NOT EXISTS meeting
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    meeting_date           DATE                  NOT NULL,
    title                  VARCHAR(255)          NULL,
    others_present         VARCHAR(1000)         NULL,
    status                 VARCHAR(16)           NOT NULL,
    recorder_person_id     BIGINT                NULL,
    started_at             DATETIME(6)           NULL,
    closed_at              DATETIME(6)           NULL,
    closed_by              BIGINT                NULL,
    minutes                JSON                  NULL,
    in_progress_marker     TINYINT AS (IF(status = 'IN_PROGRESS', 1, NULL)) STORED,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_MEETING PRIMARY KEY (id),
    CONSTRAINT FK_MEETING_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_MEETING_ON_RECORDER FOREIGN KEY (recorder_person_id) REFERENCES person (id),
    CONSTRAINT FK_MEETING_ON_CLOSED_BY FOREIGN KEY (closed_by) REFERENCES person (id),
    -- At most one meeting in progress per revival
    CONSTRAINT UC_MEETING_IN_PROGRESS UNIQUE (revival_id, in_progress_marker),
    CONSTRAINT CK_MEETING_STATUS CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'CLOSED', 'CANCELLED'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_MEETING_ON_REVIVAL_DATE ON meeting (revival_id, meeting_date);

CREATE TABLE IF NOT EXISTS meeting_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    meeting_date           DATE                  NULL,
    title                  VARCHAR(255)          NULL,
    others_present         VARCHAR(1000)         NULL,
    status                 VARCHAR(16)           NULL,
    recorder_person_id     BIGINT                NULL,
    started_at             DATETIME(6)           NULL,
    closed_at              DATETIME(6)           NULL,
    closed_by              BIGINT                NULL,
    minutes                JSON                  NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_MEETING_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS meeting_attendee
(
    meeting_id             BIGINT                NOT NULL,
    person_id              BIGINT                NOT NULL,
    CONSTRAINT PK_MEETING_ATTENDEE PRIMARY KEY (meeting_id, person_id),
    CONSTRAINT FK_MEETING_ATTENDEE_ON_MEETING FOREIGN KEY (meeting_id) REFERENCES meeting (id),
    CONSTRAINT FK_MEETING_ATTENDEE_ON_PERSON FOREIGN KEY (person_id) REFERENCES person (id)
);

CREATE TABLE IF NOT EXISTS meeting_attendee_audit
(
    meeting_id             BIGINT                NOT NULL,
    person_id              BIGINT                NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_MEETING_ATTENDEE_AUDIT PRIMARY KEY (rev, revtype, meeting_id, person_id)
);

CREATE TABLE IF NOT EXISTS meeting_agenda_point
(
    meeting_id             BIGINT                NOT NULL,
    position               INT                   NOT NULL,
    text                   VARCHAR(1000)         NOT NULL,
    CONSTRAINT PK_MEETING_AGENDA_POINT PRIMARY KEY (meeting_id, position),
    CONSTRAINT FK_MEETING_AGENDA_POINT_ON_MEETING FOREIGN KEY (meeting_id) REFERENCES meeting (id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS meeting_agenda_point_audit
(
    meeting_id             BIGINT                NOT NULL,
    position               INT                   NOT NULL,
    text                   VARCHAR(1000)         NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_MEETING_AGENDA_POINT_AUDIT PRIMARY KEY (rev, revtype, meeting_id, position)
) DEFAULT CHARSET = utf8mb4;
