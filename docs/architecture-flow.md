# Architecture Flow

End-to-end lifecycle of a payment, from creation to reconciliation and refund, including where humans decide. The webhook decision order is in the [README](../README.md#webhook-decision-flow).

```mermaid
flowchart TD
    A[Merchant API client] --> B[POST /api/payment-intents<br/>Idempotency-Key]
    B --> C{Key seen for this merchant?}
    C -- No --> C1[Insert intent CREATED<br/>201 Created]
    C -- Yes, same payload --> C2[Replay existing intent<br/>200 Idempotent-Replayed]
    C -- Yes, different payload --> C3[422 IDEMPOTENCY_KEY_REUSED]
    C1 -. concurrent loser hits unique key .-> C2

    C1 --> D[POST /start<br/>CREATED to PENDING]
    C2 --> D

    D --> E[Provider webhooks<br/>row lock on payment]
    E --> E1[APPLIED<br/>PENDING to AUTHORIZED to SUCCESS<br/>one provider tx row, status updated]
    E --> E2[DUPLICATE_IGNORED / STALE_IGNORED<br/>audited, no review]
    E --> E3[SENT_TO_REVIEW<br/>mismatch or contradiction holds an in-flight payment]
    E --> E4[DEFERRED 409<br/>payment on hold, provider redelivers]

    E3 --> R[Review queue<br/>allowedActions per case]
    R -- RELEASE_PAYMENT --> E
    R -- FAIL_PAYMENT --> X1[FAILED]
    R -- REQUIRE_REVERSAL --> X2[REVERSAL_REQUIRED]

    E1 --> S[POST /api/settlements/batches<br/>locks payments in id order<br/>only payments still SUCCESS<br/>one currency per batch]
    S --> S1[SETTLED<br/>one batch item per transaction, enforced by DB]

    S1 --> Q[POST /api/reconciliation/reports<br/>verdict per row]
    Q -- clean match --> Q1[RECONCILED]
    Q -- mismatch --> R
    Q -- re-import --> Q2[ALREADY_RECONCILED]

    E1 --> F[POST /api/refunds<br/>full amount, one live refund]
    S1 --> F
    Q1 --> F
    F -- SUCCESS or SETTLED --> F1[REFUND_REQUESTED]
    F -- RECONCILED --> F2[Refund PENDING_REVIEW<br/>202 Accepted]
    F2 --> R
    R -- APPROVE_REFUND --> F1
    R -- REJECT_REFUND --> F3[Refund REJECTED<br/>payment stays RECONCILED]
    F1 --> F4[POST /refunds/id/complete<br/>REFUNDED]

    E1 & E2 & E3 & S1 & Q1 & F1 & F4 & R --> AU[Audit events<br/>payment id + request id]
    AU --> CF[GET /api/payment-intents/id/case-file<br/>GET /api/operations/summary]
```
