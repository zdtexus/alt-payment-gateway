CREATE INDEX idx_transactions_provider_reference ON transactions (provider, provider_reference)
    WHERE provider_reference IS NOT NULL;
