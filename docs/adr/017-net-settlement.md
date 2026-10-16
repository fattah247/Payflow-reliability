# 017 Net Settlement

Status: extended by [025](025-bank-statement-three-way-reconciliation.md): a batch is a payout with a lifecycle (SENT, IN_TRANSIT, LANDED or BROKEN) that only a matched bank credit completes.

Amends [005](005-settlement-reconciliation.md).

## Context

Batches paid out captures only. A refund after payout was never recovered, and a payment with a refund in flight blocked settlement.

## Decision

- A batch adds every unsettled capture and deducts every completed refund and confirmed reversal not yet paid out, for one merchant and one currency.
- Items are signed (captures positive, deductions negative) and each capture, refund and reversal can appear in at most one batch (unique constraints). The capture is the **payment's**, not a provider transaction row's: a chargeback can arrive under a new provider transaction id, and that row must never be paid out as a second capture (`capture_payment_intent_id` is unique).
- A payment's items settle together, and only when the payment is not on hold or mid-reversal. Held payments wait; they never fail the batch.
- A zero or negative net is not paid; it carries forward to the next batch.
- The backlog endpoint shows exactly what the next batches would pay.

## Consequences

The payout journal moves the merchant balance to zero after every batch (or leaves a debt that the next one nets off), and the ledger invariant holds across refunds before and after payout.
