# 018 Two-Person Approval for Money-Moving Decisions

Amends [011](011-review-decisions.md).

## Context

One operator could approve a refund on a reconciled payment or order a reversal alone.

## Decision

- `APPROVE_REFUND` and `REQUIRE_REVERSAL` are **dual control**: resolving with them records a proposal (`AWAITING_APPROVAL`, HTTP 202) and nothing moves yet.
- A different authenticated operator approves (`/approve`) or rejects (`/reject-proposal`) it. The proposer approving their own proposal gets `403 FOUR_EYES_REQUIRED`.
- Approval re-checks the allowed actions against the payment's current state, under the same locks, before applying.
- Decisions without money movement (release, fail before capture, reject a refund, acknowledge, retry a provider call) stay single-operator.

## Consequences

Every money-moving decision has two named operators in the audit chain (`proposedBy`, `approvedBy`). An alert fires when a proposal waits for more than an hour.
