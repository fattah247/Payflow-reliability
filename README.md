# Payflow Reliability

![CI](https://github.com/fattah247/Payflow-reliability/actions/workflows/ci.yml/badge.svg)

A local-first Spring Boot payment backend built around the failures that cost real money: retried requests,
duplicated and lost webhooks, a provider that acts and then times out, concurrent jobs, refunds after payout,
reports that disagree with the books, and people who make mistakes or act alone.

It runs on a laptop with no cloud services. A provider simulator speaks real HTTP to the application,
signs its webhooks, retries them, loses them on request, and can commit a refund and then answer too late.
Everything below is proven by tests that run on H2 and on PostgreSQL, and by scripted scenarios that run
against the live application in CI.

> This is a public-safe educational project based on common payment engineering patterns. It does not use
> proprietary employer code, architecture, data, naming, business rules, logs, screenshots, or internal workflows.

---

## What This Project Proves

**Money is never moved twice, and never lost track of**
- idempotency keys on every money-moving request, including two retries racing each other
- provider calls through a transactional outbox: leases, backoff with jitter, and an "unknown outcome" state
  resolved by asking the provider before resending
- a double-entry ledger posted with every business change, plus a scheduled invariant check that compares the
  books with payments, refunds, reversals and payouts
- partial refunds that can never exceed the capture, even under concurrent requests
- net settlement: refunds and reversals are deducted from payouts, a negative net carries forward
- one lock order everywhere (payment row first), optimistic versions, and unique constraints on every
  "at most once" fact

**Provider events are trusted only as far as they deserve**
- webhooks authenticated by HMAC over timestamp and raw body, with a replay window and secret rotation
- a webhook inbox: every event gets exactly one explicit outcome; duplicates and stale events cost nobody time
- contradictions and mismatches hold the payment, so nothing settles or refunds while the facts are disputed
- a status sweeper that recovers lost webhooks through the same rules

**People are accountable**
- API keys for merchants (hashed, rotatable) and operators; merchants see only their own payments
- a review queue whose resolutions are real decisions; money-moving ones need a second operator
- a hash-chained audit trail per payment, verifiable on demand, append-only in PostgreSQL
- reconciliation in both directions: report rows that disagree, and payouts the provider did not report

**Operations can see and act**
- a case file per payment and an operations summary
- Prometheus gauges for what needs people, alert rules with a runbook, dead man's switches for the checks
- trace ids on every log line and provider call; graceful shutdown; health probes

---

## Tech Stack

- Java 21, Spring Boot 3.5 (Web, Validation, Data JPA, Security, Actuator)
- PostgreSQL 16 at runtime, H2 (PostgreSQL mode) for fast tests; the suite runs on both
- Flyway (SQL, Java data migrations, vendor-specific safeguards); Hibernate in `validate` mode
- Micrometer with Prometheus registry and OpenTelemetry tracing bridge; Prometheus alert rules
- Maven Wrapper, Docker Compose, GitHub Actions

---

## Architecture

```text
Merchants (API key)        Provider (HMAC-signed webhooks)        Operators (API key)
        |                              |                                  |
        v                              v                                  v
Spring Boot modular monolith: stateless security, one transaction per request, payment row lock first
        |
        ├── security        API keys, roles, caller identity
        ├── merchant        onboarding, key issue / rotation / revocation
        ├── idempotency     stored responses for retried operator calls
        ├── payment         intents, state machine, webhook inbox, status sweeper
        ├── provider        HTTP client with timeouts, signature verification
        ├── outbox          refunds and reversals executed at the provider, leased, retried, escalated
        ├── refund          partial refunds, cancellation, approval after reconciliation
        ├── ledger          balanced journals, balances, invariant check
        ├── settlement      net payout batches, backlog
        ├── reconciliation  two-sided report matching
        ├── review          review queue, decisions, two-person approval
        ├── audit           hash-chained audit events and verification
        ├── operations      case file, summary, alerting gauges
        ├── scheduling      every background job, switched on and off together
        └── providersim     local stand-in for the provider (own tables, own key, fault injection)

PostgreSQL (schema owned by Flyway)
        payment_intents, payment_transactions, provider_events (inbox), refund_requests, outbox_commands,
        ledger_journals + ledger_postings (append-only), settlement_batches + items, review_cases,
        audit_events (hash chains, append-only), merchants + merchant_api_keys, idempotency_records, sim_*
```

The full lifecycle, including background jobs and the lock order, is in [docs/architecture-flow.md](docs/architecture-flow.md).

---

## Webhook Decision Flow

Webhooks and status polls go through the same rules. Every authenticated event gets exactly one outcome.
Expected outcomes are returned, not thrown, so the review cases and audit events they create are committed.

```mermaid
flowchart TD
    A[POST /fake-provider/webhook] --> B{HMAC valid and timestamp<br/>within 5 minutes?}
    B -- No --> B1[401 INVALID_SIGNATURE<br/>nothing persisted]
    B -- Yes --> C[Lock payment row]
    P[Status sweeper asks the provider] --> C
    C --> D{Event id already in inbox?}
    D -- Same payload --> D1[200 DUPLICATE_IGNORED]
    D -- Different payload --> D2[202 SENT_TO_REVIEW<br/>PROVIDER_EVENT_CONFLICT]
    D -- No --> E{Payment exists?}
    E -- No --> E1[202 SENT_TO_REVIEW<br/>PAYMENT_INTENT_NOT_FOUND]
    E -- Yes --> F{Payment on hold?}
    F -- Yes --> F1[409 PAYMENT_ON_HOLD<br/>provider redelivers later]
    F -- No --> G{Currency / amount match?}
    G -- No --> G1[Hold payment if in flight<br/>202 SENT_TO_REVIEW]
    G -- Yes --> H{Provider tx owned by another payment?}
    H -- Yes --> H1[202 SENT_TO_REVIEW<br/>PROVIDER_TRANSACTION_CONFLICT]
    H -- No --> I{Status already recorded for this tx?}
    I -- Yes --> I1[200 DUPLICATE_IGNORED]
    I -- No --> J{AUTHORIZED after capture?}
    J -- Yes --> J1[200 STALE_IGNORED]
    J -- No --> K{Transition valid?}
    K -- No --> K1[Hold payment if in flight<br/>202 SENT_TO_REVIEW<br/>INVALID_STATE_TRANSITION]
    K -- Yes --> L[Apply transition, upsert provider tx<br/>capture: post ledger journal<br/>reversal request: queue provider call<br/>200 APPLIED]
```

Status codes are chosen for the provider's retry logic: **2xx means stop retrying** (200 handled, 202
accepted for human review), **409 PAYMENT_ON_HOLD means redeliver later**, 401 means the signature is wrong.

---

## Payment State Machine

```mermaid
stateDiagram-v2
    [*] --> CREATED

    CREATED --> PENDING: start
    CREATED --> EXPIRED: never started (sweeper)

    PENDING --> AUTHORIZED
    PENDING --> SUCCESS: capture (ledger CAPTURE)
    PENDING --> FAILED
    PENDING --> EXPIRED: provider never heard of it (sweeper)

    AUTHORIZED --> SUCCESS
    AUTHORIZED --> FAILED
    AUTHORIZED --> EXPIRED
    AUTHORIZED --> REVERSAL_REQUIRED

    SUCCESS --> SETTLED: payout batch
    SUCCESS --> REVERSAL_REQUIRED: provider asks before payout
    SUCCESS --> REFUNDED: refunds confirmed for the full amount

    SETTLED --> RECONCILED: clean report row
    SETTLED --> REFUNDED
    RECONCILED --> REFUNDED: refund approved by two operators

    REVERSAL_REQUIRED --> REVERSED: provider confirms (ledger REVERSAL)

    CREATED --> MANUAL_REVIEW: mismatch hold
    PENDING --> MANUAL_REVIEW: mismatch hold
    AUTHORIZED --> MANUAL_REVIEW: mismatch hold
    SUCCESS --> MANUAL_REVIEW: mismatch or contradiction
    SETTLED --> MANUAL_REVIEW: mismatch or contradiction
    RECONCILED --> MANUAL_REVIEW: contradiction after payout

    MANUAL_REVIEW --> FAILED: FAIL_PAYMENT, if never captured
    MANUAL_REVIEW --> REVERSAL_REQUIRED: REQUIRE_REVERSAL, two operators
    note right of MANUAL_REVIEW
        RELEASE_PAYMENT returns the payment
        to the state it was held from
    end note

    FAILED --> [*]
    EXPIRED --> [*]
    REFUNDED --> [*]
    REVERSED --> [*]
```

Partial refunds do not change the state; the payment tracks `refundedAmount` and becomes `REFUNDED` only
when the whole amount has been returned. Final outcomes are facts and are never overwritten by a hold.

---

## Local Setup

### Prerequisites

- JDK 21, Docker, and the included Maven Wrapper

### 1. Start PostgreSQL

```bash
docker compose up -d postgres
```

> Upgrading a local database created before Flyway? Reset the volume once: `docker compose down -v && docker compose up -d postgres`.
> Databases created by an earlier Flyway version are upgraded in place (see `LegacyDataMigrationTest`).

### 2. Run the app

```bash
./mvnw spring-boot:run
```

Flyway applies the migrations, Hibernate validates the entities, and the background jobs and the provider
simulator start. No local JDK? `docker compose up --build` runs the app and PostgreSQL together.

### 3. Keys for local use

| Who | Key | Notes |
|---|---|---|
| Operator `ops-ana` | `pf_o_local_ops-ana` | configured as a SHA-256 hash in `application.yml` |
| Operator `ops-budi` | `pf_o_local_ops-budi` | the second operator for two-person approvals |
| Merchant | issued by `POST /api/merchants` | shown once; stored as a hash |
| Provider webhook secret | `whsec_local_payflow` | `PAYFLOW_PROVIDER_WEBHOOK_SECRETS` (comma-separated for rotation) |

These are local demo values and the app logs a warning when they are configured. Override them through the
environment (`PAYFLOW_OPERATOR_ANA_KEY_SHA256`, `PAYFLOW_PROVIDER_API_KEY`, ...) anywhere else. The provider
simulator is enabled only by the local configuration, and the app refuses to start with it next to a real
provider (`PAYFLOW_PROVIDER_BASE_URL`) or with the demo secrets against a real provider.

```bash
curl http://localhost:8080/actuator/health
curl -X POST http://localhost:8080/api/merchants -H 'Authorization: Bearer pf_o_local_ops-ana' \
  -H 'Content-Type: application/json' -d '{"merchantId": "merchant-demo", "displayName": "Demo shop"}'
```

### 4. Optional: Prometheus with the alert rules

```bash
docker compose --profile observability up -d prometheus   # http://localhost:9090/alerts
```

---

## Run Tests

```bash
./mvnw test
```

The same suite runs against PostgreSQL by pointing Spring at it (CI does both):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/payflow_reliability SPRING_DATASOURCE_USERNAME=payflow_reliability SPRING_DATASOURCE_PASSWORD=payflow_reliability ./mvnw test
```

188 tests in 25 classes. They are **not** wrapped in a test-managed transaction: every service call commits or
rolls back exactly as in production, and the provider simulator and its webhooks run over the server's real
HTTP port. Background jobs are off in tests and each test runs them explicitly against a movable clock, so
retries, leases, replay windows and expiry are tested without sleeping.

| Area | What is proven |
|---|---|
| Security | no key 401, wrong role 403, other merchant 404, key rotation and revocation, keys never stored raw |
| Webhooks | HMAC over body, tampering, replay window, secret rotation, every inbox outcome, concurrent duplicates, conflicting events |
| Provider calls | outage retried, commit-then-timeout resolved by lookup (refunded once), escalation and safe retry, leases, a worker dying after the provider acted, a failed lookup, a reversal racing a refund the provider already applied, a refused reversal sent again, an answer that cannot be recorded |
| Lost webhooks | sweeper recovery as a POLL event, late webhook is a duplicate, expiry, provider unavailable, disagreeing answer |
| Refunds | partial up to the capture, 5 concurrent requests never exceed it, idempotency, cancellation, provider refusal, contradiction gate |
| Settlement | net of refunds before and after payout, negative net carried forward, 4 racing runs pay once, held payments wait, a payment changed while the run waits for its lock, a chargeback under a new transaction id never paid out as a second capture |
| Reconciliation | per-row verdicts incl. refunded amounts, payouts missing from the report, merchant-scoped reports, safe re-import |
| Review | allowed actions, release / fail / reverse, two-person approval, proposals rejected, two operators racing |
| Ledger and audit | every journal balanced, balances per merchant, invariant violations detected, hash chain tamper detection, append-only triggers (PostgreSQL) |
| Operations | case file, summary, sweep endpoint, alerting gauges, every alert rule references an exported metric and has a runbook |
| Migration | a database at the previous version with legacy rows upgrades consistently (both databases) |
| API contract | status codes, `Location`, `Idempotent-Replayed`, `X-Request-Id`, error codes, currency precision |

**The tests were checked by mutation.** Each of these deliberate breakages makes the suite fail: skipping the
webhook signature check, removing the replay window, letting a proposer approve their own decision, ignoring
refunds in progress, skipping the audit hash check, dropping payouts from the ledger invariant, settling held
payments, removing the outbox lease, replaying idempotent responses without comparing the request, turning
off merchant isolation, not holding a contradicted payment, and sending refunds while a contradiction is open.

**The tests were also checked against an independent review.** A review of the finished change found nine
defects, two of them serious: a worker crashing after the provider refunded could leave the refund cancellable
(refunded twice, booked once), and a reversal racing a refund the provider had already applied booked the wrong
amount and then retried forever. All nine are fixed, each has a regression test, and each test was verified by
putting the defect back. The findings and fixes are summarized in [docs/interview-defense.md](docs/interview-defense.md#what-did-the-independent-review-of-the-upgrade-find).

---

## Run the Demo

With the app running:

```bash
./scripts/demo-readable.sh
```

A narrated walkthrough: onboarding and keys, idempotent creation, a customer paying at the provider with
duplicated signed webhooks, a wrong amount that is held and recovered, a partial refund while the provider
times out, net settlement, two-sided reconciliation, a two-operator refund approval, the books and the audit
chain, and the operations summary. `./scripts/demo.sh` is the one-minute version.

To look at one behavior in depth, with its outcome checked:

```bash
./scripts/scenarios.sh provider-outage
```

| Scenario | Shows |
|---|---|
| `idempotency` | replay, payload mismatch, 8 parallel retries |
| `duplicate-webhook` | redelivery, restated status, late authorization, 10 parallel deliveries, one journal |
| `amount-mismatch` | hold, deferred redelivery, required decision, release, recovery |
| `provider-outage` | refund committed at the provider but timed out; resolved by lookup; refunded once |
| `lost-webhook` | the status sweeper recovers a payment whose webhook was dropped |
| `settlement` | 4 racing runs, refunds deducted, a held payment skipped, ledger at zero |
| `reconciliation` | per-row verdicts, a payout missing from the report, safe re-import |
| `four-eyes` | chargeback after payout, proposal, self-approval refused, second operator, reversal booked |
| `security` | no key, other merchant, wrong role, forged and replayed webhooks |
| `audit-trail` | case file timeline with actors, request ids and a verified hash chain |

Each prints what happened and a PASS/FAIL line per expectation, and exits non-zero if any check fails. CI runs
all of them against the packaged app on PostgreSQL. Every run registers a fresh merchant, so they can be
repeated without resetting the database.

---

## Screenshots

The first two are the public GitHub pages. Each terminal screenshot is the unedited output of the command on
its first line, run against the packaged app on PostgreSQL 16 (the test screenshot runs the suite on H2 and on
PostgreSQL), so every one can be reproduced.

| Screenshot | What it shows | Reproduce |
|---|---|---|
| Repository overview | The repository page with this README | [github.com/fattah247/Payflow-reliability](https://github.com/fattah247/Payflow-reliability) |
| CI passing | Tests on H2 and PostgreSQL, scenarios against the live app, alert rules | [Actions](https://github.com/fattah247/Payflow-reliability/actions) |
| Walkthrough | Every behavior end to end | `./scripts/demo-readable.sh` |
| Idempotency | 201, then 200 replayed, 422 for a changed payload, 8 parallel retries create one payment | `./scripts/scenarios.sh idempotency` |
| Webhook redelivery | Redeliveries and a late authorization absorbed; 10 parallel deliveries applied once | `./scripts/scenarios.sh duplicate-webhook` |
| Amount mismatch | Hold (202), redeliver later (409), a decision is required, release, recovery | `./scripts/scenarios.sh amount-mismatch` |
| Net settlement | Four racing runs, one batch; captures minus refunds; a held payment waits | `./scripts/scenarios.sh settlement` |
| Reconciliation | A verdict per row, a payout missing from the report, safe re-import | `./scripts/scenarios.sh reconciliation` |
| Audit trail | Timeline with actor and request id per step; the hash chain verifies | `./scripts/scenarios.sh audit-trail` |
| Tests | 188 tests on H2 and on PostgreSQL | `./mvnw test` |
| Provider timeout | The provider refunds, then times out; the customer is refunded exactly once | `./scripts/scenarios.sh provider-outage` |
| Lost webhook | The sweeper recovers the capture from the provider | `./scripts/scenarios.sh lost-webhook` |
| Two operators | Chargeback after payout: proposal, self-approval refused, approval, reversal booked | `./scripts/scenarios.sh four-eyes` |
| Security | No key, another merchant, wrong role, forged and replayed webhooks | `./scripts/scenarios.sh security` |

### Repository overview

![Repository overview](docs/screenshots/01-repo-overview.png)

### CI passing

![CI passing](docs/screenshots/02-ci-passing.png)

### Walkthrough

![Demo walkthrough](docs/screenshots/03-demo-script-output.png)

### Idempotency

![Idempotency replay](docs/screenshots/04-idempotency-replay.png)

### Webhook redelivery

![Duplicate webhook ignored](docs/screenshots/05-duplicate-webhook-ignored.png)

### Amount mismatch: hold, decide, recover

![Amount mismatch manual review](docs/screenshots/06-amount-mismatch-manual-review.png)

### Net settlement

![Settlement batch](docs/screenshots/07-settlement-batch.png)

### Two-sided reconciliation

![Reconciliation verdicts](docs/screenshots/08-reconciliation-mismatch.png)

### Audit trail

![Audit trail](docs/screenshots/09-audit-trail.png)

### Tests on H2 and PostgreSQL

![Local tests](docs/screenshots/10-local-tests.png)

### Provider times out after refunding

![Provider timeout](docs/screenshots/11-provider-timeout-refunded-once.png)

### Lost webhook recovered

![Lost webhook](docs/screenshots/12-lost-webhook-recovered.png)

### Two operators for a reversal after payout

![Four eyes](docs/screenshots/13-four-eyes-reversal.png)

### Authentication and isolation

![Security](docs/screenshots/14-authentication-and-isolation.png)

---

## API

Every endpoint needs `Authorization: Bearer <key>` except health, the signed webhook and the provider
simulator. **M** = merchant key (own payments only), **O** = operator key.

### Merchants

| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/merchants` | O | **201** with the merchant and its first API key, shown once. **409** `MERCHANT_ALREADY_EXISTS` |
| GET | `/api/merchants/{id}` | O | Keys listed by prefix only |
| POST | `/api/merchants/{id}/api-keys` | O | **201** a new key (rotation: issue, switch, revoke) |
| DELETE | `/api/merchants/{id}/api-keys/{keyId}` | O | Revoked keys stop working on the next request |

### Payment intents

| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/payment-intents` | M | Requires `Idempotency-Key`. Merchant taken from the key. **201** + `Location`; **200** + `Idempotent-Replayed: true` on replay; **422** `IDEMPOTENCY_KEY_REUSED` if the payload differs |
| POST | `/api/payment-intents/{id}/start` | M, O | Retrying a start is a no-op |
| GET | `/api/payment-intents/{id}` | M, O | Another merchant's payment is **404** |
| GET | `/api/payment-intents/{id}/case-file` | O | Attention summary, provider transactions, events (webhook or poll), provider calls, refunds, settlement items, ledger journals, review cases, audit chain verification, timeline |

### Provider webhook

| Method | Path | Notes |
|---|---|---|
| POST | `/fake-provider/webhook` | `X-Provider-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, "<t>." + raw body)>`. Returns `outcome`, `detail`, `reviewReason`, `reviewCaseId`, `originalOutcome` and the payment |

### Refunds

| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/refunds/payment-intents/{paymentIntentId}` | M, O | Requires `Idempotency-Key`. Partial amounts up to what is refundable (**422** otherwise). **201** `REQUESTED` (executed by the provider asynchronously); **202** `PENDING_REVIEW` on a reconciled payment; **200** + `Idempotent-Replayed` on retry |
| GET | `/api/refunds/payment-intents/{paymentIntentId}` | M, O | |
| GET | `/api/refunds/{refundId}` | M, O | `REQUESTED`, `COMPLETED`, `FAILED`, `PENDING_REVIEW`, `REJECTED`, `CANCELLED` |
| POST | `/api/refunds/{refundId}/cancel` | M, O | Until the provider has it; **409** `REFUND_NOT_CANCELLABLE` afterwards |

### Provider calls (outbox)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/outbox?status=` | O | `PENDING`, `SUCCEEDED`, `FAILED`, `NEEDS_REVIEW`, `CANCELLED`, with attempts and last error |
| GET | `/api/outbox/{id}` | O | |
| POST | `/api/outbox/{id}/retry` | O | Requeue a call with an unknown outcome (safe: same idempotency key) |
| POST | `/api/outbox/run` | O | Run the worker now |

### Settlement, ledger and reconciliation

| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/settlements/batches?merchantId=&currency=` | O | **201** with captures, deductions and net. `currency` only if several are payable (**422** lists them). **409** `NOTHING_TO_SETTLE`, also when the net is not positive (it carries forward) |
| GET | `/api/settlements/batches?merchantId=` | O | Newest first |
| GET | `/api/settlements/batches/{batchId}/items` | O | Signed items: `CAPTURE`, `REFUND`, `REVERSAL` |
| GET | `/api/settlements/backlog` | O | What the next batches would pay, per merchant and currency |
| GET | `/api/ledger/balances?merchantId=&currency=` | O | Account balances and what is owed to each merchant |
| GET | `/api/ledger/journals?paymentIntentId=` | O | Journals with their postings |
| GET | `/api/ledger/invariants` | O | Runs the books-versus-operations check |
| POST | `/api/reconciliation/reports` | O | Up to 1000 rows (optionally with `refundedAmount`). With `settlementDate` (and optionally `merchantId`), payouts missing from the report are flagged. Verdicts: `RECONCILED`, `ALREADY_RECONCILED`, `MATCHED_NO_ACTION`, `MISMATCH`, `NOT_FOUND_LOCALLY`, `DUPLICATE_ROW`, `MISSING_FROM_REPORT` |

### Review cases

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/review-cases?status=UNRESOLVED\|OPEN\|AWAITING_APPROVAL\|RESOLVED\|ALL&reason=&paymentIntentId=` | O | Oldest first, with `paymentState`, `allowedActions` and `approvalRequiredFor` |
| GET | `/api/review-cases/{id}` | O | |
| POST | `/api/review-cases/{id}/resolve` | O | Body `{note, action}`. **200** applied; **202** `AWAITING_APPROVAL` for `APPROVE_REFUND` and `REQUIRE_REVERSAL`; **422** explains what is allowed and why |
| POST | `/api/review-cases/{id}/approve` | O | A different operator than the proposer; **403** `FOUR_EYES_REQUIRED` otherwise |
| POST | `/api/review-cases/{id}/reject-proposal` | O | The case is open again |

### Audit and operations

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/audit-events?paymentIntentId=` or `?aggregateType=&aggregateId=` | O | |
| GET | `/api/audit-events/verify?paymentIntentId=` or `?chainKey=` | O | Recomputes the hash chain; reports the first broken event |
| GET | `/api/operations/summary` | O | Payments per state, stuck payments, review queue (unresolved, awaiting approval, by reason, oldest), refunds, provider calls, settlement backlog, last ledger check |
| POST | `/api/operations/sweeps/payment-status?olderThanSeconds=` | O | Ask the provider about waiting payments now |
| GET | `/actuator/prometheus`, `/actuator/metrics` | O | `/actuator/health` (and liveness/readiness) is public |

Every operator `POST` accepts an optional `Idempotency-Key`: the first response is stored for 24 hours and
replayed with `Idempotent-Replayed: true`.

### Errors

Every non-2xx response has the same shape:

```json
{
  "timestamp": "2026-10-13T09:43:45.173830Z",
  "status": 409,
  "error": "Conflict",
  "code": "PAYMENT_ON_HOLD",
  "message": "Payment a41b6477-... is on hold for manual review. Redeliver this event after the review is resolved.",
  "retryable": true,
  "requestId": "4f12f4a7-431a-45f2-9113-e3038522ffc7"
}
```

- `code` is stable and machine-readable (`ErrorCode` lists them all).
- `retryable` says whether sending the exact same request again can succeed.
- `requestId` matches the `X-Request-Id` response header, the log lines (which also carry the trace id) and the
  audit events. Send your own `X-Request-Id` (letters, digits, `.`, `_`, `-`, up to 64) to correlate across systems.

Amounts follow the currency's minor unit (`JPY 1500`, `IDR 1500.00`, `KWD 1.500`); more precision is rejected,
never rounded.

---

## Example: a provider that refunds and then times out

```bash
OPS='Authorization: Bearer pf_o_local_ops-ana'
# the provider will commit the next refund but answer after our 5 s read timeout
curl -X POST localhost:8080/provider-sim/v1/faults -H 'Authorization: Bearer sim_local_provider_key' \
  -H 'Content-Type: application/json' -d '{"operation": "REFUND", "mode": "TIMEOUT_AFTER_COMMIT", "times": 1}'

curl -X POST localhost:8080/api/refunds/payment-intents/<id> -H "Authorization: Bearer <merchant key>" \
  -H 'Idempotency-Key: refund-1' -H 'Content-Type: application/json' -d '{"amount": 40000, "reason": "returned item"}'
# 201 {"status": "REQUESTED", ...}

curl localhost:8080/api/outbox -H "$OPS"
# first attempt: "lastError": "No response from provider: ...", refund still REQUESTED (unknown, not failed)
# next attempt asks the provider about key refund:<id>, finds the refund, records it once:
curl localhost:8080/api/refunds/<refund id> -H "$OPS"
# {"status": "COMPLETED", "providerRefundId": "...", ...}
```

`./scripts/scenarios.sh provider-outage` runs exactly this and checks that the provider and our books both
show one refund.

---

## Design Choices

| Decision | Why | ADR |
|---|---|---|
| Modular monolith | correctness problems here are transaction problems; one database transaction is the right tool | [001](docs/adr/001-use-monolith-first.md) |
| One lock order, versions, constraints | concurrent work queues instead of conflicting; constraints are the last line | [009](docs/adr/009-concurrency-control.md) |
| Return outcomes, do not throw them | the evidence a rejection creates must commit | [010](docs/adr/010-webhook-inbox-and-outcomes.md) |
| API keys, merchant from the key, 404 across merchants | no anonymous money movement, no id probing | [013](docs/adr/013-api-key-authentication.md) |
| HMAC webhooks with replay window and rotation | integrity and freshness, rotation without downtime | [014](docs/adr/014-signed-webhooks.md) |
| Transactional outbox for provider calls | no locks during network I/O; unknown outcomes resolved safely | [015](docs/adr/015-transactional-outbox.md) |
| Double-entry ledger with invariant checks | one answer to "what do we owe", and detection when records disagree | [016](docs/adr/016-double-entry-ledger.md) |
| Net settlement | refunds after payout come back from the next payout | [017](docs/adr/017-net-settlement.md) |
| Two-person approval for money-moving decisions | no single operator can move money alone | [018](docs/adr/018-maker-checker.md) |
| Hash-chained, append-only audit | edits and deletions are detectable or refused | [019](docs/adr/019-tamper-evident-audit.md) |
| Currency-aware amounts, never rounded | JPY, IDR and KWD are all exact | [020](docs/adr/020-currency-aware-money.md) |
| A provider simulator over real HTTP | the hardest failures are reproducible on a laptop | [021](docs/adr/021-provider-simulator.md) |
| Status sweeper | a lost webhook delays a payment, it does not strand it | [022](docs/adr/022-status-sweeper.md) |
| Alerts on money, with dead man's switches | an alert that silently stops working is worse than none | [023](docs/adr/023-alerting.md) |
| Flyway, Java data migrations, vendor safeguards | reviewable schema; upgrades keep the books consistent | [012](docs/adr/012-schema-migrations.md) |

---

## Operations

- **Background jobs**: outbox worker and simulated webhook delivery (1 s), status sweeper and ledger check
  (5 min), idempotency cleanup (1 h), gauge refresh (30 s). `payflow.workers.enabled=false` turns them all off.
- **Metrics**: `payflow.outbox.*`, `payflow.review.queue.*`, `payflow.payments.stuck`,
  `payflow.ledger.invariant.*`, `payflow.webhook.*`, `payflow.sweeper.*`, plus the standard HTTP, JVM and pool metrics.
- **Alerts**: [`ops/prometheus/alerts.yml`](ops/prometheus/alerts.yml), validated by `promtool` in CI and by a
  test against the real metrics output. Each alert links to its section in the [runbook](docs/runbook.md).
- **Tracing**: trace and span ids on every log line and propagated on provider calls (OpenTelemetry bridge;
  sampling via `PAYFLOW_TRACE_SAMPLING`).
- **Lifecycle**: graceful shutdown waits for in-flight requests and scheduled jobs; liveness and readiness
  probes; the container image runs as a non-root user with a health check.

---

## Documentation Map

- [Demo Flow](docs/demo-flow.md)
- [Architecture Flow](docs/architecture-flow.md)
- [Failure Scenarios](docs/failure-scenarios.md), each with the test that proves it
- [Runbook](docs/runbook.md)
- [Security Review](docs/security-review.md)
- [Interview Defense](docs/interview-defense.md)
- [Architecture Decision Records](docs/adr)

---

## Continuous Verification

GitHub Actions on every push to `main`, `dev`, `dev/**` and on pull requests into `main` and `dev`:

1. the test suite on H2;
2. the test suite on a PostgreSQL 16 service container;
3. the packaged app started against PostgreSQL, then every scenario and both walkthroughs, each checking its outcome;
4. the alert rules checked with `promtool`.

No deployment, registry publish, or scheduled workflow is included.

---

## Limitations

Intentional omissions, and what a production deployment would add:

- no real payment gateway, card data, or PCI-DSS scope; the provider client is ready for one behind the same interface
- static API keys instead of an identity provider with short-lived tokens and MFA; no rate limiting (a gateway's job)
- the audit chain detects edits but a database superuser could rewrite a whole chain; anchoring chain heads in
  write-once storage closes that
- one provider, one platform account; multi-provider routing is out of scope
- no multi-region behavior or message broker; the outbox is the seam where one would go

---

## What To Review First

1. Run `./scripts/demo-readable.sh`, then `./scripts/scenarios.sh provider-outage`
2. `OutboxWorker`: leases, calls outside transactions, lookup before resend, escalation
3. `ProviderWebhookService`: the decision order and why each outcome has its status code
4. `LedgerService` and `LedgerInvariantService`: balanced journals and the books-versus-operations check
5. `ReviewWorkflowService`: decisions and two-person approval
6. `SettlementService`: net payouts, lock order, and the re-check after locking
7. `OutboxTest`, `SettlementTest`, `LegacyDataMigrationTest`

The core idea: a small backend that stays correct when the world around it misbehaves, and can prove it.
