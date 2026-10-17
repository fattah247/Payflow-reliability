# 025 Settlement Certainty: the Bank Statement as the Third Leg

Amends [005](005-settlement-reconciliation.md), [016](016-double-entry-ledger.md) and [017](017-net-settlement.md).

## Context

Settlement used to end when a batch was created: the payout journal moved the merchant's balance to zero as
if the money had arrived somewhere, and a payment became RECONCILED as soon as the provider's report agreed.
Both are claims, not evidence. The provider's report says what the provider *intended* to send; only the
bank statement says what *arrived*, when, and minus what. A reviewer put it as: where is settlement certainty?

The money can go wrong between the provider and the bank in ways neither the ledger nor the provider sees: a
credit that never arrives, arrives late, arrives twice, arrives net of a charge, arrives with the wrong
reference or none, or is sent back. And the statements themselves can arrive twice or out of order.

## Decision

### 1. A payout has a lifecycle, and only the bank can finish it

`SENT` (batch created, payout instruction queued in the outbox with key `payout-<batchId>`) → `IN_TRANSIT`
(the provider accepted it and reported fee, net and expected value date) → `LANDED` (a bank credit matched it),
or `BROKEN` with a reason: `PROVIDER_REFUSED`, `MISSING_CREDIT`, `RETURNED`. Payouts from before this change
are `LEGACY`: they have no bank evidence and are trusted as before; that boundary is explicit.

A credit lands a payout only when **all three** match:

| Check | Rule |
|---|---|
| reference | the credit's end-to-end id is the payout reference (`PFB` + batch id, 35 characters, the ISO 20022 limit) |
| amount | received + itemized bank charges = the net the provider said it sent, and gross - provider fee = net |
| value date | no later than the expected value date plus `payflow.reconciliation.value-date-window-days` (2) |

There is no matching by amount alone: two payouts of the same amount are common, and guessing wrong moves the
wrong merchant's money. An operator can assign a credit to a payout (the reference and the window are then
overridden by a person's decision); the amount check still applies.

### 2. The ledger follows the money, one account per stage

| Event | Journal (debit / credit) |
|---|---|
| batch created | `PAYOUT`: merchant_payable / payouts_due (committed to the merchant) |
| provider accepts | `PAYOUT_IN_TRANSIT`: payouts_in_transit / provider_receivable (gross) |
| matched credit | `PAYOUT_LANDED`: bank_cash (received) + provider_fees + bank_charges / payouts_in_transit (gross) |
| unexplained line | `BANK_UNMATCHED`: bank_cash / bank_suspense (credits; reversed for debits) |
| bank returns a landed payout | `PAYOUT_RETURNED`: provider_receivable / bank_cash |

Fees are their own lines, so gross minus fees equals net exactly, or the journal does not balance and the
ledger refuses it. The merchant balance invariant of ADR 016 is unchanged (zero after each batch). The
settlement account is the platform's; transferring `payouts_due` on to each merchant's own bank is the next
leg and is not modelled here.

### 3. Every disagreement becomes a break, with an owner and a due time

A break has a category, an owner (`TREASURY` talks to the bank, `PROVIDER_OPERATIONS` to the provider), an
amount, the amount it holds in `bank_suspense`, an opening time and a due time from its category. At most one
break per category and subject is open (a partial unique index on PostgreSQL). Breaks past their due time
raise `PayflowReconciliationBreaksOverdue`; the job that finds them has its own dead man's switch, and so does
the statement feed.

Breaks that hold money in suspense (unmatched, duplicate, late, amount mismatch) cannot be acknowledged away:
they close only when the money is explained by a matching bank line, a return, or an assignment.

### 4. Statements are imported idempotently, in any order

- **statement id**: the same statement again is a no-op; the same id or sequence number with different
  content is a conflict, imported never and flagged always;
- **line hash** (including the bank's own entry reference): an entry seen before is never booked twice;
- **order**: each line is matched on its own; *coverage* (every statement through date D has arrived) only
  advances over consecutive sequence numbers, starting at `payflow.bank.first-statement-sequence`. A credit is
  declared missing only once coverage passes the end of its value-date window, so a gap is never mistaken for
  a missing credit;
- **the bank's own arithmetic**: each statement must add up, chain to its neighbours (opening = previous
  closing) and not repeat entries; otherwise treasury gets a `STATEMENT_CONFLICT` break.

### 5. Three-way reconciliation, per batch and per payment

- per batch: ledger (batch total, journals) vs the provider's payout record and settlement report (gross, fee,
  net) vs the bank line (received, charges, value date). `GET /api/reconciliation/three-way/batches/{id}` shows
  every check.
- per payment: `RECONCILED` needs a clean provider report row **and** a landed payout. Whichever arrives second
  completes it (`MATCHED_AWAITING_BANK` in between). No payment counts as reconciled without a bank line.

### 6. The ledger check proves it

`LedgerInvariantService` adds: bank_cash equals the sum of imported lines; bank lines equal landed payouts
minus returns plus what open breaks hold, to the cent; bank_suspense equals the open breaks; every suspense
line has an open break; each landed payout's gross equals received + provider fee + bank charges; transit and
payouts-due balances equal the batches in those stages; and no RECONCILED payment's payout is unlanded.

## Statement format

A documented subset of ISO 20022 camt.053.001.08 (one `Stmt` per file; `Id`, `ElctrncSeqNb`, `Acct/Id/Othr/Id`,
`Acct/Ccy`; `OPBD` and `CLBD` balances; per entry `Amt`, `CdtDbtInd`, `RvslInd`, `Sts=BOOK`, `BookgDt`, `ValDt`,
`AcctSvcrRef`, `Chrgs/Rcrd/Amt`, `NtryDtls/TxDtls/Refs/EndToEndId`, `AmtDtls/InstdAmt`, `RmtInf/Ustrd`). The
parser never resolves DTDs or external entities. See docs/settlement-certainty.md for a full example.

## Alternatives considered

- **Trust the provider's settlement report.** It is the provider's intention, written by the party whose
  mistakes we are trying to catch.
- **Match on amount and date when the reference is missing.** Faster to clear, and wrong exactly when two
  merchants are paid the same amount on the same day.
- **Book unexplained money to income after N days.** A real accounting practice, deliberately left out:
  this project keeps every unexplained amount visible until a person explains it.
- **A real bank connection (EBICS, SFTP, an API).** Out of scope for a local project; the statement feed is an
  interface with one HTTP implementation, and files can be uploaded by hand.

## Consequences

Settlement certainty now has a precise meaning: the money is in our bank account, matched to the payout by
reference, amount and value date, and the books say so to the cent. Everything short of that is a named state
with a named owner. The cost: one more simulated counterparty, six new tables, and payments that stay SETTLED
until the bank speaks.
