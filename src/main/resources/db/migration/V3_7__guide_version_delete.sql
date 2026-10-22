-- An unused published version may be deleted. The record of which versions a revival followed keeps their labels, so
-- it still reads after a version it names is gone.
ALTER TABLE revival_guide_version_change
    DROP FOREIGN KEY FK_REVIVAL_GUIDE_VERSION_CHANGE_ON_FROM,
    DROP FOREIGN KEY FK_REVIVAL_GUIDE_VERSION_CHANGE_ON_TO;

ALTER TABLE revival_guide_version_change
    MODIFY COLUMN to_version_id BIGINT NULL,
    ADD COLUMN from_label VARCHAR(32) NULL AFTER to_version_id,
    ADD COLUMN to_label VARCHAR(32) NULL AFTER from_label;

UPDATE revival_guide_version_change c
    LEFT JOIN guide_version f ON f.id = c.from_version_id
    JOIN guide_version t ON t.id = c.to_version_id
SET c.from_label = f.label,
    c.to_label   = t.label;

ALTER TABLE revival_guide_version_change
    MODIFY COLUMN to_label VARCHAR(32) NOT NULL,
    ADD CONSTRAINT FK_REVIVAL_GUIDE_VERSION_CHANGE_ON_FROM FOREIGN KEY (from_version_id) REFERENCES guide_version (id) ON DELETE SET NULL,
    ADD CONSTRAINT FK_REVIVAL_GUIDE_VERSION_CHANGE_ON_TO FOREIGN KEY (to_version_id) REFERENCES guide_version (id) ON DELETE SET NULL;

ALTER TABLE revival_guide_version_change_audit
    ADD COLUMN from_label VARCHAR(32) NULL AFTER to_version_id,
    ADD COLUMN to_label VARCHAR(32) NULL AFTER from_label;
