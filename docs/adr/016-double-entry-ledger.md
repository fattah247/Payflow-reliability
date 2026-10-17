# 016 Double-Entry Ledger with Invariant Checks

Status: extended by [025](025-bank-statement-three-way-reconciliation.md): payouts in transit, bank cash, suspense, provider fees and bank charges, and the bank-leg checks.

## Context

Amounts lived on payments, refunds and batches, with no single place that says how much is owed to whom, and no way to detect that two of them disagree.

## Decision

- Two kinds of accounts: `provider_receivable` and `merchant_payable:<merchant>`, per currency.
- Every money movement posts a balanced **journal** in the same transaction as the business change: CAPTURE, REFUND, REVERSAL, PAYOUT. Postings must sum to zero and never be zero; the service refuses anything else.
- A journal is posted once per (type, reference), enforced by a unique constraint, so retries never double count.
- Ledger tables are append-only (PostgreSQL triggers, see [019](019-tamper-evident-audit.md)). Corrections are new journals.
- A scheduled **invariant check** runs in one repeatable-read snapshot and compares the books with operational records: every journal balances, and for each merchant and currency, the ledger balance equals captures minus refunds, reversals and payouts. Violations are exported as a gauge with a paging alert.
- The balance check cannot see an over-payout on its own, because both sides subtract the same batch totals. So the check also verifies the payouts: each batch's items add up to its total, a payment's capture is paid out at most once and for its amount, and every deduction equals the refund or reversal it settles.

## Consequences

"How much do we owe merchant X?" is one query. A bug that changes one side without the other is detected within one check interval instead of at month-end. The upgrade migration backfills journals from history so the books match from day one.
