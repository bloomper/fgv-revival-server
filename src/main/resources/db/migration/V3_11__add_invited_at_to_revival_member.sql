-- When the application created a Keycloak user for a waiting member and Keycloak emailed them the invitation. Null
-- when the address already had an account, or Keycloak could not be reached or could not send the email.
ALTER TABLE revival_member
    ADD COLUMN invited_at DATETIME(6) NULL AFTER pending_email;

ALTER TABLE revival_member_audit
    ADD COLUMN invited_at DATETIME(6) NULL AFTER person_id;
