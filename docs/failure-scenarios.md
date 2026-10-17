# Failure Scenarios

Each scenario lists what happens, why it matters, the expected behavior, and the test that proves it.
Tests commit like production (no test-wide transaction), talk to the provider simulator over real HTTP,
and the whole suite runs on H2 and on PostgreSQL in CI.

## Client and API

### Duplicate payment request

What happened: the client retries payment creation with the same `Idempotency-Key`.

Expected: the same intent is returned (HTTP 200, `Idempotent-Replayed: true`). Keys are scoped per
merchant. A different payload with the same key gets 422 and the original is untouched. `50000` and
`50000.00` are the same amount.

Proof: `PaymentIntentIdempotencyTest`

### Concurrent retries of the same request

Expected: exactly one intent; every caller receives it (8 threads).

Proof: `PaymentIntentIdempotencyTest.shouldCreateExactlyOneIntentWhenTheSameKeyArrivesConcurrently`

### Retried operator calls (settlement, review decisions, reconciliation)

What happened: an operator tool times out and retries a settlement run.

Expected: with an `Idempotency-Key`, the first response is stored and replayed with
`Idempotent-Replayed: true`; a different request under the same key gets 422; concurrent retries execute
once (the others get 409 retryable or the replay); a request rejected by validation leaves the key free;
keys are scoped per caller and forgotten after 24 hours.

Proof: `IdempotencyFilterTest`

### A retry that was serialized differently

What happened: an operator tool retries with the same `Idempotency-Key`, but its JSON library writes the keys
in another order and `1000.00` instead of `1000`.

Expected: recognized as the same request (canonical fingerprint) and replayed; a different amount under the same
key, or the same key on another endpoint, is 422 `IDEMPOTENCY_KEY_REUSED`. Payment keys still replay after 30
days; refund keys are scoped to their payment.

Proof: `IdempotencyKeyRulesTest`, `RequestFingerprintTest`

### Unauthenticated, cross-merchant and over-privileged calls

Expected: no key is 401; another merchant's payment or refund is 404; a merchant on an operator
endpoint is 403; a revoked key stops working at once.

Proof: `AuthorizationTest`, `MerchantOnboardingTest`

### Amounts with the wrong precision

Expected: 400 for more decimals than the currency allows (`IDR 100.005`, `JPY 10.5`), unsupported
currencies rejected, amounts returned at the currency's scale (`JPY 1500`, `IDR 125000.00`, `KWD 1.500`).
Nothing is ever rounded.

Proof: `ApiContractTest.shouldRejectAmountsMorePreciseThanTheCurrencyInsteadOfRoundingThem`,
`shouldReturnAmountsAtTheCurrencyScaleFromEveryEndpoint`, `MoneyTest`

## Provider events

### Forged, tampered or replayed webhook

Expected: 401 and **nothing written** (no inbox row, no review case, no audit event) for a missing or
wrong signature, a body changed after signing, or a timestamp more than 5 minutes away. A replay inside
the window is a harmless duplicate. The previous secret keeps working during rotation.

Proof: `WebhookSignatureTest`

### Provider redelivers an event, possibly concurrently

Expected: 200 `DUPLICATE_IGNORED` with the original outcome; no second transaction, no second ledger
journal, **no review case**. Six concurrent deliveries apply exactly once.

Proof: `ProviderWebhookTest.shouldIgnoreAnExactRedeliveryWithoutOpeningAReviewCaseOrPostingTwice`,
`shouldApplyAnEventDeliveredConcurrentlyExactlyOnce`, `ProviderSimulatorTest.shouldDeliverDuplicatedWebhooksThatAreAppliedOnce`

### Same event id, different payload

Expected: 202 `SENT_TO_REVIEW` with `PROVIDER_EVENT_CONFLICT`; state unchanged.

Proof: `ProviderWebhookTest.shouldSendAReusedEventIdWithADifferentPayloadToReview`

### Authorization then capture; authorization after capture

Expected: both steps of the two-step flow applied on one provider transaction; a late `AUTHORIZED` is
`STALE_IGNORED`.

Proof: `ProviderWebhookTest.shouldCaptureAnAuthorizedPaymentWhenTheProviderReusesItsTransactionId`,
`shouldIgnoreAnAuthorizationThatArrivesAfterCapture`

### Contradictory late event

What happened: `FAILED` after `SUCCESS`, or a chargeback after payout.

Why it matters: the provider and we disagree about whether money moved.

Expected: an in-flight payment is held (`MANUAL_REVIEW`, remembering where from) so it cannot settle and
no refund is sent; a final outcome is left alone; a review case either way. Two conflicting events
racing each other produce one transition and one case.

Proof: `ProviderWebhookTest.shouldHoldAPaymentWhenTheProviderContradictsIt`,
`shouldLeaveAFinalOutcomeAloneWhenTheProviderContradictsIt`, `shouldHoldInsteadOfReversingWhenTheProviderAsksAfterPayout`,
`shouldSerializeConflictingEventsForOnePayment`, `RefundTest.shouldHoldRefundsWhileTheProviderContradictsThePayment`

### Amount or currency mismatch

Expected: hold and review, reasons reported separately; a final payment is never overwritten.

Proof: `ProviderWebhookTest.shouldHoldAnInFlightPaymentForAmountMismatch`, `shouldReportCurrencyMismatchSeparately`,
`shouldNeverOverwriteAFinalStateWhenALateEventMismatches`

### Event while a payment is on hold

Expected: 409 `PAYMENT_ON_HOLD` (retryable), not recorded; the provider keeps retrying and the
redelivery is applied after an operator releases the payment.

Proof: `ProviderWebhookTest.shouldDeferEventsWhileAPaymentIsOnHoldAndApplyTheRedeliveryAfterRelease`,
`ProviderSimulatorTest.shouldRedeliverAWebhookDeferredByAHoldOnceTheHoldIsResolved`

### Provider transaction claimed by two payments; event for an unknown payment

Expected: review cases `PROVIDER_TRANSACTION_CONFLICT` / `PAYMENT_INTENT_NOT_FOUND`, persisted.

Proof: `ProviderWebhookTest.shouldSendAProviderTransactionClaimedByTwoPaymentsToReview`,
`shouldPersistTheReviewCaseForAnUnknownPaymentIntent`

### Lost webhook

What happened: the customer paid, but the webhook never arrives.

Expected: after the threshold the status sweeper asks the provider and applies the answer through the
same rules, recorded as a `POLL` event; the late webhook (if it ever arrives) is a duplicate; a
disagreeing answer goes to review; payments the provider never heard of expire after 24 hours; if the
provider cannot be asked, nothing changes and the next sweep tries again.

Proof: `PaymentStatusSweeperTest`

## Calling the provider

### Provider outage

Expected: the refund stays `REQUESTED`; the call is retried with exponential backoff and jitter under the
same idempotency key and succeeds when the provider is back.

Proof: `OutboxTest.shouldRetryAnOutageAndSucceedWithTheSameIdempotencyKey`

### Provider acted, then timed out

What happened: the provider refunded the customer but answered after our read timeout.

Why it matters: resending blindly can refund twice; treating it as failed loses track of money.

Expected: the outcome is unknown, not failed; the next attempt asks the provider about the idempotency
key first and records the refund it finds. The customer is refunded exactly once.

Proof: `OutboxTest.shouldLookUpAnUnknownOutcomeInsteadOfRefundingTwice`, scenario `provider-outage`

### No answer after every retry

Expected: the command needs review, the refund amount stays reserved, a `PROVIDER_OUTCOME_UNKNOWN` case
offers `RETRY_PROVIDER_CALL`, and retrying completes it.

Proof: `OutboxTest.shouldEscalateWhenRetriesRunOutAndRetryAfterAnOperatorDecides`

### Two workers, or a worker that crashed

Expected: a lease lets one worker hold a command; a dead worker's lease expires and another worker
resends with the same key.

Proof: `OutboxTest.shouldLetOnlyOneWorkerHoldACommand`, `shouldPickUpACommandWhoseWorkerDiedAfterTheLeaseExpires`

### Provider refuses

Expected: the refund `FAILED` with the provider's reason and a review case; nothing is booked; the
amount is refundable again.

Proof: `RefundTest.shouldFailTheRefundAndOpenACaseWhenTheProviderRefusesIt`

### The worker dies after the provider applied the call

What happened: the provider refunded the customer; our worker crashed before recording the answer.

Why it matters: if the refund still looked unsent, it could be cancelled and requested again: refunded twice,
booked once.

Expected: dispatch is committed before the call leaves, so the refund can no longer be cancelled; the next
worker finds the refund by its key and records it once.

Proof: `OutboxRecoveryTest.shouldNeverCancelARefundTheProviderMayAlreadyHave`

### The worker dies right after the provider refunded (real crash)

What happened: the worker's thread dies between the provider's answer and our commit.

Expected: the next worker, after the lease expires, sends the same key (`refund-<refundId>`), the provider
answers with its stored result, and the refund is recorded once. The provider's own request log shows a single
key and one applied operation.

Proof: `OutboundIdempotencyKeyTest.shouldResendTheSameKeyAfterAWorkerDiesOnceTheProviderActed`,
`shouldSendEveryAttemptUnderTheSameKey`

### The provider has forgotten the key

What happened: a call first sent more than a day ago is still unresolved, and the provider prunes idempotency
keys after 24 hours. It now answers "unknown key", which could mean "never received" or "forgot".

Why it matters: a resend under the forgotten key is a new refund.

Expected: past `payflow.provider.key-resend-window` (23 h) the worker only looks the key up and never resends;
an unknown key escalates for a person to check the provider's records. The customer is refunded once.

Proof: `OutboundIdempotencyKeyTest.shouldNeverResendUnderAKeyTheProviderMayHaveForgotten`,
`ProviderSimulatorTest.shouldForgetIdempotencyKeysAfterTheRetentionPeriodLikeARealProvider`

### A lookup fails after an unknown outcome

Expected: a refused or failed lookup (for example during a key rotation) is an unknown outcome, never a
refusal of the refund; the refund completes once.

Proof: `OutboxRecoveryTest.shouldNotTreatAFailedLookupAsARefusal`

### A chargeback races a refund the provider already applied

Expected: the reversal books what the provider says it returned (70 of 100, not 100); when the refund's
confirmation arrives it is recorded too; refunded plus reversed equals the payment and the books balance.
A refund never sent before the reversal fails instead of being sent.

Proof: `OutboxRecoveryTest.shouldBookWhatTheProviderReversedWhileARefundIsStillOnItsWay`,
`shouldNotSendARefundForAPaymentThatWasReversedFirst`

### The provider refuses a reversal

Expected: the payment keeps waiting for a reversal and a case offers `RETRY_PROVIDER_CALL`, which sends a new
command under a new idempotency key.

Proof: `OutboxRecoveryTest.shouldSendARefusedReversalAgainUnderANewKeyWhenAnOperatorDecides`

### An answer that cannot be recorded

Expected: counted as an attempt, retried, then escalated for review; never retried forever, nothing booked.

Proof: `OutboxRecoveryTest.shouldEscalateAProviderAnswerThatCannotBeRecordedInsteadOfRetryingForever`

## Refunds

### Partial refunds

Expected: allowed up to captured minus refunded minus refunds in progress; `REFUNDED` only when the whole
amount is refunded; five concurrent requests can never exceed the capture.

Proof: `RefundTest.shouldAllowPartialRefundsUpToTheCapturedAmount`, `shouldNeverRefundMoreThanCapturedUnderConcurrentRequests`

### Retried or concurrent refund requests

Expected: one refund per idempotency key; a reused key with a different amount is 422.

Proof: `RefundTest.shouldReturnTheExistingRefundWhenTheRequestIsRetried`, `shouldCreateOneRefundWhenTheSameRequestArrivesConcurrently`

### Cancelling a refund

Expected: possible until the provider has it; cancelling twice is a no-op; afterwards 409.

Proof: `RefundTest.shouldCancelARefundThatHasNotBeenSent`, `shouldRefuseToCancelARefundTheProviderAlreadyHas`

### Refund after reconciliation

Expected: 202 `PENDING_REVIEW`; one operator proposes, a different operator approves; only then does the
provider execute it.

Proof: `ReviewWorkflowTest.shouldApproveARefundOnAReconciledPaymentWithTwoOperatorsAndLetTheProviderExecuteIt`

## Settlement

### Concurrent settlement runs

Expected: one batch; the others get 409; each capture appears in exactly one batch item (database
constraint); one payout journal.

Proof: `SettlementTest.shouldNeverSettleTheSameCaptureTwiceUnderConcurrentRuns`

### Refunds and reversals before or after payout

Expected: deducted from the payout (before) or from the next one (after); a negative net is not paid and
carries forward; the ledger owes nothing after each payout.

Proof: `SettlementTest.shouldDeductRefundsCompletedBeforeThePayout`, `shouldDeductARefundMadeAfterPayoutFromTheNextBatch`,
`shouldCarryANegativeNetForwardInsteadOfPayingIt`

### A payment on hold or mid-reversal, or one that changes while the batch runs

Expected: skipped, never failing the batch; under a race with refunds and reversal webhooks the books stay
consistent.

Proof: `SettlementTest.shouldWaitForPaymentsOnHoldOrMidReversalWithoutFailingTheBatch`,
`shouldReCheckEachPaymentAfterLockingIt`, `shouldStayConsistentWhenRefundsAndReversalsRaceASettlementRun`

### A chargeback under a new provider transaction id

Why it matters: the chargeback creates a second transaction row for the payment; treated as a capture, it
would be paid out a second time.

Expected: a payment's capture is paid out once (database constraint); the invariant check also catches any
payout that does not match what it settles.

Proof: `SettlementTest.shouldPayACaptureOutOnceEvenWhenAChargebackArrivesUnderANewTransactionId`,
`LedgerTest.shouldDetectAPayoutThatDoesNotMatchWhatItSettles`

### Several currencies

Expected: 422 listing them; each currency settles in its own batch.

Proof: `SettlementTest.shouldAskForACurrencyWhenAMerchantHasSeveralAndSettleEachSeparately`

## Reconciliation

### Report rows that disagree

Expected: a verdict per row (amount, currency, status, date, refunded amount); one review case per reason;
state unchanged; re-importing reuses open cases.

Proof: `ReconciliationTest`

### Payouts missing from the report

Expected: with a settlement date (and optionally a merchant), every payout recorded for that scope but not
reported is flagged `MISSING_FROM_REPORT` and opens a case.

Proof: `ReconciliationTest.shouldFlagPayoutsTheProviderDidNotReport`, `shouldAcceptAnEmptyReportForADayWithNoProviderActivity`

## The bank leg (settlement certainty)

Every case below ends in exactly one place: the payout LANDED, or the money is in `bank_suspense` with an open
break that says why, who owns it and when it is due. After each test the ledger check proves that bank credits
equal payouts minus fees plus what open breaks hold, to the cent. Fees in the examples: the provider deducts
30 bps (9.00 on a 3000.00 payout, net 2991.00).

### The provider says it paid

Expected: the payout is IN_TRANSIT (money in `payouts_in_transit`), never LANDED, until a bank credit matches it
by reference, amount and value date.

Proof: `PayoutLifecycleTest.shouldGoFromSentToInTransitToLandedWithTheFeeOnItsOwnLine`, `shouldNotTreatTheProvidersWordAsArrival`

### The provider refuses the payout; the worker dies after the provider paid

Expected: BROKEN(PROVIDER_REFUSED) with a break for provider operations, money still with the provider; after a
crash the next worker finds the payout by its key (`payout-<batchId>`), one payout, one credit.

Proof: `PayoutLifecycleTest.shouldBreakAPayoutTheProviderRefusesAndLeaveTheMoneyWithTheProvider`,
`shouldResendAPayoutUnderItsKeyAfterAWorkerDiesOnceTheProviderPaid`

### Missing credit

Expected: declared only once every statement through the end of the value-date window has arrived; BROKEN
(MISSING_CREDIT), a treasury break due in 24 hours, overdue after that. A credit that turns up later still lands it.

Proof: `ThreeWayReconciliationTest.shouldDeclareACreditMissingOnlyWhenEveryStatementThroughTheWindowHasArrived`

### Late credit

Expected: inside the window it simply lands; outside it is held in suspense (LATE_CREDIT) until a person assigns
it, which lands the payout and closes the missing-credit break too.

Proof: `shouldLandACreditThatIsLateButInsideTheWindow`, `shouldHoldACreditOutsideTheWindowUntilSomeoneAcceptsIt`

### Duplicate credit

Expected: the first lands, the second is held in suspense (DUPLICATE_CREDIT) and cannot be acknowledged away;
when the bank takes it back, suspense returns to zero and the payout stays landed.

Proof: `shouldHoldADuplicateCreditInSuspenseUntilTheBankTakesItBack`

### Credit net of a bank charge

Expected: the charge itemized in the statement is booked as its own line: received 2984.50 + provider fee 9.00
+ bank charges 6.50 = gross 3000.00. A shortfall the bank does not itemize is an AMOUNT_MISMATCH.

Proof: `shouldBookBankChargesAsTheirOwnLineSoGrossMinusFeesIsExactlyNet`, `shouldHoldACreditWhoseAmountDoesNotAddUp`

### Wrong or missing reference

Expected: never matched by amount, even when a payout of exactly that amount is waiting; held in suspense
(UNMATCHED_CREDIT) until assigned. An assignment overrides the reference, never the amount.

Proof: `shouldNeverMatchByAmountAlone`, `shouldRefuseToAssignACreditWhoseAmountDoesNotMatch`

### Returned payout

Expected: the return debit puts the money back on the provider (`provider_receivable`), the payout is
BROKEN(RETURNED), treasury gets a break.

Proof: `shouldPutAReturnedPayoutBackOnTheProvider`

### A statement imported twice, re-sent, repeated, or out of order

Expected: the same file twice books nothing twice; the same id with other content is a conflict (409, a break);
a statement repeating yesterday's entry is not booked twice and treasury is told; statements arriving in any
order book the same, and a gap is never mistaken for a missing credit.

Proof: `shouldImportTheSameStatementTwiceWithoutBookingAnythingTwice`, `shouldIgnoreAStatementTheBankDeliversTwice`,
`shouldBookStatementsTheSameWhateverOrderTheyArriveIn`, `shouldMatchACreditThatArrivedBeforeTheProviderConfirmed`

### Provider report vs ledger; no reconciliation without the bank

Expected: the provider's payout rows must agree with the ledger and with its own answer (fee, net, payout id);
payouts it omits or invents are breaks. A clean payment row is MATCHED_AWAITING_BANK until the payout lands.

Proof: `ProviderPayoutReportTest`, `ReconciliationTest.shouldReconcileOnlyOnceTheReportMatchesAndThePayoutHasLanded`

## Slowness (up, but costing money)

The customers in these tests are simulated (`CheckoutLoadGenerator`, seed 42): their patience and choices are model
parameters, documented in ADR 027, not observed behaviour.

### The provider is slow but up

What happened: the provider's payment page and payment answers slow down and its webhooks lag, while every health
check passes.

Expected: customers with less patience leave (the shop cancels their payment as ABANDONED) or retry; paid payments
wait past the confirmation SLO. The payment system measures the abandoned amount, the payments and money stuck past
the SLO, and confirmations outside the SLO once the provider recovers, and each figure equals the simulation's own
count. `PayflowSlowButUp` pages while `payflow_health_up` is 1.

Proof: `CheckoutLoadTest.shouldMeasureWhatASlowProviderCostsWhileEverythingIsUp`, `CheckoutSloTest`,
`ops/prometheus/alerts.test.yml`

### Impatient customers press pay again

Expected: with one key per checkout every retry is answered with the original payment and the provider refuses to
charge it twice: zero duplicate charges. With a key per attempt each retry is a new payment, charged again, and the
payment system counts every duplicate (`PayflowDuplicateCharge` pages).

Proof: `CheckoutLoadTest.shouldShowWhatAKeyPerAttemptCosts`

### The customer pays after the shop gave up

Expected: a capture for a CANCELLED payment is never applied; it goes to review (the provider holds money for a
cancelled checkout and someone has to return it).

Proof: `CheckoutSloTest.shouldSendACaptureForACancelledCheckoutToReviewInsteadOfTakingTheMoney`

## Device risk (what the gate stops, what it costs)

The labelled customers in these tests are simulated (`RiskTrafficGenerator`, seed 42): device profiles, amounts,
fraud share and step-up completion are model parameters, documented in ADR 028, not observed fraud.

### A rooted phone, a lying device

What happened: the app reports a high-severity signal (for example `root_package`) and claims its own level is LOW.

Expected: the server recomputes the level from the signals, blocks (`SEVERE_SIGNAL`), records both levels, and
never starts the payment: no provider page, no provider call, no outbox command. Starting it fails with
`422 PAYMENT_BLOCKED`. It counts in money at risk as blocked today.

Proof: `DeviceRiskFlowTest.shouldBlockForGoodAndNeverHandTheBlockedPaymentToTheProvider`,
`DeviceRiskPolicyTest.shouldNeverTrustTheLevelTheDeviceReportsForItself`

### A step-up nobody confirms

Expected: the payment waits in REQUIRES_CONFIRMATION (starting it fails with `409 STEP_UP_REQUIRED`) and counts as
money awaiting confirmation. The wrong method, or an operator, cannot confirm it. With the right method it starts
like any other; without, the sweeper expires it after the confirmation timeout, still never having reached the
provider. A cancelled step-up is recorded as abandoned friction.

Proof: `DeviceRiskFlowTest.shouldHoldASteppedUpPaymentUntilTheCustomerConfirms`,
`shouldExpireAStepUpNobodyConfirmedWithoutEverReachingTheProvider`, `shouldRecordACancelledStepUpAsAbandonedFriction`

### A retry with cleaned evidence

What happened: the shop retries the blocked request with the same idempotency key, this time with no signals.

Expected: the same payment, still BLOCKED; the decision is taken once. Changing what is bought under the same key is
a different request (`422 IDEMPOTENCY_KEY_REUSED`).

Proof: `DeviceRiskFlowTest.shouldAnswerARetryWithTheFirstDecisionWhateverEvidenceItCarries`

### The provider charges a blocked or unconfirmed payment anyway

Expected: the capture is never applied; it goes to review, and no capture journal is posted.

Proof: `DeviceRiskFlowTest.shouldSendACaptureForABlockedOrUnconfirmedPaymentToReviewInsteadOfApplyingIt`

### Is the gate worth it?

Expected: from its own decisions and the labels learned afterwards, the payment system reports fraud stopped, fraud
loss, honest money lost to friction, precision and recall, each equal to the simulation's ground truth to the
rupiah. Moving the step-up threshold up lowers friction and raises fraud loss, never the other way round.

Proof: `RiskReportTest`

## Everything at once

### Random histories of faults

What happened: any mix of the failures above, in any order: retried checkouts, duplicated, lost and reordered
webhooks, refunds while the provider is down or commits then times out, workers dying after the provider acted,
racing settlement runs, chargebacks around payout, every bank fault, re-imported and re-issued statements, slow
periods, abandoned checkouts, step-ups and blocks, and a provider charging a blocked payment.

Expected: once everything settles, all twelve money rules hold (`GET /api/operations/invariants`): books balance
against the bank and the open breaks to the cent, no capture paid out twice, nothing reconciled without a bank
line, every unmatched line has a break, no bank entry booked twice, every landing proven, no blocked payment at the
provider, no step-up started unconfirmed, no retry charged twice, refunds and payouts executed once.

Proof: `MoneyInvariantPropertyTest` (50 random histories per run, with coverage checks), `ChaosRunTest`,
`./scripts/scenarios.sh chaos` against the packaged app in CI

## Telling the shop

### The shop's server is down, slow, or answers with a redirect

Expected: every failed delivery (an error status, a redirect, which is never followed, or no answer within the read
timeout) is retried with exponential backoff and jitter; after the last attempt the event is `FAILED`, still listed
and redeliverable; a shop that processed a delivery but answered too late gets it again and recognizes the event id.

Proof: `MerchantWebhookTest.shouldRetryErrorsRedirectsAndSlowAnswersWithBackoffUntilTheShopAnswers`,
`shouldGiveUpAfterTheLastAttemptAndSendAgainWhenAsked`

### Deliveries arrive out of order

Expected: events are numbered per payment; when a retried event arrives after a newer one, the shop (as the guide
asks, and as the simulated shop does) ignores it as stale and keeps the newer state.

Proof: `MerchantWebhookTest.shouldAnnounceRefundsAndPutOvertakenDeliveriesBackInOrder`

### A change rolls back, or the shop has no endpoint

Expected: an event is written in the transaction of its change, so a rolled-back change has no event; without an
endpoint, events are recorded as `SKIPPED` and listed; switching the endpoint off skips what was waiting.

Proof: `MerchantWebhookTest.shouldWriteAnEventOnlyWithItsChangeAndKeepItReadableWithoutAnEndpoint`; the money rule
`PAYMENT_CHANGES_ANNOUNCED` (`shouldCatchAChangeTheShopWasNeverTold`, and after every random history of faults)

### The signing secret is rotated

Expected: for 24 hours deliveries carry signatures with both secrets, so a shop verifies before and after it switches;
after the overlap only the new secret signs. A forged delivery does not verify.

Proof: `MerchantWebhookTest.shouldKeepVerifyingThroughASecretRotation`, the `merchant-webhooks` scenario

### A shop points its webhook at our own network

Expected: only https URLs whose host resolves to public addresses are accepted, at registration and before every
delivery: loopback, private, link-local (cloud metadata), carrier-grade NAT, multicast and reserved addresses are
refused, also inside IPv4-mapped and NAT64 IPv6 addresses; credentials in the URL are refused; redirects are never
followed. Next to a real provider, the application refuses to start with private addresses allowed.

Proof: `DestinationGuardTest`, `MerchantWebhookTest.shouldRefuseEndpointsThatAreNotSafeToSendTo`,
`ProviderConfigurationGuard`

## Operations and evidence

### Books that disagree with operations

Expected: the invariant check reports unbalanced journals and merchant balances that differ from
captures minus refunds, reversals and payouts; the gauge drives a paging alert.

Proof: `LedgerTest.shouldDetectBooksThatDisagreeWithOperations`, `shouldDetectAnUnbalancedJournalWrittenAroundTheService`

### Edited or deleted history

Expected: a modified, removed or re-hashed audit event breaks verification at that point; in PostgreSQL
the change is refused outright without the maintenance opt-in.

Proof: `AuditChainTest`

### A held payment with nobody responsible; one operator moving money alone

Expected: the last open case on a held payment that can decide the hold needs a decision (a refund case cannot
release a payment, so it does not count); a refund cannot be approved while its payment is held; money-moving
decisions need two operators; two operators resolving concurrently apply one decision.

Proof: `ReviewWorkflowTest`

### Alerts that silently stop working

Expected: every metric used by an alert rule is exported; every alert has a runbook section; the ledger
check and the gauge snapshot have dead man's switches.

Proof: `AlertRulesTest`, `OperationsTest.shouldPublishGaugesForAlerting`

### Upgrading a database with existing data

Expected: legacy states mapped, capture times taken from the audit trail, ledger journals backfilled and
balanced, audit chains built and verifiable, in-flight refunds and reversals queued for the provider, and
duplicate open review cases (possible before) closed so the new uniqueness rule can be enforced.

Proof: `LegacyDataMigrationTest` (on both databases)
