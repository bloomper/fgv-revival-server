ALTER TABLE revival
    ADD COLUMN guide_version_id BIGINT NULL AFTER status,
    ADD CONSTRAINT FK_REVIVAL_ON_GUIDE_VERSION FOREIGN KEY (guide_version_id) REFERENCES guide_version (id);

ALTER TABLE revival_audit
    ADD COLUMN guide_version_id BIGINT NULL AFTER status;

CREATE TABLE IF NOT EXISTS revival_guide_version_change
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    from_version_id        BIGINT                NULL,
    to_version_id          BIGINT                NOT NULL,
    changed_by             BIGINT                NOT NULL,
    changed_at             DATETIME(6)           NOT NULL,
    CONSTRAINT PK_REVIVAL_GUIDE_VERSION_CHANGE PRIMARY KEY (id),
    CONSTRAINT FK_REVIVAL_GUIDE_VERSION_CHANGE_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_REVIVAL_GUIDE_VERSION_CHANGE_ON_FROM FOREIGN KEY (from_version_id) REFERENCES guide_version (id),
    CONSTRAINT FK_REVIVAL_GUIDE_VERSION_CHANGE_ON_TO FOREIGN KEY (to_version_id) REFERENCES guide_version (id),
    CONSTRAINT FK_REVIVAL_GUIDE_VERSION_CHANGE_ON_PERSON FOREIGN KEY (changed_by) REFERENCES person (id)
);

CREATE TABLE IF NOT EXISTS revival_guide_version_change_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    from_version_id        BIGINT                NULL,
    to_version_id          BIGINT                NULL,
    changed_by             BIGINT                NULL,
    changed_at             DATETIME(6)           NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REVIVAL_GUIDE_VERSION_CHANGE_AUDIT PRIMARY KEY (id, rev)
);
