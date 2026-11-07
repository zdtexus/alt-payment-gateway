ALTER TABLE transactions ADD COLUMN next_check_at TIMESTAMPTZ;
ALTER TABLE transactions ADD COLUMN check_attempts INT NOT NULL DEFAULT 0;

UPDATE transactions SET next_check_at = now() WHERE status = 'PENDING';

ALTER TABLE transactions ADD CONSTRAINT chk_transactions_next_check_only_pending
    CHECK (status = 'PENDING' OR next_check_at IS NULL);
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_check_attempts_not_negative
    CHECK (check_attempts >= 0);

CREATE INDEX idx_transactions_pending_next_check ON transactions (next_check_at)
    WHERE status = 'PENDING';
