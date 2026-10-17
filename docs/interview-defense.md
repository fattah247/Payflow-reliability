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

Mutation checks (the settlement-certainty and device-risk ones are listed in
[Settlement certainty](settlement-certainty.md#53-breaking-the-rules-on-purpose-mutation-checks)): disabling the signature check, self-approval, refunds in progress, the audit hash check, the
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

## "Idempotency checks are table stakes. Where is settlement certainty?"

Agreed: a check only helps if the keys are right, and a payment is not settled because the provider says so. The
answer is in [Settlement certainty](settlement-certainty.md). In short, each stage of the money has its own
evidence (an authenticated event, a capture journal, the provider's payout answer and report, a matched bank
statement line), and the system never treats an earlier stage as a later one. A payout is in transit until a bank
credit with its reference, the right amount and a value date inside the window arrives; only then does it land,
and only a payment with both a clean provider report row and a landed payout is RECONCILED. Everything that does
not match becomes a break with an owner and a deadline.

## How are idempotency keys generated, and why does that matter more than the check?

The check compares a key with what it was first used for; generation decides whether a retry and a new purchase
look different. Clients generate one key per business operation (a checkout, a refund), before the first attempt,
and reuse it for every retry. The server stores a canonical fingerprint of the business fields (field order,
whitespace and number formatting do not matter), scoped to the merchant and the operation, and refuses the same
key with a different payload. Payment and refund keys never expire; operator keys expire after 24 hours, and the
contract says so. For calls we make, the key is derived once from the operation (`refund-<id>`,
`payout-<batch>`), never per attempt, and never resent once the provider may have forgotten it (we stop at 23
hours, it keeps keys for 24). A key per attempt would turn every timeout into a second payment; the slowness
scenario shows exactly that cost. ([client contract](idempotency-keys.md), [ADR 024](adr/024-idempotency-keys-end-to-end.md))

## When is a payout really paid, and why not match bank credits by amount?

When a bank statement line carries the payout's reference, the amount equals what the provider said it sent net
of its fee (plus any itemized bank charge), and the value date is inside the window. Matching by amount alone
lands the wrong payout whenever two batches have the same net, and hides the one that never arrived. A credit
without our reference goes to suspense and waits for an operator, who can assign it to a payout but cannot
override the amount. One of the mutation checks was exactly "match by amount when the reference is missing", and
the tests caught it.

## What happens when the bank and the books disagree?

A break: a category (missing credit, late credit, duplicate, returned, amount mismatch, statement conflict...), an
owner (treasury or provider operations), an amount, the money it holds in suspense, and a due time. Overdue breaks
page. Money in suspense cannot be clicked away: only a matching line, a return or an amount-checked assignment
closes such a break, and the ledger check proves `bank_suspense` equals the open breaks to the cent. Fees are their
own ledger lines, so gross minus fees equals cash received or the journal does not post.

## Why is slowness a money problem?

Because "slow but up" passes every health check while customers leave and impatient ones press pay again. The
system measures, from its own records, payments past the confirmation SLO, checkouts abandoned (amount per minute),
retries absorbed and duplicate charges, and pages when health is UP and money is at risk. In the seeded scenario a
slow provider costs 16 abandoned checkouts (4,369,000 IDR) and leaves 24 payments (7,073,000 IDR) unconfirmed past
the SLO, with zero duplicate charges because every retry reused its checkout's key. ([ADR 027](adr/027-slowness-as-money.md))

## How does device risk relate to trustgate-android, and why decide on the server?

The app detects device signals and signs step-up confirmations; it is the client half. The server takes the
decision because it knows what the device cannot (the amount and what is bought) and because a decision in the
app can be patched out. With amount and action points off, the server decides exactly like the app's gate, for
every combination of its signals. The device's claimed level is recorded and ignored. A step-up holds the payment
until the shop relays the confirmation (only the merchant can, never an operator); a block is final and never
reaches the provider. The decision is taken once, and the evidence is not part of the request fingerprint, so
retrying with cleaned signals under the same key returns the same block. ([ADR 028](adr/028-device-risk-as-money.md))

## How do you know a fraud threshold is right?

By pricing it. From the decisions and the labels learned later, the system reports fraud stopped, fraud loss,
honest money lost to friction, precision and recall, and a sweep re-decides every labelled payment under other
thresholds. In the seeded scenario, raising the step-up threshold from 2 to 3 saves about 4.8 million IDR of honest
sales and costs about 9.7 million IDR of extra fraud. That is a business decision, and now it can be made with
numbers. The caveat I would raise myself: in production, blocked payments are rarely labelled, so live precision is
biased; real systems keep a small random holdout.

## How do you test that all of this holds together?

Eleven money rules are production code with evidence for every violation. A property-based test plays 50 random
histories of 20 to 60 faults per run against the real application (duplicate and lost webhooks, commit-then-timeout,
crashed workers, racing settlements, chargebacks around payout, every bank fault, slow periods, device-risk
decisions) and checks every rule, and it also fails if the histories did not reach the hard states often enough.
Nine deliberate breakages of the new protections were each caught, every one by the property test on its own. The
same kinds of faults run live in CI as a seeded chaos scenario against the packaged app.

## How does an operator know what to do next?

One call: `GET /api/operations/worklist`. Review cases, proposals waiting for a second operator and reconciliation
breaks used to live in separate queues with different shapes, and only breaks had deadlines. The worklist puts them in
one list, most urgent first, with due times taken from the alerts that would page if the item waited longer, so the
list and the pager agree. Each item names the owner, the money, the next step and the exact calls that resolve it; a
test follows those calls to prove they work. For a credit nobody could match, it names the payout expecting exactly
that amount, but it does not assign it: matching by amount alone is the mistake the bank leg exists to prevent, so a
person confirms with the bank and the server still checks the amount. ([ADR 029](adr/029-one-worklist-for-people.md))

## What would you add for production?

An identity provider instead of static operator keys, a gateway with rate limiting, chain-head anchoring
outside the database, a real provider integration behind the same client interface, and the alert rules wired
to an on-call rotation. On the money side: the payout leg on to each merchant's bank (payment files out, debits
matched in the same statements), more of the bank formats (camt.052 and camt.054), velocity rules across attempts
for device risk, and a random holdout so fraud labels are unbiased. The code is structured so each of these
replaces or adds a component instead of rewriting flows.

## Why keep this a modular monolith?

The correctness problems here are transaction problems, and one database transaction is the right tool. The
outbox and the ledger are the seams along which services would later split.
