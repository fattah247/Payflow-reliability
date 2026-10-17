# 002 Idempotency Strategy

Status: extended. Every mutating operator call also accepts an optional `Idempotency-Key`; the first response is stored and replayed (see `IdempotencyFilter`). Refund creation now requires a key stored with the refund. Key generation, canonical fingerprints, scope and expiry on every path, including calls to the provider, are in [024](024-idempotency-keys-end-to-end.md).

## Context

Payment creation requests can be retried by clients or proxies.

## Decision

Persist an idempotency key on the payment intent, scoped per merchant, and reject payload changes for reused keys. A unique constraint on (merchant, key) decides concurrent retries; the loser replays the winner (see [009](009-concurrency-control.md)).

## Alternatives considered

- no idempotency
- cache-only idempotency

## Tradeoffs

Database-backed idempotency is simple and durable, but ties replay behavior to persistence.

## Consequences

The repo can prove duplicate request safety with deterministic tests.
