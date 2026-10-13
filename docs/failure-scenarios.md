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
