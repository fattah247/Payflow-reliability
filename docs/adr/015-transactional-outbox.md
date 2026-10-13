# 015 Provider Calls through a Transactional Outbox

## Context

Refunds were "completed" by an API call with no provider involved, and reversals were only a state. Calling a provider inside a database transaction holds row locks during network I/O and cannot be made atomic with the database: a crash between the call and the commit loses the result, and a timeout says nothing about whether the provider acted.

## Decision

- A refund or reversal is recorded together with an **outbox command** in the same transaction. Nothing calls the provider during the request.
- A worker claims due commands with a **lease** (an atomic update), calls the provider **outside** any transaction, then records the result, the domain change and the ledger journal in one transaction that locks the payment first.
- Every call carries an idempotency key derived from the command (`refund-<id>`, `reversal-<payment>`).
- Before the first call leaves, the command is **marked dispatched in its own committed transaction**, and the provider transaction it is about is pinned. From then on the provider may have acted: the command can no longer be cancelled, and every later attempt first **asks the provider what happened to the key**, then resends the identical request only if the provider never saw it.
- Outcomes are classified: success; a refusal of the operation (the provider processed it and says it failed: refund FAILED plus a review case); a refusal of the request (for example a validation error), final only if no earlier attempt can have reached the provider; unknown (timeouts, connection errors, 401/403, 408/409/429, 5xx, any failed lookup), retried with exponential backoff and equal jitter. After the last attempt the command needs review; retrying it is always safe.
- If the provider's answer cannot be recorded (for example it contradicts our books), that counts as an attempt too: retried, then escalated for review, never looped on.
- A crashed worker's lease simply expires; another worker resends with the same key.
- Refunds are not sent while the payment is on hold or the provider contradicts it (an open `INVALID_STATE_TRANSITION` case). A refund never sent before the payment was reversed fails: the reversal returns what is left.
- A reversal books the amount the provider reports it returned (a refund the provider already applied may still be on its way to us). A refused reversal leaves the payment waiting for one; an operator can send it again, which uses a **new** idempotency key because a provider answers a reused key with its cached refusal.

## Alternatives considered

- Synchronous provider call in the request: simplest, but locks are held during I/O and timeouts leave unknown state.
- A message broker: adds infrastructure without removing the need for idempotency and unknown-outcome handling.

## Consequences

Money is recorded as returned only when the provider confirms. A refund in an unknown state keeps its amount reserved, so a second refund can never exceed what was captured. The scenario "provider refunds, then times out" is tested end to end against the simulator.

## Review findings this design now covers

An independent review of the first version found that dispatch was only recorded after the provider answered (so a crash in between allowed cancelling a refund the provider had applied), that a failed lookup could be read as a refusal, and that an unrecordable answer looped forever. Each is fixed as above and has a regression test in `OutboxRecoveryTest`, checked by reintroducing the defect.
