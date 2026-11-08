# Database

The schema is managed by Flyway. Migrations are located in `payment-service/src/main/resources/db/migration` and are applied automatically on startup. Hibernate validates the mapping against the schema but never modifies it.

API keys are never stored. The database keeps only their SHA-256 hash (`api_key_hash`), and each request's `X-API-Key` is hashed and looked up by that hash. A database dump therefore doesn't reveal any merchant's key. New and rotated keys are 32 random bytes from `SecureRandom`, encoded as URL-safe Base64 (43 characters).

```text
merchants      id, name, api_key_hash, email, phone, status
transactions   id, merchant_id, amount, currency, payment_method, status,
               decline_reason, created_at, processed_at,
               idempotency_key, request_hash,
               provider, provider_reference,
               next_check_at, check_attempts,
               capture_method, authorized_amount, captured_amount,
               authorization_expires_at, pending_action, pending_capture_amount,
               action_idempotency_key, action_request_hash, action_requested_at
refunds        id, transaction_id, merchant_id, amount, status, reason,
               provider_reference, decline_reason, idempotency_key, request_hash,
               created_at, processed_at, next_check_at, check_attempts
```

Each transaction records which payment provider handled it (`provider`, for example `simulated`) and that provider's own transaction ID (`provider_reference`, empty if the provider returns none). Payments made before these columns existed are recorded as `simulated`. An index on (`provider`, `provider_reference`) serves the webhook lookup.

`next_check_at` is when a `PENDING` payment is next checked with its provider and `check_attempts` how often it has been checked (see [Re-checking pending payments](payments.md#re-checking-pending-payments)). A final payment has no `next_check_at`; the database enforces this with a check constraint, and a partial index over `PENDING` rows lets the job find due payments quickly.

Manual capture is enforced by check constraints too: `capture_method` is `AUTOMATIC` (existing payments, and the default) or `MANUAL`; an `AUTOMATIC` payment has no authorization columns; the captured amount is above 0 and at most the authorized one; a `pending_action` (`CAPTURE` or `VOID`) exists only on an `AUTHORIZED` payment, always with `action_requested_at`, and a pending capture's amount is within the authorization; an action's idempotency key is stored with its request hash. `next_check_at` may be set only on `PENDING` and `AUTHORIZED` payments (pending captures and voids, and the retry of an expiry void). Partial indexes find authorizations by expiry and pending actions by due time.

A refund belongs to one payment and that payment's merchant: the foreign key covers (`transaction_id`, `merchant_id`) together, so a refund can't be stored for another merchant's payment. The amount must be greater than 0, an idempotency key is unique per merchant and always stored with its request hash, and the same `next_check_at` rule and partial indexes as for payments apply.

To reset the local database:

```bash
docker compose down -v
```
