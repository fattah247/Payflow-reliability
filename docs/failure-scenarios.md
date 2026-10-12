# Failure Scenarios

Each scenario lists what happens, why it matters, the expected behavior, and the test that proves it. Tests commit like production (no test-wide transaction), and the suite also runs on PostgreSQL in CI.

## Duplicate payment request

What happened: the client retries payment creation with the same `Idempotency-Key`.

Why it matters: retries are normal on unreliable networks. Payments must not duplicate.

Expected: the same intent is returned (HTTP 200, `Idempotent-Replayed: true`). Keys are scoped per merchant. A different payload with the same key is rejected with 422 and the original is untouched.

Proof: `PaymentIntentIdempotencyTest.shouldReturnSamePaymentIntentForSameIdempotencyKey`, `shouldRejectSameIdempotencyKeyWithDifferentPayload`, `shouldScopeIdempotencyKeysPerMerchant`

## Concurrent retries of the same request

What happened: a client times out and retries while the first request is still running.

Why it matters: both requests miss the "already exists" lookup. Before this was handled, the loser hit the unique constraint and got a 500.

Expected: exactly one intent; every caller receives it.

Proof: `PaymentIntentIdempotencyTest.shouldCreateExactlyOneIntentWhenTheSameKeyArrivesConcurrently` (8 threads)

## Provider redelivers an event

What happened: the provider sends the same event again because it did not trust delivery.

Why it matters: retries are routine provider behavior, not incidents.

Expected: 200 `DUPLICATE_IGNORED` with the original outcome; no second transaction; an audit event; **no review case**.

Proof: `ProviderWebhookTest.shouldIgnoreAnExactRedeliveryWithoutOpeningAReviewCase`

## Concurrent duplicate deliveries

What happened: the provider's retry arrives while the first delivery is still processing.

Expected: applied exactly once; the others are duplicates.

Proof: `ProviderWebhookTest.shouldApplyAnEventDeliveredConcurrentlyExactlyOnce` (6 threads). Removing the payment row lock makes it fail.

## Same event id, different payload

What happened: an event id is reused with different content.

Why it matters: that is not a retry; something is wrong at the provider or in transit.

Expected: 202 `SENT_TO_REVIEW` with `PROVIDER_EVENT_CONFLICT`; state unchanged.

Proof: `ProviderWebhookTest.shouldSendAReusedEventIdWithADifferentPayloadToReview`

## Authorization then capture on one provider transaction

What happened: the provider sends `AUTHORIZED`, then `SUCCESS`, with the same provider transaction id.

Why it matters: this is the normal two-step card flow. The original implementation treated the capture as a duplicate, so the payment stayed `AUTHORIZED` and never settled.

Expected: both applied; one provider transaction row ending in `SUCCESS`; the payment is settleable.

Proof: `ProviderWebhookTest.shouldCaptureAnAuthorizedPaymentWhenTheProviderReusesItsTransactionId`

## Out-of-order authorization

What happened: `AUTHORIZED` arrives after `SUCCESS`.

Expected: 200 `STALE_IGNORED` (capture implies authorization); no review case.

Proof: `ProviderWebhookTest.shouldIgnoreAnAuthorizationThatArrivesAfterCapture`

## Contradictory late webhook

What happened: a `FAILED` event arrives after the payment succeeded.

Why it matters: the provider and the local record disagree about whether money moved. Leaving the payment `SUCCESS` would let the next settlement pay the merchant while the question is open.

Expected: the payment is held in `MANUAL_REVIEW` (held from `SUCCESS`) with review case `INVALID_STATE_TRANSITION`, so settlement skips it. A payment whose outcome is already final (for example `FAILED`) is left alone and only the case is opened.

Proof: `ProviderWebhookTest.shouldHoldAPaymentWhenTheProviderContradictsIt`, `shouldLeaveAFinalOutcomeAloneWhenTheProviderContradictsIt`, `SettlementTest.shouldKeepAPaymentTheProviderContradictsOutOfSettlement`

## Chargeback after settlement

What happened: the provider sends `REVERSAL_REQUIRED` for a payment already settled.

Expected: the payment is held; the operator can choose `REQUIRE_REVERSAL`.

Proof: `ReviewWorkflowTest.shouldLetAnOperatorReverseAPaymentTheProviderChargedBackAfterSettlement`

## Contradiction on a refund-requested payment

What happened: a refund is requested, then the provider reports a reversal (chargeback).

Why it matters: completing the refund would return the money to the customer twice.

Expected: completing the refund fails with 409 `REFUND_NOT_COMPLETABLE` until an operator resolves the case.

Proof: `RefundTest.shouldNotCompleteARefundWhileTheProviderContradictsThePayment`

## Webhook amount or currency mismatch

What happened: the callback amount or currency differs from the local payment.

Why it matters: tampering, provider bugs, or wrong local records.

Expected: an in-flight payment is held in `MANUAL_REVIEW` and remembers where it was held from. A payment with a final outcome is never overwritten; the case is opened without changing it. Amount and currency are reported as separate reasons.

Proof: `ProviderWebhookTest.shouldHoldAnInFlightPaymentForAmountMismatch`, `shouldReportCurrencyMismatchSeparately`, `shouldNeverOverwriteAFinalStateWhenALateEventMismatches`

## Event while a payment is on hold

Expected: 409 `PAYMENT_ON_HOLD` (retryable), event not recorded. After an operator releases the payment, the provider's redelivery is applied.

Proof: `ProviderWebhookTest.shouldDeferEventsWhileAPaymentIsOnHoldAndApplyTheRedeliveryAfterRelease`

## Provider transaction claimed by two payments

Expected: review case `PROVIDER_TRANSACTION_CONFLICT`; the second payment is unchanged.

Proof: `ProviderWebhookTest.shouldSendAProviderTransactionClaimedByTwoPaymentsToReview`

## Webhook for an unknown payment

Expected: 202 `SENT_TO_REVIEW`; the review case and inbox entry are **persisted**. The original implementation threw after opening the case, which rolled it back.

Proof: `ProviderWebhookTest.shouldPersistTheReviewCaseForAnUnknownPaymentIntent`

## Forged webhook

Expected: 401; no state change, no review case, no inbox row, no audit row.

Proof: `ProviderWebhookTest.shouldRejectAnInvalidSignatureWithoutWritingAnything`, `ApiContractTest.shouldReturn401ForAMissingOrForgedSignature`

## Concurrent settlement runs

What happened: the settlement job is triggered twice at once.

Why it matters: the original implementation paid the merchant once per run.

Expected: one batch; the other runs get 409; each transaction appears in exactly one batch item (database constraint).

Proof: `SettlementTest.shouldNeverSettleTheSameTransactionTwiceUnderConcurrentRuns` (4 threads, 20 transactions). Removing the version columns and the unique constraint reproduces four batches.

## A payment moves on before settlement

What happened: a refund is requested, or the provider requires a reversal, before the batch runs.

Why it matters: the original implementation crashed the whole batch with a 500, blocking settlement for every payment of that merchant.

Expected: the payment is skipped; the rest settle.

Proof: `SettlementTest.shouldSkipPaymentsThatMovedOnInsteadOfFailingTheWholeBatch`, and under concurrency `shouldStayConsistentWhenRefundsAndReversalsRaceASettlementRun`: refunds and reversal webhooks racing a run, with no errors, and every payment's settled flag matching batch membership.

## Merchant with several currencies

Expected: 422 listing the currencies; each currency settles in its own batch.

Proof: `SettlementTest.shouldAskForACurrencyWhenAMerchantHasSeveralAndSettleEachSeparately`

## Provider report mismatch

Expected: per-row verdict `MISMATCH` with each reason; one review case per reason; state unchanged. Importing the same report again does not duplicate open cases and reports `ALREADY_RECONCILED` for matched rows. A payment whose refund is only *requested* is expected to still look successful at the provider and is not flagged; a *completed* refund that the provider still reports as successful is.

Proof: `ReconciliationTest.shouldCreateManualReviewForReconciliationMismatch`, `shouldBeSafeToImportTheSameReportTwice`, `shouldReuseOpenReviewCaseForRepeatedOperationalMismatch`, `shouldOnlyFlagAProviderSuccessForACompletedRefund`

## Refund after reconciliation

Expected: 202 with the refund `PENDING_REVIEW` and a persisted review case. An operator approves it (payment becomes `REFUND_REQUESTED`, then `REFUNDED` once completed) or rejects it (payment stays `RECONCILED`). The original implementation threw and rolled back its own review case.

Proof: `RefundTest.shouldAcceptARefundAfterReconciliationAsPendingReviewAndPersistTheCase`, `ReviewWorkflowTest.shouldApproveARefundOnAReconciledPaymentAndLetItComplete`, `shouldRejectARefundOnAReconciledPaymentAndLeaveThePaymentAlone`

## Partial refund

Why it matters: the original implementation marked the whole payment `REFUNDED` for a refund of any amount.

Expected: 422; full refunds only.

Proof: `RefundTest.shouldRejectAPartialRefundInsteadOfMarkingTheWholePaymentRefunded`

## Retried or concurrent refund requests

Expected: the existing refund is returned; five simultaneous requests create one refund.

Proof: `RefundTest.shouldReturnTheExistingRefundWhenTheRequestIsRetried`, `shouldCreateOneRefundWhenTheSameRequestArrivesConcurrently`

## A held payment with nobody responsible

Expected: the last open case on a held payment cannot simply be acknowledged; it must release, fail, or reverse the payment. Two operators resolving the same case apply one decision.

Proof: `ReviewWorkflowTest.shouldOfferHoldDecisionsAndNotAPlainAcknowledgeForAHeldPayment`, `shouldApplyOnlyOneDecisionWhenTwoOperatorsResolveConcurrently`

## Sub-cent amounts

Why it matters: `100.005` used to be echoed to the client but stored as `100.01`.

Expected: 400; amounts have at most 2 decimals and are returned at that scale everywhere.

Proof: `ApiContractTest.shouldRejectSubCentAmountsInsteadOfSilentlyRoundingThem`, `shouldReturnAmountsAtTheStoredScaleFromEveryEndpoint`
