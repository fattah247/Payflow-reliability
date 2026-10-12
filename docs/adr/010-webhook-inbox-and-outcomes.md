# 010 Webhook Inbox and Explicit Outcomes

Amends: 004 Webhook Handling

## Context

The first implementation stored one transaction row per webhook event, keyed by event id, and refused any second event for a provider transaction id. Real providers reuse the transaction id across a payment's lifecycle, so an authorization followed by a capture left the payment stuck in `AUTHORIZED` forever.

It also threw exceptions after opening review cases (invalid signature, unknown payment), which rolled the review cases back. The tests passed only because each test ran inside one transaction.

Every exact redelivery opened a review case, turning normal provider retry behavior into operator toil.

## Decision

- **Inbox.** `provider_events` records every authenticated event once (unique event id) with its outcome, detail, and review case. It is the deduplication key and the evidence trail.
- **Provider transactions.** One `payment_transactions` row per provider transaction id; its status is updated as lifecycle events arrive.
- **Explicit outcomes, returned not thrown:** `APPLIED`, `DUPLICATE_IGNORED`, `STALE_IGNORED`, `SENT_TO_REVIEW`, `DEFERRED`. HTTP status follows from the outcome: 200 / 202 tell the provider to stop retrying; 409 `PAYMENT_ON_HOLD` asks it to redeliver later.
- **Review only real anomalies:** a reused event id with a different payload, a provider transaction claimed by two payments, an amount or currency mismatch, a contradictory transition, an unknown payment. Exact redeliveries, restated statuses, and authorizations arriving after capture are acknowledged and audited, not escalated.
- **Disagreement stops money.** A mismatch or a contradiction (for example FAILED after SUCCESS, or a chargeback after settlement) holds an in-flight payment, so it is neither settled nor reconciled until an operator decides. A final outcome is never overwritten. A contradiction on a refund-requested payment blocks refund completion until the case is resolved.
- **Unauthenticated requests write nothing.** Opening review cases from forged requests would let anyone fill the review queue.
- **Holds defer, not drop.** While a payment is on hold, events are not recorded, so the provider's own retry delivers them after the operator decides.

## Tradeoffs

The inbox adds a table and a write per event. Deferral relies on the provider retrying for long enough; a provider that gives up quickly would need a local replay queue.

## Consequences

Authorization then capture works, every delivery is explainable after the fact, and the review queue contains only things a human must decide.
