# 029 One Worklist for People

## Context

Everything in this system that a person must decide ended up in its own place, with its own shape: review cases
(`/api/review-cases`), proposals waiting for a second operator (the same endpoint, another status), and
reconciliation breaks (`/api/reconciliation/breaks`). Unknown provider outcomes and held payments become review
cases; missing, late, unmatched, duplicated and returned money becomes breaks. The operations summary counts them;
the alerts page when the oldest one waits too long.

An operator starting a shift therefore had to call several endpoints, line up their different fields, work out which
item is most urgent (breaks have due times, review cases did not), find the evidence for each, and remember which call
resolves which kind. For an unmatched bank credit, the usual next step, "which payout is this?", meant comparing
amounts against every payout in transit by hand.

## Decision

`GET /api/operations/worklist` returns everything waiting for a person, in one list, most urgent first.

**Sources.** Unresolved review cases, proposals awaiting approval, and open breaks. Items that resolve themselves
without a person (a payment the sweeper will recover, a customer who has not yet confirmed a step-up) are not work
and are left out; money at risk still shows them.

**Each item** carries the kind, a plain title, the owner (operations, treasury or provider operations), the currency and
money involved, the money held in suspense, when it opened, when it is due and whether it is overdue, why it exists,
the next step in words, the calls that act on it (method, path and a body ready to send once a note is added, marked
when a second operator must make it and naming who may not), and links to the evidence (the case file, the payout seen
three ways, the bank statement lines).

**Due times come from the alerts** that would fire if the item waited longer, so the list and the pager agree. A
review case stores its due time (`due_at`) when it opens, and again when a proposal starts or ends; a break has
stored its due time since it was introduced:

| Item | Due | Alert |
|---|---|---|
| review case | 4 hours after it opened | `PayflowReviewQueueAging` |
| unknown provider outcome | 5 minutes | `PayflowProviderCallOutcomeUnknown` |
| proposal awaiting a second operator | 1 hour after the proposal | `PayflowApprovalWaiting` |
| break | its category's due time | `PayflowReconciliationBreaksOverdue` |

**Order:** earliest due first (so overdue items come first), then by id, so the order is total and a page boundary
is never ambiguous. (An earlier version broke ties by the most money; that needs every candidate in memory, which is
what paging removed.)

**A suggestion, not a decision.** For a credit that could not be matched (no reference, wrong reference, late), the
worklist looks for payouts in transit or missing their credit that expect exactly the amount that arrived (received
plus itemized charges). One candidate is named and pre-filled in the assign call; several are listed. Nothing is
assigned automatically: the reference is what proves whose money it is, an operator confirms with the bank, and the
assignment still checks the amount on the server.

`?owner=` narrows the list to one team. The list is paged like every other list: `?limit=` (default 100, at most
1000), and `?after=` with the cursor from the previous page's `next` (also in the `Link` and `X-Next-After` headers).
Each page reads at most `limit + 1` rows from each source by the `(status, due_at)` indexes and merges them by due
time, so a page costs the same however long the backlog is. The summary always counts everything, with aggregate
queries instead of loading rows.

## Alternatives considered

- **A dashboard UI.** The same information, but it would need its own authentication, hosting and tests, and the
  calls an operator needs are already an API. The worklist is the data a console would be built on.
- **Push notifications per item.** Alerts already page for the urgent cases; a notification per item becomes noise
  that people mute.
- **Automatic assignment of a single matching payout.** Faster, and wrong exactly when two payouts share a net amount
  or the credit is not ours at all. Matching by amount alone is the mistake the bank leg exists to prevent
  ([ADR 025](025-bank-statement-three-way-reconciliation.md)).

## Consequences

One call answers "what needs me now, and how do I resolve it?", and the calls it hands out are proven to work by a
test that follows them (`WorklistTest`), and walking it page by page returns every item once, in order. The list is
read on each request rather than kept as a table of its own, so it is never stale; paging by stored due times keeps
that cheap. Suggestions for unmatched credits compare each credit on the page with the payouts still waiting for one
(in transit, or broken for a missing credit), read once per page that needs them: a set bounded by payouts in flight,
not by history.
