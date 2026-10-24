-- The services materials link to (product spec 5.9, M8-FR-1): a list the super admin keeps, as capabilities and guide
-- item types. FGV's own applications and services such as Google Drive. Each has the hosts its links use, so a
-- material's link shows which service it is in; a host belongs to at most one active service, which the application
-- checks. The seeded ones keep their names as ids. FGV's applications get their hosts from the super admin.
CREATE TABLE IF NOT EXISTS link_service
(
    id                     VARCHAR(36)           NOT NULL,
    name_sv                VARCHAR(100)          NOT NULL,
    name_en                VARCHAR(100)          NULL,
    home_url               VARCHAR(500)          NULL,
    position               INT                   NOT NULL,
    retired_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_LINK_SERVICE PRIMARY KEY (id),
    CONSTRAINT CK_LINK_SERVICE_HOME_URL CHECK (home_url IS NULL OR home_url LIKE 'https://%')
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS link_service_audit
(
    id                     VARCHAR(36)           NOT NULL,
    name_sv                VARCHAR(100)          NULL,
    name_en                VARCHAR(100)          NULL,
    home_url               VARCHAR(500)          NULL,
    position               INT                   NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_LINK_SERVICE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- Lower-case host names; a link belongs to the service when its host is one of these or a subdomain of one
CREATE TABLE IF NOT EXISTS link_service_host
(
    link_service_id        VARCHAR(36)           NOT NULL,
    host                   VARCHAR(253)          NOT NULL,
    CONSTRAINT PK_LINK_SERVICE_HOST PRIMARY KEY (link_service_id, host),
    CONSTRAINT FK_LINK_SERVICE_HOST_ON_SERVICE FOREIGN KEY (link_service_id) REFERENCES link_service (id),
    CONSTRAINT CK_LINK_SERVICE_HOST_LOWER CHECK (host = LOWER(host))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_LINK_SERVICE_HOST_ON_HOST ON link_service_host (host);

CREATE TABLE IF NOT EXISTS link_service_host_audit
(
    link_service_id        VARCHAR(36)           NOT NULL,
    host                   VARCHAR(253)          NOT NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               NOT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_LINK_SERVICE_HOST_AUDIT PRIMARY KEY (rev, revtype, link_service_id, host)
) DEFAULT CHARSET = utf8mb4;

INSERT INTO link_service (id, name_sv, name_en, home_url, position, created_by, created_at, version)
VALUES ('SPEXFLIX', 'Spexflix', NULL, NULL, 0, 'system', NOW(6), 0),
       ('MANUSARKIV', 'Manusarkiv', NULL, NULL, 1, 'system', NOW(6), 0),
       ('SPEXIPEDIA', 'Spexipedia', NULL, NULL, 2, 'system', NOW(6), 0),
       ('SPEXIFY', 'Spexify', NULL, NULL, 3, 'system', NOW(6), 0),
       ('LATARKIV', 'Låtarkiv', 'Song archive', NULL, 4, 'system', NOW(6), 0),
       ('GOOGLE_DRIVE', 'Google Drive', NULL, 'https://drive.google.com/', 5, 'system', NOW(6), 0),
       ('DROPBOX', 'Dropbox', NULL, 'https://www.dropbox.com/', 6, 'system', NOW(6), 0),
       ('YOUTUBE', 'YouTube', NULL, 'https://www.youtube.com/', 7, 'system', NOW(6), 0),
       ('ONEDRIVE', 'OneDrive', NULL, 'https://onedrive.live.com/', 8, 'system', NOW(6), 0);

INSERT INTO link_service_host (link_service_id, host)
VALUES ('GOOGLE_DRIVE', 'drive.google.com'),
       ('GOOGLE_DRIVE', 'docs.google.com'),
       ('DROPBOX', 'dropbox.com'),
       ('YOUTUBE', 'youtube.com'),
       ('YOUTUBE', 'youtu.be'),
       ('ONEDRIVE', 'onedrive.live.com'),
       ('ONEDRIVE', '1drv.ms'),
       ('ONEDRIVE', 'sharepoint.com');

-- The validity audit strategy refuses to change a row without history ("Cannot update previous revision"), so the
-- seeded services get one baseline revision, as in V3_9
INSERT INTO revinfo (modified_at, modified_by, source, operation)
VALUES (UNIX_TIMESTAMP(NOW(3)) * 1000, 'system', 'SYSTEM', 'linkServiceBaseline');

SET @rev = LAST_INSERT_ID();

INSERT INTO link_service_audit (id, name_sv, name_en, home_url, position, retired_at, version, rev, revtype)
SELECT s.id, s.name_sv, s.name_en, s.home_url, s.position, s.retired_at, s.version, @rev, 0
FROM link_service s
WHERE NOT EXISTS (SELECT 1 FROM link_service_audit a WHERE a.id = s.id);

INSERT INTO link_service_host_audit (link_service_id, host, rev, revtype)
SELECT h.link_service_id, h.host, @rev, 0
FROM link_service_host h
WHERE NOT EXISTS (SELECT 1 FROM link_service_host_audit a WHERE a.link_service_id = h.link_service_id AND a.host = h.host);

INSERT INTO revchanges (rev, entityname)
VALUES (@rev, 'nu.fgv.revival.server.material.LinkService');
