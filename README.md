# Alternative Payment Gateway

A payment gateway built with Spring Boot. Merchants authenticate with an API key, submit payments through a single entry point, and receive email and SMS receipts once a payment is final.

The system is an API gateway and a payment service, backed by PostgreSQL. Payment providers are simulated by default (an HTTP provider can be pointed at a real or stub endpoint), so declines, limits and other edge cases can be tried locally.

## Features

- Single entry point through Spring Cloud Gateway, with per-key and per-IP rate limits in Redis
- API key authentication with strict per-merchant data isolation, and an admin API for merchants and their keys
- Safe retries with the `Idempotency-Key` header: a retried payment is never charged twice
- Per-method currency and amount rules, set in configuration
- Pluggable payment providers per method: `simulated`, `fixed` and `http`
- Pending payments settled exactly once, by a signed provider webhook or a scheduled status check
- Full and partial refunds, never more than was paid
- Manual capture: reserve the amount (`AUTHORIZED`), then capture or void it; expired authorizations are voided automatically
- Email and SMS receipts, sent asynchronously after the result is committed
- Consistent JSON errors, Flyway migrations, OpenAPI with Swagger UI, Docker Compose

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
| `api-gateway` | 8080 | Public entry point. Routes `/api/v1/**`, `/admin/**` and the API docs to `payment-service`, and applies rate limits. No business logic. |
| `payment-service` | 8081 | Payment processing, validation, persistence and notifications. Reachable only through the gateway. |
| `postgres` | 5432 | Primary database. |
| `mailpit` | 1025, 8025 | Local SMTP server with a web inbox. |
| `redis` | 6379 | Rate limit counters only; keeps no data on disk. |

A payment is saved as `PENDING` and committed, then sent to its provider outside any database transaction, then updated with the result in a second transaction. Receipts are sent only after that commit. See [Payments](docs/payments.md#how-a-payment-is-processed).

## Quick start

Requirements: JDK 25 and Docker. Maven isn't needed; each service has the Maven Wrapper.

```bash
git clone https://github.com/zdtexus/alt-payment-gateway.git
cd alt-payment-gateway
cp .env.example .env
docker compose up --build
```

Open http://localhost:8080/swagger-ui.html, click **Authorize**, enter `demo-key-123`, and try any endpoint. Stop with `Ctrl+C` or `docker compose down`.

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
| Phone | `+380501112233` (SMS receipts appear in the `payment-service` log as `SMS to ***2233: …`) |

### Running without Docker

Start only the infrastructure in Docker:

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

## Usage

Create a payment:

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "X-API-Key: demo-key-123" \
  -H "Content-Type: application/json" \
  -d '{"amount": 49.90, "currency": "EUR", "paymentMethod": "CARD"}'
```

The response is `201 Created` with a `Location` header. A declined payment is a normal result with status `DECLINED`, not an error. A payment the provider hasn't confirmed yet has status `PENDING`; fetch it again with `GET /api/v1/payments/{id}` for its final status.

- Retry safely with an `Idempotency-Key` header: see [Idempotent requests](docs/payments.md#idempotent-requests).
- Reserve now and charge later with `"captureMethod": "MANUAL"`: see [Authorize and capture](docs/payments.md#authorize-and-capture).
- Refund in full or in parts: see [Refunds](docs/payments.md#refunds).

## API

Payment endpoints need the `X-API-Key` header, and a merchant sees only its own payments. Provider webhooks are signed instead. Admin endpoints need `X-Admin-Key` (the value of `ADMIN_API_KEY`).

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/payments` | Create and process a payment |
| `GET` | `/api/v1/payments/{id}` | Retrieve a payment |
| `GET` | `/api/v1/payments` | List payments, newest first (`status`, `page`, `size`) |
| `POST` | `/api/v1/payments/{id}/capture` | Capture an `AUTHORIZED` payment, in full or in part |
| `POST` | `/api/v1/payments/{id}/void` | Void an `AUTHORIZED` payment |
| `POST` | `/api/v1/payments/{id}/refunds` | Refund a succeeded payment, in full or in parts |
| `GET` | `/api/v1/payments/{id}/refunds` | List a payment's refunds, oldest first |
| `GET` | `/api/v1/payments/{id}/refunds/{refundId}` | Retrieve a refund |
| `POST` | `/api/v1/providers/{name}/webhook` | Result sent by a provider (signed, no API key) |
| `POST` | `/admin/v1/merchants` | Create a merchant and return its API key |
| `PATCH` | `/admin/v1/merchants/{id}` | Suspend or reactivate a merchant |
| `POST` | `/admin/v1/merchants/{id}/api-key` | Replace a merchant's API key |

Swagger UI has every request and response body. Errors use one JSON format; the codes are listed in [Errors](docs/api.md#errors).

## Configuration

Everything is configured with environment variables, and the defaults work for local development. With Docker Compose, put overrides in `.env` (see `.env.example`) and run `docker compose up -d`. The only required setting is `ADMIN_API_KEY` (at least 16 characters).

Common settings:

| Variable | Default | Description |
|---|---|---|
| `PAYMENT_PROVIDER_DEFAULT` | `simulated` | Payment provider for methods without their own route |
| `PAYMENT_PROVIDER_ROUTES_<METHOD>` | | Provider for one method, for example `PAYMENT_PROVIDER_ROUTES_CARD=http` |
| `PAYMENT_PROVIDER_SUCCESS_RATE` | `0.9` | Share of payments the `simulated` provider approves |
| `PAYMENT_RULES_<METHOD>_MAX_AMOUNT` | per method | Largest amount for one method (also `_MIN_AMOUNT`, `_CURRENCIES`) |
| `SMS_PROVIDER` | `log` | `log` writes SMS to the log; `http` sends them |
| `RATE_LIMIT_REPLENISH_RATE` | `10` | Requests per second per API key |
| `TRUSTED_PROXIES` | | Proxies allowed to set the client IP with `X-Forwarded-For` |

Every setting, the startup checks and the payment method rules are in [Configuration](docs/configuration.md).

## Documentation

| Document | Contents |
|---|---|
| [Payments](docs/payments.md) | Processing flow, statuses, re-checking, idempotency, authorize and capture, refunds, receipts |
| [Providers](docs/providers.md) | Routing, `simulated`, `fixed` and `http` providers, webhooks, adding a provider, SMS |
| [API](docs/api.md) | Authentication, merchant administration, error codes |
| [Configuration](docs/configuration.md) | All settings, startup checks, secrets, payment method rules |
| [Rate limiting](docs/rate-limiting.md) | Limits, proxies, Redis outages, several gateway instances |
| [Database](docs/database.md) | Schema, constraints, resetting the local database |

## Running tests

Docker must be running: integration tests start PostgreSQL in a container. Build and test both services from the repository root:

```bash
payment-service/mvnw -f pom.xml verify
```

Or one service at a time, with `./mvnw verify` in its folder.

## Project layout

```text
alt-payment-gateway/
├── api-gateway/          Spring Cloud Gateway
├── payment-service/
│   └── src/main/java/com/example/payment_service/
│       ├── auth/             API key and admin key filters, key generation, @CurrentMerchant
│       ├── config/           Web, async and OpenAPI configuration
│       ├── controller/       REST endpoints, including provider webhooks
│       ├── dto/              Request and response bodies
│       ├── entity/           JPA entities and enums
│       ├── error/            Exceptions and the common error format
│       ├── event/            PaymentProcessedEvent, RefundProcessedEvent
│       ├── notification/     Receipt listener, email and SMS senders
│       ├── provider/         Payment providers, registry, per-method router, webhook, status and refund contracts
│       ├── reconciliation/   Scheduled re-checking of pending payments, refunds, captures and voids; authorization expiry
│       ├── repository/       Spring Data repositories
│       ├── rules/            Per-method currency and amount rules
│       └── service/          Payments, capture and void, refunds, merchant administration
├── docs/                 Detailed documentation
├── docker-compose.yml
├── docker-compose.scale.yml  Override for two gateway instances on ports 8080-8081
├── .env.example
└── pom.xml               Aggregator for building both services
```

## Tech stack

Java 25, Spring Boot 4, Spring Cloud Gateway, Spring Data JPA, Hibernate, Bean Validation, Spring Mail, PostgreSQL, Redis, Flyway, springdoc-openapi, JUnit 6, Mockito, Testcontainers, Docker.
