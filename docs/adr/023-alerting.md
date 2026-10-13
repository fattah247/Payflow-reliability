# 023 Alerting on Money, Not Only on Errors

## Context

Metrics existed, but nothing said when a human must act, and nothing noticed when a safety check silently stopped running.

## Decision

- Gauges for the states that need people: unresolved and aging review cases, proposals waiting for approval, provider calls with unknown outcomes, the age of the oldest pending provider call, stuck payments, ledger invariant violations. They are refreshed on a schedule, so a scrape never queries the database.
- Dead man's switches: the age of the last ledger check and of the gauge snapshot itself.
- Prometheus rules in `ops/prometheus/alerts.yml`, each with a severity (`page` when money may be wrong or stuck now, `ticket` otherwise) and a runbook section in `docs/runbook.md`.
- A test scrapes `/actuator/prometheus` and fails if a rule references a metric the application does not export, or lacks a runbook entry.
- Distributed tracing (Micrometer + OpenTelemetry bridge) puts trace and span ids on every log line and propagates them on provider calls.

## Consequences

Renaming a metric cannot silently disable an alert, and every alert tells the responder what to do.
