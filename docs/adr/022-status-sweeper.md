# 022 Status Sweeper for Lost Webhooks

## Context

A payment whose webhook never arrives stays PENDING forever, and nobody notices.

## Decision

- A scheduled sweeper finds payments waiting on the provider (PENDING, AUTHORIZED) longer than a threshold, asks the provider for their status, and applies the answer through the **same rules as a webhook** (inbox, mismatch holds, contradictions), recorded as a `POLL` event.
- Each payment is asked at most once per recheck interval; provider errors are counted and retried on the next sweep.
- Payments the provider never heard of expire after a grace period; payments never started expire too.
- Operators can trigger a sweep (`POST /api/operations/sweeps/payment-status`).

## Consequences

A lost webhook delays a payment by minutes instead of leaving it stuck. A late webhook after a recovery is a harmless duplicate.
