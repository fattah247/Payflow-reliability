# Idempotency Keys: the Client Contract

An idempotency key turns "I am not sure my request arrived" into a safe question: *send it again*. The server
remembers the key and answers a repeat with the original result instead of doing the work twice. That only
works if the client plays its part. This page is the part the client plays, why each rule exists, and what
happens when it is broken. The server side is in [ADR 024](adr/024-idempotency-keys-end-to-end.md).

## Why a server-side check is not enough

Picture a checkout. The customer presses Pay, the merchant's server calls `POST /api/payment-intents`, and the
response never comes back (a timeout, a dropped connection, a deploy). Did the payment get created?

```text
client                          payflow
  | -- POST (key=A) ------------->  creates payment P1
  |        x <---- 201 P1 ------  response lost
  | -- POST (key=A) ------------->  key A seen: same request? yes -> replay
  | <---- 200 P1, Replayed: true -
```

The second request is safe **only because it carries the same key**. If the client had generated a new key for
the second attempt, the server would see two unrelated requests and create P2. Two payments for one order is a
double charge, and no server-side check can tell it apart from a customer who really bought twice.

## The rules

### 1. One key per business operation, generated before the first attempt

A business operation is "charge order 1042", "refund 20.00 of payment P1". Generate the key **once**, when the
operation starts, and **store it with the order** before you send anything. Then every attempt, including one
made by a different process after a restart, can find and reuse it.

```python
# Good: the key belongs to the checkout, not to the HTTP call.
checkout = db.get_or_create_checkout(order_id=1042)
if checkout.idempotency_key is None:
    checkout.idempotency_key = f"checkout-{uuid4()}"
    db.save(checkout)                       # persisted before the first attempt

for attempt in range(5):
    try:
        return post("/api/payment-intents", body=checkout.request_body(),
                    headers={"Idempotency-Key": checkout.idempotency_key})
    except (Timeout, ConnectionError, ServerError):
        sleep(backoff_with_jitter(attempt))  # same key, same body, every time
```

```python
# Bad: a new key per attempt. A lost response becomes a second payment.
for attempt in range(5):
    post("/api/payment-intents", body=..., headers={"Idempotency-Key": str(uuid4())})
```

Good key sources: a random UUID stored with the order, or a value derived from your own stable identifier
(`checkout-<orderId>`, `refund-<returnId>`). Bad sources: the time, the attempt number, a request id, anything
that changes between attempts.

### 2. Retry with the identical request

The server compares a canonical **fingerprint** of the request with the one stored under the key:

| Change between attempts | Same request? |
|---|---|
| JSON keys in another order, other whitespace | yes |
| `50000` instead of `50000.00` | yes (amounts compare by value) |
| query parameters in another order | yes |
| a different amount, currency, reference or reason | **no: 422 `IDEMPOTENCY_KEY_REUSED`** |
| the same key on a different endpoint | **no: 422** |

A 422 means your client has a bug: it used one key for two different things. Do not retry it; fix it.

### 3. Know what each answer means

| Response | Meaning | What to do |
|---|---|---|
| `201 Created` | First time this key was seen; the operation happened | done |
| `200` + `Idempotent-Replayed: true` | An earlier attempt already succeeded; this is its result | done (it is the same payment) |
| `409 IDEMPOTENCY_KEY_IN_PROGRESS` (`retryable: true`) | An earlier attempt with this key is still running | wait and retry with the same key |
| timeout, connection error, `5xx` | Unknown: it may or may not have happened | retry with the same key |
| `4xx` with `retryable: false` | Refused; repeating the same request gives the same answer | do not retry; fix the request |
| `422 IDEMPOTENCY_KEY_REUSED` | Same key, different request | bug in the client |

Every error carries `retryable`, so a client never has to parse messages to decide.

### 4. Keys live as long as a retry can still arrive

| Key | Remembered for | Why |
|---|---|---|
| payment creation | as long as the payment exists | a retry a month later must not create a second payment |
| refund creation | as long as the refund exists | same reason |
| optional key on operator calls | 24 hours | operator actions re-check their preconditions, so a late repeat is harmless |

### 5. Scope: your keys are yours

Keys are scoped to the caller and the operation: merchant A and merchant B can both use `checkout-1`, and the
same refund key on two different payments creates two refunds. Within one scope, a key names exactly one
operation.

### 6. Lost the key? Look the payment up by your own reference

If your process crashed after sending the request but before it stored the key, you cannot retry safely: a new
key would be a new payment. Find what happened by the reference you gave the payment instead:

```bash
curl -H "Authorization: Bearer $MERCHANT_KEY" "localhost:8080/api/payment-intents?merchantReference=checkout-1001"
```

It returns your payments with that reference, newest first (at most 50; never another merchant's). An empty list
means the request never created anything, and a new attempt is safe. Store the key before the first attempt and
this is never needed.

## The same rules apply to us

Payflow is a client too, of the payment provider. The calls it makes follow the same contract:

- the key is derived from the business operation when the call is recorded (`refund-<refundId>`), in the same
  database transaction as the refund itself, never generated per attempt;
- the request is recorded as "sent" before it leaves, so a worker that dies after the provider acted is
  replaced by one that resends **the same key** and gets the provider's stored answer;
- providers forget keys after a while (commonly 24 hours). A call first sent longer ago than that is only looked
  up, never resent, because the provider would treat the resend as new.

`OutboundIdempotencyKeyTest` proves each of these from the provider's own request log, including a real crash of
the worker right after the provider refunded the customer.

## Try it

```bash
./scripts/scenarios.sh idempotency          # replay, changed payload, 8 parallel retries
./scripts/scenarios.sh outbound-key-crash   # the provider acts then times out; one outbound key, applied once
./scripts/scenarios.sh slow-but-up          # impatient customers retry; every retry answered by its key
```
