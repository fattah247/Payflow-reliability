# Interview Defense

## Why does idempotency matter?

Payment clients retry. Without idempotency, a timeout becomes a duplicate payment. Payment and refund
creation require a key stored with the record (a unique constraint settles concurrent retries); every other
mutating call accepts a key and replays the stored response.

## Why guard state transitions?

Payment state is not CRUD. A late or contradictory event must not rewrite a more final state, and money must
not move while the provider and we disagree. Contradictions hold in-flight payments; final outcomes stay.

## What is the hardest failure in payments, and how is it handled?

"The provider did it, but we do not know." A refund call times out after the provider committed it. Treating
that as a failure loses track of money; resending blindly can pay twice. Here every provider call goes through
an outbox, carries an idempotency key, and after an unknown outcome the worker asks the provider what happened
to that key before resending. The simulator can reproduce exactly this (commit, then answer after the client's
timeout), and a test proves the customer is refunded once.

## Why an outbox instead of calling the provider in the request?

Calling inside a database transaction holds row locks during network I/O and cannot be atomic with the
database. The outbox records the intent in the same transaction as the business change; a worker calls the
provider outside any transaction under a lease and records the result in one transaction that locks the
payment first.

## Why a double-entry ledger when amounts are already on payments?

Because "how much do we owe merchant X" should be one query, and because two records of the same money can
disagree. Every movement is a balanced journal posted with the business change, idempotent per reference, and
a scheduled check compares the books with payments, refunds, reversals and payouts, with a paging alert.

## Why net settlement?

A refund after payout must come back from somewhere. Batches add captures and deduct refunds and reversals not
yet paid out; a negative net carries forward. The ledger balance returns to zero after each payout.

## Why two operators for some decisions?

Approving a refund on a reconciled payment or ordering a reversal moves money on one person's judgment.
Maker-checker is standard in finance operations; the proposer cannot approve their own proposal, and both names
end up in the audit chain.

## How do you know the audit trail was not edited?

Events are hash-chained per payment; verification recomputes the chain and points at the first broken event.
PostgreSQL triggers refuse updates and deletes on audit and ledger tables without an explicit maintenance
opt-in. A superuser could still rewrite a whole chain; anchoring chain heads externally is the next step.

## How are webhooks authenticated?

HMAC-SHA256 over the timestamp and the exact raw body, checked before parsing, in constant time, with a
5-minute window. Inside the window a replay is a duplicate the inbox ignores. Several secrets can be active
for rotation. Rejected requests write nothing, so they cannot flood the review queue.

## What if a webhook never arrives?

The status sweeper asks the provider about payments waiting too long and applies the answer through the same
rules as a webhook. A late webhook afterwards is a duplicate.

## Why lock the payment row and also use versions and constraints?

The row lock gives the behavior I want for one payment: concurrent work queues and each request sees the
previous result, so a duplicate webhook is answered "duplicate" instead of "conflict, retry". There is one lock
order everywhere: payment first, in id order for several. Versions are the safety net for anything that slips
through, and unique constraints are the last line: even with a bug above them, a capture cannot be paid out
twice and a journal cannot be posted twice.

## Why return outcomes instead of throwing exceptions?

A duplicate webhook, an amount mismatch, or a refund needing approval are expected outcomes. Throwing rolled
back the review case and audit event they had just written. Returning outcomes lets the evidence commit, and
the controller maps each outcome to the status the caller needs.

## How do you know the tests test anything?

Mutation checks: disabling the signature check, self-approval, refunds in progress, the audit hash check, the
payout term of the ledger invariant, the outbox lease, the idempotency hash comparison, merchant isolation, the
contradiction hold, the contradiction gate on refunds, or the replay window each makes a test fail, and so does
putting back any of the defects the independent review found. One mutation initially survived: settlement
re-checks a payment's state after locking it, but the candidate query already filtered by state, so only a race
could exercise it. A test now holds the payment lock, changes the payment while the batch waits for it, and
proves the re-check matters.

## What did the independent review of the upgrade find?

Nine defects, two of them serious, all fixed with a regression test, each checked by putting the defect back:

- A refund's dispatch was only recorded after the provider answered. A worker crashing in between left a refund
  the provider had applied looking unsent, so it could be cancelled and requested again: refunded twice, booked
  once. Dispatch is now committed before the call leaves.
- A reversal booked a locally computed amount. If a partial refund the provider had already applied was still
  on its way to us, the books said 100 reversed while the provider said 70, and the late refund confirmation
  could not be recorded and retried forever. Reversals now book what the provider reports, and an answer that
  cannot be recorded counts as an attempt and escalates.
- A chargeback arriving under a new provider transaction id could be paid out as a second capture, and the
  ledger check could not see it because both sides used the same batch totals. Captures are now unique per
  payment, and the check verifies payouts against what they settle.
- Smaller ones: a failed lookup treated as a refusal, a refused reversal with no way forward, a refund case that
  let a hold be acknowledged away, a migration that could fail on duplicate legacy cases, a simulator that could
  be left on in production, and a race on expired idempotency keys.

The lesson I took: "the provider might have acted" has to be written down before the call, not inferred after.

## What would you add for production?

An identity provider instead of static operator keys, a gateway with rate limiting, chain-head anchoring
outside the database, a real provider integration behind the same client interface, and the alert rules wired
to an on-call rotation. The code is structured so each of these replaces a component instead of rewriting flows.

## Why keep this a modular monolith?

The correctness problems here are transaction problems, and one database transaction is the right tool. The
outbox and the ledger are the seams along which services would later split.
