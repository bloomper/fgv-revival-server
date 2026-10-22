-- A revival is named after its spex and year, so one spex is revived at most once a year
ALTER TABLE revival
    DROP COLUMN working_title,
    ADD CONSTRAINT UC_REVIVAL_SPEX_YEAR UNIQUE (spex_id, year);

ALTER TABLE revival_audit
    DROP COLUMN working_title;
