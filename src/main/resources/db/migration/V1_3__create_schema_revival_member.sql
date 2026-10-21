CREATE TABLE IF NOT EXISTS revival_member
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    person_id              BIGINT                NULL,
    pending_email          VARCHAR(255)          NULL,
    role                   VARCHAR(16)           NOT NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    active_person_id       BIGINT AS (IF(removed_at IS NULL, person_id, NULL)) STORED,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_REVIVAL_MEMBER PRIMARY KEY (id),
    CONSTRAINT FK_REVIVAL_MEMBER_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_REVIVAL_MEMBER_ON_PERSON FOREIGN KEY (person_id) REFERENCES person (id),
    CONSTRAINT FK_REVIVAL_MEMBER_ON_REMOVED_BY FOREIGN KEY (removed_by) REFERENCES person (id),
    -- While not removed, exactly one of person_id and pending_email is set
    CONSTRAINT CK_REVIVAL_MEMBER_IDENTITY CHECK (removed_at IS NOT NULL OR ((person_id IS NULL) <> (pending_email IS NULL))),
    CONSTRAINT UC_REVIVAL_MEMBER_ACTIVE_PERSON UNIQUE (revival_id, active_person_id),
    CONSTRAINT UC_REVIVAL_MEMBER_PENDING_EMAIL UNIQUE (revival_id, pending_email)
);

CREATE INDEX IX_REVIVAL_MEMBER_ON_PENDING_EMAIL ON revival_member (pending_email);

CREATE TABLE IF NOT EXISTS revival_member_capability
(
    member_id              BIGINT                NOT NULL,
    capability             VARCHAR(16)           NOT NULL,
    CONSTRAINT PK_REVIVAL_MEMBER_CAPABILITY PRIMARY KEY (member_id, capability),
    CONSTRAINT FK_REVIVAL_MEMBER_CAPABILITY_ON_MEMBER FOREIGN KEY (member_id) REFERENCES revival_member (id)
);

CREATE TABLE IF NOT EXISTS revival_member_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    person_id              BIGINT                NULL,
    role                   VARCHAR(16)           NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REVIVAL_MEMBER_AUDIT PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS revival_member_capability_audit
(
    member_id              BIGINT                NOT NULL,
    capability             VARCHAR(16)           NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REVIVAL_MEMBER_CAPABILITY_AUDIT PRIMARY KEY (rev, revtype, member_id, capability)
);
