-- M7: the participation sync to Spexregister (product spec 5.11). A run writes, in steps, what the preview the starter
-- confirmed planned; every step is recorded per participant, so a run that stopped halfway resumes without duplicates.
-- No personal data: participants by id, Spexregister's ids, a short error code and a detail such as a position's name.

CREATE TABLE IF NOT EXISTS sync_run
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    started_by             BIGINT                NULL,
    started_at             DATETIME(6)           NOT NULL,
    finished_at            DATETIME(6)           NULL,
    status                 VARCHAR(16)           NOT NULL,
    preview_hash           CHAR(64)              NOT NULL,
    running_marker         TINYINT AS (IF(status = 'RUNNING', 1, NULL)) STORED,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_SYNC_RUN PRIMARY KEY (id),
    CONSTRAINT FK_SYNC_RUN_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_SYNC_RUN_ON_STARTED_BY FOREIGN KEY (started_by) REFERENCES person (id),
    -- At most one run at a time per revival
    CONSTRAINT UC_SYNC_RUN_RUNNING UNIQUE (revival_id, running_marker),
    CONSTRAINT CK_SYNC_RUN_STATUS CHECK (status IN ('RUNNING', 'DONE', 'FAILED'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_SYNC_RUN_ON_REVIVAL_STARTED ON sync_run (revival_id, started_at);

CREATE TABLE IF NOT EXISTS sync_run_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    started_by             BIGINT                NULL,
    started_at             DATETIME(6)           NULL,
    finished_at            DATETIME(6)           NULL,
    status                 VARCHAR(16)           NULL,
    preview_hash           CHAR(64)              NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_SYNC_RUN_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS sync_step
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    run_id                 BIGINT                NOT NULL,
    -- NULL for the REVIVAL step, and once the revival is anonymized
    participant_id         BIGINT                NULL,
    operation              VARCHAR(16)           NOT NULL,
    status                 VARCHAR(16)           NOT NULL,
    remote_id              VARCHAR(64)           NULL,
    error_code             VARCHAR(40)           NULL,
    detail                 VARCHAR(255)          NULL,
    updated_at             DATETIME(6)           NOT NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_SYNC_STEP PRIMARY KEY (id),
    CONSTRAINT FK_SYNC_STEP_ON_RUN FOREIGN KEY (run_id) REFERENCES sync_run (id),
    CONSTRAINT FK_SYNC_STEP_ON_PARTICIPANT FOREIGN KEY (participant_id) REFERENCES participant (id),
    CONSTRAINT UC_SYNC_STEP UNIQUE (run_id, participant_id, operation),
    CONSTRAINT CK_SYNC_STEP_OPERATION CHECK (operation IN ('REVIVAL', 'PARTICIPATION', 'TASK', 'ACTOR', 'MEMBERSHIP')),
    CONSTRAINT CK_SYNC_STEP_STATUS CHECK (status IN ('PENDING', 'DONE', 'EXISTED', 'FAILED'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_SYNC_STEP_ON_PARTICIPANT ON sync_step (participant_id, operation, status);

CREATE TABLE IF NOT EXISTS sync_step_audit
(
    id                     BIGINT                NOT NULL,
    run_id                 BIGINT                NULL,
    participant_id         BIGINT                NULL,
    operation              VARCHAR(16)           NULL,
    status                 VARCHAR(16)           NULL,
    remote_id              VARCHAR(64)           NULL,
    error_code             VARCHAR(40)           NULL,
    detail                 VARCHAR(255)          NULL,
    updated_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_SYNC_STEP_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
