-- FGV memberships are Medlemskap's: the sync asks it for one payment request per revival (step PAYMENT) instead of
-- writing a membership per participant to Spexregister (step MEMBERSHIP, which Spexregister no longer accepts).
DELETE FROM sync_step WHERE operation = 'MEMBERSHIP';

ALTER TABLE sync_step DROP CHECK CK_SYNC_STEP_OPERATION;
ALTER TABLE sync_step ADD CONSTRAINT CK_SYNC_STEP_OPERATION CHECK (operation IN ('REVIVAL', 'PARTICIPATION', 'TASK', 'ACTOR', 'PAYMENT'));
