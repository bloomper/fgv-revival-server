-- Each item from a template keeps its own copy of the relative deadline, so changing the template later leaves
-- existing checklists alone (M3 FR-032)
ALTER TABLE checklist_item
    ADD COLUMN deadline_kind    VARCHAR(24) NULL AFTER deadline_manual,
    ADD COLUMN deadline_amount  INT         NULL AFTER deadline_kind,
    ADD COLUMN deadline_weekday VARCHAR(9)  NULL AFTER deadline_amount,
    ADD CONSTRAINT CK_CHECKLIST_ITEM_DEADLINE CHECK (deadline_kind IN ('DAYS', 'WORKING_DAYS', 'WEEKDAY_WEEK_BEFORE'));

ALTER TABLE checklist_item_audit
    ADD COLUMN deadline_kind    VARCHAR(24) NULL AFTER deadline_manual,
    ADD COLUMN deadline_amount  INT         NULL AFTER deadline_kind,
    ADD COLUMN deadline_weekday VARCHAR(9)  NULL AFTER deadline_amount;

UPDATE checklist_item item
    JOIN checklist_template_item template_item ON template_item.id = item.template_item_id
SET item.deadline_kind    = template_item.deadline_kind,
    item.deadline_amount  = template_item.deadline_amount,
    item.deadline_weekday = template_item.deadline_weekday;
