-- A show's start time in Swedish time (Europe/Stockholm), null until it is decided, as existing shows are
ALTER TABLE revival_show
    ADD COLUMN start_time TIME NULL AFTER show_date;

ALTER TABLE revival_show_audit
    ADD COLUMN start_time TIME NULL AFTER show_date;
