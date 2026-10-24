-- A revival's participants and helpers (product spec 5.6, M6): who is asked, their responses, fees and referrals.
-- Spexare are referred to by their Spexregister id; nothing from Spexregister is copied. A pending person's name and
-- contact details, dietary needs and referrals are not audited, so deleting them leaves no trace (product spec 7).

CREATE TABLE IF NOT EXISTS participant
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    kind                   VARCHAR(16)           NOT NULL,
    -- Spexregister's id; null for a pending person
    spexare_id             BIGINT                NULL,
    -- Their participant or helper role, made when they are invited
    member_id              BIGINT                NULL,
    -- Spexregister task ids and vocal type id
    position_task_id       VARCHAR(36)           NULL,
    role_name              VARCHAR(100)          NULL,
    vocal_part_id          VARCHAR(36)           NULL,
    preferred_task_id      VARCHAR(36)           NULL,
    response               VARCHAR(16)           NOT NULL,
    responded_at           DATETIME(6)           NULL,
    comment                VARCHAR(1000)         NULL,
    terms_accepted_at      DATETIME(6)           NULL,
    register_consent       BOOLEAN               NULL,
    register_consent_at    DATETIME(6)           NULL,
    invited_at             DATETIME(6)           NULL,
    reminded_at            DATETIME(6)           NULL,
    -- This participant's own fee, overriding the revival's
    fee                    DECIMAL(12, 2)        NULL,
    fee_paid_at            DATETIME(6)           NULL,
    fee_paid_by            BIGINT                NULL,
    compensation_per_shift DECIMAL(12, 2)        NULL,
    membership_paid        BOOLEAN               NOT NULL DEFAULT FALSE,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    active_spexare_id      BIGINT AS (IF(removed_at IS NULL, spexare_id, NULL)) STORED,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_PARTICIPANT PRIMARY KEY (id),
    CONSTRAINT FK_PARTICIPANT_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_PARTICIPANT_ON_MEMBER FOREIGN KEY (member_id) REFERENCES revival_member (id),
    CONSTRAINT FK_PARTICIPANT_ON_FEE_PAID_BY FOREIGN KEY (fee_paid_by) REFERENCES person (id),
    CONSTRAINT FK_PARTICIPANT_ON_REMOVED_BY FOREIGN KEY (removed_by) REFERENCES person (id),
    -- A spexare takes part once per revival while not removed
    CONSTRAINT UC_PARTICIPANT_ACTIVE_SPEXARE UNIQUE (revival_id, active_spexare_id),
    CONSTRAINT CK_PARTICIPANT_KIND CHECK (kind IN ('PARTICIPANT', 'HELPER')),
    CONSTRAINT CK_PARTICIPANT_RESPONSE CHECK (response IN ('PENDING', 'YES', 'NO', 'MAYBE'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_PARTICIPANT_ON_REVIVAL ON participant (revival_id);

CREATE TABLE IF NOT EXISTS participant_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    kind                   VARCHAR(16)           NULL,
    spexare_id             BIGINT                NULL,
    member_id              BIGINT                NULL,
    position_task_id       VARCHAR(36)           NULL,
    role_name              VARCHAR(100)          NULL,
    vocal_part_id          VARCHAR(36)           NULL,
    preferred_task_id      VARCHAR(36)           NULL,
    response               VARCHAR(16)           NULL,
    responded_at           DATETIME(6)           NULL,
    terms_accepted_at      DATETIME(6)           NULL,
    register_consent       BOOLEAN               NULL,
    register_consent_at    DATETIME(6)           NULL,
    invited_at             DATETIME(6)           NULL,
    reminded_at            DATETIME(6)           NULL,
    fee                    DECIMAL(12, 2)        NULL,
    fee_paid_at            DATETIME(6)           NULL,
    fee_paid_by            BIGINT                NULL,
    compensation_per_shift DECIMAL(12, 2)        NULL,
    membership_paid        BOOLEAN               NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_PARTICIPANT_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- Not audited: the application is its home, and it is deleted on decline, on becoming a spexare, or when the revival
-- leaves OPEN without an answer
CREATE TABLE IF NOT EXISTS pending_person
(
    participant_id         BIGINT                NOT NULL,
    first_name             VARCHAR(100)          NOT NULL,
    last_name              VARCHAR(100)          NOT NULL,
    email                  VARCHAR(255)          NULL,
    phone                  VARCHAR(50)           NULL,
    street_address         VARCHAR(255)          NULL,
    postal_code            VARCHAR(20)           NULL,
    city                   VARCHAR(100)          NULL,
    CONSTRAINT PK_PENDING_PERSON PRIMARY KEY (participant_id),
    CONSTRAINT FK_PENDING_PERSON_ON_PARTICIPANT FOREIGN KEY (participant_id) REFERENCES participant (id)
) DEFAULT CHARSET = utf8mb4;

-- Not audited: deleted the day after the revival's last show
CREATE TABLE IF NOT EXISTS dietary_need
(
    participant_id         BIGINT                NOT NULL,
    text                   VARCHAR(1000)         NOT NULL,
    updated_at             DATETIME(6)           NOT NULL,
    CONSTRAINT PK_DIETARY_NEED PRIMARY KEY (participant_id),
    CONSTRAINT FK_DIETARY_NEED_ON_PARTICIPANT FOREIGN KEY (participant_id) REFERENCES participant (id)
) DEFAULT CHARSET = utf8mb4;

-- One row per revival, made the first time it is saved
CREATE TABLE IF NOT EXISTS participation_settings
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    swish_number           VARCHAR(20)           NULL,
    whatsapp_link          VARCHAR(255)          NULL,
    terms                  VARCHAR(4000)         NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_PARTICIPATION_SETTINGS PRIMARY KEY (id),
    CONSTRAINT UC_PARTICIPATION_SETTINGS_REVIVAL UNIQUE (revival_id),
    CONSTRAINT FK_PARTICIPATION_SETTINGS_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS participation_settings_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    swish_number           VARCHAR(20)           NULL,
    whatsapp_link          VARCHAR(255)          NULL,
    terms                  VARCHAR(4000)         NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_PARTICIPATION_SETTINGS_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- Not audited: deleted once the steering group has handled it
CREATE TABLE IF NOT EXISTS referral
(
    id                         BIGINT AUTO_INCREMENT NOT NULL,
    revival_id                 BIGINT                NOT NULL,
    referred_by_participant_id BIGINT                NOT NULL,
    name                       VARCHAR(100)          NOT NULL,
    contact                    VARCHAR(200)          NULL,
    comment                    VARCHAR(1000)         NULL,
    created_at                 DATETIME(6)           NOT NULL,
    CONSTRAINT PK_REFERRAL PRIMARY KEY (id),
    CONSTRAINT FK_REFERRAL_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_REFERRAL_ON_PARTICIPANT FOREIGN KEY (referred_by_participant_id) REFERENCES participant (id)
) DEFAULT CHARSET = utf8mb4;
