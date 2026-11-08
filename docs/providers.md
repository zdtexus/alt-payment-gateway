# Providers

Email, SMS and payment providers are defined as interfaces. Implementations are selected through configuration, so a real provider can be added or swapped without changes to business code. Several payment providers can be active at once, each serving the payment methods routed to it.

- [Routing](#routing)
- [Available payment providers](#available-payment-providers)
- [The `http` provider](#the-http-provider)
- [Provider webhooks](#provider-webhooks)
- [Adding a payment provider](#adding-a-payment-provider)
- [SMS providers](#sms-providers)

## Routing

Each payment method is sent to one payment provider. A method uses its route (`PAYMENT_PROVIDER_ROUTES_<METHOD>`) if one is set, and otherwise the default provider (`PAYMENT_PROVIDER_DEFAULT`, `simulated`). At startup the service logs the result, for example `Payment provider routes: CARD=simulated, BONUS=simulated, CRYPTO=simulated, KVIK=simulated`, and refuses to start if a route or the default names an unknown provider, or a method is left without one.

## Available payment providers

- `simulated` (default) approves a share of payments (`PAYMENT_PROVIDER_SUCCESS_RATE`) after a delay (`PAYMENT_PROVIDER_LATENCY`), and returns a reference such as `sim_04347846-62b6-42f4-9d6b-917f5704225c`. With manual capture it authorizes with the same success rate (`sim_a_…` references), its authorizations can be captured for `PAYMENT_PROVIDER_AUTHORIZATION_VALIDITY` (7 days), and captures and voids always succeed.
- `fixed` gives the same answer to every payment, at once: it approves (`PAYMENT_PROVIDER_FIXED_OUTCOME=approve`, the default) or declines with `PAYMENT_PROVIDER_FIXED_DECLINE_REASON`. References look like `fix_<uuid>`. No method uses it unless routed, for example `PAYMENT_PROVIDER_ROUTES_CRYPTO=fixed` with `PAYMENT_PROVIDER_FIXED_OUTCOME=decline` to reject all crypto payments while card payments keep using `simulated`. With manual capture it gives that answer to authorize, capture and void alike (`fix_a_`, `fix_c_`, `fix_v_`), with authorizations valid for `PAYMENT_PROVIDER_FIXED_AUTHORIZATION_VALIDITY`.
- `http` sends each payment to an external endpoint; see [below](#the-http-provider).

Each transaction stores the provider name and the provider's reference (see [Database](database.md)). Both are internal: merchants don't see them in API responses, so providers and routes can change without affecting merchants.

## The `http` provider

`http` sends each payment to an external endpoint (`PAYMENT_PROVIDER_HTTP_URL`) and is available only when that URL is set; route methods to it, for example `PAYMENT_PROVIDER_ROUTES_CARD=http`. It posts JSON with an `Idempotency-Key` header set to the payment ID, so the provider can ignore a repeated call:

```json
{"paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "method": "CARD", "currency": "EUR", "amount": 49.90}
```

and expects `{"status": "APPROVED" | "DECLINED" | "PENDING", "reference": "...", "reason": "..."}` (`reason` is required for `DECLINED`, `reference` for `PENDING`). Any answer the service can't trust leaves the payment `PENDING` instead of declining it: no connection within the connect timeout, no answer within the read timeout, an error status, or an unknown or incomplete body.

With `PAYMENT_PROVIDER_HTTP_STATUS_URL` set, the provider is also asked about payments that are still `PENDING`: `GET <status URL>?paymentId=<id>&reference=<reference>` (no `reference` while the provider hasn't given one), with the same bearer key and timeouts. It answers in the same format; `PENDING` means "still not known". Untrusted answers, including any error status, count as a failed check, never as a decline. The provider may have charged in those cases, so declining would be wrong. The provider reports the final result later through a [webhook](#provider-webhooks).

With `PAYMENT_PROVIDER_HTTP_REFUND_URL` set, refunds of its payments are sent there: `POST <refund URL>` with `{"refundId": "...", "paymentId": "...", "paymentReference": "...", "currency": "EUR", "amount": 20.00}` and `Idempotency-Key: <refundId>`, the same bearer key and timeouts, and the same answer format. An answer it can't trust leaves the refund `PENDING`. A `PENDING` refund is asked about with `GET <status URL>?refundId=<id>&paymentId=<id>&reference=<reference>`.

With `PAYMENT_PROVIDER_HTTP_AUTHORIZE_URL`, `PAYMENT_PROVIDER_HTTP_CAPTURE_URL` and `PAYMENT_PROVIDER_HTTP_VOID_URL` set (all three), it supports [manual capture](payments.md#authorize-and-capture), with the same bearer key, timeouts and answer format:

| Step | Request | `Idempotency-Key` |
|---|---|---|
| Authorize | `POST <authorize URL>` `{"paymentId", "method", "currency", "amount"}` | `<paymentId>` |
| Capture | `POST <capture URL>` `{"paymentId", "authorizationReference", "currency", "amount"}` | `<paymentId>:capture` |
| Void | `POST <void URL>` `{"paymentId", "authorizationReference"}` | `<paymentId>:void` |

An approved authorization must include `"expiresAt": "2026-01-22T10:30:00Z"`; without it the answer isn't trusted and the payment stays `PENDING`. With the status URL set, open steps are asked about with `GET <status URL>?paymentId=<id>&reference=<reference>&type=authorization|capture|void` (an `authorization` answer carries `expiresAt` too).

## Provider webhooks

A provider that leaves a payment `PENDING` reports the final result later by calling `POST /api/v1/providers/{name}/webhook`. Only providers with a webhook secret accept webhooks; today that is `http` with `PAYMENT_PROVIDER_HTTP_WEBHOOK_SECRET` set. Other names get `404 PROVIDER_NOT_FOUND`. The endpoint needs no API key: it is authenticated by the signature, an HMAC-SHA256 of the exact raw body with the shared secret, sent as `X-Webhook-Signature: sha256=<hex>`. The body has the same fields as the provider's answer, plus an optional `paymentId`:

```json
{"paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "status": "APPROVED", "reference": "acme_123"}
```

- The payment is found by the provider's `reference`. A payment that has no reference yet, for example because the provider timed out, is found by `paymentId`, if it belongs to that provider; the reference is then stored.
- A `PENDING` payment moves to `SUCCEEDED` or `DECLINED` (with `reason`) and its receipt is sent then. A `PENDING` webhook only stores the reference.
- A payment moves once. A repeated webhook, or one that contradicts the final status, changes nothing (the contradiction is logged) and still returns `204`, so the provider stops retrying. The webhook and the provider's own late answer can't both settle a payment: both lock the row and only change it while it is `PENDING`.
- `204` means accepted. `401 INVALID_WEBHOOK_SIGNATURE` means a missing or wrong signature, `400 INVALID_WEBHOOK` a signed body that isn't a valid webhook (unknown status, decline without reason, no reference), and `404 PAYMENT_NOT_FOUND` no matching payment yet (the provider should retry).

Refund results use the same endpoint and signature, with `"type": "refund"` (a body without `type`, or with `"type": "payment"`, is a payment webhook; any other type is `400 INVALID_WEBHOOK`):

```json
{"type": "refund", "refundId": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "status": "APPROVED", "reference": "acme_r_7"}
```

The refund is found by the provider's `reference` among that provider's refunds, or, while it has no reference yet, by `refundId`. It then moves once, exactly as a payment does; no match is `404 REFUND_NOT_FOUND`, so the provider retries.

Manual capture uses the same endpoint and signature with three more types:

```json
{"type": "authorization", "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "status": "APPROVED", "reference": "acme_a_1", "expiresAt": "2026-01-22T10:30:00Z"}
{"type": "capture", "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "status": "APPROVED", "reference": "acme_c_1"}
{"type": "void", "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "status": "APPROVED", "reference": "acme_v_1"}
```

- An `authorization` webhook is matched like a payment webhook and moves a `PENDING` manual payment to `AUTHORIZED` (approved, `expiresAt` required, else `400 INVALID_WEBHOOK`) or `DECLINED`.
- A `capture` or `void` webhook needs `paymentId` (else `400`) and must come from the payment's own provider (else `404 PAYMENT_NOT_FOUND`). It applies only while that capture or void is pending, so a webhook for a capture the merchant never asked for captures nothing. A declined one leaves the payment `AUTHORIZED`.

To set up webhooks for the `http` provider:

1. Choose a random secret of at least 16 characters and set `PAYMENT_PROVIDER_HTTP_WEBHOOK_SECRET` in `.env` (never commit it).
2. Give the provider the same secret and the webhook URL through the gateway, for example `https://<gateway host>/api/v1/providers/http/webhook`.
3. The provider signs every webhook body with the secret and retries until it gets `204`. Payments it answered with `PENDING`, or that timed out, then become `SUCCEEDED` or `DECLINED` and get their receipt.

## Adding a payment provider

1. Implement `PaymentProvider` in the `provider` package as a Spring `@Component`. `name()` returns a short unique name (at most 64 characters), for example `acme`. Startup fails if two providers share a name.
2. Put its settings in a `@ConfigurationProperties("payment.provider.acme")` record, validated like `SimulatedPaymentProviderProperties`, and add any environment variables to this README and `.env.example`.
3. Return `ProviderResult.approved(reference)` or `ProviderResult.declined(reason, reference)`, passing the provider's own transaction ID (at most 255 characters) when it has one. The provider is called outside any database transaction, after the `PENDING` payment is committed. A thrown exception counts as an unknown result: it is logged as an error with the payment ID, the payment stays `PENDING` with no receipt, and the merchant gets `201` with status `PENDING` (see [Payment statuses](payments.md#payment-statuses)).
4. Route methods to it, for example `PAYMENT_PROVIDER_ROUTES_CRYPTO=acme`. Payment rules (currencies and limits per method) still apply before any provider is called.
5. If the provider confirms payments later, return `ProviderResult.pending(reference)` and also implement `WebhookProvider`: `acceptsWebhooks()` (for example only when a secret is configured) and `readWebhook(body, signature)`, which checks the provider's signature over the raw body and returns the result with its reference. Its webhooks are then accepted at `/api/v1/providers/acme/webhook`.
6. If the provider can be asked about a payment, also implement `StatusCheckingProvider`: `checksStatus()` (for example only when a status URL is configured) and `checkStatus(request)`, which returns the current result for `request.paymentId()` and `request.providerReference()` (which may be `null`). Its `PENDING` payments are then re-checked on a schedule. Throw on anything you can't trust.
7. If the provider can refund, also implement `RefundingProvider`: `supportsRefunds()` and `refund(request)`, which returns the result for `request.refundId()` (with `paymentId`, the payment's `paymentReference`, `currency` and `amount`), in the same way as `process`. `simulated` refunds with the same success rate and latency as its payments, and `fixed` follows its configured outcome, with `sim_r_`/`fix_r_` references. To have `PENDING` refunds re-checked, implement `RefundStatusCheckingProvider.checkRefundStatus(request)` too; refund webhooks are read by the same `readWebhook` (see [Provider webhooks](#provider-webhooks)).
8. If the provider supports manual capture, also implement `AuthorizingProvider`: `supportsManualCapture()`, `authorize(request)` returning an `AuthorizationResult` (an approval needs its `expiresAt`), `capture(request)` and `voidAuthorization(request)` (`void` is a Java keyword). Captures and voids go to the provider that authorized the payment, by its stored name. To have pending authorizations, captures and voids re-checked, implement `AuthorizationStatusCheckingProvider` (`checkAuthorizationStatus`, `checkActionStatus`); its webhooks of types `authorization`, `capture` and `void` are read by the same `readWebhook`.

## SMS providers

- `log` (default) writes each message to the application log instead of sending it, with only the last 4 digits of the phone number, for example `SMS to ***2233: Payment 49.90 EUR succeeded`. The full number never appears in the log. Because no SMS is actually sent, the service logs one warning at startup (`SMS_PROVIDER=log: SMS are written to the log, not sent to phones…`), so a deployment that forgot to set `SMS_PROVIDER=http` is easy to spot.
- `http` sends `POST <NOTIFICATION_SMS_HTTP_URL>` with the JSON body `{"to": "+380501112233", "message": "Payment 49.90 EUR succeeded"}`. A `4xx` or `5xx` response, or a timeout, counts as a failed notification.
