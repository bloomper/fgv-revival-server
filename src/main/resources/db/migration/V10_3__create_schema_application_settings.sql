-- The application settings the super admin keeps: one row, made here.
CREATE TABLE IF NOT EXISTS application_settings
(
    id                        BIGINT                NOT NULL,
    terms_sv                  TEXT                  NULL,
    terms_en                  TEXT                  NULL,
    default_participation_fee DECIMAL(12, 2)        NULL,
    created_by                VARCHAR(255)          NOT NULL,
    created_at                DATETIME(6)           NOT NULL,
    last_modified_by          VARCHAR(255)          NULL,
    last_modified_at          DATETIME(6)           NULL,
    version                   BIGINT                NULL,
    CONSTRAINT PK_APPLICATION_SETTINGS PRIMARY KEY (id),
    CONSTRAINT CK_APPLICATION_SETTINGS_ONE CHECK (id = 1)
) DEFAULT CHARSET = utf8mb4;

INSERT INTO application_settings (id, created_by, created_at, version)
VALUES (1, 'system', NOW(6), 0);

CREATE TABLE IF NOT EXISTS application_settings_audit
(
    id                        BIGINT                NOT NULL,
    terms_sv                  TEXT                  NULL,
    terms_en                  TEXT                  NULL,
    default_participation_fee DECIMAL(12, 2)        NULL,
    version                   BIGINT                NULL,
    rev                       BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                    BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                   TINYINT               DEFAULT NULL,
    revend_tstmp              DATETIME(6)           DEFAULT NULL,

    KEY                       IX_rev                (rev),
    KEY                       IX_revend             (revend),

    CONSTRAINT PK_APPLICATION_SETTINGS_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- The validity audit strategy refuses to update an entity without audit rows, so the row starts with a baseline.
INSERT INTO revinfo (modified_at, modified_by, source, operation)
VALUES (UNIX_TIMESTAMP(NOW(3)) * 1000, 'system', 'SYSTEM', 'applicationSettingsBaseline');

SET @rev = LAST_INSERT_ID();

INSERT INTO application_settings_audit (id, terms_sv, terms_en, default_participation_fee, version, rev, revtype)
SELECT id, terms_sv, terms_en, default_participation_fee, version, @rev, 0
FROM application_settings;

INSERT INTO revchanges (rev, entityname)
VALUES (@rev, 'nu.fgv.revival.server.settings.ApplicationSettings');
