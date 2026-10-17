# 005 Settlement Reconciliation

Status: amended by [017](017-net-settlement.md) (net payouts), by two-sided reconciliation (payouts missing from a report are flagged), and by [025](025-bank-statement-three-way-reconciliation.md) (the bank statement as the third leg: a payment is reconciled only when its payout has landed).

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
