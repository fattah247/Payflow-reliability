# Merchant Webhooks: Integration Guide

How a shop learns what happened to its payments and refunds, and the few things it must do to process those
notifications correctly. The design and its trade-offs are in [ADR 030](adr/030-merchant-notifications.md).

## 1. Register an endpoint

```bash
curl -s -X PUT localhost:8080/api/webhook-endpoint \
  -H "Authorization: Bearer $MERCHANT_KEY" -H "Content-Type: application/json" \
  -d '{"url": "https://shop.example/payflow/webhooks"}'
```

```json
{
  "url": "https://shop.example/payflow/webhooks",
  "enabled": true,
  "secret": "whsec_Qk2v...",
  "createdAt": "2026-10-17T09:00:00Z",
  "updatedAt": "2026-10-17T09:00:00Z"
}
```

**Store the `secret` now.** It is shown in this response and never again; if it is lost, rotate it (section 5).

The URL must be `https`, carry no user name or password, and point at a public address. Addresses on private networks,
loopback, link-local (for example `169.254.169.254`) and similar are refused with **422** `WEBHOOK_URL_NOT_ALLOWED`,
when you register and again before every delivery. Redirects are not followed: give the final URL.

Calling `PUT` again moves the endpoint to a new URL and keeps the secret. `GET /api/webhook-endpoint` shows the endpoint
without the secret; `DELETE /api/webhook-endpoint` switches deliveries off (events keep being recorded, see section 6).

## 2. What you receive

A `POST` to your URL with a JSON body:

```json
{
  "id": "5b0d8a7e-1d4c-4c5e-9a43-2f6f0d1f7a10",
  "type": "payment.updated",
  "createdAt": "2026-10-17T09:01:12.481Z",
  "merchantId": "shop-a",
  "sequence": 3,
  "data": {
    "paymentIntentId": "a41b6477-0a3c-4e3b-b1e5-6c1c6a0e9d21",
    "merchantReference": "order-1001",
    "state": "SUCCESS",
    "previousState": "PENDING",
    "amount": "150000.00",
    "currency": "IDR",
    "refundedAmount": "0.00",
    "reversedAmount": "0.00"
  }
}
```

and these headers:

| Header | Meaning |
|---|---|
| `Payflow-Event-Id` | The event's id (the same as `id` in the body). Process each id once. |
| `Payflow-Event-Type` | `payment.created`, `payment.updated`, `refund.created` or `refund.updated` |
| `Payflow-Signature` | `t=<unix seconds>,v1=<hex>`; during a secret rotation, two `v1` values |

| Type | When | `data` |
|---|---|---|
| `payment.created` | a payment was created (also when device risk immediately holds or blocks it: a `payment.updated` follows) | the payment, `state` = its first state |
| `payment.updated` | its state changed | the payment, `state` and `previousState` |
| `refund.created` | a refund was requested | `refundId`, `paymentIntentId`, `merchantReference`, `status`, `amount`, `currency` |
| `refund.updated` | its status changed (`COMPLETED`, `FAILED`, `REJECTED`, `CANCELLED`, approved after review) | the same, with `failureReason` when it failed |

Amounts are decimal strings in the currency's precision, so no JSON parser rounds them. `merchantReference` is the
reference you gave the payment, so you can find your order without storing our id.

The states that matter to most shops: `SUCCESS` (paid: ship), `FAILED`, `EXPIRED`, `CANCELLED` and `BLOCKED`
(not paid: release the order), `REQUIRES_CONFIRMATION` (ask the customer for the step-up), `REFUNDED` (fully refunded),
`REVERSAL_REQUIRED` and `REVERSED` (a chargeback: stop fulfilment if you can). `SETTLED` and `RECONCILED` are about
the money reaching you and need no action at checkout.

## 3. Verify the signature

Compute HMAC-SHA256 over `<t>.<raw body>` with your secret, hex-encode it, and compare it with each `v1` in constant
time. Accept the delivery if any matches and `t` is within five minutes of your clock. Use the **raw** body bytes: a
body parsed and re-serialized will not match.

```python
import hashlib, hmac, time

def verified(secret: str, header: str, raw_body: bytes, tolerance=300) -> bool:
    parts = [p.split("=", 1) for p in header.split(",")]
    timestamp = next((v for k, v in parts if k == "t"), None)
    signatures = [v for k, v in parts if k == "v1"]
    if timestamp is None or not signatures or abs(time.time() - int(timestamp)) > tolerance:
        return False
    expected = hmac.new(secret.encode(), timestamp.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    return any(hmac.compare_digest(expected, s) for s in signatures)
```

```java
boolean verified(String secret, String header, byte[] rawBody) {
    WebhookSignatures.Parsed parsed = WebhookSignatures.parse(header);   // or split on "," and "=" yourself
    if (parsed == null || Math.abs(Instant.now().getEpochSecond() - parsed.timestamp()) > 300) return false;
    byte[] expected = WebhookSignatures.expectedSignature(secret, parsed.timestamp(), rawBody).getBytes(UTF_8);
    return parsed.signatures().stream().anyMatch(s -> MessageDigest.isEqual(expected, s.getBytes(UTF_8)));
}
```

Answer **401** (or any non-2xx) when it does not verify. Never act on an unverified body.

## 4. Process each event once, in order

Deliveries are **at least once**: your server can process an event and fail to answer in time, and we will send it
again. They can also arrive **out of order**: if one delivery fails and is retried, a later event can overtake it.
Handle both:

```text
on delivery(event):
    if not verified(...):                 return 401
    if seen(event.id):                    return 200     # a copy: acknowledge, do nothing
    key = event.data.refundId or event.data.paymentIntentId
    if event.sequence < applied_sequence(key):
        mark_seen(event.id);              return 200     # older than what you have: ignore
    apply(event); set applied_sequence(key, event.sequence); mark_seen(event.id)
    return 200
```

Store "seen" and "applied sequence" in the same database transaction as the change you make (the order status), so a
crash cannot apply an event without remembering it. Answer quickly (within 5 seconds) and do slow work afterwards:
a slow answer counts as a failure and is retried.

`sequence` counts all events of one payment, including its refunds' events, starting at 1. Gaps are normal for a
given key (a refund's events and the payment's events share the counter).

## 5. Rotate the secret

```bash
curl -s -X POST localhost:8080/api/webhook-endpoint/rotate-secret -H "Authorization: Bearer $MERCHANT_KEY"
```

The response carries the new `secret` and `previousSecretSignsUntil`. For the next 24 hours every delivery is signed
with both secrets (two `v1` values), so switch your server to the new secret any time in that window without missing a
delivery. After it, only the new secret signs.

## 6. When your server was down: retries, failures and catching up

Failed deliveries (any non-2xx, a redirect, no answer within 5 seconds, a refused connection) are retried with
exponential backoff and jitter: about 30 seconds, then 1, 2, 4 minutes and so on, at most an hour apart, 20 attempts
over roughly 7 to 13 hours. After the last attempt the event is `FAILED`. Nothing is lost: every event stays in the
events list, oldest first.

```bash
# everything since a time (start a few minutes before the newest event you processed, and skip ids you have seen)
curl -s -H "Authorization: Bearer $MERCHANT_KEY" "localhost:8080/api/events?from=2026-10-17T08:55:00Z&limit=100"

# one payment's events
curl -s -H "Authorization: Bearer $MERCHANT_KEY" "localhost:8080/api/events?paymentIntentId=a41b6477-..."

# what failed
curl -s -H "Authorization: Bearer $MERCHANT_KEY" "localhost:8080/api/events?status=FAILED"

# send one again: a delivered, failed or skipped event from its first attempt, one waiting for a retry right away
curl -s -X POST -H "Authorization: Bearer $MERCHANT_KEY" localhost:8080/api/events/5b0d8a7e-.../redeliver
```

Each listed event has its delivery state (`PENDING`, `DELIVERED`, `FAILED`, `SKIPPED`), `attempts`, the last status
code and error, and `payload`: the exact body that is or was delivered. Follow the `Link: <...>; rel="next"` header (or
`X-Next-After`) to the next page.

Why start a few minutes back: the list is ordered by when each event was written, and a change whose transaction
commits a moment late can appear just behind a reader who already moved on. Deduplicating by id makes the overlap free.

Events written while you had no endpoint, or after you switched it off, are `SKIPPED`: never sent, always listed.

## 7. Try it locally

The application includes a simulated shop at `/merchant-sim/v1/shops/{shop}` that does everything above: verifies
signatures, deduplicates by id, ignores stale sequences, and can be told to fail or answer slowly.

```bash
./scripts/scenarios.sh merchant-webhooks
```

The scenario registers an endpoint, pays, refunds, takes the shop down mid-way, rotates the secret, and shows what the
shop applied, what it ignored and why.
