-- A decision log row can link to one of the revival's events.
ALTER TABLE log_entry
    ADD COLUMN event_id BIGINT NULL AFTER checklist_item_id,
    ADD CONSTRAINT FK_LOG_ENTRY_ON_EVENT FOREIGN KEY (event_id) REFERENCES event (id);

ALTER TABLE log_entry_audit
    ADD COLUMN event_id BIGINT NULL AFTER checklist_item_id;
