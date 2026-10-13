# 020 Currency-Aware Amounts

## Context

Every amount had 2 decimals. That is wrong for JPY (0), KWD and BHD (3), and silently rounding or padding amounts changes money.

## Decision

- The currency's ISO 4217 minor unit decides precision (`java.util.Currency`). Funds and metal codes (no minor unit) are rejected.
- Requests with more precision than the currency allows are rejected (400), never rounded. Amounts are stored in `numeric(19, 4)` and returned at the currency's scale everywhere (`JPY 1500`, `IDR 1500.00`, `KWD 1.500`).
- Normalizing uses `RoundingMode.UNNECESSARY`, so any code path that would round fails loudly.

## Consequences

Idempotency comparisons, reconciliation matching and refund limits work on exact values in every currency.
