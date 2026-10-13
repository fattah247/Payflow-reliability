# 007 Manual Review Queue

Status: amended by [011](011-review-decisions.md) and [018](018-maker-checker.md) (two-person approval).

## Context

Not every mismatch should be auto-corrected by code.

## Decision

Create explicit review cases for mismatches, contradictory events, and blocked operations.

## Alternatives considered

- reject everything with no queue
- auto-fix mismatches

## Tradeoffs

The queue adds one more concept, but it reflects how operational teams actually work.

## Consequences

The repo can show where automation should stop and human review should begin.
