# 021 A Provider Simulator over Real HTTP

## Context

Tests and demos called services directly with hand-made webhook payloads. Timeouts, lost and duplicated webhooks, signatures and provider idempotency were never exercised.

## Decision

The application ships a provider simulator (`/provider-sim/v1`, own tables, own API key) that behaves like a payment provider:

- customers pay, authorize, get declined, expire, or charge back; the provider then delivers **signed** webhooks with retries and backoff, optionally lost, duplicated, or with a wrong amount;
- an idempotent refund and reversal API, plus lookup by idempotency key;
- fault injection: outage (503), **commit then answer after the client's timeout**, refusal.

The platform talks to it through the same HTTP client it would use for a real provider. Setting `payflow.provider.base-url` and `payflow.provider-sim.enabled=false` switches to a real one.

Because the simulator's key can create signed provider events, it is off unless enabled (application.yml enables it for local runs), the application refuses to start with the simulator next to a real provider or with the local demo secrets against a real provider (`ProviderConfigurationGuard`), and it logs a warning whenever the simulator is on.

## Consequences

The hardest failure in payments (the provider acted but we do not know) is reproducible on a laptop and covered by tests and scripted scenarios.
