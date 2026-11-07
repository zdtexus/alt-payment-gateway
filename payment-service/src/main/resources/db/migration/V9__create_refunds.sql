ALTER TABLE transactions ADD CONSTRAINT uk_transactions_id_merchant UNIQUE (id, merchant_id);

CREATE TABLE refunds (
    id                 UUID           PRIMARY KEY,
    transaction_id     UUID           NOT NULL,
    merchant_id        UUID           NOT NULL REFERENCES merchants (id),
    amount             NUMERIC(19, 2) NOT NULL,
    status             VARCHAR(32)    NOT NULL,
    reason             VARCHAR(255),
    provider_reference VARCHAR(255),
    decline_reason     VARCHAR(255),
    idempotency_key    VARCHAR(255),
    request_hash       CHAR(64),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,
    next_check_at      TIMESTAMPTZ,
    check_attempts     INT            NOT NULL DEFAULT 0,
    CONSTRAINT fk_refunds_transaction_of_merchant FOREIGN KEY (transaction_id, merchant_id)
        REFERENCES transactions (id, merchant_id),
    CONSTRAINT ck_refunds_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'DECLINED')),
    CONSTRAINT uk_refunds_merchant_idempotency_key UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT ck_refunds_idempotency_key_has_request_hash
        CHECK ((idempotency_key IS NULL) = (request_hash IS NULL)),
    CONSTRAINT chk_refunds_next_check_only_pending CHECK (status = 'PENDING' OR next_check_at IS NULL),
    CONSTRAINT chk_refunds_check_attempts_not_negative CHECK (check_attempts >= 0)
);

CREATE INDEX idx_refunds_transaction_created ON refunds (transaction_id, created_at);

CREATE INDEX idx_refunds_provider_reference ON refunds (provider_reference)
    WHERE provider_reference IS NOT NULL;

CREATE INDEX idx_refunds_pending_next_check ON refunds (next_check_at)
    WHERE status = 'PENDING';
