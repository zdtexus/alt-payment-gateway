# Payments

How a payment moves through the service, and the features built on it: safe retries, manual capture and refunds. Swagger UI (http://localhost:8080/swagger-ui.html) has every request and response body.

- [How a payment is processed](#how-a-payment-is-processed)
- [Payment statuses](#payment-statuses)
- [Re-checking pending payments](#re-checking-pending-payments)
- [Idempotent requests](#idempotent-requests)
- [Authorize and capture](#authorize-and-capture)
- [Refunds](#refunds)
- [Receipts](#receipts)

## How a payment is processed

Notifications are decoupled from payment processing through Spring application events. The listener runs asynchronously after the transaction commits, so a slow or failing mail server never affects the payment, and no receipt is sent for a payment that was rolled back.

```text
PaymentService.process()
  ├── 1. save Transaction as PENDING         transaction 1, committed
  ├── 2. PaymentProvider.process()           no transaction open
  └── 3. lock row, apply result              transaction 2
        └── SUCCEEDED / DECLINED: publish PaymentProcessedEvent
              └── NotificationListener       @Async, after commit
                    ├── EmailSender
                    └── SmsSender

POST /api/v1/providers/{name}/webhook        signed by the provider
  └── PaymentService.settleFromWebhook()     lock row, apply result if still PENDING
        └── SUCCEEDED / DECLINED: publish PaymentProcessedEvent (same listener)

POST /api/v1/payments/{id}/capture | /void   captureMethod MANUAL, status AUTHORIZED
  └── CaptureService                         lock row, claim the action, commit;
                                             call the provider; lock row, apply once
        └── SUCCEEDED / VOIDED: publish PaymentProcessedEvent (same listener)
```

## Payment statuses

A payment is stored as `PENDING` and committed before its provider is called, then updated to `SUCCEEDED` or `DECLINED` in a second transaction, and only then is a receipt sent. Keeping the provider call out of the transaction means a slow provider holds no database connection or row lock.

| Status | Meaning | Receipt |
|---|---|---|
| `PENDING` | Stored; the provider's result isn't known yet. `processedAt` is `null`. | none yet |
| `SUCCEEDED` | The provider approved the payment. Final. | sent |
| `DECLINED` | The provider declined it; `declineReason` says why. Final. | sent |
| `AUTHORIZED` | `captureMethod` `MANUAL` only: the amount is reserved until `authorizationExpiresAt`. `processedAt` is `null`. | none yet |
| `VOIDED` | `MANUAL` only: the authorization was voided, by the merchant or because it expired; nothing was charged. Final. | sent |

A `MANUAL` payment moves `PENDING` → `AUTHORIZED` → `SUCCEEDED` (captured, with `capturedAmount`) or `VOIDED`, each step once (see [Authorize and capture](#authorize-and-capture)). A refunded payment keeps `SUCCEEDED`; its refunds are a resource of their own with the same three statuses (see [Refunds](#refunds)). A payment moves from `PENDING` to `SUCCEEDED` or `DECLINED` once, either from the provider's answer to the payment request or later from the provider's [webhook](providers.md#provider-webhooks), whichever comes first; a final status never changes.

A payment stays `PENDING` when the provider's result is unknown: the provider answered `PENDING` (it confirms later, for example after a redirect), failed with an error, or didn't answer within its timeout. The merchant still gets `201` with status `PENDING` and the payment's `Location`. It gets no receipt yet, and is not charged again on an idempotent retry. A provider failure or timeout is logged as an error with the provider name and payment ID. The provider settles the payment through its webhook, or the service asks the provider again (see below), whichever happens first.

## Re-checking pending payments

A webhook can get lost, and some providers send none. So a background job regularly asks every provider that supports status queries (today `http` with `PAYMENT_PROVIDER_HTTP_STATUS_URL` set) what happened to its `PENDING` payments:

- **When.** A payment is first checked `PAYMENT_RECONCILIATION_MIN_AGE` (2 minutes) after its creation. The job runs every `PAYMENT_RECONCILIATION_INTERVAL` (1 minute) and checks at most `PAYMENT_RECONCILIATION_BATCH_SIZE` (50) due payments per run.
- **Backoff.** If the answer is still `PENDING`, or the check fails (timeout, error status, untrusted answer), the next check waits twice as long as the one before: 1, 2, 4, 8 … minutes, up to `PAYMENT_RECONCILIATION_MAX_BACKOFF` (1 hour). A payment is never declined or expired because it is old, because the provider may have charged it.
- **Settling.** An `APPROVED` or `DECLINED` answer settles the payment exactly like a webhook: the row is locked and changed only while it is `PENDING`, and the receipt is sent once. If a webhook settles the payment while a check is running, the webhook wins and the late answer is ignored (with a warning).
- **Alert.** A payment still `PENDING` after `PAYMENT_RECONCILIATION_ALERT_AFTER` (24 hours) is logged at `ERROR` on every check, with its ID, for example `Payment 4adb5fb6-… with provider http is still PENDING PT26H after it was created (check 9); check it with the provider`.
- **Several instances.** Each run claims its payments with `SELECT … FOR UPDATE SKIP LOCKED` and moves their next check forward before calling the provider. Instances sharing the database therefore never ask about the same payment at the same time, and they don't wait for each other.
- **Logs.** Each run that checked something logs one summary, for example `Checked 8 pending payments with their providers: 1 settled, 0 still pending, 7 failed`; a failed check is one `WARN` line with the cause.

`PENDING` refunds are re-checked the same way, with the same settings and backoff, through `GET <status URL>?refundId=…` (log `Checked N pending refunds with their providers: …`, alert `Refund … of payment … is still PENDING …`).

For manual capture, the same job asks `http` (with its status URL) about `PENDING` authorizations (`type=authorization`) and about captures and voids whose answer is unknown (`type=capture|void`, first checked `PAYMENT_RECONCILIATION_MIN_AGE` after the request, log `Checked N pending captures and voids with their providers: …`, alert `The capture of payment … is still pending …`). It also voids expired authorizations of every provider that supports manual capture, `simulated` and `fixed` included (log `Voided N expired authorizations with their providers: …`).

Payments of providers without status queries (`simulated`, `fixed`, or `http` without a status URL) are never checked; they are settled by the provider's answer or webhook. `GET /api/v1/payments?status=PENDING` lists everything still open. Set `PAYMENT_RECONCILIATION_ENABLED=false` to switch the job off.

## Idempotent requests

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

## Authorize and capture

A merchant that ships goods later can reserve the amount first and charge it when the order ships:

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "X-API-Key: demo-key-123" \
  -H "Content-Type: application/json" \
  -d '{"amount": 49.90, "currency": "EUR", "paymentMethod": "CARD", "captureMethod": "MANUAL"}'
```

The provider authorizes the amount, and the payment is `201` with status `AUTHORIZED`, no receipt yet, and the time the reservation lasts:

```json
{"id": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b", "amount": 49.90, "refundedAmount": 0.00, "currency": "EUR",
 "paymentMethod": "CARD", "captureMethod": "MANUAL", "status": "AUTHORIZED", "declineReason": null,
 "createdAt": "2026-01-15T10:30:00Z", "processedAt": null,
 "authorizationExpiresAt": "2026-01-22T10:30:00.300Z", "capturedAmount": null, "pendingAction": null}
```

Then capture all of it, or part of it (the rest is released), or void it:

```bash
curl -X POST http://localhost:8080/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/capture \
  -H "X-API-Key: demo-key-123" -H "Content-Type: application/json" -d '{"amount": 30.00}'

curl -X POST http://localhost:8080/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/void \
  -H "X-API-Key: demo-key-123"
```

A capture returns `200` with status `SUCCEEDED` and `capturedAmount` (`amount` stays what was authorized); without a body it captures everything. A void returns `200` with status `VOIDED`.

- **Once.** A payment is captured or voided once, never both: anything else gets `409 PAYMENT_NOT_CAPTURABLE` or `409 PAYMENT_NOT_VOIDABLE`. The row is locked while the request is claimed, so a capture and a void sent at the same time can't both win.
- **Never more than authorized.** A capture may be at most the authorized amount; more gets `422 CAPTURE_EXCEEDS_AUTHORIZED`. The amount has at most 2 decimals.
- **Expiry.** After `authorizationExpiresAt` a payment can't be captured any more (`409`), only voided. The background job voids expired authorizations by itself, through their provider, with the same receipt; a void the provider declines or fails is retried with the usual backoff.
- **Unknown result.** If the provider fails, times out or answers `PENDING`, the request gets `202` and the payment stays `AUTHORIZED` with `pendingAction: "CAPTURE"` or `"VOID"`. The provider's [webhook](providers.md#provider-webhooks) or a [status check](#re-checking-pending-payments) settles it later; until then another capture or void gets `409`.
- **Declined.** A capture or void the provider declines changes nothing: `422 CAPTURE_DECLINED` or `VOID_DECLINED` with the provider's reason, and the payment stays `AUTHORIZED`, so it can be captured or voided again, or expire. A declined authorization is a normal `DECLINED` payment.
- **Idempotent retries.** Send an `Idempotency-Key` with a capture or void: a retry with the same key and body returns the payment as it is now with `Idempotent-Replayed: true` and calls the provider once; the same key with another amount, or on the other action, gets `422 IDEMPOTENCY_KEY_REUSED`. Keys are scoped to the payment; a declined request frees its key.
- **Receipts.** None for the authorization; one when the payment is captured (`Payment 30.00 EUR succeeded`, with the captured amount) or voided (`Payment … voided`).
- **Refunds** are possible only after the capture, up to `capturedAmount` (see [Refunds](#refunds)).
- **Providers.** `simulated`, `fixed`, and `http` with its authorize, capture and void URLs support it. A `MANUAL` payment whose provider doesn't gets `422 MANUAL_CAPTURE_NOT_SUPPORTED` before anything is stored, so its idempotency key stays free.

## Refunds

A `SUCCEEDED` payment can be refunded in full or in several parts:

```bash
curl -X POST http://localhost:8080/api/v1/payments/7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b/refunds \
  -H "X-API-Key: demo-key-123" \
  -H "Content-Type: application/json" \
  -d '{"amount": 20.00, "reason": "Item returned"}'
```

Without a body (or with `{}`), everything that is still refundable is refunded. The response is `201 Created` with `Location: /api/v1/payments/{id}/refunds/{refundId}`:

```json
{"id": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "paymentId": "7f8e2a1c-4b3d-4e5f-9a6b-1c2d3e4f5a6b",
 "amount": 20.00, "currency": "EUR", "status": "SUCCEEDED", "reason": "Item returned",
 "declineReason": null, "createdAt": "2026-01-16T09:00:00Z", "processedAt": "2026-01-16T09:00:00.300Z"}
```

- **Only succeeded payments.** A `PENDING`, `DECLINED`, `AUTHORIZED` (capture it first) or `VOIDED` payment gets `409 PAYMENT_NOT_REFUNDABLE`. A payment of a provider that can't refund gets `422 REFUND_NOT_SUPPORTED`; today `simulated` and `fixed` can, and `http` can with `PAYMENT_PROVIDER_HTTP_REFUND_URL`.
- **Never more than was paid.** A refund may be at most the payment's amount (for a manually captured payment, its `capturedAmount`) minus its `SUCCEEDED` and `PENDING` refunds; more gets `422 REFUND_EXCEEDS_REMAINING` (`Refund 30.00 exceeds the remaining refundable amount 20.00`, or `Payment is already fully refunded`). The payment row is locked while a refund is created, so refunds sent at the same time can't add up to more either. The amount has at most 2 decimals; `reason` is optional, up to 255 characters.
- **The payment keeps its status.** A refunded payment stays `SUCCEEDED`; `refundedAmount` on the payment is the sum of its `SUCCEEDED` refunds (`0.00` when nothing was refunded).
- **Refund statuses** work like payment statuses: `PENDING` while the provider's answer isn't known (it answered `PENDING`, failed or timed out), then `SUCCEEDED` or `DECLINED` (with `declineReason`) exactly once. A `PENDING` refund still counts against the remaining amount and is settled later by the provider's [webhook](providers.md#provider-webhooks) or by [re-checking](#re-checking-pending-payments). A declined refund frees its amount again.
- **Idempotent retries** work as for payments: send an `Idempotency-Key`, and a retry with the same body returns the same refund with `Idempotent-Replayed: true` without calling the provider again; a different body gets `422 IDEMPOTENCY_KEY_REUSED`. Refund keys are separate from payment keys.
- **Receipts.** The merchant gets an email and an SMS once a refund becomes final, after it's committed (`Refund 10.00 EUR succeeded`).
- `GET /api/v1/payments/{id}/refunds` lists a payment's refunds, oldest first, and `GET /api/v1/payments/{id}/refunds/{refundId}` returns one. Another merchant's payment, or a refund of another payment, is `404`.

## Receipts

The merchant receives an email receipt and an SMS for every payment that becomes final, whether it succeeded or was declined. A `PENDING` payment gets its receipt when its provider's webhook settles it. A channel is skipped when the merchant has no email address or phone number. A failed email or SMS is logged as a warning with the payment ID and does not stop the other channel. The mail server is not part of `/actuator/health`, so a mail outage does not mark the service as down. Refunds get their own receipt (`Refund <id> succeeded`, SMS `Refund 10.00 EUR succeeded`) once they become final. An `AUTHORIZED` payment gets no receipt yet; it gets one when it is captured (with the captured amount) or voided.
