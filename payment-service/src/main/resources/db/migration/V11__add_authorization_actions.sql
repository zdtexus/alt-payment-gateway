ALTER TABLE transactions ADD COLUMN pending_action VARCHAR(16);
ALTER TABLE transactions ADD COLUMN pending_capture_amount NUMERIC(19, 2);
ALTER TABLE transactions ADD COLUMN action_idempotency_key VARCHAR(255);
ALTER TABLE transactions ADD COLUMN action_request_hash CHAR(64);

ALTER TABLE transactions ADD CONSTRAINT ck_transactions_pending_action
    CHECK (pending_action IS NULL OR (pending_action IN ('CAPTURE', 'VOID') AND status = 'AUTHORIZED'));
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_pending_capture_amount
    CHECK (CASE WHEN pending_action = 'CAPTURE'
                THEN pending_capture_amount IS NOT NULL AND pending_capture_amount > 0
                     AND authorized_amount IS NOT NULL AND pending_capture_amount <= authorized_amount
                ELSE pending_capture_amount IS NULL END);
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_action_key_has_request_hash
    CHECK ((action_idempotency_key IS NULL) = (action_request_hash IS NULL));
