# 009 Concurrency Control

## Context

Tests that ran requests one at a time showed a correct system. Running them concurrently did not:

- four simultaneous settlement runs each settled the same 20 transactions, paying the merchant four times
- eight simultaneous creates with one idempotency key: four failed with an unhandled unique violation (500)
- two webhooks for one payment could both read `PENDING` and both write, so the last writer won and the audit trail claimed both transitions happened

## Decision

Three layers, each covering what the one above can miss:

1. **Per-payment serialization.** Every flow that changes one payment (webhooks, refunds, review decisions, start) first loads it with a row lock (Hibernate emits `FOR NO KEY UPDATE` on PostgreSQL). Work on one payment queues; each request sees the previous one's committed result. Settlement locks all candidate payments in id order, re-checks their state under the lock, and only then loads and changes their transactions.
2. **One lock order: payment, then anything that belongs to it.** Webhooks lock the payment and then update its provider transaction or reference its review case; review resolution locks the payment and then the case; settlement locks payments and then transactions. An independent review found settlement doing the reverse (transactions flushed before payments), which can deadlock against a webhook; a mutation test now fails if the payment locks are removed.
3. **Optimistic versions.** `@Version` on payments, provider transactions, refunds, and review cases. A write path that does not lock (reconciliation) fails with `409 CONCURRENT_MODIFICATION` (retryable) instead of overwriting.
4. **Database invariants.** Unique (merchant, idempotency key), unique provider transaction id, unique provider event id, unique settlement item per transaction, plus check constraints on hold state and settlement fields.

Idempotent create retries once in a fresh transaction when it loses the unique-key race, then replays the winner. Unique violations are detected by SQLState `23505`, because Hibernate does not classify them as UNIQUE on PostgreSQL.

## Alternatives considered

- optimistic locking only: correct, but every concurrent duplicate webhook becomes a 409 and a provider retry
- serializable isolation: heavier, and the failure mode (serialization errors) is harder to explain to API clients
- application-level distributed locks: unnecessary with one database

## Tradeoffs

Row locks hold for the length of a request transaction, which is short here. A settlement run holds locks on every payment it settles until it commits, so webhooks for those payments wait for the batch. At much larger volumes that would call for smaller batches or `SKIP LOCKED`.

## Consequences

Each concurrency test fails when its protection is removed (verified by mutation), and the suite runs on PostgreSQL in CI, where locking semantics differ from H2.
