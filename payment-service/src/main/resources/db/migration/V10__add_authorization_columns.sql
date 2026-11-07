ALTER TABLE transactions ADD COLUMN capture_method VARCHAR(16) NOT NULL DEFAULT 'AUTOMATIC';
ALTER TABLE transactions ADD COLUMN authorized_amount NUMERIC(19, 2);
ALTER TABLE transactions ADD COLUMN captured_amount NUMERIC(19, 2);
ALTER TABLE transactions ADD COLUMN authorization_expires_at TIMESTAMPTZ;

ALTER TABLE transactions ADD CONSTRAINT ck_transactions_capture_method
    CHECK (capture_method IN ('AUTOMATIC', 'MANUAL'));
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_automatic_has_no_authorization
    CHECK (capture_method = 'MANUAL'
        OR (authorized_amount IS NULL AND captured_amount IS NULL AND authorization_expires_at IS NULL));
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_authorization_amounts
    CHECK ((authorized_amount IS NULL OR authorized_amount > 0)
        AND (captured_amount IS NULL OR (captured_amount > 0 AND authorized_amount IS NOT NULL
            AND captured_amount <= authorized_amount)));

CREATE INDEX idx_transactions_authorized_expiry ON transactions (authorization_expires_at)
    WHERE status = 'AUTHORIZED';
