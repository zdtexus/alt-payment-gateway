CREATE TABLE merchants (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    api_key     VARCHAR(255) NOT NULL UNIQUE,
    email       VARCHAR(255),
    phone       VARCHAR(32),
    status      VARCHAR(32)  NOT NULL
);

CREATE TABLE transactions (
    id              UUID PRIMARY KEY,
    merchant_id     UUID           NOT NULL REFERENCES merchants (id),
    amount          NUMERIC(19, 2) NOT NULL,
    currency        CHAR(3)        NOT NULL,
    payment_method  VARCHAR(32)    NOT NULL,
    status          VARCHAR(32)    NOT NULL,
    decline_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);

CREATE INDEX idx_transactions_merchant_created ON transactions (merchant_id, created_at DESC);
