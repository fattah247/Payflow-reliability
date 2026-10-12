# Interview Defense

## Why does idempotency matter?

Payment clients retry. Without idempotency, a timeout can become a duplicate payment or duplicate downstream processing.

## Why guard state transitions?

Payment state is not normal CRUD. A late or contradictory event must not rewrite a more final state.

## Why is duplicate webhook handling important?

Providers retry callbacks. If the system cannot ignore duplicates safely, it will create repeated effects.

## Why do settlement and reconciliation both exist?

Settlement groups successful transactions for payout logic. Reconciliation checks whether internal records still match provider-facing records after processing.

## Why does manual review exist?

Not every mismatch should be auto-fixed. Some cases need a queue and an explicit operator action.

## Why keep this as a modular monolith?

The goal is to go deep on correctness, tests, and failure rules before introducing distributed-system overhead.

## Why no real payment provider?

The point is to show reliability logic safely. A fake provider is enough to prove state handling, retries, and mismatches without operational or compliance risk.

## Why no cloud deployment?

This repo is meant to prove backend judgment on a laptop. Cloud setup would add noise without strengthening the core reliability argument.

## Why lock the payment row and also use versions?

The row lock gives the behavior I want for one payment: concurrent work queues and each request sees the previous result, so a duplicate webhook is answered "duplicate" instead of "conflict, retry". Versions are the safety net for paths that do not lock (settlement, reconciliation), and the unique constraints are the last line: even with a bug above them, a transaction cannot be paid out twice.

## Why return outcomes instead of throwing exceptions?

A duplicate webhook, an amount mismatch, or a refund needing approval are expected business outcomes. Throwing them rolled back the review case and audit event they had just written. The original tests missed it because each test ran inside one transaction. Returning outcomes lets the evidence commit, and the controller maps each outcome to the HTTP status the caller needs.

## Why do duplicate webhooks no longer open review cases?

Retries are how providers guarantee delivery. Escalating each one turns normal behavior into operator toil and hides real anomalies. Duplicates are acknowledged and audited. A reused event id with a *different* payload is genuinely suspicious, and that still goes to review.

## What does "manual review" actually decide?

Release the payment back to where it was held, fail it if nothing was captured, require a reversal if money moved, or approve or reject a refund. Each case lists the actions valid right now, and the last case holding a payment cannot be closed without a decision.

## Why full refunds only?

A partial refund used to mark the whole payment refunded. Doing partial refunds properly needs refunded-amount tracking, a partially-refunded state, and settlement netting. Rejecting partial amounts explicitly is honest; silently corrupting state is not.

## How do you know the concurrency tests test anything?

They were checked by mutation. Removing the row lock breaks the webhook race tests; removing the version columns and the settlement-item constraint brings back the original bug: four batches paying out the same transactions. CI also runs the suite on PostgreSQL, which caught a real dialect difference in unique-violation handling.

## What did an independent review of this change find?

Three things worth knowing. Settlement flushed transaction updates before payment updates, the reverse of the webhook path, which can deadlock; it now locks payments first, in id order. A contradicting provider event left a captured payment settleable while its review case was open; it now holds the payment. And one claimed deadlock did not reproduce: Hibernate takes `FOR NO KEY UPDATE` on PostgreSQL, which does not conflict with foreign-key checks. Checking the actual SQL instead of assuming was the useful habit.
