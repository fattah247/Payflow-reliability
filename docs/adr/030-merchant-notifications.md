# 030 Merchant Notifications

## Context

A shop sells something, creates a payment here, and sends its customer to pay. Then it has to know what happened:
ship the order when the payment succeeds, release the stock when it fails or is cancelled, tell the customer when a
refund lands. Until now the only way to know was to ask (`GET /api/payment-intents/{id}`) and keep asking. Shops
that poll too slowly ship late; shops that poll fast load both sides; shops that forget to poll ship nothing, or ship
an order whose payment was later reversed.

This system already *receives* webhooks from the provider and treats them carefully (signatures, a replay window, an
inbox with one outcome per event, ADR 004, 010, 014). Sending them is the same problem seen from the other side, with
two additions: the receiver is a server we do not control and that will be slow, down or wrong some of the time, and
the address is chosen by someone outside, so sending to it must not become a way into our network.

## Decision

**Events are written with the change (a transactional outbox).** Every payment state change already passes through
one place, `AuditService.recordStateChange`, because the audit chain needs it. That method now also publishes
`PaymentStateChanged`; payment creation publishes `PaymentCreated`, and every refund transition publishes
`RefundChanged`. `MerchantEventRecorder` listens in the same transaction (`Propagation.MANDATORY`) and writes a
`merchant_events` row with the exact JSON the shop will receive. A change that commits always has its event; one that
rolls back never does. No event is lost between "changed" and "sent", because there is no gap: sending happens later,
from the table.

**Events are numbered per payment.** `sequence` counts the events of one payment (its own events and its refunds'),
starting at 1. Every writer holds the payment's row lock, the same rule as the audit chain, so numbers never collide,
and a unique (payment, sequence) constraint would turn a collision into a retryable conflict. The number is what lets
a shop put deliveries back in order: retries mean a later event can arrive before an earlier one, and a shop that
applies an event older than the one it already has would move a paid order back to pending.

**Event types:** `payment.created`, `payment.updated`, `refund.created`, `refund.updated`. Each carries the state it
announces (a payment's state, a refund's status), the amounts as decimal strings (no JSON parser turns them into
floats), and the shop's own `merchantReference`, so the shop can find its order without storing our id.

**Delivery is at least once, signed, with backoff.** `MerchantWebhookDispatcher` takes due events oldest first. For
each one it

1. takes a **lease** in a short transaction (row lock, `lease_until`), so two instances never send the same attempt at
   once, and a crashed sender's attempts are taken over when the lease runs out;
2. **sends with no transaction or lock held**, so a slow shop never holds up payments; connect timeout 2 s, read timeout
   5 s, redirects never followed;
3. writes the **outcome** in another short transaction: any 2xx is delivered; anything else (an error status, a
   redirect, a timeout, a refused connection) is retried after `30 s × 2^(attempt−1)`, capped at an hour, with jitter
   (a random point in the upper half, so shops that failed together do not retry together). After 20 attempts,
   roughly 7 to 13 hours, the event is `FAILED`: kept, readable, and sent again on request.

A crash between sending and writing the outcome sends the event again after the lease. That is the price of at least
once, and the reason every delivery carries `Payflow-Event-Id`: shops process each id once.

**Signatures are the same scheme as inbound.** `Payflow-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret,
"<t>." + raw body)>`. Each shop has its own secret, generated here (`whsec_` and 32 random bytes) and shown once.
Rotating it keeps the previous secret signing *next to* the new one for 24 hours: deliveries carry two `v1` values,
so a shop that has not switched yet still verifies, and one that has switched verifies too. After the overlap only
the new secret signs.

**The events list is the record; webhooks are a prompt.** `GET /api/events` lists a shop's events oldest first,
paged with the same cursor as every other list, filterable by payment, type, status and a start time. A shop whose
server was down catches up from it instead of depending on our retries, and a shop that wants a copy asks for one
(`POST /api/events/{id}/redeliver`).

**Where we send is checked (SSRF).** A shop could register `https://169.254.169.254/...` (a cloud metadata service),
`https://localhost:5432` or an address on our private network and use deliveries to probe it. `DestinationGuard`
accepts only https URLs without credentials whose host resolves to public addresses: not loopback, private (10/8,
172.16/12, 192.168/16, fc00::/7), link-local (169.254/16, fe80::/10), carrier-grade NAT, multicast or reserved, also
when hidden in an IPv4-mapped or NAT64 IPv6 address. It checks when the endpoint is registered and again before every
delivery, because a name can resolve differently later. Redirects are never followed, because the URL was checked
and the redirect target was not. Local demos send to the merchant simulator on localhost, so
`payflow.merchant-webhooks.allow-private-addresses` exists; the application refuses to start with it (or the merchant
simulator) next to a real provider, the same guard as for the other simulators.

**Without an endpoint, events are still written**, as `SKIPPED`, and readable through the events list. A shop that
registers later can catch up; switching an endpoint off skips what was waiting.

**A money rule checks it.** `PAYMENT_CHANGES_ANNOUNCED`: every payment's latest event announces its current state, and
every state change has its own event. It runs with the other money rules, including after every random history of
faults in the property-based test.

## Alternatives considered

- **Send from the transaction (call the shop, then commit).** A slow shop would hold the payment's lock, a failed
  call would roll back a real payment change, and a call that succeeded before a rollback would announce something that
  never happened.
- **Send after commit (`@TransactionalEventListener(AFTER_COMMIT)`) without a table.** No lock held, but a crash right
  after the commit loses the event, and there is nothing to retry from or to show the shop.
- **A message broker.** Kafka or a queue would carry the events, but the outbox table is still needed to get them
  there atomically with the change, and the retries, leases and backoff per shop would remain. The outbox is the seam
  where a broker would go (ADR 015).
- **Exactly-once delivery.** Not possible over HTTP to a server we do not control: a shop can process a delivery and
  fail before answering. At least once with event ids and sequence numbers moves the last step to the shop, where it
  can be done correctly, and the integration guide shows how.
- **Shared secret per platform, or no signature, or IP allow-lists.** A secret per shop limits what one leaked secret
  exposes; allow-lists break when our egress addresses change and say nothing about the body.

## Consequences

Shops are told within about a second of a change (the sender runs every second), and every change is told at least
once while their endpoint works within the retry window, with a list to catch up from when it did not. The cost is a
row per event and a background job, and two things shops must do: verify the signature and deduplicate by event id
while ordering by sequence. [docs/merchant-webhooks.md](../merchant-webhooks.md) shows both, and the merchant simulator
does both in the demo.

Limits, honestly:

- The guard checks the address before sending, and the HTTP client resolves the name again when it connects. A name
  whose DNS answer changes between the two (DNS rebinding) could slip through. Production closes this by sending
  through an egress proxy that enforces the same rule on the address it actually connects to.
- Secrets are stored as they are, because signing needs them. Production keeps them encrypted with a key held
  outside the database.
- The events list is ordered by when an event was written; a transaction that commits late can land just behind a
  reader's cursor. The guide tells shops to catch up from a few minutes before their newest event and skip ids they
  have seen. All transactions here are short, but the advice is cheap insurance.
- One shop, one endpoint, every event type. Several endpoints per shop and per-type subscriptions are straightforward
  additions to the same table.
- Payments created before this change have no events; the money rule only checks payments created since.
