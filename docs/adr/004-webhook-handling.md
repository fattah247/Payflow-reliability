# 004 Webhook Handling

Status: amended by [010](010-webhook-inbox-and-outcomes.md) (duplicate handling, outcomes) and superseded for signatures by [014](014-signed-webhooks.md).

## Context

Provider callbacks are retried, delayed, and occasionally contradictory.

## Decision

Use a fake provider endpoint with signature checks, duplicate detection, and guarded transition handling.

## Alternatives considered

- no webhook simulation
- accept every provider callback at face value

## Tradeoffs

The fake provider is still simple, but it is enough to prove replay and state-transition handling.

## Consequences

The repo can show backend chaos handling without a real third-party dependency.
