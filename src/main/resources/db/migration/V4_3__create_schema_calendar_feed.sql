-- Secret calendar links (M4-FR-5). A credential, like person, so not audited. Only the SHA-256 of the token is kept;
-- at most one link per person and revival is active.
CREATE TABLE IF NOT EXISTS calendar_feed
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    person_id              BIGINT                NOT NULL,
    token_hash             CHAR(64)              NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    revoked_at             DATETIME(6)           NULL,
    active_person_id       BIGINT AS (IF(revoked_at IS NULL, person_id, NULL)) STORED,
    CONSTRAINT PK_CALENDAR_FEED PRIMARY KEY (id),
    CONSTRAINT UC_CALENDAR_FEED_TOKEN_HASH UNIQUE (token_hash),
    CONSTRAINT UC_CALENDAR_FEED_ACTIVE UNIQUE (revival_id, active_person_id),
    CONSTRAINT FK_CALENDAR_FEED_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_CALENDAR_FEED_ON_PERSON FOREIGN KEY (person_id) REFERENCES person (id)
) DEFAULT CHARSET = utf8mb4;
