# 011 Review Resolutions Are Decisions

Amends: 007 Manual Review Queue

## Context

Resolving a review case only marked it resolved. A payment held in `MANUAL_REVIEW` could never leave it: it could not be released, failed, settled, or refunded. A refund blocked after reconciliation threw an error, and its review case was rolled back with it, so the "manual review" it promised did not exist.

## Decision

- A held payment stores `stateBeforeReview`. Holds apply only to in-flight states; final outcomes are never overwritten.
- Resolving a case takes an `action`:
  - `RELEASE_PAYMENT`: back to the held-from state
  - `FAIL_PAYMENT`: only if nothing was captured
  - `REQUIRE_REVERSAL`: only if money was authorized or captured
  - `APPROVE_REFUND` / `REJECT_REFUND`: for refunds pending review
  - `ACKNOWLEDGE`: close without side effects
- A held payment cannot be acknowledged away unless another open case still covers it, so no payment is left on hold with nobody responsible.
- Each case returns `allowedActions` computed from live state; invalid actions get `422 RESOLUTION_ACTION_NOT_APPLICABLE` with the reason.
- A refund on a reconciled payment is accepted as `PENDING_REVIEW` (HTTP 202) with a review case, instead of an error.

## Tradeoffs

The review module now depends on payment and refund services; the dependency points one way (review to refund to review-service), with resolution logic kept in its own service to avoid a cycle.

## Consequences

The queue does what it exists for: a human decides, and the decision changes the money state, with an audit event that names the actor and the action.
