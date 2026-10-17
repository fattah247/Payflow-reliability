# Payflow Reliability

![CI](https://github.com/fattah247/Payflow-reliability/actions/workflows/ci.yml/badge.svg)

A local-first Spring Boot payment backend built around the failures that cost real money: retried requests,
duplicated and lost webhooks, a provider that acts and then times out, concurrent jobs, refunds after payout,
payouts that never reach the bank, reports and statements that disagree with the books, a provider that is slow
but "up", risky devices, and people who make mistakes or act alone.

It runs on a laptop with no cloud services. A provider simulator speaks real HTTP to the application, signs its
webhooks, retries them, loses them on request, and can commit a refund and then answer too late. A bank simulator
issues camt.053 statements for the settlement account, with late, missing, duplicated, net-of-fee, unreferenced
and returned credits on request. Everything below is proven by tests that run on H2 and on PostgreSQL, and by
scripted scenarios that run against the live application in CI.

**Start here:** [Settlement certainty](docs/settlement-certainty.md) follows a payment's money from capture to
the bank statement, with the evidence at each stage, and shows slowness and device risk measured as money.

> This is a public-safe educational project based on common payment engineering patterns. It does not use
> proprietary employer code, architecture, data, naming, business rules, logs, screenshots, or internal workflows.

---

## What This Project Proves

**Settlement is certain only when the bank says so**
- payouts have a lifecycle (sent, in transit, landed, or broken with a reason), and only a bank credit matched by
  reference, amount and value date lands one; the provider's word is never treated as cash
- a three-way reconciliation of ledger, provider report and bank statement, per payout and per payment; a payment
  is RECONCILED only with both a clean report row and a landed payout
- provider fees and bank charges as their own ledger lines, so gross minus fees equals cash received to the cent;
  unexplained money goes to suspense
- every disagreement becomes a break with a category, an owner, an amount and a due time; overdue breaks page,
  and the jobs that find them have dead man's switches
- statement import is idempotent (statement id plus each line's bank reference) and safe out of order

**Money is never moved twice, and never lost track of**
- idempotency keys on every money-moving request, including two retries racing each other; the request
  fingerprint is a canonical hash of the business fields, scoped to the merchant and operation, with a stated expiry
  ([client contract](docs/idempotency-keys.md))
- outbound keys derived once from the business operation (`refund-<id>`, `payout-<batch>`), never per attempt, and
  never resent after the provider may have forgotten them
- provider calls through a transactional outbox: leases, backoff with jitter, and an "unknown outcome" state
  resolved by asking the provider before resending
- a double-entry ledger posted with every business change, plus a scheduled invariant check that compares the
  books with payments, refunds, reversals, payouts, bank statements and open breaks
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

**Slowness and fraud controls are measured in money**
- a seeded load generator drives simulated customers through the real API while the provider turns slow but
  healthy; abandoned money, payments past the confirmation SLO and duplicate charges (zero with proper keys) are
  measured from the system's own records, and a "slow but up" alert fires while health says UP
- a server-side device-risk decision on every payment, compatible with
  [trustgate-android](https://github.com/fattah247/trustgate-android): step-ups hold the payment until confirmed,
  blocked payments never reach the provider, and every decision carries its rule and points in the audit chain
- fraud stopped, fraud loss, honest money lost to friction, precision and recall from labelled traffic, and a
  threshold sweep that prices moving one threshold

**Shops are told what happened, once and in order**
- every payment and refund change is written as an event in the transaction of the change (a transactional outbox),
  numbered per payment so a shop can put retried deliveries back in order
- signed webhooks with a secret per shop and rotation without a gap; at least once, with leases, backoff and jitter,
  a night's worth of retries, then kept and redeliverable; an events list to catch up from
- webhook URLs cannot reach our own network: https to public addresses only, checked at registration and before every
  delivery, redirects never followed ([integration guide](docs/merchant-webhooks.md))

**The money rules hold together, under chaos**
- twelve money rules checked on demand, with evidence for every violation
- a property-based test throws long random histories of faults at the real application and checks every rule;
  nine deliberate breakages of the protections were each caught by it
- a seeded chaos run plays the same kinds of faults against the packaged app in CI

**Operations can see and act**
- one worklist of everything waiting for a person, most urgent first, with owners, due times that match the alerts,
  the next step, the calls that resolve each item, and a suggested payout for credits nobody could match
- every list paged with one cursor contract (`Link: rel="next"`), so a page size never hides a row; the worklist pages
  by due time, in time proportional to the page, not the backlog
- one view of money at risk (past the SLO, abandoned, held, in breaks by age, awaiting a step-up, blocked today)
- a case file per payment and an operations summary
- Prometheus gauges for what needs people, alert rules with a runbook, dead man's switches for the checks
- trace ids on every log line and provider call; graceful shutdown; health probes

---

## Tech Stack

- Java 21, Spring Boot 3.5 (Web, Validation, Data JPA, Security, Actuator)
- PostgreSQL 16 at runtime, H2 (PostgreSQL mode) for fast tests; the suite runs on both
- Flyway (SQL, Java data migrations, vendor-specific safeguards); Hibernate in `validate` mode
- Micrometer with Prometheus registry and OpenTelemetry tracing bridge; Prometheus alert rules tested with `promtool`
- JUnit 5, AssertJ, MockMvc, and jqwik for property-based tests
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
        ├── outbox          refunds, reversals and payouts executed at the provider, leased, retried, escalated
        ├── refund          partial refunds, cancellation, approval after reconciliation
        ├── ledger          balanced journals, balances, invariant check
        ├── settlement      net payout batches, payout lifecycle, backlog
        ├── bank            camt.053 statement import, coverage, the bank's statement feed
        ├── reconciliation  three-way matching (ledger, provider report, bank), breaks with owners and aging
        ├── checkout        confirmation SLO and slowness measured as money
        ├── risk            device-risk policy, step-up and block, labels, report and threshold sweep
        ├── review          review queue, decisions, two-person approval
        ├── audit           hash-chained audit events and verification
        ├── operations      case file, summary, money at risk, money rules, alerting gauges
        ├── scheduling      every background job, switched on and off together
        ├── providersim     local stand-in for the provider (own tables, own key, fault and latency injection)
        ├── banksim         local stand-in for the bank holding the settlement account (statements, faults)
        └── loadgen         seeded simulated customers, labelled risk traffic, and the chaos run (local only)

PostgreSQL (schema owned by Flyway)
        payment_intents, payment_transactions, provider_events (inbox), refund_requests, outbox_commands,
        ledger_journals + ledger_postings (append-only), settlement_batches + items, review_cases,
        bank_statements + bank_statement_lines (append-only), bank_accounts, reconciliation_breaks,
        provider_payout_reports, risk_decisions, audit_events (hash chains, append-only),
        merchants + merchant_api_keys, idempotency_records, sim_*, bank_sim_*
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
    [*] --> REQUIRES_CONFIRMATION: device risk asks for a step-up
    [*] --> BLOCKED: device risk blocks

    REQUIRES_CONFIRMATION --> CREATED: customer confirms (required method)
    REQUIRES_CONFIRMATION --> EXPIRED: not confirmed in time
    REQUIRES_CONFIRMATION --> CANCELLED: shop gives up

    CREATED --> PENDING: start (handed to the provider)
    CREATED --> EXPIRED: never started (sweeper)
    CREATED --> CANCELLED: shop gives up
    PENDING --> CANCELLED: abandoned before the customer paid

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

    SETTLED --> RECONCILED: clean report row and the payout landed in the bank
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
    BLOCKED --> [*]
    CANCELLED --> [*]
```

Partial refunds do not change the state; the payment tracks `refundedAmount` and becomes `REFUNDED` only
when the whole amount has been returned. Final outcomes are facts and are never overwritten by a hold. A capture
for a blocked, unconfirmed or cancelled payment is a contradiction: it goes to review and is never applied.

A settlement batch has its own lifecycle (`SENT`, `IN_TRANSIT`, `LANDED`, or `BROKEN` with a reason), shown with
the ledger postings at each step in [Settlement certainty](docs/settlement-certainty.md#the-payout-lifecycle).

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

326 tests in 45 classes, one of them a property-based test that runs 50 random histories of faults. They are
**not** wrapped in a test-managed transaction: every service call commits or rolls back exactly as in production,
and the provider and bank simulators and the webhooks run over the server's real HTTP port. Background jobs are
off in tests and each test runs them explicitly against a movable clock, so retries, leases, replay windows,
value-date windows and expiry are tested without sleeping.

| Area | What is proven |
|---|---|
| Security | no key 401, wrong role 403, other merchant 404, key rotation and revocation, keys never stored raw |
| Webhooks | HMAC over body, tampering, replay window, secret rotation, every inbox outcome, concurrent duplicates, conflicting events |
| Provider calls | outage retried, commit-then-timeout resolved by lookup (refunded once), escalation and safe retry, leases, a worker dying after the provider acted, a failed lookup, a reversal racing a refund the provider already applied, a refused reversal sent again, an answer that cannot be recorded |
| Lost webhooks | sweeper recovery as a POLL event, late webhook is a duplicate, expiry, provider unavailable, disagreeing answer |
| Refunds | partial up to the capture, 5 concurrent requests never exceed it, idempotency, cancellation, provider refusal, contradiction gate |
| Settlement | net of refunds before and after payout, negative net carried forward, 4 racing runs pay once, held payments wait, a payment changed while the run waits for its lock, a chargeback under a new transaction id never paid out as a second capture |
| Idempotency keys | canonical request fingerprints (field order, whitespace and number formatting do not matter), scope per merchant and operation, expiry, outbound keys derived once and never resent after the provider's retention |
| Bank leg | the camt.053 subset parsed safely (no DTDs, must add up), payouts sent, in transit, landed or broken, matching by reference, amount and value-date window, fees and bank charges as their own lines, late, missing, duplicated, unreferenced and returned credits, statements re-imported, re-sent, re-issued or out of order |
| Reconciliation | per-row verdicts incl. refunded amounts, payouts missing from the report, merchant-scoped reports, safe re-import, nothing RECONCILED without a landed payout, breaks with owners, due times and the actions allowed |
| Slowness | confirmations counted against the SLO, money past the SLO, abandoned checkouts measured exactly as the seeded customers behaved, zero duplicate charges with per-checkout keys and every one counted with per-attempt keys, alert rules unit-tested with `promtool` |
| Device risk | the app's decisions reproduced for all 256 signal combinations, step-up hold, confirmation method, expiry, block never started, retries keep the first decision, rogue captures to review, fraud stopped, loss, friction, precision and recall equal to the ground truth, the threshold sweep's trade-off |
| Merchant webhooks | events written only with their change (a rolled-back change has none), per-payment sequence, deliveries over real HTTP to a simulated shop that verifies signatures, deduplicates and ignores stale events; retries for errors, redirects and slow answers; giving up and redelivering; secret rotation with overlap; catch-up paging; isolation between shops; SSRF refusals for 23 kinds of URL (private, loopback, link-local and mapped addresses, names that resolve to them, plain http, credentials) |
| Money rules | twelve rules checked after 50 random histories of faults per run, with coverage checks on what the histories reached; each rule shown catching a planted violation; a seeded chaos run against the live API |
| Review | allowed actions, release / fail / reverse, two-person approval, proposals rejected, two operators racing |
| Ledger and audit | every journal balanced, balances per merchant, invariant violations detected, hash chain tamper detection, append-only triggers (PostgreSQL) |
| Operations | case file, summary, sweep endpoint, alerting gauges, every alert rule references an exported metric and has a runbook, the worklist (one queue across review cases, proposals and breaks, due order, owners, the suggested payout for an unmatched credit, and calls that are followed to prove they resolve the item) |
| Migration | a database at the previous version with legacy rows upgrades consistently (both databases) |
| API contract | status codes, `Location`, `Idempotent-Replayed`, `X-Request-Id`, error codes, currency precision, bounded and filtered lists, no persistence internals in responses, finding a payment by merchant reference after losing its key, every list walked page by page through its `Link` header without a gap or a repeat |

**The tests were checked by mutation.** Each of these deliberate breakages makes the suite fail: skipping the
webhook signature check, removing the replay window, letting a proposer approve their own decision, ignoring
refunds in progress, skipping the audit hash check, dropping payouts from the ledger invariant, settling held
payments, removing the outbox lease, replaying idempotent responses without comparing the request, turning
off merchant isolation, not holding a contradicted payment, and sending refunds while a contradiction is open.
For settlement certainty and device risk: matching a bank credit by amount when the reference is missing,
skipping the fee lines, accepting a re-imported statement, scoping a bank line's identity to its statement,
regenerating the outbound key per attempt, letting a step-up payment through unconfirmed (two ways), and sending a
blocked payment (two ways). The property-based test alone caught every one of these nine
([details](docs/settlement-certainty.md#53-breaking-the-rules-on-purpose-mutation-checks)).

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
times out, net settlement, three-way reconciliation against the provider's report and the bank statement, a
two-operator refund approval, the books and the audit chain, and the operations summary. `./scripts/demo.sh` is
the one-minute version.

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
| `reconciliation` | per-row verdicts, a payout missing from the report; a clean match waits for the bank, then reconciles; safe re-import |
| `four-eyes` | chargeback after payout, proposal, self-approval refused, second operator, reversal booked |
| `security` | no key, other merchant, wrong role, forged and replayed webhooks |
| `audit-trail` | case file timeline with actors, request ids and a verified hash chain |
| `outbound-key-crash` | the provider refunds and pays out, then times out each time; one key per operation, applied once |
| `bank-reconciliation` | ledger, provider report and bank side by side; the payout lands only on the matched credit |
| `fee-deduction` | a provider fee and a bank charge, each its own ledger line; gross = received + fees |
| `missing-credit` | late inside the value-date window, broken after it; a break with an owner and a due time |
| `returned-payout` | the bank credits, then returns the payout; the money is the provider's to send again |
| `statement-reimport` | a statement imported twice, re-sent and re-issued; one bank line, one landing |
| `slow-but-up` | 80 seeded customers; abandoned money and payments past the SLO, zero duplicate charges, health UP |
| `device-risk` | a block, a step-up, then 200 labelled customers: fraud stopped, friction, and a threshold's price |
| `chaos` | a seeded sequence of faults against the live app; every money rule holds |

Each prints what happened and a PASS/FAIL line per expectation, and exits non-zero if any check fails. CI runs
all of them against the packaged app on PostgreSQL. Every run registers a fresh merchant, so they can be
repeated without resetting the database. The simulated customers and the chaos run are seeded: their numbers
repeat exactly, and their parameters are documented model assumptions, not data.

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
| Reconciliation | A verdict per row, a payout missing from the report; the clean match reconciles only once the bank confirms | `./scripts/scenarios.sh reconciliation` |
| Audit trail | Timeline with actor and request id per step; the hash chain verifies | `./scripts/scenarios.sh audit-trail` |
| Tests | 326 tests on H2 and on PostgreSQL | `./mvnw test` |
| Provider timeout | The provider refunds, then times out; the customer is refunded exactly once | `./scripts/scenarios.sh provider-outage` |
| Lost webhook | The sweeper recovers the capture from the provider | `./scripts/scenarios.sh lost-webhook` |
| Two operators | Chargeback after payout: proposal, self-approval refused, approval, reversal booked | `./scripts/scenarios.sh four-eyes` |
| Security | No key, another merchant, wrong role, forged and replayed webhooks | `./scripts/scenarios.sh security` |
| Outbound key | Refund and payout each applied once under a key derived from the operation, despite timeouts | `./scripts/scenarios.sh outbound-key-crash` |
| Three-way reconciliation | Every check between ledger, provider and bank; reconciled only after the credit | `./scripts/scenarios.sh bank-reconciliation` |
| Fees | 500,000 = 492,000 received + 1,500 provider fee + 6,500 bank charge, as journal lines | `./scripts/scenarios.sh fee-deduction` |
| Missing credit | In transit inside the window, broken after it, with a treasury-owned break | `./scripts/scenarios.sh missing-credit` |
| Returned payout | Landed, then returned; both bank lines explained | `./scripts/scenarios.sh returned-payout` |
| Statement re-import | Twice by hand, re-sent, re-issued: one bank line, one landing | `./scripts/scenarios.sh statement-reimport` |
| Slow but up | 4,369,000 IDR abandoned and 7,073,000 past the SLO while health says UP; no duplicate charges | `./scripts/scenarios.sh slow-but-up` |
| Device risk | Block, step-up, and what each step-up threshold costs in fraud and friction | `./scripts/scenarios.sh device-risk` |
| Chaos | 150 seeded faults against the live app; all 12 money rules hold | `./scripts/scenarios.sh chaos` |
| Merchant webhooks | A payment and a refund told to a shop; the shop down for one delivery, the late copy ignored as stale; a secret rotation; a forged delivery refused | `./scripts/scenarios.sh merchant-webhooks` |

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

### Provider report against the ledger

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

### One outbound key per operation

![Outbound key](docs/screenshots/15-outbound-key-crash.png)

### Three-way reconciliation

![Three-way reconciliation](docs/screenshots/16-three-way-reconciliation.png)

### Fees as their own ledger lines

![Fee deduction](docs/screenshots/17-fee-deduction.png)

### A credit that never arrives

![Missing credit](docs/screenshots/18-missing-credit.png)

### A returned payout

![Returned payout](docs/screenshots/19-returned-payout.png)

### The same statement, again and again

![Statement re-import](docs/screenshots/20-statement-reimport.png)

### Slow but up, measured in money

![Slow but up](docs/screenshots/21-slow-but-up.png)

### Device risk as money

![Device risk](docs/screenshots/22-device-risk.png)

### Chaos against the running app

![Chaos](docs/screenshots/23-chaos-money-rules.png)

### Telling the shop

![Merchant webhooks](docs/screenshots/24-merchant-webhooks.png)

---

## API

Every endpoint needs `Authorization: Bearer <key>` except health, the signed webhook and the simulators (which
check their own keys). **M** = merchant key (own payments only), **O** = operator key.

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
| POST | `/api/payment-intents` | M | Requires `Idempotency-Key`. Merchant taken from the key. Optional `action` (`PHYSICAL_GOODS`, `DIGITAL_GOODS`, `STORED_VALUE`) and `riskEvidence` (the device's signals, see [ADR 028](docs/adr/028-device-risk-as-money.md)). **201** + `Location`, in state `CREATED`, `REQUIRES_CONFIRMATION` (with `requiredConfirmation`) or `BLOCKED`; **200** + `Idempotent-Replayed: true` on replay, with the first decision; **422** `IDEMPOTENCY_KEY_REUSED` if the payload differs |
| POST | `/api/payment-intents/{id}/start` | M, O | Retrying a start is a no-op. **409** `STEP_UP_REQUIRED` before a step-up is confirmed; **422** `PAYMENT_BLOCKED` |
| POST | `/api/payment-intents/{id}/confirm` | M | Body `{method}`: the step-up confirmation the shop verified (`STEP_UP_KEY` or `TAP`, as required); **422** `CONFIRMATION_METHOD_MISMATCH`. Repeating it is a no-op |
| POST | `/api/payment-intents/{id}/cancel` | M, O | Body `{reason}` (for example `ABANDONED`), only before the customer paid. Repeating it is a no-op |
| GET | `/api/payment-intents/{id}` | M, O | Another merchant's payment is **404** |
| GET | `/api/payment-intents?merchantReference=&limit=&after=` | M, O | Your payments for one of your references, newest first: how to find a payment after losing its idempotency key. Operators also pass `merchantId` |
| GET | `/api/payment-intents/{id}/case-file` | O | Attention summary, settlement certainty per stage, device-risk decision, provider transactions, events (webhook or poll), provider calls, refunds, settlement items, ledger journals, review cases, audit chain verification, timeline |

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
| GET | `/api/outbox?status=&limit=&after=` | O | `PENDING`, `SUCCEEDED`, `FAILED`, `NEEDS_REVIEW`, `CANCELLED`, with attempts and last error |
| GET | `/api/outbox/{id}` | O | |
| POST | `/api/outbox/{id}/retry` | O | Requeue a call with an unknown outcome (safe: same idempotency key) |
| POST | `/api/outbox/run` | O | Run the worker now |

### Settlement, ledger and reconciliation

| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/settlements/batches?merchantId=&currency=` | O | **201** with captures, deductions and net. `currency` only if several are payable (**422** lists them). **409** `NOTHING_TO_SETTLE`, also when the net is not positive (it carries forward) |
| GET | `/api/settlements/batches?merchantId=&limit=&after=` | O | Newest first |
| GET | `/api/settlements/batches/{batchId}/items` | O | Signed items: `CAPTURE`, `REFUND`, `REVERSAL` |
| GET | `/api/settlements/backlog` | O | What the next batches would pay, per merchant and currency |
| GET | `/api/ledger/balances?merchantId=&currency=` | O | Account balances and what is owed to each merchant |
| GET | `/api/ledger/journals?paymentIntentId=` or `?reference=` | O | Journals with their postings; `reference` is, for example, a settlement batch id |
| GET | `/api/ledger/invariants` | O | Runs the books-versus-operations check |
| POST | `/api/reconciliation/reports` | O | Up to 1000 rows (optionally with `refundedAmount`) and the provider's `payouts`. With `settlementDate` (and optionally `merchantId`), payouts missing from the report are flagged. Verdicts: `RECONCILED`, `MATCHED_AWAITING_BANK`, `ALREADY_RECONCILED`, `MATCHED_NO_ACTION`, `MISMATCH`, `NOT_FOUND_LOCALLY`, `DUPLICATE_ROW`, `MISSING_FROM_REPORT` |

### Bank statements and three-way reconciliation

| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/bank/statements` | O | One camt.053 file (`application/xml`). **200** `IMPORTED` or `ALREADY_IMPORTED`; **409** `CONFLICT` (same id or number, other content; a break is opened); **422** `BANK_STATEMENT_INVALID` |
| POST | `/api/bank/statements/pull` | O | Download and import every statement the bank has that is not complete here yet (also runs every minute) |
| GET | `/api/bank/statements?limit=&after=` | O | Imported statements (number, date, balances, entries), newest first |
| GET | `/api/bank/lines?endToEndId=&statementId=&status=&limit=&after=` | O | Bank lines carrying one payout reference, of one statement, or with one match status (`MATCHED`, `SUSPENSE`, `RETURN`, `CLEARED`); oldest first when filtered, newest first otherwise |
| POST | `/api/reconciliation/three-way/run` | O | Match suspense lines, declare missing credits, reconcile payments (also runs every 5 minutes) |
| GET | `/api/reconciliation/three-way/batches/{batchId}` | O | One payout seen three ways: ledger, provider, bank lines, every check, its breaks |
| GET | `/api/reconciliation/breaks?status=&category=&batchId=&limit=&after=` | O | Breaks with owner, amount, suspense, age, due time, overdue and allowed actions; oldest first with a status (a queue), newest first otherwise |
| POST | `/api/reconciliation/breaks/{id}/resolve` | O | Body `{action, batchId, note}`: `ASSIGN_TO_BATCH` (an unmatched or late credit; the amount must still match) or `ACKNOWLEDGE` (only breaks that hold no money); **422** `BREAK_ACTION_NOT_APPLICABLE` |

### Device risk

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/risk/decisions/{paymentIntentId}` | O | Decision, rule, points by component, signals, reported and recomputed level, outcome, label |
| POST | `/api/risk/outcomes` | O | Labels learned later: `{outcomes: [{paymentIntentId, label: FRAUD\|LEGITIMATE, source}]}` |
| POST | `/api/risk/expire-unconfirmed?olderThanSeconds=` | O | Expire step-ups nobody confirmed (the sweeper does this on its own) |
| GET | `/api/risk/report?currency=&merchantId=` | O | Allowed, stepped up (confirmed, abandoned, waiting), blocked; fraud stopped, fraud loss, friction, precision, recall; per rule |
| GET | `/api/risk/threshold-sweep?parameter=STEP_UP\|BLOCK&from=&to=&merchantId=` | O | Each threshold value re-decided from stored points, with fraud loss and friction |

### Review cases

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/review-cases?status=UNRESOLVED\|OPEN\|AWAITING_APPROVAL\|RESOLVED\|ALL&reason=&paymentIntentId=&limit=&after=` | O | Oldest first, with `paymentState`, `allowedActions`, `approvalRequiredFor`, `dueAt` and `overdue` |
| GET | `/api/review-cases/{id}` | O | |
| POST | `/api/review-cases/{id}/resolve` | O | Body `{note, action}`. **200** applied; **202** `AWAITING_APPROVAL` for `APPROVE_REFUND` and `REQUIRE_REVERSAL`; **422** explains what is allowed and why |
| POST | `/api/review-cases/{id}/approve` | O | A different operator than the proposer; **403** `FOUR_EYES_REQUIRED` otherwise |
| POST | `/api/review-cases/{id}/reject-proposal` | O | The case is open again |

### Audit and operations

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/audit-events?paymentIntentId=` or `?aggregateType=&aggregateId=` | O | |
| GET | `/api/audit-events/verify?paymentIntentId=` or `?chainKey=` | O | Recomputes the hash chain; reports the first broken event |
| GET | `/api/operations/summary` | O | Payments per state, stuck payments, review queue (unresolved, awaiting approval, by reason, oldest), refunds, provider calls, settlement backlog, last ledger check, money at risk |
| GET | `/api/operations/worklist?owner=&limit=&after=` | O | Everything waiting for a person, most urgent first (paged by due time; the summary counts everything): owner, money, due time, next step, the calls that resolve it and the evidence; for an unmatched credit, the payout it most likely belongs to ([ADR 029](docs/adr/029-one-worklist-for-people.md)) |
| GET | `/api/operations/money-at-risk?merchantId=` | O | Every kind of money at risk as counts and amounts, for everything or one merchant |
| GET | `/api/operations/invariants` | O | Every money rule checked now, with the evidence of each violation |
| POST | `/api/operations/sweeps/payment-status?olderThanSeconds=` | O | Ask the provider about waiting payments now |
| GET | `/actuator/prometheus`, `/actuator/metrics` | O | `/actuator/health` (and liveness/readiness) is public |

Every operator `POST` accepts an optional `Idempotency-Key`: the first response is stored for 24 hours and
replayed with `Idempotent-Replayed: true`. The full key contract is in [docs/idempotency-keys.md](docs/idempotency-keys.md).

**Every list is paged the same way.** A page holds at most `limit` rows (default 100, at most 1000; anything else is
**400** `VALIDATION_FAILED`). When more rows exist, the response carries `Link: <same request, after=...>; rel="next"`
and `X-Next-After: <cursor>`; the last page carries neither. The cursor is opaque and stands for the last row shown
(its time and id), so rows inserted while you page never shift what comes next, and walking the pages returns every
matching row exactly once. Bodies stay plain JSON arrays (the worklist keeps its object and adds `next`). A cursor
that was not issued by the list is **400** `VALIDATION_FAILED`.

### Merchant notifications

See the [integration guide](docs/merchant-webhooks.md) for verifying signatures and processing events once, in order.

| Method | Path | Who | Notes |
|---|---|---|---|
| PUT | `/api/webhook-endpoint` | M | Body `{url}`. **201** with the signing `secret`, shown once; **200** when moving an existing endpoint (the secret is kept and not shown). **422** `WEBHOOK_URL_NOT_ALLOWED` for anything but https to a public address |
| GET | `/api/webhook-endpoint` | M | The endpoint, without the secret; **404** `WEBHOOK_ENDPOINT_NOT_FOUND` |
| DELETE | `/api/webhook-endpoint` | M | Switch deliveries off; events are still recorded (`SKIPPED`) and listed |
| POST | `/api/webhook-endpoint/rotate-secret` | M | A new `secret`; the previous one keeps signing next to it until `previousSecretSignsUntil` (24 h) |
| GET | `/api/events?paymentIntentId=&type=&status=&from=&limit=&after=` | M, O | Events oldest first, with their delivery state and the exact `payload`; a merchant sees its own, an operator may pass `merchantId` |
| GET | `/api/events/{id}` | M, O | Another merchant's event is **404** `MERCHANT_EVENT_NOT_FOUND` |
| POST | `/api/events/{id}/redeliver` | M, O | Send again from the first attempt (a pending one: now, without waiting for its backoff); **404** `WEBHOOK_ENDPOINT_NOT_FOUND` without an enabled endpoint |
| POST | `/api/merchant-webhooks/run` | O | Run the sender now (it also runs every second) |

Deliveries are `POST`s with `Payflow-Event-Id`, `Payflow-Event-Type` and `Payflow-Signature: t=<unix>,v1=<hex>` (two
`v1` during a rotation). Event types: `payment.created`, `payment.updated`, `refund.created`, `refund.updated`.

### Simulations and simulators (local only)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/simulations/checkout-runs` (O) | Seeded customers through the real API: `{seed, phase, merchantApiKey, providerSlow, holdWebhooks, customers, keyStrategy}` |
| POST | `/api/simulations/risk-runs` (O) | Labelled honest and fraudulent customers with device evidence: `{seed, phase, merchantApiKey, customers}` |
| POST | `/api/simulations/chaos-runs` (O) | A seeded sequence of faults, then every money rule: `{seed, steps, merchantApiKey}` |
| PUT / DELETE | `/provider-sim/v1/latency` | Make the provider slow (page, payment answer, held webhooks) while it stays up; recover |
| POST / DELETE | `/provider-sim/v1/faults` | Outages, commit-then-timeout and refusals for refunds, reversals and payouts |
| GET | `/provider-sim/v1/requests?idempotencyKey=` | What the provider saw under one key: applied, replayed, refused, unavailable |
| GET | `/provider-sim/v1/reports/settlement?date=` | The provider's settlement report, ready for `POST /api/reconciliation/reports` |
| GET | `/bank-sim/v1/accounts`, `/bank-sim/v1/statements/{id}` | The settlement account and its camt.053 statements |
| POST | `/bank-sim/v1/days/close` | Close business days through a date, issuing a statement for each |
| POST / DELETE | `/bank-sim/v1/faults` | `LATE_CREDIT`, `DROP_CREDIT`, `DUPLICATE_CREDIT`, `NET_OF_FEES`, `WRONG_REFERENCE`, `MISSING_REFERENCE`, `RETURN_PAYOUT`, `RESEND_STATEMENT` |
| POST | `/merchant-sim/v1/shops/{shop}/webhooks` | A simulated shop's webhook receiver: verifies the signature, processes each event id once, ignores events older than what it applied |
| PUT / POST / GET / DELETE | `/merchant-sim/v1/shops/{shop}/secret`, `/faults`, `/merchant-sim/v1/shops/{shop}` | Give the shop its secret; make it fail (`failNext`, `status`) or answer slowly (`slowNext`, `delayMillis`); see what it received and applied; forget it |

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
| Keys end to end: canonical fingerprint, scope, expiry; outbound keys derived once | a retry is recognized however it is serialized; a resend never becomes a second payment | [024](docs/adr/024-idempotency-keys-end-to-end.md) |
| The bank statement as the third leg | the provider's word is a claim; only a matched bank credit is cash, and every disagreement gets an owner | [025](docs/adr/025-bank-statement-three-way-reconciliation.md) |
| Money rules as production code, tested with random histories | incidents are combinations nobody wrote a test for | [026](docs/adr/026-property-based-money-invariants.md) |
| Slowness measured as money | "slow but up" passes every health check while customers leave | [027](docs/adr/027-slowness-as-money.md) |
| Device risk decided on the server, measured as money | the device can lie; the server knows the amount; every threshold has a price | [028](docs/adr/028-device-risk-as-money.md) |
| One worklist for people | one call answers "what needs me now, and how do I resolve it?", with due times that match the alerts | [029](docs/adr/029-one-worklist-for-people.md) |

---

## Operations

- **Background jobs**: outbox worker, simulated provider webhooks and merchant webhooks (1 s), bank statement pull (1 min), status
  sweeper (which also expires unconfirmed step-ups), ledger check and three-way reconciliation (5 min),
  idempotency cleanup (1 h), gauge refresh (30 s). `payflow.workers.enabled=false` turns them all off.
- **Metrics**: `payflow.outbox.*`, `payflow.review.queue.*`, `payflow.payments.stuck`,
  `payflow.ledger.invariant.*`, `payflow.webhook.*`, `payflow.sweeper.*`, `payflow.reconciliation.breaks.*`,
  `payflow.payouts.broken`, `payflow.bank.statement.import.age`, `payflow.money.at.risk.*`,
  `payflow.payment.confirmations`, `payflow.risk.*`, `payflow.merchant.events.*`, `payflow.merchant.webhook.deliveries`,
  `payflow.health.up`, plus the standard HTTP, JVM and pool metrics.
- **Alerts**: [`ops/prometheus/alerts.yml`](ops/prometheus/alerts.yml), validated by `promtool` in CI and by a
  test against the real metrics output. Each alert links to its section in the [runbook](docs/runbook.md).
- **Tracing**: trace and span ids on every log line and propagated on provider calls (OpenTelemetry bridge;
  sampling via `PAYFLOW_TRACE_SAMPLING`).
- **Lifecycle**: graceful shutdown waits for in-flight requests and scheduled jobs; liveness and readiness
  probes; the container image runs as a non-root user with a health check.

---

## Documentation Map

- [Settlement Certainty](docs/settlement-certainty.md): the money from capture to the bank, slowness and device
  risk as money, and how it is all tested together
- [Idempotency Keys: the client contract](docs/idempotency-keys.md)
- [Merchant Webhooks: the integration guide](docs/merchant-webhooks.md)
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
- one provider, one settlement account per currency; multi-provider routing, FX and payouts on to each merchant's
  own bank are out of scope
- the bank is simulated and speaks a subset of camt.053; intraday and notification messages are not handled
- simulated customers and fraudsters are models with documented parameters, never data; device risk decides per
  payment, without velocity rules across attempts
- no multi-region behavior or message broker; the outbox is the seam where one would go
- merchant webhook URLs are checked before sending, but the HTTP client resolves the name again (DNS rebinding);
  production sends through an egress proxy that enforces the same rule, and keeps signing secrets encrypted

The full list for the settlement and risk work is in [Settlement certainty: honest limits](docs/settlement-certainty.md#6-honest-limits).

---

## What To Review First

1. Read [Settlement certainty](docs/settlement-certainty.md), run `./scripts/demo-readable.sh`, then
   `./scripts/scenarios.sh bank-reconciliation` and `./scripts/scenarios.sh chaos`
2. `OutboxWorker`: leases, calls outside transactions, lookup before resend, escalation, keys derived once
3. `ProviderWebhookService`: the decision order and why each outcome has its status code
4. `LedgerService` and `LedgerInvariantService`: balanced journals and the books-versus-operations check
5. `ReviewWorkflowService`: decisions and two-person approval
6. `SettlementService`: net payouts, lock order, and the re-check after locking
7. `PayoutMatcher`, `ThreeWayReconciliationService` and `BankStatementImportService`: what lands a payout and what
   becomes a break
8. `RiskPolicy` and `RiskService`: the decision, the hold and the evidence
9. `MoneyInvariantService` and `MoneyInvariantPropertyTest`: the rules and the random histories that test them
10. `OutboxTest`, `ThreeWayReconciliationTest`, `DeviceRiskFlowTest`, `LegacyDataMigrationTest`

The core idea: a small backend that stays correct when the world around it misbehaves, and can prove it.
