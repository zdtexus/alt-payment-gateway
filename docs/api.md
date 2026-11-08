# API

All payment requests require the `X-API-Key` header. A merchant can only access its own payments. Provider webhooks are the exception: they are called by payment providers and signed instead (see [Provider webhooks](providers.md#provider-webhooks)). Merchants and their keys are managed through the [admin API](#merchant-administration).

The endpoints are listed in the [README](../README.md#api), and Swagger UI (http://localhost:8080/swagger-ui.html) has the full reference. Payments, refunds and manual capture are explained in [Payments](payments.md).

- [Merchant administration](#merchant-administration)
- [Errors](#errors)

## Merchant administration

Admin endpoints manage merchants and require the `X-Admin-Key` header with the value of `ADMIN_API_KEY`. The two keys are separate: a merchant key never opens `/admin/**`, and the admin key never opens `/api/**`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/admin/v1/merchants` | Create an `ACTIVE` merchant and return its API key |
| `PATCH` | `/admin/v1/merchants/{id}` | Suspend or reactivate a merchant |
| `POST` | `/admin/v1/merchants/{id}/api-key` | Replace the merchant's API key |

Create a merchant. `name` is required; `email` and `phone` (E.164, for example `+380501234567`) are optional and receive payment receipts:

```bash
curl -X POST http://localhost:8080/admin/v1/merchants \
  -H "X-Admin-Key: $ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Store", "email": "billing@acme.example", "phone": "+380501234567"}'
```

```json
{
  "id": "3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f",
  "name": "Acme Store",
  "email": "billing@acme.example",
  "phone": "+380501234567",
  "status": "ACTIVE",
  "apiKey": "q3Zb8kV1xYwP0nR7sT2uL5mH9cJ4dF6gA1eB3iK8oQ0"
}
```

The `apiKey` is shown only once: only its hash is stored, so it can't be retrieved later. Responses that contain a key are sent with `Cache-Control: no-store`.

Suspend a merchant. Its key gets `403 MERCHANT_SUSPENDED` from the next request; send `"ACTIVE"` to reactivate it:

```bash
curl -X PATCH http://localhost:8080/admin/v1/merchants/3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f \
  -H "X-Admin-Key: $ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"status": "SUSPENDED"}'
```

Rotate a merchant's key, for example when it has leaked or been lost. The response contains the new key, and the old key gets `401` immediately. The merchant's status does not change:

```bash
curl -X POST http://localhost:8080/admin/v1/merchants/3c9d6f1e-2a4b-4c8d-9e0f-1a2b3c4d5e6f/api-key \
  -H "X-Admin-Key: $ADMIN_API_KEY"
```

In Swagger UI, the admin endpoints are listed under **Merchant administration** and use the separate `AdminKey` scheme in **Authorize**.

## Errors

Errors are returned in a common format:

```json
{
  "timestamp": "2026-01-15T10:30:00Z",
  "status": 422,
  "error": "PAYMENT_RULE_VIOLATION",
  "message": "Amount 600.00 exceeds BONUS limit 500.00",
  "path": "/api/v1/payments"
}
```

| Status | Error | Cause |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Malformed request body, invalid field, or invalid `id`, `status`, `page`, `size` or `Idempotency-Key` |
| 400 | `INVALID_WEBHOOK` | Signed provider webhook whose body is not a valid result (unknown status, decline without reason, no reference) |
| 401 | `INVALID_API_KEY` | Missing or unknown API key |
| 401 | `INVALID_ADMIN_KEY` | Missing or wrong `X-Admin-Key` on an `/admin/**` path |
| 401 | `INVALID_WEBHOOK_SIGNATURE` | Provider webhook with a missing or wrong `X-Webhook-Signature` |
| 403 | `MERCHANT_SUSPENDED` | Merchant account is suspended |
| 404 | `PAYMENT_NOT_FOUND` | Payment does not exist or belongs to another merchant; for a webhook, no payment of that provider matches yet |
| 404 | `PROVIDER_NOT_FOUND` | Webhook for a provider that doesn't exist or doesn't accept webhooks |
| 404 | `MERCHANT_NOT_FOUND` | Admin endpoint called with an unknown merchant id |
| 404 | `NOT_FOUND` | Unknown path |
| 404 | `REFUND_NOT_FOUND` | No such refund of that payment; for a refund webhook, no refund of that provider matches yet |
| 405 | `METHOD_NOT_ALLOWED` | HTTP method not supported on the path; the `Allow` header lists the supported ones |
| 406 | `NOT_ACCEPTABLE` | `Accept` header asks for a type the endpoint can't return (there is no body when JSON isn't accepted either) |
| 409 | `PAYMENT_NOT_REFUNDABLE` | Refund of a payment that isn't `SUCCEEDED`; an `AUTHORIZED` one must be captured first |
| 409 | `PAYMENT_NOT_CAPTURABLE` | Capture of a payment that isn't `AUTHORIZED`, whose capture or void is in progress, or whose authorization expired |
| 409 | `PAYMENT_NOT_VOIDABLE` | Void of a payment that isn't `AUTHORIZED`, or whose capture or void is in progress |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request body that isn't JSON; the `Accept` header lists the supported types |
| 422 | `PAYMENT_RULE_VIOLATION` | Amount or currency not allowed for the payment method |
| 422 | `REFUND_EXCEEDS_REMAINING` | Refund larger than the payment's amount (its `capturedAmount` for manual capture) minus its `SUCCEEDED` and `PENDING` refunds |
| 422 | `MANUAL_CAPTURE_NOT_SUPPORTED` | `"captureMethod": "MANUAL"`, or a capture or void, for a payment whose provider can't authorize |
| 422 | `CAPTURE_EXCEEDS_AUTHORIZED` | Capture larger than the authorized amount |
| 422 | `CAPTURE_DECLINED` | The provider declined the capture; the payment stays `AUTHORIZED` |
| 422 | `VOID_DECLINED` | The provider declined the void; the payment stays `AUTHORIZED` |
| 422 | `REFUND_NOT_SUPPORTED` | Refund of a payment whose provider can't refund |
| 422 | `IDEMPOTENCY_KEY_REUSED` | `Idempotency-Key` already used with a different request body |
| 429 | `TOO_MANY_REQUESTS` | Returned by the gateway when the API key, client IP or webhook sender is over the rate limit; see `Retry-After` |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected failure in `payment-service`. The message is always `Internal Server Error`; the details are logged at `ERROR` with the request path |
| 502 | `BAD_GATEWAY` | Returned by the gateway when `payment-service` drops the connection |
| 503 | `SERVICE_UNAVAILABLE` | The database is unavailable (message `Database is unavailable`, header `Retry-After: 5`), or, from the gateway, `payment-service` cannot be reached |

Codes without their own name (`NOT_FOUND`, `METHOD_NOT_ALLOWED`, `INTERNAL_SERVER_ERROR`, …) are the HTTP status name, and their message is the standard reason phrase. This also applies to errors raised before a controller runs, for example in the API key check or for an unknown path. A message never contains exception text, SQL or connection details.

While the database is down, every request that needs it gets `503` after at most `DB_CONNECTION_TIMEOUT_MS`. That includes the API key check and a request whose connection is lost halfway through, for example when the rollback fails. The log has one line per request: `Database unavailable on GET /api/v1/payments: <cause>`. Requests work again as soon as the database is back; no restart is needed.
