# Demo Flow

## Goal

Show the reliability rules in motion, not CRUD screens.

## Before starting

1. Start PostgreSQL with `docker compose up -d postgres`
2. Run the app with `./mvnw spring-boot:run` (background workers and the provider simulator start with it)
3. In another terminal, run `./scripts/demo-readable.sh`

Every run registers a fresh merchant and uses fresh ids, so the demo can be repeated without resetting the
database. The scripts use the local demo operator keys (`pf_o_local_ops-ana`, `pf_o_local_ops-budi`) and
webhook secret from `application.yml`; override them with `OPS_KEY`, `OPS_KEY_2`, `WEBHOOK_SECRET`.

## Walkthrough (`demo-readable.sh`)

1. **Onboarding.** An operator registers a merchant; the API key is shown once. No key is 401; a merchant key
   on an operator endpoint is 403.
2. **Idempotent creation.** 201, then 200 with `Idempotent-Replayed: true`, then 422 for a changed payload.
3. **The customer pays at the provider simulator.** Authorization and capture arrive as signed webhooks, each
   delivered three times; each distinct event is applied once and the capture is booked. A forged webhook is 401.
4. **Wrong amount.** 202 `SENT_TO_REVIEW` and a hold; the corrected event gets 409 until an operator releases
   the payment; the provider's redelivery is applied.
5. **Partial refund while the provider times out.** The provider refunds but answers late; the next attempt
   finds the refund by its idempotency key. The customer is refunded once. Refunding more than is left is 422.
6. **Net settlement.** Captures minus the refund; running it again is 409.
7. **Two-sided reconciliation.** The reported payment reconciles (including its refunded amount); the paid-out
   payment the provider did not report is flagged.
8. **Refund after reconciliation.** One operator proposes, self-approval is 403, a second operator approves,
   the provider executes it.
9. **Books and evidence.** What is owed to the merchant, ledger invariants, the payment's verified audit chain,
   and what the held payment is waiting on.
10. **Operations summary.**

## What to point out

- Retries, duplicates and reordering are absorbed; only real anomalies reach a human.
- Status codes tell the provider what to do: 2xx stop, 409 on hold redeliver later, 401 bad signature.
- "The provider did it but we do not know" is a normal state with a safe way out, not a guess.
- Money moves only when the provider confirms, and every movement is a balanced journal.
- Decisions that move money need two operators; every action is in a hash chain with its actor and request id.

## Single scenarios

`./scripts/scenarios.sh <name>` runs one behavior in depth and checks it, printing PASS or FAIL per
expectation (non-zero exit on failure). The README screenshots are this output.

| Scenario | Shows |
|---|---|
| `idempotency` | replay, payload mismatch, 8 parallel retries |
| `duplicate-webhook` | redelivery, restated status, late authorization, 10 parallel deliveries, one journal |
| `amount-mismatch` | hold, deferred redelivery, required decision, release, recovery |
| `provider-outage` | refund committed at the provider but timed out; resolved by lookup, refunded once |
| `lost-webhook` | the status sweeper recovers a payment whose webhook was dropped |
| `settlement` | 4 racing runs, refunds deducted, a held payment skipped, ledger at zero |
| `reconciliation` | per-row verdicts, a payout missing from the report, safe re-import |
| `four-eyes` | chargeback after payout, proposal, self-approval refused, second operator, reversal booked |
| `security` | no key, other merchant, wrong role, forged and replayed webhooks |
| `audit-trail` | case file timeline with actors, request ids and a verified hash chain |

`./scripts/demo.sh` is a one-minute version of the walkthrough.
