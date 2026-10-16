# 026 Property-Based Money Invariants

## Context

Every example-based test in this repository checks one story: a crash here, a duplicate there. Real incidents are
combinations nobody wrote down: a refund that times out while a chargeback arrives, during a week when one bank
statement came twice and another came late. Writing every combination by hand is impossible; leaving them
untested is how double payments survive code review.

## Decision

### 1. The money rules are production code

`MoneyInvariantService` (also `GET /api/operations/invariants`) checks, in one repeatable-read snapshot, the rules
that must hold whatever happened, each with the evidence of every violation:

| Rule | Meaning |
|---|---|
| `BOOKS_BALANCE` | every journal balances; the books agree with payments, refunds, payouts, bank statements and open breaks, to the cent (the full ledger check of ADR 016 and 025) |
| `CAPTURE_PAID_OUT_AT_MOST_ONCE` | no capture is in two batches |
| `RECONCILED_ONLY_WITH_A_BANK_LINE` | no payment is RECONCILED unless a bank credit landed its payout |
| `UNMATCHED_MONEY_HAS_A_BREAK` | every bank line in suspense has an open break saying why |
| `BANK_ENTRY_BOOKED_ONCE` | the bank's own entry reference is booked once, however often it is delivered |
| `LANDING_IS_PROVEN` | every landed payout carries its reference, or a named operator assigned it |
| `BLOCKED_NEVER_REACHES_THE_PROVIDER` | a payment device risk blocked is never started, never captured, never the subject of a provider call (ADR 028) |
| `STEP_UP_CONFIRMED_BEFORE_CAPTURE` | a payment that needed a step-up is started, and so captured, only after it was confirmed with the required method |
| `NO_RETRY_CHARGES_TWICE` | no merchant reference is charged twice; no idempotency key is applied twice at the provider |
| `REFUNDED_ONCE` | what the provider refunded equals what we recorded plus refunds still in flight |
| `PAYOUT_SENT_ONCE` | the provider paid each batch out at most once |

The provider-side rules read the provider simulator's records and run only where it runs; with a real provider,
its reports are the place to check them.

### 2. A property-based test throws random histories at the whole system

`MoneyInvariantPropertyTest` uses jqwik to generate sequences of 20 to 60 actions and runs each against the real
application (real transactions, the provider and bank simulators over real HTTP, the movable clock):

- checkouts retried under one key; customers paying with duplicated, lost and reordered webhooks; status sweeps;
- partial refunds while the provider is down, commits then times out, or refuses;
- outbox runs where the worker dies right after the provider acted;
- racing settlement runs; chargebacks before and after payout;
- every bank fault; statements imported twice, in reverse order, or re-issued by the bank under a new number;
- the provider's settlement report; operators assigning unmatched credits; hours passing;
- the provider turning slow (webhooks held back while it stays up) and recovering; shops abandoning checkouts,
  sometimes after the customer already paid;
- device-risk decisions: clean devices, step-ups confirmed (and paid) or left to expire, blocks, and a provider
  that charges a blocked or unconfirmed payment anyway.

After each sequence the system settles down (webhooks delivered, provider calls run, an end-of-day settlement,
the bank closed through two days past every payout's window, the provider's report imported, the reconciliation
job run) and every rule above must hold. On failure, jqwik shrinks the sequence to the shortest one that still
breaks a rule and prints the seed to replay it.

### 3. The test proves what it covered

A property that only ever walks the happy path proves nothing, and the first version of this test did exactly
that: only one sequence in fifty landed a payout. So the test now collects statistics on every run (payouts
landed, payouts broken, money in suspense, payments reconciled three ways, workers crashed, statements re-issued,
payments blocked, step-ups confirmed and captured, rogue provider charges, slow periods, break categories reached)
and fails if the sequences did not reach those states often enough.

### 4. Every rule is mutation-checked

Each rule was broken on purpose and the suite (this test included) had to fail: matching a credit by amount when
the reference is missing, skipping the fee lines when a payout lands, accepting a re-imported statement, scoping a
bank line's identity to its statement, regenerating the outbound idempotency key per attempt, letting a step-up
payment through unconfirmed (the decision recorded but not enforced, or the start guard removed), and sending a
blocked payment. The results are in the README.

## Alternatives considered

- **More example tests.** Still needed (they document behavior and fail with a readable message), but they only
  check what someone thought of.
- **Model-based testing against an in-memory model.** Stronger in theory; in practice a second implementation of
  payments to keep in sync. Invariants on the real system's own records are cheaper and catch the same bugs.
- **Fixed seeds only.** Reproducible but never explores further. jqwik uses a new seed per run, prints it, and
  replays a failed seed first; any run can be reproduced exactly.

## Consequences

About 20 seconds on H2 and 40 on PostgreSQL per 50 sequences. New features add their actions to the alphabet and
their rules to `MoneyInvariantService`, so they are tested against every existing failure at once.
