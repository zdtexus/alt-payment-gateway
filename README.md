# Alternative Payment Gateway

A payment gateway built with Spring Boot. Merchants authenticate with an API key, submit payments through a single entry point, and receive email and SMS receipts once a payment is processed.

The system consists of an API gateway and a payment service, backed by PostgreSQL. Payment providers are simulated, which makes it possible to exercise declines, limits and other edge cases locally.

## Features

- Single entry point through Spring Cloud Gateway
- API key authentication with strict per-merchant data isolation
- Multiple payment methods with per-method currency and amount rules
- Simulated payment provider with configurable success rate and latency
- Asynchronous email and SMS receipts, sent only after the payment is committed
- Consistent JSON error responses
- Versioned database schema with Flyway
- OpenAPI documentation with Swagger UI
- Local environment with Docker Compose

## Architecture

```text
Client ──► api-gateway :8080 ──(/api/v1/**)──► payment-service :8081
                                                     │
                                   ┌─────────────────┼───────────────────────┐
                                   ▼                 ▼                       ▼
                             PostgreSQL :5432      SMTP (Mailpit)        SMS sender
```

| Component | Port | Description |
|---|---|---|
| `api-gateway` | 8080 | Public entry point. Routes `/api/v1/**` and the API docs to `payment-service`. Contains no business logic. |
| `payment-service` | 8081 | Payment processing, validation, persistence and notifications. Reachable only through the gateway. |
| `postgres` | 5432 | Primary database. |
| `mailpit` | 1025, 8025 | Local SMTP server with a web inbox for development. |

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

Email, SMS and the payment provider are defined as interfaces. Implementations are selected through configuration, so a real provider can replace the default one without changes to business code.

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

If you prefer to run the services from your IDE, start only the database and mail server in Docker:

```bash
docker compose up -d postgres mailpit
```

Then start each service in its own terminal:

```bash
cd payment-service
./mvnw spring-boot:run
```

```bash
cd api-gateway
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

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
| `DB_URL` | payment-service | `jdbc:postgresql://localhost:5432/payments` | JDBC connection URL |
| `DB_USER` | payment-service | `payments` | Database user |
| `DB_PASSWORD` | payment-service | `payments` | Database password |
| `MAIL_HOST` | payment-service | `localhost` | SMTP host |
| `MAIL_PORT` | payment-service | `1025` | SMTP port |
| `MAIL_TIMEOUT_MS` | payment-service | `5000` | SMTP connect, read and write timeout in milliseconds |
| `PAYMENT_PROVIDER_SUCCESS_RATE` | payment-service | `0.9` | Share of payments the simulated provider approves, from `0.0` to `1.0` |
| `PAYMENT_PROVIDER_LATENCY` | payment-service | `300ms` | Simulated provider response time |
| `SMS_PROVIDER` | payment-service | `log` | SMS implementation: `log` or `http` |
| `NOTIFICATION_SMS_HTTP_URL` | payment-service | | SMS gateway endpoint, required when `SMS_PROVIDER=http` |
| `NOTIFICATION_SMS_HTTP_API_KEY` | payment-service | | Sent as `Authorization: Bearer <key>` when set |
| `NOTIFICATION_SMS_HTTP_TIMEOUT` | payment-service | `5s` | SMS gateway connect and read timeout |
| `PAYMENT_SERVICE_URL` | api-gateway | `http://localhost:8081` | Upstream payment service |

With Docker Compose, put any of these in `.env` (see the commented examples in `.env.example`) and run `docker compose up -d` to apply them. Settings that are not set keep their defaults. `DB_URL`, `MAIL_HOST` and `MAIL_PORT` are fixed in `docker-compose.yml` to point at the containers.

The service refuses to start with invalid settings, for example a success rate outside `0.0`–`1.0`, an unknown SMS provider, or `SMS_PROVIDER=http` without a URL.

### SMS providers

- `log` (default) writes each message to the application log.
- `http` sends `POST <NOTIFICATION_SMS_HTTP_URL>` with the JSON body `{"to": "+380501112233", "message": "Payment 49.90 EUR succeeded"}`. A `4xx` or `5xx` response, or a timeout, counts as a failed notification.

## API

All requests require the `X-API-Key` header. A merchant can only access its own payments.

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

### Payment methods

| Method | Currencies | Minimum | Maximum |
|---|---|---|---|
| `CARD` | EUR, USD, PLN, UAH | 0.50 | 10,000 |
| `BONUS` | EUR | 0.01 | 500 |
| `CRYPTO` | EUR, USD | 1.00 | 5,000 |
| `KVIK` | EUR, PLN | 0.50 | 2,000 |

A payment starts as `PENDING` and ends as `SUCCEEDED` or `DECLINED`.

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
| 400 | `VALIDATION_FAILED` | Malformed request body, invalid field, or invalid `id`, `status`, `page` or `size` |
| 401 | `INVALID_API_KEY` | Missing or unknown API key |
| 403 | `MERCHANT_SUSPENDED` | Merchant account is suspended |
| 404 | `PAYMENT_NOT_FOUND` | Payment does not exist or belongs to another merchant |
| 422 | `PAYMENT_RULE_VIOLATION` | Amount or currency not allowed for the payment method |
| 502 | `BAD_GATEWAY` | Returned by the gateway when `payment-service` drops the connection |
| 503 | `SERVICE_UNAVAILABLE` | Returned by the gateway when `payment-service` cannot be reached |

## Database

The schema is managed by Flyway. Migrations are located in `payment-service/src/main/resources/db/migration` and are applied automatically on startup. Hibernate validates the mapping against the schema but never modifies it.

```text
merchants      id, name, api_key, email, phone, status
transactions   id, merchant_id, amount, currency, payment_method, status,
               decline_reason, created_at, processed_at
```

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
│       ├── auth/             API key filter and @CurrentMerchant
│       ├── config/           Web, async and OpenAPI configuration
│       ├── controller/       REST endpoints
│       ├── dto/              Request and response bodies
│       ├── entity/           JPA entities and enums
│       ├── error/            Exceptions and the common error format
│       ├── event/            PaymentProcessedEvent
│       ├── notification/     Receipt listener, email and SMS senders
│       ├── provider/         Payment provider interface and simulator
│       ├── repository/       Spring Data repositories
│       ├── rules/            Per-method currency and amount rules
│       └── service/          Payment processing
├── docker-compose.yml
├── .env.example
└── pom.xml               Aggregator for building both services
```

## Tech Stack

Java 25, Spring Boot 4, Spring Cloud Gateway, Spring Data JPA, Hibernate, Bean Validation, Spring Mail, PostgreSQL, Flyway, springdoc-openapi, JUnit 5, Mockito, Testcontainers, Docker.
