# Configuration

Services are configured through environment variables. Defaults are suitable for local development.

- [Settings](#settings)
- [Startup checks](#startup-checks)
- [Secrets](#secrets)
- [Payment methods](#payment-methods)

How the provider settings are used is explained in [Providers](providers.md), and the rate limit settings in [Rate limiting](rate-limiting.md).

## Settings

| Variable | Service | Default | Description |
|---|---|---|---|
| `ADMIN_API_KEY` | payment-service | | **Required.** Admin key for `/admin/**`, sent as `X-Admin-Key`. At least 16 characters. |
| `DB_URL` | payment-service | `jdbc:postgresql://localhost:5432/payments` | JDBC connection URL |
| `DB_USER` | payment-service | `payments` | Database user |
| `DB_PASSWORD` | payment-service | `payments` | Database password |
| `DB_CONNECTION_TIMEOUT_MS` | payment-service | `3000` | How long a request waits for a database connection before it fails with `503` (`spring.datasource.hikari.connection-timeout`) |
| `MAIL_HOST` | payment-service | `localhost` | SMTP host |
| `MAIL_PORT` | payment-service | `1025` | SMTP port |
| `MAIL_TIMEOUT_MS` | payment-service | `5000` | SMTP connect, read and write timeout in milliseconds |
| `PAYMENT_RULES_<METHOD>_CURRENCIES` | payment-service | see [Payment methods](#payment-methods) | Currencies one method accepts, as comma-separated ISO 4217 codes, for example `PAYMENT_RULES_CARD_CURRENCIES=EUR,USD` (`payment.rules.CARD.currencies`) |
| `PAYMENT_RULES_<METHOD>_MIN_AMOUNT` | payment-service | see [Payment methods](#payment-methods) | Smallest amount for one method, greater than 0, at most 2 decimals (`payment.rules.<METHOD>.min-amount`) |
| `PAYMENT_RULES_<METHOD>_MAX_AMOUNT` | payment-service | see [Payment methods](#payment-methods) | Largest amount for one method, at least the minimum, at most 2 decimals (`payment.rules.<METHOD>.max-amount`) |
| `PAYMENT_PROVIDER_DEFAULT` | payment-service | `simulated` | Payment provider for methods without their own route. Empty means no default; then every method needs a route. |
| `PAYMENT_PROVIDER_ROUTES_<METHOD>` | payment-service | | Payment provider for one method, for example `PAYMENT_PROVIDER_ROUTES_CARD=simulated` (`payment.provider.routes.CARD`). Overrides the default for that method. |
| `PAYMENT_PROVIDER_SUCCESS_RATE` | payment-service | `0.9` | Share of payments the `simulated` provider approves, from `0.0` to `1.0` (`payment.provider.simulated.success-rate`) |
| `PAYMENT_PROVIDER_LATENCY` | payment-service | `300ms` | `simulated` provider response time (`payment.provider.simulated.latency`) |
| `PAYMENT_PROVIDER_AUTHORIZATION_VALIDITY` | payment-service | `7d` | How long a `simulated` authorization can be captured (`payment.provider.simulated.authorization-validity`). Must be positive. |
| `PAYMENT_PROVIDER_FIXED_OUTCOME` | payment-service | `approve` | What the `fixed` provider answers to every payment: `approve` or `decline` (`payment.provider.fixed.outcome`) |
| `PAYMENT_PROVIDER_FIXED_DECLINE_REASON` | payment-service | `Declined by fixed provider` | Decline reason the `fixed` provider gives, at most 255 characters (`payment.provider.fixed.decline-reason`) |
| `PAYMENT_PROVIDER_FIXED_AUTHORIZATION_VALIDITY` | payment-service | `7d` | How long a `fixed` authorization can be captured (`payment.provider.fixed.authorization-validity`). Must be positive. |
| `PAYMENT_PROVIDER_HTTP_URL` | payment-service | | Endpoint of the `http` payment provider (`payment.provider.http.url`). The `http` provider exists only when this is set. |
| `PAYMENT_PROVIDER_HTTP_API_KEY` | payment-service | | Sent to the `http` provider as `Authorization: Bearer <key>` when set (`payment.provider.http.api-key`) |
| `PAYMENT_PROVIDER_HTTP_CONNECT_TIMEOUT` | payment-service | `2s` | How long the `http` provider may take to accept the connection (`payment.provider.http.connect-timeout`) |
| `PAYMENT_PROVIDER_HTTP_READ_TIMEOUT` | payment-service | `10s` | How long the `http` provider may take to answer (`payment.provider.http.read-timeout`). A payment whose provider doesn't answer in time stays `PENDING`. |
| `PAYMENT_PROVIDER_HTTP_WEBHOOK_SECRET` | payment-service | | Shared secret the `http` provider signs its webhooks with, at least 16 characters (`payment.provider.http.webhook-secret`). Empty means the provider sends no webhooks (`404`). See [Provider webhooks](providers.md#provider-webhooks). |
| `PAYMENT_PROVIDER_HTTP_STATUS_URL` | payment-service | | Endpoint the `http` provider answers status queries on (`payment.provider.http.status-url`). When set, `PENDING` payments of the `http` provider are re-checked there. See [Re-checking pending payments](payments.md#re-checking-pending-payments). |
| `PAYMENT_PROVIDER_HTTP_REFUND_URL` | payment-service | | Endpoint the `http` provider sends refunds to (`payment.provider.http.refund-url`). Payments of the `http` provider can be refunded only when this is set; otherwise refunds get `422 REFUND_NOT_SUPPORTED`. |
| `PAYMENT_PROVIDER_HTTP_AUTHORIZE_URL` | payment-service | | Endpoint the `http` provider authorizes `MANUAL` payments at (`payment.provider.http.authorize-url`). Set it together with the capture and void URLs, or none of them; without them `MANUAL` payments routed to `http` get `422 MANUAL_CAPTURE_NOT_SUPPORTED`. |
| `PAYMENT_PROVIDER_HTTP_CAPTURE_URL` | payment-service | | Endpoint the `http` provider captures authorized payments at (`payment.provider.http.capture-url`). |
| `PAYMENT_PROVIDER_HTTP_VOID_URL` | payment-service | | Endpoint the `http` provider voids authorizations at (`payment.provider.http.void-url`). |
| `PAYMENT_RECONCILIATION_ENABLED` | payment-service | `true` | Run the job that re-checks `PENDING` payments with their providers |
| `PAYMENT_RECONCILIATION_INTERVAL` | payment-service | `1m` | Pause between two runs of the job, and the first backoff step |
| `PAYMENT_RECONCILIATION_MIN_AGE` | payment-service | `2m` | How long after its creation a `PENDING` payment is checked for the first time |
| `PAYMENT_RECONCILIATION_BATCH_SIZE` | payment-service | `50` | Payments one run checks at most, from 1 to 1000 |
| `PAYMENT_RECONCILIATION_MAX_BACKOFF` | payment-service | `1h` | Longest wait between two checks of one payment. At least the interval. |
| `PAYMENT_RECONCILIATION_ALERT_AFTER` | payment-service | `24h` | Age after which a payment that is still `PENDING` is logged as an error on every check |
| `SMS_PROVIDER` | payment-service | `log` | SMS implementation: `log` or `http` |
| `NOTIFICATION_SMS_HTTP_URL` | payment-service | | SMS gateway endpoint, required when `SMS_PROVIDER=http` |
| `NOTIFICATION_SMS_HTTP_API_KEY` | payment-service | | Sent as `Authorization: Bearer <key>` when set |
| `NOTIFICATION_SMS_HTTP_TIMEOUT` | payment-service | `5s` | SMS gateway connect and read timeout |
| `PAYMENT_SERVICE_URL` | api-gateway | `http://localhost:8081` | Upstream payment service |
| `REDIS_HOST` | api-gateway | `localhost` | Redis host |
| `REDIS_PORT` | api-gateway | `6379` | Redis port |
| `REDIS_USERNAME` | api-gateway | | Redis ACL user, for a Redis that requires one (`spring.data.redis.username`) |
| `REDIS_PASSWORD` | api-gateway | | Redis password (`spring.data.redis.password`). Never logged. |
| `REDIS_SSL` | api-gateway | `false` | Connect to Redis over TLS (`spring.data.redis.ssl.enabled`) |
| `REDIS_TIMEOUT` | api-gateway | `250ms` | Redis connect and command timeout. Bounds how much a slow or hung Redis can delay a request. |
| `RATE_LIMIT_REPLENISH_RATE` | api-gateway | `10` | Requests per second each API key (or client IP without a key) may make on `/api/v1/**` over time |
| `RATE_LIMIT_BURST_CAPACITY` | api-gateway | `20` | Requests allowed at once before the per-second rate applies. At least `RATE_LIMIT_REPLENISH_RATE`. |
| `RATE_LIMIT_IP_REPLENISH_RATE` | api-gateway | `20` | Requests per second one client IP may make on `/api/v1/**` across all API keys |
| `RATE_LIMIT_IP_BURST_CAPACITY` | api-gateway | `40` | Requests one client IP may send at once. At least `RATE_LIMIT_IP_REPLENISH_RATE`. |
| `RATE_LIMIT_WEBHOOK_REPLENISH_RATE` | api-gateway | `50` | Provider webhooks per second one client IP may send |
| `RATE_LIMIT_WEBHOOK_BURST_CAPACITY` | api-gateway | `100` | Provider webhooks one client IP may send at once. At least `RATE_LIMIT_WEBHOOK_REPLENISH_RATE`. |
| `TRUSTED_PROXIES` | api-gateway | | Reverse proxies or load balancers in front of the gateway, as comma-separated IPs and CIDRs, for example `10.0.0.0/8, 192.168.1.10` (`rate-limit.trusted-proxies`). Only requests from these addresses may set the client IP with `X-Forwarded-For`. Empty trusts no proxy. An invalid entry stops startup. See [Client IP behind a proxy](rate-limiting.md#client-ip-behind-a-proxy). |

With Docker Compose, put any of these in `.env` (see the commented examples in `.env.example`) and run `docker compose up -d` to apply them. Settings that are not set keep their defaults. `DB_URL`, `MAIL_HOST`, `MAIL_PORT`, `PAYMENT_SERVICE_URL`, `REDIS_HOST` and `REDIS_PORT` are fixed in `docker-compose.yml` to point at the containers. The bundled Redis has no password, so `REDIS_USERNAME`, `REDIS_PASSWORD` and `REDIS_SSL` are not passed to the gateway there; they are for running the gateway against another Redis, such as a managed one.

## Startup checks

The gateway refuses to start when a rate limit is below 1 or its burst capacity is smaller than its replenish rate. The service refuses to start with invalid settings, for example a `PAYMENT_PROVIDER_DEFAULT` or route that names no registered provider, a payment method left without a provider, a missing or short `ADMIN_API_KEY`, a success rate outside `0.0`–`1.0`, a `fixed` outcome other than `approve`/`decline` or a blank decline reason, an `http` provider timeout that is zero or negative, an `http` webhook secret shorter than 16 characters, an unknown SMS provider, or `SMS_PROVIDER=http` without a URL.

## Secrets

Secret settings (`ADMIN_API_KEY`, `NOTIFICATION_SMS_HTTP_API_KEY`, `PAYMENT_PROVIDER_HTTP_API_KEY`, `PAYMENT_PROVIDER_HTTP_WEBHOOK_SECRET`, `REDIS_PASSWORD`) are never written to logs or error messages. The settings and response classes that hold them print `***` instead of the value, and a test fails if any record in the code would print a field named like a key, secret, password or token. Keep real values in the gitignored `.env`; `.env.example` holds placeholders only.

## Payment methods

Each payment method has a rule: the currencies it accepts and the smallest and largest amount, both inclusive. These are the defaults:

| Method | Currencies | Minimum | Maximum |
|---|---|---|---|
| `CARD` | EUR, USD, PLN, UAH | 0.50 | 10,000.00 |
| `BONUS` | EUR | 0.01 | 500.00 |
| `CRYPTO` | EUR, USD | 1.00 | 5,000.00 |
| `KVIK` | EUR, PLN | 0.50 | 2,000.00 |

A payment outside its method's rule is rejected with `422 PAYMENT_RULE_VIOLATION` before any provider is called, for example `Amount 600.00 exceeds BONUS limit 500.00` or `Currency USD is not supported for BONUS`.

The rules are configuration, not code, so changing one needs a restart but no release. Set only the values you want to change; the others keep their defaults. For example, in `.env`:

```bash
PAYMENT_RULES_BONUS_MAX_AMOUNT=100.00
PAYMENT_RULES_CARD_CURRENCIES=EUR,USD,PLN
```

then `docker compose up -d payment-service`. The names are `PAYMENT_RULES_<METHOD>_CURRENCIES`, `_MIN_AMOUNT` and `_MAX_AMOUNT` (`payment.rules.<METHOD>.currencies`, `min-amount` and `max-amount` in `application.yml`).

- **Checked at startup.** Currencies must be ISO 4217 codes in upper case. Amounts must be greater than 0 with at most 2 decimals, and the minimum can't be above the maximum. An invalid rule stops `payment-service`, and one message lists every problem with its setting, for example:

  ```text
  Invalid payment rules: payment.rules.CARD.currencies (PAYMENT_RULES_CARD_CURRENCIES) must contain ISO 4217 codes like EUR, was 'eur'; payment.rules.BONUS.max-amount (PAYMENT_RULES_BONUS_MAX_AMOUNT) must have at most 2 decimals, was 50.005
  ```

- **Visible.** The active rules are logged at startup (`Payment rules: CARD=EUR/PLN/UAH/USD 0.50-10000.00, BONUS=EUR 0.01-500.00, …`), and the API description in Swagger UI shows the same table.
- **Not retroactive.** A rule applies to new payments only; stored payments are never re-checked.
