# Alternative Payment Gateway

A payment gateway built with Spring Boot. Merchants authenticate with an API key, submit payments through a single entry point, and receive email and SMS receipts once a payment is processed.

The system consists of an API gateway and a payment service, backed by PostgreSQL. Payment providers are simulated, which makes it possible to exercise declines, limits and other edge cases locally.

## Features

- Single entry point through Spring Cloud Gateway
- API key authentication with strict per-merchant data isolation
- Admin API to create merchants, suspend them and rotate their API keys
- Safe retries with the `Idempotency-Key` header, so a retried payment is never charged twice
- Multiple payment methods with per-method currency and amount rules
- Payment providers selected per payment method: a simulated provider with a configurable success rate and latency, a fixed provider that always approves or always declines, and an HTTP provider with connect and read timeouts
- Asynchronous email and SMS receipts, sent only after the payment is committed
- Consistent JSON error responses
- Versioned database schema with Flyway
- OpenAPI documentation with Swagger UI
- Local environment with Docker Compose

## Architecture

```text
Client ──► api-gateway :8080 ──(/api/v1/**)──► payment-service :8081
                │                                    │
                │                  ┌─────────────────┼───────────────────────┐
                ▼                  ▼                 ▼                       ▼
           Redis :6379       PostgreSQL :5432      SMTP (Mailpit)        SMS sender
```

| Component | Port | Description |
|---|---|---|
| `api-gateway` | 8080 | Public entry point. Routes `/api/v1/**`, `/admin/**` and the API docs to `payment-service`. Contains no business logic. |
| `payment-service` | 8081 | Payment processing, validation, persistence and notifications. Reachable only through the gateway. |
| `postgres` | 5432 | Primary database. |
| `mailpit` | 1025, 8025 | Local SMTP server with a web inbox for development. |
| `redis` | 6379 | Used by the gateway for rate limiting. Holds only short-lived counters, so it keeps no data on disk. |

Notifications are decoupled from payment processing through Spring application events. The listener runs asynchronously after the transaction commits, so a slow or failing mail server never affects the payment, and no receipt is sent for a payment that was rolled back.

```text
PaymentService.process()                     @Transactional
  ├── save Transaction
  └── publish PaymentProcessedEvent
        └── NotificationListener             @Async, after commit
              ├── EmailSender
              └── SmsSender
```

The merchant receives an email receipt and an SMS for every processed payment, whether it succeeded or was declined. A channel is skipped when the merchant has no email address or phone number. A failed email or SMS is logged as a warning with the payment ID and does not stop the other channel. The mail server is not part of `/actuator/health`, so a mail outage does not mark the service as down.

Email, SMS and payment providers are defined as interfaces. Implementations are selected through configuration, so a real provider can be added or swapped without changes to business code. Several payment providers can be active at once, each serving the payment methods routed to it (see [Payment providers](#payment-providers)).

## Requirements

- JDK 25
- Docker and Docker Compose

Maven does not need to be installed; each service includes the Maven Wrapper.

## Getting Started

1. Clone the repository:

   ```bash
   git clone https://github.com/zdtexus/alt-payment-gateway.git
   cd alt-payment-gateway
   ```

2. Create the environment file:

   ```bash
   cp .env.example .env
   ```

3. Start everything with Docker:

   ```bash
   docker compose up --build
   ```

4. Open http://localhost:8080/swagger-ui.html, click **Authorize**, enter `demo-key-123`, and try any endpoint with **Try it out**.

To stop, press `Ctrl+C` or run `docker compose down`.

### Running without Docker

If you prefer to run the services from your IDE, start only the database, mail server and Redis in Docker:

```bash
docker compose up -d postgres mailpit redis
```

Then start each service in its own terminal. `payment-service` needs an admin key:

```bash
cd payment-service
ADMIN_API_KEY=local-admin-key-change-me ./mvnw spring-boot:run
```

```bash
cd api-gateway
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd` instead of `./mvnw`, and set the key first with `set ADMIN_API_KEY=...` (cmd) or `$env:ADMIN_API_KEY="..."` (PowerShell).

Once running, the following are available:

| Service | URL |
|---|---|
| API | http://localhost:8080/api/v1/payments |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Gateway routes | http://localhost:8080/actuator/gateway/routes |
| Mail inbox | http://localhost:8025 |

A demo merchant is created on first start:

| Field | Value |
|---|---|
| Merchant ID | `11111111-1111-1111-1111-111111111111` |
| API key | `demo-key-123` |
| Email | `shop@demo.local` (receipts appear in the Mailpit inbox) |
| Phone | `+380501112233` (SMS receipts appear in the `payment-service` log) |

## Configuration

Services are configured through environment variables. Defaults are suitable for local development.

| Variable | Service | Default | Description |
|---|---|---|---|
| `ADMIN_API_KEY` | payment-service | | **Required.** Admin key for `/admin/**`, sent as `X-Admin-Key`. At least 16 characters. |
| `DB_URL` | payment-service | `jdbc:postgresql://localhost:5432/payments` | JDBC connection URL |
| `DB_USER` | payment-service | `payments` | Database user |
| `DB_PASSWORD` | payment-service | `payments` | Database password |
| `MAIL_HOST` | payment-service | `localhost` | SMTP host |
| `MAIL_PORT` | payment-service | `1025` | SMTP port |
| `MAIL_TIMEOUT_MS` | payment-service | `5000` | SMTP connect, read and write timeout in milliseconds |
| `PAYMENT_PROVIDER_DEFAULT` | payment-service | `simulated` | Payment provider for methods without their own route. Empty means no default; then every method needs a route. |
| `PAYMENT_PROVIDER_ROUTES_<METHOD>` | payment-service | | Payment provider for one method, for example `PAYMENT_PROVIDER_ROUTES_CARD=simulated` (`payment.provider.routes.CARD`). Overrides the default for that method. |
| `PAYMENT_PROVIDER_SUCCESS_RATE` | payment-service | `0.9` | Share of payments the `simulated` provider approves, from `0.0` to `1.0` (`payment.provider.simulated.success-rate`) |
| `PAYMENT_PROVIDER_LATENCY` | payment-service | `300ms` | `simulated` provider response time (`payment.provider.simulated.latency`) |
| `PAYMENT_PROVIDER_FIXED_OUTCOME` | payment-service | `approve` | What the `fixed` provider answers to every payment: `approve` or `decline` (`payment.provider.fixed.outcome`) |
| `PAYMENT_PROVIDER_FIXED_DECLINE_REASON` | payment-service | `Declined by fixed provider` | Decline reason the `fixed` provider gives, at most 255 characters (`payment.provider.fixed.decline-reason`) |
| `PAYMENT_PROVIDER_HTTP_URL` | payment-service | | Endpoint of the `http` payment provider (`payment.provider.http.url`). The `http` provider exists only when this is set. |
| `PAYMENT_PROVIDER_HTTP_API_KEY` | payment-service | | Sent to the `http` provider as `Authorization: Bearer <key>` when set (`payment.provider.http.api-key`) |
| `PAYMENT_PROVIDER_HTTP_CONNECT_TIMEOUT` | payment-service | `2s` | How long the `http` provider may take to accept the connection (`payment.provider.http.connect-timeout`) |
| `PAYMENT_PROVIDER_HTTP_READ_TIMEOUT` | payment-service | `10s` | How long the `http` provider may take to answer (`payment.provider.http.read-timeout`). A payment whose provider doesn't answer in time stays `PENDING`. |
| `SMS_PROVIDER` | payment-service | `log` | SMS implementation: `log` or `http` |
| `NOTIFICATION_SMS_HTTP_URL` | payment-service | | SMS gateway endpoint, required when `SMS_PROVIDER=http` |
| `NOTIFICATION_SMS_HTTP_API_KEY` | payment-service | | Sent as `Authorization: Bearer <key>` when set |
| `NOTIFICATION_SMS_HTTP_TIMEOUT` | payment-service | `5s` | SMS gateway connect and read timeout |
| `PAYMENT_SERVICE_URL` | api-gateway | `http://localhost:8081` | Upstream payment service |
| `REDIS_HOST` | api-gateway | `localhost` | Redis host |
| `REDIS_PORT` | api-gateway | `6379` | Redis port |
| `REDIS_TIMEOUT` | api-gateway | `250ms` | Redis connect and command timeout. Bounds how much a slow or hung Redis can delay a request. |
| `RATE_LIMIT_REPLENISH_RATE` | api-gateway | `10` | Requests per second each API key (or client IP without a key) may make on `/api/v1/**` over time |
| `RATE_LIMIT_BURST_CAPACITY` | api-gateway | `20` | Requests allowed at once before the per-second rate applies. At least `RATE_LIMIT_REPLENISH_RATE`. |
| `RATE_LIMIT_IP_REPLENISH_RATE` | api-gateway | `20` | Requests per second one client IP may make on `/api/v1/**` across all API keys |
| `RATE_LIMIT_IP_BURST_CAPACITY` | api-gateway | `40` | Requests one client IP may send at once. At least `RATE_LIMIT_IP_REPLENISH_RATE`. |

With Docker Compose, put any of these in `.env` (see the commented examples in `.env.example`) and run `docker compose up -d` to apply them. Settings that are not set keep their defaults. `DB_URL`, `MAIL_HOST`, `MAIL_PORT`, `PAYMENT_SERVICE_URL`, `REDIS_HOST` and `REDIS_PORT` are fixed in `docker-compose.yml` to point at the containers.

The gateway refuses to start when a rate limit is below 1 or its burst capacity is smaller than its replenish rate. The service refuses to start with invalid settings, for example a `PAYMENT_PROVIDER_DEFAULT` or route that names no registered provider, a payment method left without a provider, a missing or short `ADMIN_API_KEY`, a success rate outside `0.0`–`1.0`, a `fixed` outcome other than `approve`/`decline` or a blank decline reason, an `http` provider timeout that is zero or negative, an unknown SMS provider, or `SMS_PROVIDER=http` without a URL.

### SMS providers

- `log` (default) writes each message to the application log.
- `http` sends `POST <NOTIFICATION_SMS_HTTP_URL>` with the JSON body `{"to": "+380501112233", "message": "Payment 49.90 EUR succeeded"}`. A `4xx` or `5xx` response, or a timeout, counts as a failed notification.

### Payment providers

Each payment method is sent to one payment provider. A method uses its route (`PAYMENT_PROVIDER_ROUTES_<METHOD>`) if one is set, and otherwise the default provider (`PAYMENT_PROVIDER_DEFAULT`, `simulated`). At startup the service logs the result, for example `Payment provider routes: CARD=simulated, BONUS=simulated, CRYPTO=simulated, KVIK=simulated`, and refuses to start if a route or the default names an unknown provider, or a method is left without one.

Available providers:

- `simulated` (default) approves a share of payments (`PAYMENT_PROVIDER_SUCCESS_RATE`) after a delay (`PAYMENT_PROVIDER_LATENCY`), and returns a reference such as `sim_04347846-62b6-42f4-9d6b-917f5704225c`.
- `fixed` gives the same answer to every payment, at once: it approves (`PAYMENT_PROVIDER_FIXED_OUTCOME=approve`, the default) or declines with `PAYMENT_PROVIDER_FIXED_DECLINE_REASON`. References look like `fix_<uuid>`. No method uses it unless routed, for example `PAYMENT_PROVIDER_ROUTES_CRYPTO=fixed` with `PAYMENT_PROVIDER_FIXED_OUTCOME=decline` to reject all crypto payments while card payments keep using `simulated`.
- `http` sends each payment to an external endpoint (`PAYMENT_PROVIDER_HTTP_URL`) and is available only when that URL is set; route methods to it, for example `PAYMENT_PROVIDER_ROUTES_CARD=http`. It posts JSON with an `Idempotency-Key` header set to the payment ID, so the provider can ignore a repeated call:

  ```json
  {"paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "method": "CARD", "currency": "EUR", "amount": 49.90}
  ```

  and expects `{"status": "APPROVED" | "DECLINED" | "PENDING", "reference": "...", "reason": "..."}` (`reason` is required for `DECLINED`, `reference` for `PENDING`). Any answer the service can't trust leaves the payment `PENDING` instead of declining it: no connection within the connect timeout, no answer within the read timeout, an error status, or an unknown or incomplete body. The provider may have charged in those cases, so declining would be wrong.

Each transaction stores the provider name and the provider's reference (see [Database](#database)). Both are internal: merchants don't see them in API responses, so providers and routes can change without affecting merchants.

To add a provider:

1. Implement `PaymentProvider` in the `provider` package as a Spring `@Component`. `name()` returns a short unique name (at most 64 characters), for example `acme`. Startup fails if two providers share a name.
2. Put its settings in a `@ConfigurationProperties("payment.provider.acme")` record, validated like `SimulatedPaymentProviderProperties`, and add any environment variables to this README and `.env.example`.
3. Return `ProviderResult.approved(reference)` or `ProviderResult.declined(reason, reference)`, passing the provider's own transaction ID (at most 255 characters) when it has one. The provider is called outside any database transaction, after the `PENDING` payment is committed. A thrown exception counts as an unknown result: it is logged as an error with the payment ID, the payment stays `PENDING` with no receipt, and the merchant gets `201` with status `PENDING` (see [Payment statuses](#payment-statuses)).
4. Route methods to it, for example `PAYMENT_PROVIDER_ROUTES_CRYPTO=acme`. Payment rules (currencies and limits per method) still apply before any provider is called.

## API

All requests require the `X-API-Key` header. A merchant can only access its own payments. Merchants and their keys are managed through the [admin API](#merchant-administration).

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/payments` | Create and process a payment |
| `GET` | `/api/v1/payments/{id}` | Retrieve a payment |
| `GET` | `/api/v1/payments` | List payments, newest first. Supports `status`, `page` and `size`. |

Create a payment:

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "X-API-Key: demo-key-123" \
  -H "Content-Type: application/json" \
  -d '{"amount": 49.90, "currency": "EUR", "paymentMethod": "CARD"}'
```

A successful request returns `201 Created` with a relative `Location` header, for example `/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b`. A declined payment is a regular result and is returned with status `DECLINED`, not as an error.

The full API reference is available in Swagger UI.

### Idempotent requests

A network error can leave the client unsure whether a payment was created. To retry safely, send an `Idempotency-Key` header with a value unique to the payment, for example the order ID:

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "X-API-Key: demo-key-123" \
  -H "Idempotency-Key: order-1001" \
  -H "Content-Type: application/json" \
  -d '{"amount": 49.90, "currency": "EUR", "paymentMethod": "CARD"}'
```

- The first request processes the payment as usual.
- A retry with the same key and the same body returns the original response (`201`, same payment and `Location`) with the header `Idempotent-Replayed: true`. The provider is not called again and no second receipt is sent. A retry that arrives while the payment is still `PENDING` (the provider is still answering, or it failed) returns the payment as stored, with status `PENDING`, without waiting and without calling the provider again; fetch it with `GET` for its final status.
- A retry with the same key and a different body is rejected with `422 IDEMPOTENCY_KEY_REUSED`. Amounts are compared by value, so `49.9` and `49.90` are the same.
- Keys are scoped to the merchant and may be 1 to 255 characters long.
- A request rejected with `400` or `422 PAYMENT_RULE_VIOLATION` stores nothing, so the key can be reused with a corrected body.
- Requests without the header are processed every time.

### Merchant administration

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

### Rate limiting

The gateway limits `/api/v1/**` per API key with a token bucket stored in Redis. Each key may send `RATE_LIMIT_BURST_CAPACITY` requests at once (default 20), and the bucket refills at `RATE_LIMIT_REPLENISH_RATE` requests per second (default 10). Requests without an API key share a bucket per client IP. `/admin/**` and the API docs are not limited.

A second, looser limit applies per client IP across all keys (`RATE_LIMIT_IP_BURST_CAPACITY`, default 40, refilled at `RATE_LIMIT_IP_REPLENISH_RATE`, default 20 per second). Without it, someone guessing keys could send a different key with every request and never be limited, because every unknown key starts with a full bucket. Only requests that pass their key's limit count against the IP limit, so a client retrying a key that is already limited doesn't use up the allowance of other merchants behind the same IP. The client IP is the address of the TCP connection; `X-Forwarded-For` is ignored because clients can set it freely.

The bucket is identified by a SHA-256 hash of the API key, so Redis never holds a merchant's key. Responses include `X-RateLimit-Remaining`, `X-RateLimit-Burst-Capacity` and `X-RateLimit-Replenish-Rate`. A request over the limit is rejected before it reaches `payment-service`, with `429` in the common error format and a `Retry-After` header giving the seconds until a request is allowed again:

```json
{
  "timestamp": "2026-01-15T10:30:00Z",
  "status": 429,
  "error": "TOO_MANY_REQUESTS",
  "message": "Rate limit exceeded, retry later",
  "path": "/api/v1/payments"
}
```

#### When Redis is down

Rate limiting fails open: if Redis can't be reached, requests go through without a limit instead of failing, so a Redis outage never stops payments. The gateway keeps reporting `UP` on `/actuator/health`, because Redis is only needed for rate limiting.

- **Redis stopped or unreachable:** requests are let through immediately. While the connection is down, the Redis client rejects commands at once instead of queuing them.
- **Redis up but not answering:** each limiter gives up after `REDIS_TIMEOUT` (250 ms), so a request waits at most about 0.5 s extra (one per-key and one per-IP check).
- **Redis back:** the client reconnects on its own and limiting resumes without a restart.
- During an outage, responses carry `X-RateLimit-Remaining: -1`. Requests made during the outage are not counted.
- The log shows the outage once per reconnect attempt (`Reconnecting…`, `Cannot reconnect…`, `Reconnected…`) instead of an error for every request. A Redis that is connected but not answering is not logged; it only shows as the extra delay.

### Payment methods

| Method | Currencies | Minimum | Maximum |
|---|---|---|---|
| `CARD` | EUR, USD, PLN, UAH | 0.50 | 10,000 |
| `BONUS` | EUR | 0.01 | 500 |
| `CRYPTO` | EUR, USD | 1.00 | 5,000 |
| `KVIK` | EUR, PLN | 0.50 | 2,000 |

### Payment statuses

A payment is stored as `PENDING` and committed before its provider is called, then updated to `SUCCEEDED` or `DECLINED` in a second transaction, and only then is a receipt sent. Keeping the provider call out of the transaction means a slow provider holds no database connection or row lock.

A payment stays `PENDING` when the provider's result is unknown, for example when the provider fails with an error. The merchant still gets `201` with status `PENDING` and the payment's `Location`. It gets no receipt, and is not charged again on an idempotent retry. Such payments are logged as errors and are not yet resolved automatically.

### Errors

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
| 401 | `INVALID_API_KEY` | Missing or unknown API key |
| 401 | `INVALID_ADMIN_KEY` | Missing or wrong `X-Admin-Key` on an `/admin/**` path |
| 403 | `MERCHANT_SUSPENDED` | Merchant account is suspended |
| 404 | `PAYMENT_NOT_FOUND` | Payment does not exist or belongs to another merchant |
| 404 | `MERCHANT_NOT_FOUND` | Admin endpoint called with an unknown merchant id |
| 422 | `PAYMENT_RULE_VIOLATION` | Amount or currency not allowed for the payment method |
| 422 | `IDEMPOTENCY_KEY_REUSED` | `Idempotency-Key` already used with a different request body |
| 429 | `TOO_MANY_REQUESTS` | Returned by the gateway when the API key or client IP is over the rate limit; see `Retry-After` |
| 502 | `BAD_GATEWAY` | Returned by the gateway when `payment-service` drops the connection |
| 503 | `SERVICE_UNAVAILABLE` | Returned by the gateway when `payment-service` cannot be reached |

## Database

The schema is managed by Flyway. Migrations are located in `payment-service/src/main/resources/db/migration` and are applied automatically on startup. Hibernate validates the mapping against the schema but never modifies it.

API keys are never stored. The database keeps only their SHA-256 hash (`api_key_hash`), and each request's `X-API-Key` is hashed and looked up by that hash. A database dump therefore doesn't reveal any merchant's key. New and rotated keys are 32 random bytes from `SecureRandom`, encoded as URL-safe Base64 (43 characters).

```text
merchants      id, name, api_key_hash, email, phone, status
transactions   id, merchant_id, amount, currency, payment_method, status,
               decline_reason, created_at, processed_at,
               idempotency_key, request_hash,
               provider, provider_reference
```

Each transaction records which payment provider handled it (`provider`, for example `simulated`) and that provider's own transaction ID (`provider_reference`, empty if the provider returns none). Payments made before these columns existed are recorded as `simulated`.

To reset the local database:

```bash
docker compose down -v
```

## Running Tests

Run the tests for each service from its folder:

```bash
cd payment-service
./mvnw verify
```

```bash
cd api-gateway
./mvnw verify
```

Integration tests start PostgreSQL in a container, so Docker must be running.

To build and test both services at once, run Maven against the root `pom.xml`:

```bash
payment-service/mvnw -f pom.xml verify
```

## Project Layout

```text
alt-payment-gateway/
├── api-gateway/          Spring Cloud Gateway
├── payment-service/
│   └── src/main/java/com/example/payment_service/
│       ├── auth/             API key and admin key filters, key generation, @CurrentMerchant
│       ├── config/           Web, async and OpenAPI configuration
│       ├── controller/       REST endpoints
│       ├── dto/              Request and response bodies
│       ├── entity/           JPA entities and enums
│       ├── error/            Exceptions and the common error format
│       ├── event/            PaymentProcessedEvent
│       ├── notification/     Receipt listener, email and SMS senders
│       ├── provider/         Payment provider interface, registry, per-method router, simulated, fixed and HTTP providers
│       ├── repository/       Spring Data repositories
│       ├── rules/            Per-method currency and amount rules
│       └── service/          Payment processing and merchant administration
├── docker-compose.yml
├── .env.example
└── pom.xml               Aggregator for building both services
```

## Tech Stack

Java 25, Spring Boot 4, Spring Cloud Gateway, Spring Data JPA, Hibernate, Bean Validation, Spring Mail, PostgreSQL, Redis, Flyway, springdoc-openapi, JUnit 5, Mockito, Testcontainers, Docker.
