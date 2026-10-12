# Demo Flow

## Goal

Show the reliability rules in motion, not CRUD screens.

## Before starting

1. Start PostgreSQL with `docker compose up -d postgres`
2. Run the app with `./mvnw spring-boot:run`
3. In another terminal, run `./scripts/demo-readable.sh`

Every run uses fresh ids, so the demo can be repeated without resetting the database.

## Walkthrough

1. **Idempotent creation.** The first request gets 201. The same key and payload gets 200 with the same id. The same key with a different payload gets 422.
2. **Authorization then capture.** `AUTHORIZED` then `SUCCESS` on one provider transaction id, both `APPLIED`.
3. **Provider noise.** A retried event (`DUPLICATE_IGNORED`) and a late authorization (`STALE_IGNORED`) open zero review cases.
4. **Wrong amount.** The provider reports 59000 for a 60000 payment: 202 `SENT_TO_REVIEW`, payment held. The corrected event gets 409 `PAYMENT_ON_HOLD`. The review case offers `RELEASE_PAYMENT` and `FAIL_PAYMENT`. The operator releases, and the provider's redelivery is `APPLIED`.
5. **Settlement.** One batch for both payments; running it again gives 409 `NOTHING_TO_SETTLE`.
6. **Reconciliation.** One row `RECONCILED`, one `MISMATCH [AMOUNT_MISMATCH]`.
7. **Refund after reconciliation.** A partial refund gets 422. The full refund gets 202 `PENDING_REVIEW`; the operator approves; completing is idempotent.
8. **Case file.** What payment B is waiting on, every provider event and its outcome, and the full timeline.
9. **Operations summary.** Payments per state, the open review queue, refunds waiting, and settlement backlog.

## What to point out

- Retries and reordering are absorbed; only real anomalies reach a human.
- Status codes tell the provider what to do: 2xx stop, 409 on hold redeliver later.
- A hold always ends in a decision that changes the payment, and the decision is audited with its actor.
- Settlement only takes payments that are still plainly successful, and the database guarantees a transaction is paid out once.
- Every error carries a `code`, `retryable`, and a `requestId` that matches the logs and the audit trail.

## Scripted demos

```bash
./scripts/demo-readable.sh   # narrated
./scripts/demo.sh            # short
```

## Single scenarios

`./scripts/scenarios.sh <name>` runs one behavior in depth and checks it, printing PASS or FAIL per expectation (non-zero exit on failure). The README screenshots are this output.

| Scenario | Shows |
|---|---|
| `idempotency` | replay, payload mismatch, 8 parallel retries |
| `duplicate-webhook` | redelivery, restated status, late authorization, 10 parallel deliveries |
| `amount-mismatch` | hold, deferred redelivery, required decision, release, recovery |
| `settlement` | 4 racing runs, a payment skipped after a refund request |
| `reconciliation` | per-row verdicts, safe re-import |
| `audit-trail` | case file timeline with actors and request ids |
