# 019 Tamper-Evident Audit Trail

Amends [006](006-audit-log-design.md).

## Context

Audit events were ordinary rows: anyone with database access could change or delete history without trace.

## Decision

- Audit events form a **hash chain**: one chain per payment, one per other aggregate. Each event stores a sequence number, the previous event's hash and its own SHA-256 hash over every field (length-prefixed, so no two different events can produce the same input).
- Writers append under the payment lock, so a chain never forks; a unique constraint on (chain, sequence) is the backstop.
- `GET /api/audit-events/verify` recomputes a chain and reports the first broken event. The case file includes the result.
- In PostgreSQL, triggers refuse UPDATE, DELETE and TRUNCATE on audit and ledger tables unless a maintenance transaction opts in with `SET LOCAL payflow.evidence_maintenance = 'on'`.

## Limits

A database superuser can still rewrite a whole chain consistently. Anchoring chain heads outside the database (a WORM bucket, a transparency log) closes that gap and is the next step in a real deployment.

## Consequences

Edits, deletions and reordering are detectable, and accidental modification is blocked outright. The upgrade migration builds chains for existing events.
