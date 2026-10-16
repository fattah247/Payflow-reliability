# 028 Device Risk as Money: a Server-Side Gate on Payment Creation

## Context

A device-risk check in a mobile app is easy to demo: the app sees a rooted phone and shows a warning. In a payment
system the same check is a money decision with two costs. Every fraudulent payment it lets through is a loss; every
honest customer it blocks, or asks for a step-up they give up on, is a sale lost to friction. A check that only
reports "risk: HIGH" answers neither question.

[trustgate-android](https://github.com/fattah247/trustgate-android) is the client half: it detects device signals
(root, emulator, debugger, unknown installer), keeps hardware-backed keys, and signs requests and step-up
confirmations. Its own gate decides from the device alone. The server knows what the device cannot: how much money
is moving and what it buys. And only the server's decision cannot be patched out of the app.

## Decision

### 1. The contract with the device app

The shop sends the device's evidence with the payment request (`riskEvidence`, optional). The fields are the app's
own model, so the app can send what it already has:

| trustgate-android | Payflow | Notes |
|---|---|---|
| `RiskEvidence.level` | `riskEvidence.level` | recorded, **never trusted**: the server recomputes it from the signals |
| `RiskEvidence.flaggedSignalIds` | `riskEvidence.signals[].id` | with each signal's `category` and `severity` as the app assigns them |
| `RiskEvidence.simulated` | `riskEvidence.simulated` | the app was in a lab profile |
| `SignalSeverity` LOW/MEDIUM/HIGH | `SignalSeverity`, weights 1/2/3 | same names, same weights |
| `DeviceRiskLevel` | `DeviceRiskLevel` | same rules: any HIGH signal, or 5+ points, is HIGH; 2+ is MEDIUM |
| `SensitiveActionDecision` | `RiskDecisionType` ALLOW / REQUIRE_CONFIRMATION / BLOCK | same three outcomes |
| `RequiredConfirmation` | `RequiredConfirmation` NONE / STEP_UP_KEY / TAP | STEP_UP_KEY when the device has a step-up key, else TAP |
| key id | `riskEvidence.deviceKeyId` | correlation only |

Each signal also says where it came from (`source`): the app's own checks on the device (`DEVICE_CHECK`, which an
attacker controlling the device can hide), the app's backend from the key's attestation chain
(`KEY_ATTESTATION`), or a simulated profile.

What Payflow deliberately does **not** reimplement: key attestation, request signing and signature verification,
and step-up key enrolment. Those belong to the app and the shop's backend. When the customer confirms a step-up,
the shop's backend verifies the device's signature and tells Payflow which method was used
(`POST /api/payment-intents/{id}/confirm {"method": "STEP_UP_KEY"}`); Payflow checks that it is the method the
decision required.

### 2. The reference policy: points, thresholds, a named rule

`RiskPolicy` is pure: the same evidence, amount and action always give the same decision.

| Points | Rule (configurable, `payflow.risk.*`) |
|---|---|
| device | sum of the detected signals' weights (LOW 1, MEDIUM 2, HIGH 3) |
| amount | +1 from IDR 1,000,000, +1 more from IDR 5,000,000 |
| action | PHYSICAL_GOODS 0, DIGITAL_GOODS 1, STORED_VALUE 2 (what the merchant declares the money buys) |
| missing evidence | 0 by default: shops that integrate server to server are not penalised |

Then, in order: any HIGH signal blocks (`SEVERE_SIGNAL`); total points at or above 5 block (`SCORE_BLOCK`); at or
above 2 require a step-up (`SCORE_STEP_UP`); otherwise allow (`BELOW_THRESHOLDS`).

**Compatibility guarantee:** with amount and action points at zero, the policy decides exactly as the app's gate.
`DeviceRiskPolicyTest` checks this for all 256 combinations of the app's eight signals, for every action.

### 3. A step-up holds the payment; a block is final

```
CREATED ── ALLOW ───────────────────────────────▶ (start) PENDING ...
   │
   ├── REQUIRE_CONFIRMATION ─▶ REQUIRES_CONFIRMATION ── confirm ─▶ CREATED ─▶ (start) PENDING ...
   │                                │
   │                                ├── no confirmation within 10 min ─▶ EXPIRED
   │                                └── shop cancels ─────────────────▶ CANCELLED
   │
   └── BLOCK ─▶ BLOCKED (final)
```

- The decision is taken once, in the transaction that creates the payment. A retry with the same idempotency key
  is answered with that decision, whatever evidence the retry carries: evidence is not part of the request
  fingerprint, so an attacker cannot "shop" for a better decision by retrying with cleaned signals. The action is
  part of the fingerprint (except the default, so older fingerprints still match).
- Starting a payment that waits for a step-up fails with `409 STEP_UP_REQUIRED`; starting a blocked one fails with
  `422 PAYMENT_BLOCKED`. Starting is what hands a payment to the provider, so neither ever reaches it.
- Only the merchant can confirm (an operator confirming a customer's step-up would defeat it). The confirmation
  must use the required method; repeating it is a no-op.
- A capture webhook for a blocked or unconfirmed payment is a contradiction and goes to review; it is never
  applied (as for cancelled payments, ADR 027).
- The status sweeper expires step-ups nobody confirmed within `payflow.risk.confirmation-timeout`.
- The create response carries `requiredConfirmation`, so the shop knows which confirmation to ask for. It does not
  carry the rule or the points: explaining a block to the caller teaches an attacker the thresholds. Operators see
  everything.

### 4. Every decision leaves evidence

- `risk_decisions`: one row per payment with each component's points (a database check makes the total their
  sum), the rule, the signals, the device's reported level and the recomputed one, the required confirmation, the
  outcome (ALLOWED, AWAITING_CONFIRMATION, CONFIRMED, CONFIRMATION_EXPIRED, CANCELLED_UNCONFIRMED, BLOCKED) and,
  later, a label.
- The payment's tamper-evident audit chain gets a `RISK_DECISION` event (rule, points by component, signals, both
  levels) and the state changes it caused.
- The payment's case file shows the decision; operations attention explains what a held or blocked payment is
  waiting on.
- Money at risk adds `AWAITING_CONFIRMATION` and `BLOCKED_TODAY` (count and amount), with gauges.
- Counters `payflow_risk_decisions_total{decision,rule}` and `payflow_risk_decided_amount_total{decision,currency}`.

### 5. Measured as money, from labels

What a payment turned out to be arrives later, as a label: a fraud chargeback, a customer report, or (only in a
simulation) the generator's ground truth (`POST /api/risk/outcomes`). `GET /api/risk/report` then states, in counts
and amounts:

| Measure | Definition |
|---|---|
| fraud stopped | fraud blocked, or stepped up and never confirmed |
| fraud loss | fraud allowed, or stepped up and confirmed anyway |
| legitimate lost to friction | honest customers blocked, or stepped up who gave up |
| precision | of the payments the gate flagged (stepped up or blocked), the share that were fraud |
| recall | of the fraud, the share the gate flagged |

`GET /api/risk/threshold-sweep?parameter=STEP_UP&from=1&to=7` re-decides every labelled payment from its stored
points with one threshold moved, and shows what each value would trade. Where the re-decided outcome is a step-up
the payment never had, nobody knows whether it would have been confirmed, so the observed confirmation rate for its
label is used (or `payflow.risk.assumed-confirm-rate-*` when nothing was observed); those amounts are expected
values and the response says so. At the configured threshold the sweep reproduces the report exactly
(`RiskReportTest`).

### 6. A labelled simulation drives it, and never pretends to be data

`RiskTrafficGenerator` (local only, `POST /api/simulations/risk-runs`) runs seeded customers through the public
API: honest or fraudulent, with a device profile, an amount, an action, a step-up key or not, and whether they would
confirm a step-up. The groups overlap on purpose (account takeover from clean phones, honest customers with rooted
phones, victims talked into confirming), which is what makes every threshold a trade-off. After the run it
discloses its ground truth as labels. Every weight lives in the generator or `payflow.loadgen.risk.*` and is echoed
in the report with a disclaimer.

## Alternatives considered

- **Trust the device's own level.** The device is the attacker's in the cases that matter. The server recomputes
  from the signals and adds what only it knows.
- **A machine-learned score.** No labelled data in a public project, and an opaque score cannot be explained to an
  operator or audited per decision. A points policy with a named rule is explainable, and the sweep shows its
  trade-off; a learned model could later replace `RiskPolicy` behind the same evidence and decision records.
- **Block on any signal.** Maximum recall, and the friction the report would show is the reason not to.
- **Decide when the payment starts.** The payment would exist with unknown risk, and the shop would learn about a
  step-up after it had already sent the customer towards the provider.
- **Re-decide on every retry.** Lets a caller retry with cleaned evidence until it is allowed.

## Consequences

Device risk shows up as money in the same places as everything else: the operations summary, the money-at-risk
gauges, the case file and the audit chain. The limits are stated, not hidden:

- A new idempotency key with cleaned evidence is a new payment and gets a new decision. Correlating attempts by
  device key, card or account (velocity rules) is out of scope.
- `DEVICE_CHECK` signals are self-reported by the device; only attestation-backed signals resist a compromised
  device, and verifying attestation is the app backend's job.
- In production, blocked payments are almost never labelled, so precision measured on live data is biased; real
  systems keep a small random holdout. Only the simulation can label everything.
- Every number the generator produces is model output, not observed fraud.
