-- Lines an event type proposes for a revival's budget (product spec 5.10, M5-FR-3), e.g. food per participant for a
-- rehearsal weekend. Kept by the super admin; removing one deletes it, while budget lines made from it keep its id.
CREATE TABLE IF NOT EXISTS event_type_budget_line
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    event_type_id          BIGINT                NOT NULL,
    category_id            VARCHAR(36)           NOT NULL,
    description_sv         VARCHAR(255)          NOT NULL,
    description_en         VARCHAR(255)          NULL,
    quantity_rule          VARCHAR(16)           NOT NULL,
    quantity               DECIMAL(10, 2)        NULL,
    unit_amount            DECIMAL(12, 2)        NOT NULL,
    position               INT                   NOT NULL,
    created_by             VARCHAR(255)          NOT NULL,
    created_at             DATETIME(6)           NOT NULL,
    last_modified_by       VARCHAR(255)          NULL,
    last_modified_at       DATETIME(6)           NULL,
    version                BIGINT                NULL,
    CONSTRAINT PK_EVENT_TYPE_BUDGET_LINE PRIMARY KEY (id),
    CONSTRAINT FK_EVENT_TYPE_BUDGET_LINE_ON_EVENT_TYPE FOREIGN KEY (event_type_id) REFERENCES event_type (id),
    CONSTRAINT FK_EVENT_TYPE_BUDGET_LINE_ON_CATEGORY FOREIGN KEY (category_id) REFERENCES budget_category (id),
    CONSTRAINT CK_EVENT_TYPE_BUDGET_LINE_QUANTITY CHECK ((quantity_rule = 'FIXED' AND quantity IS NOT NULL)
        OR (quantity_rule = 'PER_PARTICIPANT' AND quantity IS NULL))
) DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS event_type_budget_line_audit
(
    id                     BIGINT                NOT NULL,
    event_type_id          BIGINT                NULL,
    category_id            VARCHAR(36)           NULL,
    description_sv         VARCHAR(255)          NULL,
    description_en         VARCHAR(255)          NULL,
    quantity_rule          VARCHAR(16)           NULL,
    quantity               DECIMAL(10, 2)        NULL,
    unit_amount            DECIMAL(12, 2)        NULL,
    position               INT                   NULL,
    version                BIGINT                NULL,
    rev                    BIGINT                NOT NULL REFERENCES revinfo (id),
    revend                 BIGINT                DEFAULT NULL REFERENCES revinfo (id),
    revtype                TINYINT               DEFAULT NULL,
    revend_tstmp           DATETIME(6)           DEFAULT NULL,

    KEY                    IX_rev                (rev),
    KEY                    IX_revend             (revend),

    CONSTRAINT PK_EVENT_TYPE_BUDGET_LINE_AUDIT PRIMARY KEY (id, rev)
) DEFAULT CHARSET = utf8mb4;
