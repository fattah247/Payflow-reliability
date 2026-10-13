# Security Review

Threats this service is designed against, how each is mitigated, and what remains. Each mitigation
names the test that proves it.

## Unauthenticated or impersonated callers

Risk: anyone can create payments for any merchant, read every payment, or approve refunds under any name.

Mitigation: every endpoint requires `Authorization: Bearer <key>` except health, the signed webhook and
the provider simulator (which checks its own key). Merchant keys are random (256 bits), shown once,
stored as SHA-256 hashes and revocable; operator keys are configured as hashes, so configuration holds
no usable secret. The actor on every decision and audit event is the authenticated caller, not a field
in the request body. ([ADR 013](adr/013-api-key-authentication.md); `AuthorizationTest`, `MerchantOnboardingTest`)

Remaining limitation: no rate limiting or lockout on failed keys; a real deployment puts the service
behind a gateway that does both. Operator keys are long-lived; an identity provider with short-lived
tokens and MFA is the production answer.

## Cross-merchant access

Risk: merchant A reads or refunds merchant B's payment by guessing ids.

Mitigation: the merchant is derived from the key, a mismatching `merchantId` in a body is refused (403),
and another merchant's payment or refund returns the same 404 as a missing one, so ids cannot be probed.
Idempotency keys are scoped per merchant (payments) and per caller (generic idempotency).
(`AuthorizationTest.shouldHideOtherMerchantsPaymentsAndRefunds`)

## Privilege escalation

Risk: a merchant key calls operator endpoints (settlement, review, ledger, metrics).

Mitigation: URL rules allow merchants only payment creation, start, read, and their refunds; everything
else needs the operator role. Operators cannot create payments. (`AuthorizationTest.shouldKeepOperationalEndpointsForOperators`)

## Single-person fraud

Risk: one operator approves a refund on a reconciled payment, or orders a reversal, alone.

Mitigation: these decisions are proposed by one operator and applied only when a different operator
approves; self-approval returns `403 FOUR_EYES_REQUIRED`. Both names are in the audit chain.
([ADR 018](adr/018-maker-checker.md); `ReviewWorkflowTest.shouldNeedASecondOperatorToReverseAPaymentAfterPayout`)

## Webhook spoofing and replay

Risk: a forged provider callback marks a payment as paid; a captured callback is replayed later.

Mitigation: HMAC-SHA256 over the timestamp and the exact raw body, verified before parsing, constant-time
comparison, 5-minute window. Inside the window a replay is a harmless duplicate (the inbox). Several
secrets can be active for rotation. ([ADR 014](adr/014-signed-webhooks.md); `WebhookSignatureTest`)

## Review-queue flooding

Risk: unauthenticated requests create review cases or audit rows.

Mitigation: rejected webhooks persist nothing and are counted by reason (`payflow.webhook.rejected`, with
an alert). Authenticated but repeated problems reuse the open case; in PostgreSQL a partial unique index
allows only one unresolved case per (reason, subject) even under concurrent writers.

## Duplicate or concurrent money movement

Risk: retried requests, double clicks, racing jobs or a provider timeout cause money to move twice.

Mitigation:
- payment and refund creation require an idempotency key stored with the record; every other mutating
  call accepts one and replays the stored response;
- per-payment row locks in one global order, optimistic versions, and unique constraints on every
  "at most once" fact (inbox event, provider transaction, settlement item per capture/refund/reversal,
  ledger journal per reference);
- provider calls carry idempotency keys, and an unknown outcome is resolved by asking the provider
  before resending ([ADR 015](adr/015-transactional-outbox.md); `OutboxTest`);
- refunds count in-flight amounts against the refundable amount, so concurrent partial refunds can
  never exceed the capture (`RefundTest.shouldNeverRefundMoreThanCapturedUnderConcurrentRequests`).

## Invalid state transitions

Risk: late or contradictory events rewrite final states or let money move while facts are disputed.

Mitigation: the state machine rejects invalid transitions; a contradiction holds an in-flight payment so
it cannot settle, and refunds are not sent to the provider while it is open. Final outcomes are never
overwritten. (`ProviderWebhookTest`, `RefundTest.shouldHoldRefundsWhileTheProviderContradictsThePayment`)

## Silent data alteration

Risk: rounding changes money; someone edits history in the database.

Mitigation: amounts follow the currency's minor unit and are never rounded (`RoundingMode.UNNECESSARY`).
Audit events are hash-chained and verifiable; in PostgreSQL, audit and ledger tables refuse UPDATE,
DELETE and TRUNCATE unless a maintenance transaction opts in. The ledger invariant check detects books
that disagree with operational records. ([ADR 019](adr/019-tamper-evident-audit.md), [ADR 016](adr/016-double-entry-ledger.md);
`AuditChainTest`, `LedgerTest`)

Remaining limitation: a database superuser can rewrite a whole chain consistently; anchoring chain heads
in external write-once storage closes that.

## A simulator left on in production

Risk: the provider simulator's key can create correctly signed provider events, so a deployment that keeps it
on could be told any payment succeeded.

Mitigation: the simulator is off unless enabled; the application refuses to start with the simulator next to a
real provider, or with the local demo key or webhook secret against a real provider, and warns whenever the
simulator is on. (`ProviderConfigurationGuardTest`)

## Secrets

- No secret is stored in plain text: merchant keys and operator keys are hashes; webhook secrets and the
  provider API key come from the environment (`PAYFLOW_PROVIDER_WEBHOOK_SECRETS`, `PAYFLOW_PROVIDER_API_KEY`).
- The defaults in `application.yml` are local demo values, documented as such, and a warning is logged
  if the demo operator keys are configured.
- Merchant onboarding responses (which contain a key) are never stored by the idempotency layer.

## Error disclosure

Unexpected errors return a generic 500 body with a request id; stack traces stay in the logs. Caller-supplied
request ids are accepted only if they match a safe pattern, so they cannot inject into logs.

## Out of scope

Card data and PCI-DSS scope (no card numbers are handled), TLS termination (done by the platform in
front of the service), and network-level DoS protection.
