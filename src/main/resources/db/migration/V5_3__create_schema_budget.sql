-- A revival's budget (product spec 5.10, M5): its settings, budget lines, transactions with their receipts, and saved
-- scenarios. Lines and scenarios are removed and transactions cancelled, never deleted (constitution IV); only a
-- receipt's file is deleted when it is replaced or removed. Amounts are kronor with two decimals.

-- One row per revival, made when the revival is created
CREATE TABLE IF NOT EXISTS budget_settings
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    participants_planned   INT                   NULL,
    participation_fee      DECIMAL(12, 2)        NULL,
    loan_amount            DECIMAL(12, 2)        NULL,
    loan_received_on       DATE                  NULL,
    loan_repaid_on         DATE                  NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_BUDGET_SETTINGS PRIMARY KEY (id),
    CONSTRAINT UC_BUDGET_SETTINGS_REVIVAL UNIQUE (revival_id),
    CONSTRAINT FK_BUDGET_SETTINGS_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT CK_BUDGET_SETTINGS_PARTICIPANTS CHECK (participants_planned IS NULL OR participants_planned BETWEEN 0 AND 2000)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS budget_settings_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    participants_planned   INT                   NULL,
    participation_fee      DECIMAL(12, 2)        NULL,
    loan_amount            DECIMAL(12, 2)        NULL,
    loan_received_on       DATE                  NULL,
    loan_repaid_on         DATE                  NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_BUDGET_SETTINGS_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS budget_line
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    category_id            VARCHAR(36)           NOT NULL,
    description            VARCHAR(255)          NOT NULL,
    quantity               DECIMAL(10, 2)        NOT NULL,
    unit_amount            DECIMAL(12, 2)        NOT NULL,
    event_id               BIGINT                NULL,
    log_entry_id           BIGINT                NULL,
    -- The event type's line it was proposed from; no foreign key, so the line outlives a removed template line
    event_type_line_id     BIGINT                NULL,
    comment                VARCHAR(1000)         NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_BUDGET_LINE PRIMARY KEY (id),
    CONSTRAINT FK_BUDGET_LINE_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_BUDGET_LINE_ON_CATEGORY FOREIGN KEY (category_id) REFERENCES budget_category (id),
    CONSTRAINT FK_BUDGET_LINE_ON_EVENT FOREIGN KEY (event_id) REFERENCES event (id),
    CONSTRAINT FK_BUDGET_LINE_ON_LOG_ENTRY FOREIGN KEY (log_entry_id) REFERENCES log_entry (id),
    CONSTRAINT CK_BUDGET_LINE_EVENT_TYPE_LINE CHECK (event_type_line_id IS NULL OR event_id IS NOT NULL)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_BUDGET_LINE_ON_REVIVAL ON budget_line (revival_id);

CREATE TABLE IF NOT EXISTS budget_line_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    category_id            VARCHAR(36)           NULL,
    description            VARCHAR(255)          NULL,
    quantity               DECIMAL(10, 2)        NULL,
    unit_amount            DECIMAL(12, 2)        NULL,
    event_id               BIGINT                NULL,
    log_entry_id           BIGINT                NULL,
    event_type_line_id     BIGINT                NULL,
    comment                VARCHAR(1000)         NULL,
    removed_at             DATETIME(6)           NULL,
    removed_by             BIGINT                NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_BUDGET_LINE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- "transaction" is a reserved word in MySQL
CREATE TABLE IF NOT EXISTS budget_transaction
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    date                   DATE                  NOT NULL,
    amount                 DECIMAL(12, 2)        NOT NULL,
    category_id            VARCHAR(36)           NOT NULL,
    description            VARCHAR(255)          NOT NULL,
    budget_line_id         BIGINT                NULL,
    paid_by_person_id      BIGINT                NULL,
    paid_by_name           VARCHAR(100)          NULL,
    reimbursed_at          DATETIME(6)           NULL,
    reimbursed_by          BIGINT                NULL,
    receipt_filename       VARCHAR(255)          NULL,
    receipt_content_type   VARCHAR(64)           NULL,
    receipt_size           INT                   NULL,
    receipt_uploaded_at    DATETIME(6)           NULL,
    cancelled_at           DATETIME(6)           NULL,
    cancelled_by           BIGINT                NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_BUDGET_TRANSACTION PRIMARY KEY (id),
    CONSTRAINT FK_BUDGET_TRANSACTION_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id),
    CONSTRAINT FK_BUDGET_TRANSACTION_ON_CATEGORY FOREIGN KEY (category_id) REFERENCES budget_category (id),
    CONSTRAINT FK_BUDGET_TRANSACTION_ON_BUDGET_LINE FOREIGN KEY (budget_line_id) REFERENCES budget_line (id),
    CONSTRAINT CK_BUDGET_TRANSACTION_AMOUNT CHECK (amount > 0),
    CONSTRAINT CK_BUDGET_TRANSACTION_PAID_BY CHECK (paid_by_person_id IS NULL OR paid_by_name IS NULL)
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_BUDGET_TRANSACTION_ON_REVIVAL ON budget_transaction (revival_id, date);

CREATE TABLE IF NOT EXISTS budget_transaction_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    date                   DATE                  NULL,
    amount                 DECIMAL(12, 2)        NULL,
    category_id            VARCHAR(36)           NULL,
    description            VARCHAR(255)          NULL,
    budget_line_id         BIGINT                NULL,
    paid_by_person_id      BIGINT                NULL,
    paid_by_name           VARCHAR(100)          NULL,
    reimbursed_at          DATETIME(6)           NULL,
    reimbursed_by          BIGINT                NULL,
    receipt_filename       VARCHAR(255)          NULL,
    receipt_content_type   VARCHAR(64)           NULL,
    receipt_size           INT                   NULL,
    receipt_uploaded_at    DATETIME(6)           NULL,
    cancelled_at           DATETIME(6)           NULL,
    cancelled_by           BIGINT                NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_BUDGET_TRANSACTION_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- A receipt's file, at most 10 MB, checked by the application. Not audited: the transaction's receipt columns record
-- every upload and removal.
CREATE TABLE IF NOT EXISTS receipt_content
(
    transaction_id         BIGINT                NOT NULL,
    content                LONGBLOB              NOT NULL,
    CONSTRAINT PK_RECEIPT_CONTENT PRIMARY KEY (transaction_id),
    CONSTRAINT FK_RECEIPT_CONTENT_ON_TRANSACTION FOREIGN KEY (transaction_id) REFERENCES budget_transaction (id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS budget_scenario
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    revival_id             BIGINT                NOT NULL,
    name                   VARCHAR(100)          NOT NULL,
    parameters             JSON                  NOT NULL,
    removed_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_BUDGET_SCENARIO PRIMARY KEY (id),
    CONSTRAINT FK_BUDGET_SCENARIO_ON_REVIVAL FOREIGN KEY (revival_id) REFERENCES revival (id)
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS budget_scenario_audit
(
    id                     BIGINT                NOT NULL,
    revival_id             BIGINT                NULL,
    name                   VARCHAR(100)          NULL,
    parameters             JSON                  NULL,
    removed_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_BUDGET_SCENARIO_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

-- Every existing revival gets its settings, with one baseline revision, so that they can be changed
INSERT INTO budget_settings (revival_id, created_by, created_at, version)
SELECT r.id, 'system', NOW(6), 0
FROM revival r
WHERE NOT EXISTS (SELECT 1 FROM budget_settings s WHERE s.revival_id = r.id);

INSERT INTO revinfo (modified_at, modified_by, source, operation)
VALUES (UNIX_TIMESTAMP(NOW(3)) * 1000, 'system', 'SYSTEM', 'budgetSettingsBaseline');

SET @rev = LAST_INSERT_ID();

INSERT INTO budget_settings_audit (id, revival_id, participants_planned, participation_fee, loan_amount, loan_received_on,
                                   loan_repaid_on, version, rev, revtype)
SELECT s.id, s.revival_id, s.participants_planned, s.participation_fee, s.loan_amount, s.loan_received_on, s.loan_repaid_on,
       s.version, @rev, 0
FROM budget_settings s
WHERE NOT EXISTS (SELECT 1 FROM budget_settings_audit a WHERE a.id = s.id);

INSERT INTO revchanges (rev, entityname)
VALUES (@rev, 'nu.fgv.revival.server.budget.BudgetSettings');
