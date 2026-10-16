# Runbook

One section per alert in [`ops/prometheus/alerts.yml`](../ops/prometheus/alerts.yml). Each says what the
alert means, why it matters for money, and what to do. All endpoints need an operator API key
(`Authorization: Bearer <key>`); every decision an operator takes is recorded in the audit chain.

General first steps for any payment-level problem:

```bash
curl -s -H "Authorization: Bearer $OPS_KEY" localhost:8080/api/operations/summary | jq
curl -s -H "Authorization: Bearer $OPS_KEY" localhost:8080/api/operations/money-at-risk | jq '.items[] | select(.count > 0)'
curl -s -H "Authorization: Bearer $OPS_KEY" localhost:8080/api/operations/invariants | jq '.rules[] | select(.holds | not)'
curl -s -H "Authorization: Bearer $OPS_KEY" localhost:8080/api/payment-intents/<id>/case-file | jq '.attention'
```

Money at risk says where money is waiting (past the SLO, abandoned, held, in breaks by age, awaiting a step-up,
blocked today); the invariants say which money rule is broken and for which records. The case file shows the
payment, what is known at each stage of its money and the evidence (`certainty`), its device-risk decision,
provider events, provider calls (outbox), refunds, settlement items, ledger journals, review cases, whether the
audit chain verifies, and the full timeline.

---

<a id="ledger-invariant-violated"></a>
## PayflowLedgerInvariantViolated (page)

**Meaning.** The last ledger check found a journal whose postings do not sum to zero, or a merchant
balance that differs from what payments, refunds, reversals and payouts imply.

**Why it matters.** The books no longer explain the money. Payouts computed from either side may be wrong.

**Do.**
1. `GET /api/ledger/invariants` lists each violation with merchant, currency, expected and actual.
2. For `UNBALANCED_JOURNAL`: the journal was written around the service (the service refuses unbalanced
   journals). Find who wrote it from database logs; ledger tables are append-only in PostgreSQL, so a
   bypass needed `SET LOCAL payflow.evidence_maintenance = 'on'`.
3. For `MERCHANT_BALANCE_MISMATCH`: compare `GET /api/ledger/balances?merchantId=` with recent
   settlement batches and refunds for that merchant; look for a journal without a matching record (or
   the reverse) around the time the check last passed.
4. Pause payouts for the affected merchant until the difference is explained. Correct with a new,
   documented journal; never edit or delete existing ones.

<a id="ledger-check-not-running"></a>
## PayflowLedgerCheckNotRunning (ticket)

**Meaning.** The scheduled ledger check has not completed for over 30 minutes.

**Do.** Check that background workers are enabled (`payflow.workers.enabled`), the scheduler thread is
alive (thread dump), and the database is reachable. Run a check on demand: `GET /api/ledger/invariants`
returns the last report; restarting one instance restarts its scheduler.

<a id="provider-call-outcome-unknown"></a>
## PayflowProviderCallOutcomeUnknown (page)

**Meaning.** A refund or reversal call got no definite answer from the provider (timeouts, 5xx, 409/429)
after every retry. The money may or may not have moved.

**Why it matters.** Until resolved, the refund stays "in progress" and its amount stays reserved, so the
customer may be waiting and the merchant's refundable amount is reduced.

**Do.**
1. `GET /api/outbox?status=NEEDS_REVIEW` shows the command, its idempotency key, attempts and last error.
2. Check provider status. Retrying is always safe: the provider deduplicates by idempotency key, and the
   worker first asks the provider whether it already processed the key before resending.
3. If `lastError` says the answer "could not be recorded", the provider answered but its answer contradicts our
   books (for example it reversed more than we think is unrefunded): compare the provider dashboard with the
   case file before retrying.
4. Resolve the `PROVIDER_OUTCOME_UNKNOWN` review case with `RETRY_PROVIDER_CALL`
   (or `POST /api/outbox/<id>/retry`). Never create a second refund for the same request: a call that was
   dispatched cannot be cancelled, because the provider may already have applied it.

A **refused reversal** (`PROVIDER_REJECTED_OPERATION` on a provider call) leaves the payment in
`REVERSAL_REQUIRED`. Once the reason is cleared with the provider, `RETRY_PROVIDER_CALL` sends it again
under a new idempotency key (a reused key would only return the cached refusal).

<a id="provider-calls-backlog-aging"></a>
## PayflowProviderCallsBacklogAging (page)

**Meaning.** A provider call has been pending for over 15 minutes.

**Do.** Usually the provider is down (calls retry with exponential backoff up to 5 minutes apart) or the
outbox worker is not running. Check `GET /api/outbox?status=PENDING` (`lastError`, `nextAttemptAt`,
`leaseOwner`). A command blocked by an open `INVALID_STATE_TRANSITION` case waits for that decision on
purpose: money is not returned while the provider contradicts the payment.

<a id="provider-calls-failing"></a>
## PayflowProviderCallsFailing (ticket)

**Meaning.** Over 20% of provider calls in the last 10 minutes ended without a definite answer.

**Do.** Compare with the provider's status page; check `payflow_outbox_calls_total` by `result` and the
client timeouts (`payflow.provider.connect-timeout`, `read-timeout`). Expect the
`PayflowProviderCallOutcomeUnknown` alert next if it persists.

<a id="reconciliation-breaks-overdue"></a>
## PayflowReconciliationBreaksOverdue (page)

**Meaning.** The ledger, the provider's report and the bank statement disagree about at least one payout, and
the disagreement has been open longer than its window (24 h for missing, returned or refused payouts and
statement conflicts, 48 h for unexplained or late credits and amount mismatches, 72 h for duplicates and
provider report differences).

**Why it matters.** Each break is money we cannot account for: a payout that left the provider and did not
arrive, a credit nobody can explain (held in `bank_suspense`), or a provider report that contradicts our books.

**Do.**
1. `GET /api/reconciliation/breaks?status=OPEN` lists each break with its category, owner, amount, age and
   `allowedActions`. `GET /api/reconciliation/three-way/batches/<batchId>` shows the three legs of a payout
   side by side, with every check and whether it passed.
2. By owner:
   - **TREASURY** (`MISSING_CREDIT`, `UNMATCHED_CREDIT`, `UNMATCHED_DEBIT`, `DUPLICATE_CREDIT`, `LATE_CREDIT`,
     `RETURNED_PAYOUT`, `STATEMENT_CONFLICT`): ask the bank. A late or misreferenced credit that the bank
     confirms belongs to a payout: `POST /api/reconciliation/breaks/<id>/resolve` with
     `{"action": "ASSIGN_TO_BATCH", "batchId": "...", "note": "..."}`. The amount is still checked.
   - **PROVIDER_OPERATIONS** (`PROVIDER_REFUSED_PAYOUT`, `AMOUNT_MISMATCH`, `PROVIDER_PAYOUT_MISMATCH`,
     `MISSING_FROM_PROVIDER_REPORT`, `UNKNOWN_PROVIDER_PAYOUT`): ask the provider, with the payout reference.
3. Breaks that hold money in suspense cannot be acknowledged away: they close when the money is explained (a
   matching bank line, a return, or an assignment). The others can be closed with `ACKNOWLEDGE` and a note
   once the investigation is done outside the system.
4. A missing credit that arrives later lands the payout automatically and closes the break.

<a id="reconciliation-not-running"></a>
## PayflowReconciliationNotRunning (ticket)

**Meaning.** The scheduled three-way reconciliation (suspense retries, missing credits, payments ready to
reconcile) has not completed for over 30 minutes.

**Do.** Check that background workers are enabled and the database is reachable. Run it on demand:
`POST /api/reconciliation/three-way/run` returns what it did. Until it runs, missing credits are not declared
and payments whose payout landed stay SETTLED.

<a id="bank-statements-not-arriving"></a>
## PayflowBankStatementsNotArriving (ticket)

**Meaning.** No bank statement has been imported for 36 hours. Banks deliver end-of-day statements daily.

**Why it matters.** Without statements no payout can land, no credit can be declared missing (that needs every
statement through the end of a payout's window), and suspense items cannot be explained.

**Do.**
1. `POST /api/bank/statements/pull` fetches and imports what the bank has; the response shows each statement's
   outcome and how far coverage reaches (`coveredThrough`).
2. If the bank feed is down, import files by hand: `POST /api/bank/statements` with the camt.053 file
   (`Content-Type: application/xml`). Importing the same file twice is harmless.
3. If statements arrive but coverage does not move, a statement in the sequence is missing: ask the bank for it.

<a id="slow-but-up"></a>
## PayflowSlowButUp (page)

**Meaning.** Every health check reports UP, yet money is at risk: at least three payments have waited for the
provider's confirmation longer than the checkout SLO (`payflow.checkout.confirmation-slo`, 10 s), or customers
are abandoning checkout (three or more in ten minutes).

**Why it matters.** Nothing is "down", so no availability alert fires, while sales are being lost and customers
who are not sure they paid are about to try again. This is the alert for the provider being slow, not absent.

**Do.**
1. `GET /api/operations/summary` → `moneyAtRisk`: `PENDING_BEYOND_SLA` (count and amount waiting) and
   `ABANDONED` (count, amount and amount per minute over the window).
2. Check the provider's status page and the latency of its webhooks. If webhooks are delayed, the status sweeper
   (`POST /api/operations/sweeps/payment-status?olderThanSeconds=60`) asks the provider directly.
3. Watch `PayflowDuplicateCharge`: impatient customers retry, and only clients that reuse their idempotency key
   are protected.
4. Tell merchants: a payment page that does not load is costing them checkouts right now.

<a id="checkout-slo-burn"></a>
## PayflowCheckoutSloBurn (page)

**Meaning.** The share of checkouts confirmed later than the SLO is using up the error budget fast: more than
14.4 times the allowed rate over an hour, or 6 times over six hours (with a 95 % objective, 5 % may be slow).

**Do.** Same first steps as PayflowSlowButUp. `payflow_payment_confirmation_seconds` (histogram, with the SLO as a
bucket boundary) shows how slow; `payflow_payment_confirmations_total{within_slo}` shows how many.

<a id="duplicate-charge"></a>
## PayflowDuplicateCharge (page)

**Meaning.** A merchant reference was captured more than once: a customer was charged twice for one checkout.

**Why it matters.** The server deduplicates only requests that carry the same idempotency key. A duplicate means a
client generated a new key for a retry (see [the client contract](idempotency-keys.md)).

**Do.**
1. `GET /api/operations/summary` → `moneyAtRisk.DUPLICATE_CHARGES` gives the count and the extra amount.
2. Find the payments: same `merchantReference`, more than one captured (`GET /api/payment-intents/<id>` for each).
3. Refund the extra charge (`POST /api/refunds/payment-intents/<id>` with its own key) and contact the merchant
   about their client's key generation.

<a id="payments-stuck"></a>
## PayflowPaymentsStuck (ticket)

**Meaning.** More than 10 payments have waited on the provider (PENDING/AUTHORIZED) longer than
`payflow.sweeper.stuck-after`.

**Do.** The status sweeper asks the provider about these payments and applies the answer as a `POLL`
event. If the number keeps growing: webhooks may not be arriving (check `PayflowWebhookSignatureFailures`,
the provider's webhook dashboard) and the provider status API may be failing (sweeper `errors` in the
logs). Trigger a sweep: `POST /api/operations/sweeps/payment-status`.

<a id="webhook-signature-failures"></a>
## PayflowWebhookSignatureFailures (ticket)

**Meaning.** Webhooks are being rejected with 401: missing or wrong signature, or a timestamp outside
the 5-minute replay window. Nothing from those requests is stored.

**Do.**
- `payflow_webhook_rejected_total` by `reason` tells which:
  - `signature_mismatch` right after a secret rotation: add the new secret first in
    `PAYFLOW_PROVIDER_WEBHOOK_SECRETS` and keep the old one until the provider switches.
  - `timestamp_outside_tolerance`: check the server clock (NTP).
  - Unexpected traffic: someone may be probing the endpoint; the provider will redeliver genuine events.
- Lost events are recovered by the status sweeper, so payments are not stuck forever.

<a id="webhooks-sent-to-review"></a>
## PayflowWebhooksSentToReview (ticket)

**Meaning.** Many provider events contradict our records (amount/currency mismatch, conflicting
transaction, impossible transition) and went to manual review.

**Do.** Group the review queue by reason (`GET /api/operations/summary`). A burst with one reason usually
means a provider-side bug or an integration change; contact the provider before resolving cases one by one.

<a id="http-errors"></a>
## PayflowHttpErrors (page)

**Meaning.** Over 2% of requests return 5xx.

**Do.** Search logs by `requestId` (returned in `X-Request-Id` and in every error body). `DATA_INTEGRITY_ERROR`
means a database constraint caught a bug: the change was rolled back, nothing partial was saved.
Check database health and connection pool metrics (`hikaricp_*`).

<a id="review-queue-aging"></a>
## PayflowReviewQueueAging (ticket)

**Meaning.** A review case has waited for over 4 hours. Held payments do not settle, and webhooks for
them are deferred until a decision.

**Do.** `GET /api/review-cases` lists unresolved cases oldest first with `allowedActions`; decide each.

<a id="approval-waiting"></a>
## PayflowApprovalWaiting (ticket)

**Meaning.** A money-moving decision (approve a refund on a reconciled payment, require a reversal) was
proposed and waits for a second operator for over an hour.

**Do.** A different operator reviews the proposal (`GET /api/review-cases?status=AWAITING_APPROVAL`) and
calls `/approve` or `/reject-proposal`. The proposer cannot approve their own proposal.

<a id="metrics-stale"></a>
## PayflowMetricsStale (ticket)

**Meaning.** The operational gauges have not refreshed for 5 minutes, so other alerts may be blind.

**Do.** The refresh reads the database: check database availability and the application log for
"Could not refresh operational metrics". Check that the scheduler is running.
