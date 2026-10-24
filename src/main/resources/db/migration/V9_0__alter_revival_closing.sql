-- M7: closing a revival (product spec 5.11) and anonymizing it (section 7). The year the revival pays FGV membership
-- for, Spexregister's id for the revival once it is known there, and when and by whom the revival was anonymized (NULL
-- by: automatically, 36 months after it was archived or rejected).

ALTER TABLE revival
    ADD COLUMN membership_year         SMALLINT              NULL,
    ADD COLUMN spexregister_revival_id BIGINT                NULL,
    ADD COLUMN anonymized_at           DATETIME(6)           NULL,
    ADD COLUMN anonymized_by           BIGINT                NULL,
    ADD CONSTRAINT FK_REVIVAL_ON_ANONYMIZED_BY FOREIGN KEY (anonymized_by) REFERENCES person (id);

CREATE INDEX IX_REVIVAL_ON_ANONYMIZED_AT ON revival (anonymized_at);

-- Spexregister's id is a technical link written by the sync, so not audited
ALTER TABLE revival_audit
    ADD COLUMN membership_year         SMALLINT              NULL,
    ADD COLUMN anonymized_at           DATETIME(6)           NULL,
    ADD COLUMN anonymized_by           BIGINT                NULL;

-- An anonymized person's external id is "anonymized-" and a random UUID, so the next login makes a new row
ALTER TABLE person
    MODIFY COLUMN external_id VARCHAR(64) NOT NULL;

-- M8 follow-up: whether a material's service was picked explicitly or recognised from the link's host
ALTER TABLE material
    ADD COLUMN service_chosen BOOLEAN NOT NULL DEFAULT FALSE AFTER service_id;

ALTER TABLE material_audit
    ADD COLUMN service_chosen BOOLEAN NULL AFTER service_id;
