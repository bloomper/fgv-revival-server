-- Budget categories (product spec 5.10, M5-FR-1): a list the super admin keeps, as capabilities and guide item types.
-- A category's kind, income or cost, is fixed when it is created. Its role says what it stands for in scenarios; at
-- most one active category has each role, which the application checks. The seeded ones keep their names as ids.
CREATE TABLE IF NOT EXISTS budget_category
(
    id                     VARCHAR(36)           NOT NULL,
    kind                   VARCHAR(16)           NOT NULL,
    role                   VARCHAR(16)           NULL,
    name_sv                VARCHAR(100)          NOT NULL,
    name_en                VARCHAR(100)          NULL,
    position               INT                   NOT NULL,
    retired_at             DATETIME(6)           NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_BUDGET_CATEGORY PRIMARY KEY (id),
    CONSTRAINT CK_BUDGET_CATEGORY_KIND CHECK (kind IN ('INCOME', 'COST')),
    CONSTRAINT CK_BUDGET_CATEGORY_ROLE CHECK (role IS NULL
        OR (kind = 'INCOME' AND role IN ('SHOW_TICKETS', 'PARTY_TICKETS', 'FEES'))
        OR (kind = 'COST' AND role IN ('VENUE', 'PARTY', 'HELPERS')))
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS budget_category_audit
(
    id                     VARCHAR(36)           NOT NULL,
    kind                   VARCHAR(16)           NULL,
    role                   VARCHAR(16)           NULL,
    name_sv                VARCHAR(100)          NULL,
    name_en                VARCHAR(100)          NULL,
    position               INT                   NULL,
    retired_at             DATETIME(6)           NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_BUDGET_CATEGORY_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;

INSERT INTO budget_category (id, kind, role, name_sv, name_en, position, created_by, created_at, version)
VALUES ('SHOW_TICKETS', 'INCOME', 'SHOW_TICKETS', 'Biljetter föreställning', 'Show tickets', 0, 'system', NOW(6), 0),
       ('PARTY_TICKETS', 'INCOME', 'PARTY_TICKETS', 'Biljetter kalas', 'Party tickets', 1, 'system', NOW(6), 0),
       ('FEES', 'INCOME', 'FEES', 'Spexpengar', 'Participation fees', 2, 'system', NOW(6), 0),
       ('SPONSORSHIP', 'INCOME', NULL, 'Sponsring', 'Sponsorship', 3, 'system', NOW(6), 0),
       ('OTHER_INCOME', 'INCOME', NULL, 'Övriga intäkter', 'Other income', 4, 'system', NOW(6), 0),
       ('VENUE', 'COST', 'VENUE', 'Lokaler', 'Venues', 5, 'system', NOW(6), 0),
       ('FOOD', 'COST', NULL, 'Mat och dryck', 'Food and drink', 6, 'system', NOW(6), 0),
       ('PARTY', 'COST', 'PARTY', 'Kalas', 'Party', 7, 'system', NOW(6), 0),
       ('TECHNICAL', 'COST', NULL, 'Teknik', 'Technical', 8, 'system', NOW(6), 0),
       ('COSTUMES', 'COST', NULL, 'Kostym och smink', 'Costumes and make-up', 9, 'system', NOW(6), 0),
       ('SCENERY', 'COST', NULL, 'Rekvisita och kulisser', 'Props and scenery', 10, 'system', NOW(6), 0),
       ('MARKETING', 'COST', NULL, 'Marknadsföring', 'Marketing', 11, 'system', NOW(6), 0),
       ('PRINTING', 'COST', NULL, 'Tryck', 'Printing', 12, 'system', NOW(6), 0),
       ('HELPERS', 'COST', 'HELPERS', 'Springare', 'External helpers', 13, 'system', NOW(6), 0),
       ('ADMIN', 'COST', NULL, 'Administration', 'Administration', 14, 'system', NOW(6), 0),
       ('OTHER_COST', 'COST', NULL, 'Övriga kostnader', 'Other costs', 15, 'system', NOW(6), 0);

-- The validity audit strategy refuses to change a row without history ("Cannot update previous revision"), so the
-- seeded categories get one baseline revision, as in V3_9
INSERT INTO revinfo (modified_at, modified_by, source, operation)
VALUES (UNIX_TIMESTAMP(NOW(3)) * 1000, 'system', 'SYSTEM', 'budgetCategoryBaseline');

SET @rev = LAST_INSERT_ID();

INSERT INTO budget_category_audit (id, kind, role, name_sv, name_en, position, retired_at, version, rev, revtype)
SELECT c.id, c.kind, c.role, c.name_sv, c.name_en, c.position, c.retired_at, c.version, @rev, 0
FROM budget_category c
WHERE NOT EXISTS (SELECT 1 FROM budget_category_audit a WHERE a.id = c.id);

INSERT INTO revchanges (rev, entityname)
VALUES (@rev, 'nu.fgv.revival.server.budget.BudgetCategory');
