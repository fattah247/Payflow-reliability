# 005 Settlement Reconciliation

Status: amended by [017](017-net-settlement.md) (net payouts) and by two-sided reconciliation (payouts missing from a report are flagged).

## Context

Successful authorization is not the same as settlement, and settlement is not the same as reconciliation.

## Decision

Model settlement batching and report-based reconciliation as separate flows.

## Alternatives considered

- treat success as final
- skip reconciliation entirely

## Tradeoffs

More moving parts than a basic demo, but closer to real payment operations.

## Consequences

The repo proves accounting-minded backend thinking instead of API-only thinking.
