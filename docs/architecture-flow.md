# Architecture Flow

End-to-end lifecycle of a payment, from creation to reconciliation, including the provider, the
background workers, and where humans decide. The webhook decision order is in the
[README](../README.md#webhook-decision-flow).

```mermaid
flowchart TD
    A[Merchant, API key] --> B[POST /api/payment-intents<br/>Idempotency-Key, merchant from key]
    B --> C{Key seen for this merchant?}
    C -- No --> C1[Insert intent CREATED<br/>201 Created]
    C -- Yes, same payload --> C2[Replay existing intent<br/>200 Idempotent-Replayed]
    C -- Yes, different payload --> C3[422 IDEMPOTENCY_KEY_REUSED]
    C1 --> D[POST /start<br/>CREATED to PENDING]

    D --> P[Customer pays at the provider]
    P --> W[Signed webhook<br/>HMAC + timestamp, verified before parsing]
    P -. webhook lost .-> SW[Status sweeper<br/>asks the provider, POLL event]
    W --> E[Same rules for webhook and poll<br/>row lock on payment, inbox]
    SW --> E
    E --> E1[APPLIED<br/>SUCCESS posts the CAPTURE journal]
    E --> E2[DUPLICATE_IGNORED / STALE_IGNORED]
    E --> E3[SENT_TO_REVIEW<br/>mismatch or contradiction holds an in-flight payment]
    E --> E4[DEFERRED 409<br/>on hold, provider redelivers]

    E3 --> R[Review queue<br/>allowedActions per case]
    R -- RELEASE / FAIL<br/>one operator --> E
    R -- REQUIRE_REVERSAL / APPROVE_REFUND<br/>proposal --> R2[Second operator approves]

    E1 --> F[POST /api/refunds<br/>partial, Idempotency-Key, in-flight amounts reserved]
    F -- reconciled payment --> R
    R2 --> O
    F --> O[Outbox command<br/>same transaction]
    E1 -- provider asks for reversal before payout --> O
    O --> OW[Outbox worker<br/>lease, call outside the transaction,<br/>lookup by key before resend, backoff + jitter]
    OW -- success --> OS[Refund COMPLETED / payment REVERSED<br/>REFUND / REVERSAL journal]
    OW -- refused --> OR[Refund FAILED + review case]
    OW -- unknown after retries --> OU[NEEDS_REVIEW<br/>RETRY_PROVIDER_CALL is safe]

    E1 & OS --> S[POST /api/settlements/batches<br/>locks payments in id order<br/>captures minus refunds and reversals<br/>negative net carries forward]
    S --> S1[SETTLED + PAYOUT journal]

    S1 --> Q[POST /api/reconciliation/reports<br/>verdict per row, plus payouts missing from the report]
    Q -- clean match --> Q1[RECONCILED]
    Q -- mismatch or missing --> R

    E1 & OS & S1 --> L[Ledger<br/>balanced journals, invariant check]
    E & R & O & S & Q --> AU[Hash-chained audit events<br/>actor + request id]
    AU & L --> CF[Case file, operations summary,<br/>Prometheus gauges and alerts]
```

## Lock order

Every flow that changes a payment locks the payment row first (several payments: in id order), then
dependent rows. Provider calls happen outside transactions. This single order is why concurrent webhooks,
settlement runs, refunds, review decisions and the outbox worker queue instead of deadlocking.

## Background jobs

| Job | Default interval | Purpose |
|---|---|---|
| Outbox worker | 1 s | Executes refunds and reversals at the provider |
| Provider simulator dispatcher | 1 s | Delivers simulated webhooks with retries (local only) |
| Status sweeper | 5 min | Recovers lost webhooks, expires abandoned payments |
| Ledger invariant check | 5 min | Books versus operational records |
| Idempotency cleanup | 1 h | Removes stored responses after 24 h |
| Metrics refresh | 30 s | Snapshot behind the alerting gauges |

All are switched off together with `payflow.workers.enabled=false` (tests run them explicitly).
