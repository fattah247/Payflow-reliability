# 027 Slowness as Money: "Up, but Costing You"

## Context

Latency dashboards answer "is it slow?". The business question is "what is slowness costing?". A provider whose
payment page takes six seconds and whose webhooks lag behind does not fail any health check, so no availability
alert fires, while customers leave, payments sit unconfirmed, and impatient customers retry. Whether a retry is
harmless or a second charge depends entirely on the client reusing its idempotency key.

The critique called latency monitoring a nice demo. This decision makes it part of the payment system: measured
from the system's own records, in money and counts, with an alert that fires while health still says UP.

## Decision

### 1. What is measured, and where

| Measure | How | Where |
|---|---|---|
| Time to confirmation | start (handed to the provider) to recorded capture; every confirmation counted good or bad against `payflow.checkout.confirmation-slo` (10 s) | `payflow_payment_confirmations_total{within_slo}`, `payflow_payment_confirmation_seconds` |
| Payments stuck past the SLO | started longer ago than the SLO, still not confirmed: count and amount per currency | money at risk `PENDING_BEYOND_SLA` |
| Abandoned checkouts | payments the shop cancelled with reason `ABANDONED`: count, amount, amount per minute over `payflow.checkout.abandonment-window` (15 min) | money at risk `ABANDONED`, `payflow_checkout_abandoned_amount_total` |
| Retries absorbed | create requests answered with the original payment | `payflow_idempotency_replays_total` |
| Duplicate charges | a merchant reference captured more than once: extra charges and their amount (must stay zero) | money at risk `DUPLICATE_CHARGES` |
| Health | 1 when `/actuator/health` is UP | `payflow_health_up` |

Payments gain a `CANCELLED` state (only before the customer paid) so the shop can tell the system a checkout was
abandoned. A capture arriving for a cancelled payment is a contradiction and goes to review; it is never applied.

### 2. Alerts on money, not on latency alone

- `PayflowSlowButUp` (page): health is UP **and** at least three payments are past the SLO or three checkouts were
  abandoned in ten minutes. Down is a different alert's job.
- `PayflowCheckoutSloBurn` (page): multi-window error-budget burn on the confirmation SLO (14.4 over an hour or 6
  over six hours, against `payflow_checkout_slo_objective`).
- `PayflowDuplicateCharge` (page): any duplicate charge.

`ops/prometheus/alerts.test.yml` unit-tests them with `promtool test rules` (in CI): firing when slow but up,
silent when down, burn rate thresholds, duplicates paging at once.

### 3. A seeded simulation drives it, and never pretends to be data

`CheckoutLoadGenerator` (local only, `POST /api/simulations/checkout-runs`) simulates customers at a shop that
uses the public API. Each customer's amount, patience and choices come from the run's seed, so the same seed gives
the same customers and decisions whatever the thread timing. Decisions use the latency the provider *declares*;
the simulator also waits that long (scaled by `payflow.provider-sim.latency-time-scale`) so real HTTP latency is
visible too.

The provider simulator's slow mode slows the hosted payment page and the payment answer and holds webhooks in its
backlog, while its API (and our health) stay up. A customer:

1. has the shop create and start the payment (one idempotency key per checkout);
2. opens the provider's page: if it is slower than their patience they leave (probability
   `abandon-probability`; the shop cancels the payment as ABANDONED) or reload, which re-sends the same request;
3. pays: if the answer is slower than their patience they press pay again, and the shop re-submits. With the same
   key that is answered with the original payment and the provider refuses to charge it twice. With
   `keyStrategy: PER_ATTEMPT` it is a new payment, charged again: the anti-pattern, made visible.

Every parameter lives in `payflow.loadgen.checkout.*` and is echoed in each run's report with a disclaimer: the
numbers are what this model costs, not what any real shop loses.

### 4. Two views that must agree

Each run returns the simulation's ground truth (who paid, who left, retries, duplicates). The payment system
measures the same things from its own records. `CheckoutLoadTest` requires them to match exactly: abandoned count
and amount, payments stuck past the SLO, duplicate charges (zero with per-checkout keys, all of them seen with
per-attempt keys), and confirmations outside the SLO once the provider recovers.

## Alternatives considered

- **Alert on p99 latency.** Says something is slow, not what it costs or who is affected; it also fires for
  slowness nobody pays for.
- **Infer abandonment from expired payments.** Needs the 24-hour expiry to pass and cannot tell abandonment from
  a lost webhook. An explicit cancel with a reason is what real integrations do.
- **Replay recorded production traffic.** There is none in a public project, and inventing it would be presenting
  a model as data.

## Consequences

Slowness shows up where people already look for money: the operations summary, the money-at-risk gauges, and a
paging alert that does not depend on anything being down. The model is simple on purpose and documented as such.
