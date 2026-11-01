-- M11: every entity that leaves the system is known by a stable external id; the BIGINT id stays internal. The default
-- fills in rows inserted without one, such as the sample data's.

ALTER TABLE availability ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE availability SET uuid = UUID();
ALTER TABLE availability MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_AVAILABILITY_UUID UNIQUE (uuid);
ALTER TABLE availability_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE availability_audit a JOIN availability e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM availability_audit WHERE uuid IS NULL) d;
UPDATE availability_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_AVAILABILITY_AUDIT_ON_UUID ON availability_audit (uuid);

ALTER TABLE budget_line ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE budget_line SET uuid = UUID();
ALTER TABLE budget_line MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_BUDGET_LINE_UUID UNIQUE (uuid);
ALTER TABLE budget_line_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE budget_line_audit a JOIN budget_line e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM budget_line_audit WHERE uuid IS NULL) d;
UPDATE budget_line_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_BUDGET_LINE_AUDIT_ON_UUID ON budget_line_audit (uuid);

ALTER TABLE budget_scenario ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE budget_scenario SET uuid = UUID();
ALTER TABLE budget_scenario MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_BUDGET_SCENARIO_UUID UNIQUE (uuid);
ALTER TABLE budget_scenario_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE budget_scenario_audit a JOIN budget_scenario e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM budget_scenario_audit WHERE uuid IS NULL) d;
UPDATE budget_scenario_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_BUDGET_SCENARIO_AUDIT_ON_UUID ON budget_scenario_audit (uuid);

ALTER TABLE budget_settings ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE budget_settings SET uuid = UUID();
ALTER TABLE budget_settings MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_BUDGET_SETTINGS_UUID UNIQUE (uuid);
ALTER TABLE budget_settings_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE budget_settings_audit a JOIN budget_settings e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM budget_settings_audit WHERE uuid IS NULL) d;
UPDATE budget_settings_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_BUDGET_SETTINGS_AUDIT_ON_UUID ON budget_settings_audit (uuid);

ALTER TABLE budget_transaction ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE budget_transaction SET uuid = UUID();
ALTER TABLE budget_transaction MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_BUDGET_TRANSACTION_UUID UNIQUE (uuid);
ALTER TABLE budget_transaction_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE budget_transaction_audit a JOIN budget_transaction e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM budget_transaction_audit WHERE uuid IS NULL) d;
UPDATE budget_transaction_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_BUDGET_TRANSACTION_AUDIT_ON_UUID ON budget_transaction_audit (uuid);

ALTER TABLE calendar_feed ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE calendar_feed SET uuid = UUID();
ALTER TABLE calendar_feed MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_CALENDAR_FEED_UUID UNIQUE (uuid);

ALTER TABLE checklist ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE checklist SET uuid = UUID();
ALTER TABLE checklist MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_CHECKLIST_UUID UNIQUE (uuid);
ALTER TABLE checklist_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE checklist_audit a JOIN checklist e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM checklist_audit WHERE uuid IS NULL) d;
UPDATE checklist_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_CHECKLIST_AUDIT_ON_UUID ON checklist_audit (uuid);

ALTER TABLE checklist_item ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE checklist_item SET uuid = UUID();
ALTER TABLE checklist_item MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_CHECKLIST_ITEM_UUID UNIQUE (uuid);
ALTER TABLE checklist_item_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE checklist_item_audit a JOIN checklist_item e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM checklist_item_audit WHERE uuid IS NULL) d;
UPDATE checklist_item_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_CHECKLIST_ITEM_AUDIT_ON_UUID ON checklist_item_audit (uuid);

ALTER TABLE checklist_template ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE checklist_template SET uuid = UUID();
ALTER TABLE checklist_template MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_CHECKLIST_TEMPLATE_UUID UNIQUE (uuid);
ALTER TABLE checklist_template_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE checklist_template_audit a JOIN checklist_template e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM checklist_template_audit WHERE uuid IS NULL) d;
UPDATE checklist_template_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_CHECKLIST_TEMPLATE_AUDIT_ON_UUID ON checklist_template_audit (uuid);

ALTER TABLE checklist_template_item ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE checklist_template_item SET uuid = UUID();
ALTER TABLE checklist_template_item MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_CHECKLIST_TEMPLATE_ITEM_UUID UNIQUE (uuid);
ALTER TABLE checklist_template_item_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE checklist_template_item_audit a JOIN checklist_template_item e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM checklist_template_item_audit WHERE uuid IS NULL) d;
UPDATE checklist_template_item_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_CHECKLIST_TEMPLATE_ITEM_AUDIT_ON_UUID ON checklist_template_item_audit (uuid);

ALTER TABLE event ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE event SET uuid = UUID();
ALTER TABLE event MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_EVENT_UUID UNIQUE (uuid);
ALTER TABLE event_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE event_audit a JOIN event e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM event_audit WHERE uuid IS NULL) d;
UPDATE event_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_EVENT_AUDIT_ON_UUID ON event_audit (uuid);

ALTER TABLE event_type ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE event_type SET uuid = UUID();
ALTER TABLE event_type MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_EVENT_TYPE_UUID UNIQUE (uuid);
ALTER TABLE event_type_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE event_type_audit a JOIN event_type e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM event_type_audit WHERE uuid IS NULL) d;
UPDATE event_type_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_EVENT_TYPE_AUDIT_ON_UUID ON event_type_audit (uuid);

ALTER TABLE event_type_budget_line ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE event_type_budget_line SET uuid = UUID();
ALTER TABLE event_type_budget_line MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_EVENT_TYPE_BUDGET_LINE_UUID UNIQUE (uuid);
ALTER TABLE event_type_budget_line_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE event_type_budget_line_audit a JOIN event_type_budget_line e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM event_type_budget_line_audit WHERE uuid IS NULL) d;
UPDATE event_type_budget_line_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_EVENT_TYPE_BUDGET_LINE_AUDIT_ON_UUID ON event_type_budget_line_audit (uuid);

ALTER TABLE guide_chapter ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_chapter SET uuid = UUID();
ALTER TABLE guide_chapter MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_GUIDE_CHAPTER_UUID UNIQUE (uuid);
ALTER TABLE guide_chapter_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_chapter_audit a JOIN guide_chapter e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM guide_chapter_audit WHERE uuid IS NULL) d;
UPDATE guide_chapter_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_GUIDE_CHAPTER_AUDIT_ON_UUID ON guide_chapter_audit (uuid);

ALTER TABLE guide_item ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_item SET uuid = UUID();
ALTER TABLE guide_item MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_GUIDE_ITEM_UUID UNIQUE (uuid);
ALTER TABLE guide_item_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_item_audit a JOIN guide_item e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM guide_item_audit WHERE uuid IS NULL) d;
UPDATE guide_item_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_GUIDE_ITEM_AUDIT_ON_UUID ON guide_item_audit (uuid);

ALTER TABLE guide_section ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_section SET uuid = UUID();
ALTER TABLE guide_section MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_GUIDE_SECTION_UUID UNIQUE (uuid);
ALTER TABLE guide_section_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_section_audit a JOIN guide_section e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM guide_section_audit WHERE uuid IS NULL) d;
UPDATE guide_section_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_GUIDE_SECTION_AUDIT_ON_UUID ON guide_section_audit (uuid);

ALTER TABLE guide_suggestion ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_suggestion SET uuid = UUID();
ALTER TABLE guide_suggestion MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_GUIDE_SUGGESTION_UUID UNIQUE (uuid);
ALTER TABLE guide_suggestion_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_suggestion_audit a JOIN guide_suggestion e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM guide_suggestion_audit WHERE uuid IS NULL) d;
UPDATE guide_suggestion_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_GUIDE_SUGGESTION_AUDIT_ON_UUID ON guide_suggestion_audit (uuid);

ALTER TABLE guide_version ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_version SET uuid = UUID();
ALTER TABLE guide_version MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_GUIDE_VERSION_UUID UNIQUE (uuid);
ALTER TABLE guide_version_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE guide_version_audit a JOIN guide_version e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM guide_version_audit WHERE uuid IS NULL) d;
UPDATE guide_version_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_GUIDE_VERSION_AUDIT_ON_UUID ON guide_version_audit (uuid);

ALTER TABLE inventory_item ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE inventory_item SET uuid = UUID();
ALTER TABLE inventory_item MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_INVENTORY_ITEM_UUID UNIQUE (uuid);
ALTER TABLE inventory_item_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE inventory_item_audit a JOIN inventory_item e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM inventory_item_audit WHERE uuid IS NULL) d;
UPDATE inventory_item_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_INVENTORY_ITEM_AUDIT_ON_UUID ON inventory_item_audit (uuid);

ALTER TABLE library_entry ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE library_entry SET uuid = UUID();
ALTER TABLE library_entry MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_LIBRARY_ENTRY_UUID UNIQUE (uuid);
ALTER TABLE library_entry_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE library_entry_audit a JOIN library_entry e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM library_entry_audit WHERE uuid IS NULL) d;
UPDATE library_entry_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_LIBRARY_ENTRY_AUDIT_ON_UUID ON library_entry_audit (uuid);

ALTER TABLE log_entry ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE log_entry SET uuid = UUID();
ALTER TABLE log_entry MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_LOG_ENTRY_UUID UNIQUE (uuid);
ALTER TABLE log_entry_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE log_entry_audit a JOIN log_entry e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM log_entry_audit WHERE uuid IS NULL) d;
UPDATE log_entry_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_LOG_ENTRY_AUDIT_ON_UUID ON log_entry_audit (uuid);

ALTER TABLE mailing ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE mailing SET uuid = UUID();
ALTER TABLE mailing MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_MAILING_UUID UNIQUE (uuid);
ALTER TABLE mailing_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE mailing_audit a JOIN mailing e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM mailing_audit WHERE uuid IS NULL) d;
UPDATE mailing_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_MAILING_AUDIT_ON_UUID ON mailing_audit (uuid);

ALTER TABLE mailing_recipient ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE mailing_recipient SET uuid = UUID();
ALTER TABLE mailing_recipient MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_MAILING_RECIPIENT_UUID UNIQUE (uuid);
ALTER TABLE mailing_recipient_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE mailing_recipient_audit a JOIN mailing_recipient e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM mailing_recipient_audit WHERE uuid IS NULL) d;
UPDATE mailing_recipient_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_MAILING_RECIPIENT_AUDIT_ON_UUID ON mailing_recipient_audit (uuid);

ALTER TABLE mailing_reminder ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE mailing_reminder SET uuid = UUID();
ALTER TABLE mailing_reminder MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_MAILING_REMINDER_UUID UNIQUE (uuid);

ALTER TABLE mailing_template ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE mailing_template SET uuid = UUID();
ALTER TABLE mailing_template MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_MAILING_TEMPLATE_UUID UNIQUE (uuid);
ALTER TABLE mailing_template_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE mailing_template_audit a JOIN mailing_template e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM mailing_template_audit WHERE uuid IS NULL) d;
UPDATE mailing_template_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_MAILING_TEMPLATE_AUDIT_ON_UUID ON mailing_template_audit (uuid);

ALTER TABLE material ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE material SET uuid = UUID();
ALTER TABLE material MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_MATERIAL_UUID UNIQUE (uuid);
ALTER TABLE material_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE material_audit a JOIN material e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM material_audit WHERE uuid IS NULL) d;
UPDATE material_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_MATERIAL_AUDIT_ON_UUID ON material_audit (uuid);

ALTER TABLE meeting ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE meeting SET uuid = UUID();
ALTER TABLE meeting MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_MEETING_UUID UNIQUE (uuid);
ALTER TABLE meeting_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE meeting_audit a JOIN meeting e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM meeting_audit WHERE uuid IS NULL) d;
UPDATE meeting_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_MEETING_AUDIT_ON_UUID ON meeting_audit (uuid);

ALTER TABLE meeting_entry_change ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE meeting_entry_change SET uuid = UUID();
ALTER TABLE meeting_entry_change MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_MEETING_ENTRY_CHANGE_UUID UNIQUE (uuid);

ALTER TABLE minutes_email ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE minutes_email SET uuid = UUID();
ALTER TABLE minutes_email MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_MINUTES_EMAIL_UUID UNIQUE (uuid);

ALTER TABLE news ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE news SET uuid = UUID();
ALTER TABLE news MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_NEWS_UUID UNIQUE (uuid);
ALTER TABLE news_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE news_audit a JOIN news e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM news_audit WHERE uuid IS NULL) d;
UPDATE news_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_NEWS_AUDIT_ON_UUID ON news_audit (uuid);

ALTER TABLE participant ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE participant SET uuid = UUID();
ALTER TABLE participant MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_PARTICIPANT_UUID UNIQUE (uuid);
ALTER TABLE participant_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE participant_audit a JOIN participant e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM participant_audit WHERE uuid IS NULL) d;
UPDATE participant_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_PARTICIPANT_AUDIT_ON_UUID ON participant_audit (uuid);

ALTER TABLE participation_settings ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE participation_settings SET uuid = UUID();
ALTER TABLE participation_settings MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_PARTICIPATION_SETTINGS_UUID UNIQUE (uuid);
ALTER TABLE participation_settings_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE participation_settings_audit a JOIN participation_settings e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM participation_settings_audit WHERE uuid IS NULL) d;
UPDATE participation_settings_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_PARTICIPATION_SETTINGS_AUDIT_ON_UUID ON participation_settings_audit (uuid);

ALTER TABLE person ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE person SET uuid = UUID();
ALTER TABLE person MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_PERSON_UUID UNIQUE (uuid);

ALTER TABLE reference_figure ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE reference_figure SET uuid = UUID();
ALTER TABLE reference_figure MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_REFERENCE_FIGURE_UUID UNIQUE (uuid);
ALTER TABLE reference_figure_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE reference_figure_audit a JOIN reference_figure e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM reference_figure_audit WHERE uuid IS NULL) d;
UPDATE reference_figure_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_REFERENCE_FIGURE_AUDIT_ON_UUID ON reference_figure_audit (uuid);

ALTER TABLE reference_revival ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE reference_revival SET uuid = UUID();
ALTER TABLE reference_revival MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_REFERENCE_REVIVAL_UUID UNIQUE (uuid);
ALTER TABLE reference_revival_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE reference_revival_audit a JOIN reference_revival e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM reference_revival_audit WHERE uuid IS NULL) d;
UPDATE reference_revival_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_REFERENCE_REVIVAL_AUDIT_ON_UUID ON reference_revival_audit (uuid);

ALTER TABLE referral ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE referral SET uuid = UUID();
ALTER TABLE referral MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_REFERRAL_UUID UNIQUE (uuid);

ALTER TABLE revival ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival SET uuid = UUID();
ALTER TABLE revival MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_REVIVAL_UUID UNIQUE (uuid);
ALTER TABLE revival_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_audit a JOIN revival e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM revival_audit WHERE uuid IS NULL) d;
UPDATE revival_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_REVIVAL_AUDIT_ON_UUID ON revival_audit (uuid);

ALTER TABLE revival_guide_version_change ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_guide_version_change SET uuid = UUID();
ALTER TABLE revival_guide_version_change MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_REVIVAL_GUIDE_VERSION_CHANGE_UUID UNIQUE (uuid);
ALTER TABLE revival_guide_version_change_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_guide_version_change_audit a JOIN revival_guide_version_change e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM revival_guide_version_change_audit WHERE uuid IS NULL) d;
UPDATE revival_guide_version_change_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_REVIVAL_GUIDE_VERSION_CHANGE_AUDIT_ON_UUID ON revival_guide_version_change_audit (uuid);

ALTER TABLE revival_member ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_member SET uuid = UUID();
ALTER TABLE revival_member MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_REVIVAL_MEMBER_UUID UNIQUE (uuid);
ALTER TABLE revival_member_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_member_audit a JOIN revival_member e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM revival_member_audit WHERE uuid IS NULL) d;
UPDATE revival_member_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_REVIVAL_MEMBER_AUDIT_ON_UUID ON revival_member_audit (uuid);

ALTER TABLE revival_show ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_show SET uuid = UUID();
ALTER TABLE revival_show MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_REVIVAL_SHOW_UUID UNIQUE (uuid);
ALTER TABLE revival_show_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_show_audit a JOIN revival_show e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM revival_show_audit WHERE uuid IS NULL) d;
UPDATE revival_show_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_REVIVAL_SHOW_AUDIT_ON_UUID ON revival_show_audit (uuid);

ALTER TABLE revival_status_change ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_status_change SET uuid = UUID();
ALTER TABLE revival_status_change MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_REVIVAL_STATUS_CHANGE_UUID UNIQUE (uuid);
ALTER TABLE revival_status_change_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE revival_status_change_audit a JOIN revival_status_change e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM revival_status_change_audit WHERE uuid IS NULL) d;
UPDATE revival_status_change_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_REVIVAL_STATUS_CHANGE_AUDIT_ON_UUID ON revival_status_change_audit (uuid);

ALTER TABLE shift_assignment ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE shift_assignment SET uuid = UUID();
ALTER TABLE shift_assignment MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_SHIFT_ASSIGNMENT_UUID UNIQUE (uuid);
ALTER TABLE shift_assignment_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE shift_assignment_audit a JOIN shift_assignment e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM shift_assignment_audit WHERE uuid IS NULL) d;
UPDATE shift_assignment_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_SHIFT_ASSIGNMENT_AUDIT_ON_UUID ON shift_assignment_audit (uuid);

ALTER TABLE shift_slot ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE shift_slot SET uuid = UUID();
ALTER TABLE shift_slot MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_SHIFT_SLOT_UUID UNIQUE (uuid);
ALTER TABLE shift_slot_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE shift_slot_audit a JOIN shift_slot e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM shift_slot_audit WHERE uuid IS NULL) d;
UPDATE shift_slot_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_SHIFT_SLOT_AUDIT_ON_UUID ON shift_slot_audit (uuid);

ALTER TABLE spex_calendar_date ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE spex_calendar_date SET uuid = UUID();
ALTER TABLE spex_calendar_date MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_SPEX_CALENDAR_DATE_UUID UNIQUE (uuid);
ALTER TABLE spex_calendar_date_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE spex_calendar_date_audit a JOIN spex_calendar_date e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM spex_calendar_date_audit WHERE uuid IS NULL) d;
UPDATE spex_calendar_date_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_SPEX_CALENDAR_DATE_AUDIT_ON_UUID ON spex_calendar_date_audit (uuid);

ALTER TABLE sync_run ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE sync_run SET uuid = UUID();
ALTER TABLE sync_run MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_SYNC_RUN_UUID UNIQUE (uuid);
ALTER TABLE sync_run_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE sync_run_audit a JOIN sync_run e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM sync_run_audit WHERE uuid IS NULL) d;
UPDATE sync_run_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_SYNC_RUN_AUDIT_ON_UUID ON sync_run_audit (uuid);

ALTER TABLE sync_step ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE sync_step SET uuid = UUID();
ALTER TABLE sync_step MODIFY uuid CHAR(36) NOT NULL DEFAULT (UUID()), ADD CONSTRAINT UC_SYNC_STEP_UUID UNIQUE (uuid);
ALTER TABLE sync_step_audit ADD COLUMN uuid CHAR(36) NULL AFTER id;
UPDATE sync_step_audit a JOIN sync_step e ON a.id = e.id SET a.uuid = e.uuid;
CREATE TEMPORARY TABLE deleted_uuid AS SELECT d.id, UUID() AS uuid FROM (SELECT DISTINCT id FROM sync_step_audit WHERE uuid IS NULL) d;
UPDATE sync_step_audit a JOIN deleted_uuid g ON a.id = g.id SET a.uuid = g.uuid;
DROP TEMPORARY TABLE deleted_uuid;
CREATE INDEX IX_SYNC_STEP_AUDIT_ON_UUID ON sync_step_audit (uuid);

-- Spexregister's spex, spexare and revivals are referred to by their external ids, like its tasks and vocal parts
-- already are. Numeric ids left from before point at nothing and are set again from fresh sample data.
ALTER TABLE revival MODIFY spex_id VARCHAR(36) NOT NULL, MODIFY spexregister_revival_id VARCHAR(36) NULL;
ALTER TABLE revival_audit MODIFY spex_id VARCHAR(36) NULL;

ALTER TABLE participant
    MODIFY spexare_id VARCHAR(36) NULL,
    MODIFY active_spexare_id VARCHAR(36) AS (IF(removed_at IS NULL, spexare_id, NULL)) STORED;
ALTER TABLE participant_audit MODIFY spexare_id VARCHAR(36) NULL;
