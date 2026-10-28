ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(255);
ALTER TABLE transactions ADD COLUMN request_hash CHAR(64);

ALTER TABLE transactions ADD CONSTRAINT uk_transactions_merchant_idempotency_key
    UNIQUE (merchant_id, idempotency_key);

ALTER TABLE transactions ADD CONSTRAINT ck_transactions_idempotency_key_has_request_hash
    CHECK ((idempotency_key IS NULL) = (request_hash IS NULL));
