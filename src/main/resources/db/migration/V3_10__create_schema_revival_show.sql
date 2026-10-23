-- A revival's shows are date and venue pairs, the venue null until it is decided. They replace revival_show_date and
-- revival_venue, two lists that said nothing about which show was where. The same date and venue only once is checked
-- by the application, since a unique key cannot hold a null venue.
CREATE TABLE IF NOT EXISTS revival_show
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    show_date              DATE                  NOT NULL,
    venue                  VARCHAR(255)          NULL,
    CONSTRAINT PK_REVIVAL_SHOW PRIMARY KEY (id),
    CONSTRAINT FK_REVIVAL_SHOW_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_REVIVAL_SHOW_ON_REVIVAL_DATE ON revival_show (revival_id, show_date);

CREATE TABLE IF NOT EXISTS revival_show_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    show_date              DATE                  NULL,
    venue                  VARCHAR(255)          NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_REVIVAL_SHOW_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- Existing data (no application is live, so only local and staging data): every show date becomes a show. A revival
-- with one venue gets it on every show; one with as many venues as dates pairs them in order, dates ascending and
-- venues by position. Otherwise the shows have no venue and the venues are dropped, since nothing says which goes where.
INSERT INTO revival_show (revival_id, show_date, venue)
SELECT d.revival_id, d.show_date, NULLIF(TRIM(v.name), '')
FROM (SELECT revival_id,
             show_date,
             ROW_NUMBER() OVER (PARTITION BY revival_id ORDER BY show_date) AS n,
             COUNT(*) OVER (PARTITION BY revival_id)                        AS total
      FROM revival_show_date) d
         LEFT JOIN (SELECT revival_id,
                           name,
                           ROW_NUMBER() OVER (PARTITION BY revival_id ORDER BY position) AS n,
                           COUNT(*) OVER (PARTITION BY revival_id)                       AS total
                    FROM revival_venue) v
                   ON v.revival_id = d.revival_id AND ((v.total = 1 AND v.n = 1) OR (v.total = d.total AND v.n = d.n))
ORDER BY d.revival_id, d.show_date;

-- The validity audit strategy refuses to change a row without history ("Cannot update previous revision"), so the
-- migrated shows get one baseline revision, as in V3_9
INSERT INTO revinfo (modified_at, modified_by, source, operation)
SELECT UNIX_TIMESTAMP(NOW(3)) * 1000, 'system', 'SYSTEM', 'showBaseline'
FROM DUAL
WHERE EXISTS (SELECT 1 FROM revival_show);

SET @rev = IF(EXISTS (SELECT 1 FROM revival_show), LAST_INSERT_ID(), NULL);

INSERT INTO revival_show_audit (id, revival_id, show_date, venue, rev, revtype)
SELECT id, revival_id, show_date, venue, @rev, 0
FROM revival_show
WHERE @rev IS NOT NULL;

INSERT INTO revchanges (rev, entityname)
SELECT @rev, 'nu.fgv.revival.server.revival.RevivalShow'
FROM DUAL
WHERE @rev IS NOT NULL;

DROP TABLE revival_show_date_audit;
DROP TABLE revival_venue_audit;
DROP TABLE revival_show_date;
DROP TABLE revival_venue;
