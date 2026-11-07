ALTER TABLE transactions DROP CONSTRAINT chk_transactions_next_check_only_pending;

ALTER TABLE transactions ADD COLUMN action_requested_at TIMESTAMPTZ;

UPDATE transactions SET action_requested_at = now(), next_check_at = now(), check_attempts = 0
WHERE pending_action IS NOT NULL;

ALTER TABLE transactions ADD CONSTRAINT chk_transactions_next_check_only_open
    CHECK (next_check_at IS NULL OR status IN ('PENDING', 'AUTHORIZED'));
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_action_requested_with_pending_action
    CHECK ((pending_action IS NULL) = (action_requested_at IS NULL));

CREATE INDEX idx_transactions_pending_action_next_check ON transactions (next_check_at)
    WHERE pending_action IS NOT NULL;
