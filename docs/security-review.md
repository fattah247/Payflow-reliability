# Security Review

## Idempotency abuse

Risk: an attacker or buggy client replays payment creation requests.

Example attack: the same request is sent many times after a timeout, including concurrently.

Impact: duplicate intents or duplicated downstream processing.

Mitigation in this repo: same key and payload returns the existing intent; a changed payload is rejected with 422. A unique constraint on (merchant, key) decides concurrent retries. Keys are scoped per merchant, so one merchant cannot probe another's keys.

Remaining limitation: no rate limiting. `merchantId` comes from the request body because there is no authentication.

## Webhook spoofing

Risk: an attacker sends a forged provider callback.

Impact: false success, state corruption.

Mitigation in this repo: the shared signature is compared in constant time. A missing or wrong signature returns 401 **before anything is written**.

Remaining limitation: the signature is a static shared secret (similar to some regional providers' callback tokens), not an HMAC over the body with a timestamp, so it does not prove body integrity or bound replay windows.

## Review-queue flooding

Risk: unauthenticated requests create review cases or audit rows.

Example attack: thousands of forged webhooks with random event ids.

Impact: operators drown in noise; storage grows.

Mitigation in this repo: rejected signatures persist nothing; they are logged at WARN and counted in `payflow.webhook.rejected`. Authenticated but repeated problems reuse the open case for the same reason and subject.

Remaining limitation: open-case deduplication is a lookup, not a database constraint.

## Replay and duplicate delivery

Risk: the same webhook is delivered more than once, possibly concurrently.

Impact: duplicate transaction rows or repeated state transitions.

Mitigation in this repo: the webhook inbox has a unique event id, payment rows are locked while an event is processed, and payments carry an optimistic version. A reused event id with a different payload goes to review.

Remaining limitation: no time window; very old valid events are still processed (and usually end as duplicates or contradictions).

## Concurrent money movement

Risk: two requests or jobs act on the same money at once.

Example: two settlement runs; a double-clicked refund; two operators resolving the same case.

Mitigation in this repo: per-payment row locks, optimistic versions, and database uniqueness (one settlement item per transaction, one inbox row per event, one provider transaction row per id). Losing requests get 409 and change nothing.

## Invalid state transition

Risk: late or contradictory events rewrite final states.

Mitigation in this repo: the state machine rejects invalid transitions, holds an in-flight payment so it cannot settle while the provider disagrees, and opens a review case. Holds apply only to in-flight states, so a final outcome such as `REFUNDED` can never be overwritten by a suspicious event.

## Refund abuse

Risk: refunds on reconciled payments without additional control; refunds for more or less than was paid.

Mitigation in this repo: refunds after reconciliation require an operator decision (approve or reject) recorded in the audit trail. Only full refunds are accepted. A payment has at most one live refund. A refund cannot be completed while the provider reports something that contradicts the payment (for example a chargeback), which prevents paying the customer back twice.

Remaining limitation: the operator `actor` is self-declared because there is no authentication; there is no four-eyes rule (requester and approver must differ).

## Silent data alteration

Risk: the database silently rounds monetary values.

Mitigation in this repo: amounts are validated to at most 2 decimals and 17 integer digits, and normalized to the stored scale without rounding.

## Audit log integrity

Risk: important changes happen without a trace, or the trace claims changes that were rolled back.

Mitigation in this repo: audit events are written in the same transaction as the change they describe, link to the payment, and carry the request's correlation id (`X-Request-Id`). Expected outcomes are returned rather than thrown, so their audit rows commit.

Remaining limitation: audit records live in the same database and are not tamper-evident.

## Error disclosure

Mitigation in this repo: unexpected errors return a generic 500 body with a request id; stack traces stay in the logs. Caller-supplied request ids are accepted only if they match a safe pattern, so they cannot inject into logs.
