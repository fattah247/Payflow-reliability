package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderWebhookTest extends IntegrationTest {

    @Test
    void shouldApplySuccessAndRecordTheProviderTransactionAndEvent() {
        PaymentIntent intent = startedIntent("ORDER-1001", "125000");

        WebhookResult result = webhook(intent.getId(), "evt-001", "provider-tx-001", ProviderStatus.SUCCESS, "125000");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.APPLIED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
        assertThat(paymentTransactionRepository.findAll()).singleElement()
                .extracting(PaymentTransaction::getProviderStatus).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(providerEventRepository.findByProviderEventId("evt-001")).get()
                .extracting(ProviderEvent::getOutcome).isEqualTo(WebhookOutcome.APPLIED);
    }

    @Test
    void shouldIgnoreAnExactRedeliveryWithoutOpeningAReviewCase() {
        PaymentIntent intent = startedIntent("ORDER-1001", "125000");

        webhook(intent.getId(), "evt-001", "provider-tx-001", ProviderStatus.SUCCESS, "125000");
        WebhookResult redelivery = webhook(intent.getId(), "evt-001", "provider-tx-001", ProviderStatus.SUCCESS, "125000");

        assertThat(redelivery.outcome()).isEqualTo(WebhookOutcome.DUPLICATE_IGNORED);
        assertThat(redelivery.originalOutcome()).isEqualTo(WebhookOutcome.APPLIED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
        assertThat(paymentTransactionRepository.count()).isEqualTo(1);
        assertThat(providerEventRepository.count()).isEqualTo(1);
        assertThat(openCases()).isEmpty();
        assertThat(auditFor(intent)).extracting("eventType").contains("DUPLICATE_WEBHOOK_IGNORED");
    }

    @Test
    void shouldSendAReusedEventIdWithADifferentPayloadToReview() {
        PaymentIntent intent = startedIntent("ORDER-1002", "50000");
        webhook(intent.getId(), "evt-002", "provider-tx-002", ProviderStatus.AUTHORIZED, "50000");

        WebhookResult conflicting = webhook(intent.getId(), "evt-002", "provider-tx-002", ProviderStatus.SUCCESS, "50000");

        assertThat(conflicting.outcome()).isEqualTo(WebhookOutcome.SENT_TO_REVIEW);
        assertThat(conflicting.reviewReason()).isEqualTo(ReviewReason.PROVIDER_EVENT_CONFLICT);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.AUTHORIZED);
    }

    @Test
    void shouldCaptureAnAuthorizedPaymentWhenTheProviderReusesItsTransactionId() {
        PaymentIntent intent = startedIntent("ORDER-1003", "150000");

        WebhookResult authorized = webhook(intent.getId(), "evt-003-auth", "provider-tx-003", ProviderStatus.AUTHORIZED, "150000");
        WebhookResult captured = webhook(intent.getId(), "evt-003-capture", "provider-tx-003", ProviderStatus.SUCCESS, "150000");

        assertThat(authorized.outcome()).isEqualTo(WebhookOutcome.APPLIED);
        assertThat(captured.outcome()).isEqualTo(WebhookOutcome.APPLIED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
        assertThat(paymentTransactionRepository.findAll()).singleElement()
                .extracting(PaymentTransaction::getProviderStatus).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(settlementService.createBatch(MERCHANT, null).getTransactionCount()).isEqualTo(1);
    }

    @Test
    void shouldCreateAuditEventForEveryStateChange() {
        PaymentIntent intent = startedIntent("ORDER-1004", "150000");

        webhook(intent.getId(), "evt-004-auth", "provider-tx-004", ProviderStatus.AUTHORIZED, "150000");
        webhook(intent.getId(), "evt-004-success", "provider-tx-004", ProviderStatus.SUCCESS, "150000");

        assertThat(auditFor(intent))
                .filteredOn(event -> "TRANSACTION_STATE_CHANGED".equals(event.getEventType()))
                .extracting(event -> event.getPreviousState() + "->" + event.getNewState())
                .containsExactly("CREATED->PENDING", "PENDING->AUTHORIZED", "AUTHORIZED->SUCCESS");
    }

    @Test
    void shouldIgnoreAStatusAlreadyRecordedForTheTransactionUnderANewEventId() {
        PaymentIntent intent = startedIntent("ORDER-1005", "70000");
        webhook(intent.getId(), "evt-005-a", "provider-tx-005", ProviderStatus.SUCCESS, "70000");

        WebhookResult restated = webhook(intent.getId(), "evt-005-b", "provider-tx-005", ProviderStatus.SUCCESS, "70000");

        assertThat(restated.outcome()).isEqualTo(WebhookOutcome.DUPLICATE_IGNORED);
        assertThat(openCases()).isEmpty();
        assertThat(paymentTransactionRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldIgnoreAnAuthorizationThatArrivesAfterCapture() {
        PaymentIntent intent = startedIntent("ORDER-1006", "80000");
        webhook(intent.getId(), "evt-006-success", "provider-tx-006", ProviderStatus.SUCCESS, "80000");

        WebhookResult lateAuthorization = webhook(intent.getId(), "evt-006-auth", "provider-tx-006", ProviderStatus.AUTHORIZED, "80000");

        assertThat(lateAuthorization.outcome()).isEqualTo(WebhookOutcome.STALE_IGNORED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
        assertThat(openCases()).isEmpty();
    }

    @Test
    void shouldHoldAPaymentWhenTheProviderContradictsIt() {
        PaymentIntent intent = startedIntent("ORDER-1007", "225000");
        webhook(intent.getId(), "evt-007-success", "provider-tx-007", ProviderStatus.SUCCESS, "225000");

        WebhookResult late = webhook(intent.getId(), "evt-007-failed", "provider-tx-007", ProviderStatus.FAILED, "225000");

        assertThat(late.outcome()).isEqualTo(WebhookOutcome.SENT_TO_REVIEW);
        assertThat(late.reviewReason()).isEqualTo(ReviewReason.INVALID_STATE_TRANSITION);
        PaymentIntent held = reload(intent);
        assertThat(held.getState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(held.getStateBeforeReview()).isEqualTo(PaymentState.SUCCESS);
        assertThat(paymentTransactionRepository.findAll()).singleElement()
                .extracting(PaymentTransaction::getProviderStatus).isEqualTo(ProviderStatus.SUCCESS);
    }

    @Test
    void shouldLeaveAFinalOutcomeAloneWhenTheProviderContradictsIt() {
        PaymentIntent intent = startedIntent("ORDER-1018", "1000");
        webhook(intent.getId(), "evt-1018-failed", "provider-tx-1018", ProviderStatus.FAILED, "1000");

        WebhookResult late = webhook(intent.getId(), "evt-1018-success", "provider-tx-1018", ProviderStatus.SUCCESS, "1000");

        assertThat(late.outcome()).isEqualTo(WebhookOutcome.SENT_TO_REVIEW);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.FAILED);
        assertThat(openReviewReasons()).containsExactly(ReviewReason.INVALID_STATE_TRANSITION);
    }

    @Test
    void shouldHoldAnInFlightPaymentForAmountMismatch() {
        PaymentIntent intent = startedIntent("ORDER-1008", "99000");

        WebhookResult result = webhook(intent.getId(), "evt-008", "provider-tx-008", ProviderStatus.SUCCESS, "98000");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.SENT_TO_REVIEW);
        assertThat(result.reviewReason()).isEqualTo(ReviewReason.AMOUNT_MISMATCH);
        PaymentIntent held = reload(intent);
        assertThat(held.getState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(held.getStateBeforeReview()).isEqualTo(PaymentState.PENDING);
        assertThat(paymentTransactionRepository.count()).isZero();
        assertThat(openCases()).singleElement().satisfies(reviewCase -> {
            assertThat(reviewCase.getReason()).isEqualTo(ReviewReason.AMOUNT_MISMATCH);
            assertThat(reviewCase.getPaymentIntentId()).isEqualTo(intent.getId());
        });
    }

    @Test
    void shouldReportCurrencyMismatchSeparately() {
        PaymentIntent intent = startedIntent("ORDER-1009", "99000");

        WebhookResult result = webhook(intent.getId(), "evt-009", "provider-tx-009", ProviderStatus.SUCCESS, "99000", "USD");

        assertThat(result.reviewReason()).isEqualTo(ReviewReason.CURRENCY_MISMATCH);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.MANUAL_REVIEW);
    }

    @Test
    void shouldNeverOverwriteAFinalStateWhenALateEventMismatches() {
        PaymentIntent intent = startedIntent("ORDER-1010", "100");
        webhook(intent.getId(), "evt-010-failed", "provider-tx-010", ProviderStatus.FAILED, "100");

        WebhookResult late = webhook(intent.getId(), "evt-010-late", "provider-tx-010-b", ProviderStatus.SUCCESS, "1");

        assertThat(late.outcome()).isEqualTo(WebhookOutcome.SENT_TO_REVIEW);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.FAILED);
        assertThat(openReviewReasons()).containsExactly(ReviewReason.AMOUNT_MISMATCH);
    }

    @Test
    void shouldDeferEventsWhileAPaymentIsOnHoldAndApplyTheRedeliveryAfterRelease() {
        PaymentIntent intent = startedIntent("ORDER-1011", "60000");
        webhook(intent.getId(), "evt-011-wrong", "provider-tx-011", ProviderStatus.SUCCESS, "6000");

        WebhookResult corrected = webhook(intent.getId(), "evt-011-correct", "provider-tx-011", ProviderStatus.SUCCESS, "60000");
        assertThat(corrected.outcome()).isEqualTo(WebhookOutcome.DEFERRED);
        assertThat(providerEventRepository.findByProviderEventId("evt-011-correct")).isEmpty();

        reviewWorkflowService.resolve(openCases().getFirst().getId(),
                new ResolveReviewCaseRequest("ops-1", "Provider confirmed the first amount was a display bug.", ReviewAction.RELEASE_PAYMENT));
        WebhookResult redelivered = webhook(intent.getId(), "evt-011-correct", "provider-tx-011", ProviderStatus.SUCCESS, "60000");

        assertThat(redelivered.outcome()).isEqualTo(WebhookOutcome.APPLIED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
    }

    @Test
    void shouldSendAProviderTransactionClaimedByTwoPaymentsToReview() {
        PaymentIntent first = startedIntent("ORDER-1012", "1000");
        PaymentIntent second = startedIntent("ORDER-1013", "1000");
        webhook(first.getId(), "evt-012", "provider-tx-shared", ProviderStatus.SUCCESS, "1000");

        WebhookResult crossWired = webhook(second.getId(), "evt-013", "provider-tx-shared", ProviderStatus.SUCCESS, "1000");

        assertThat(crossWired.reviewReason()).isEqualTo(ReviewReason.PROVIDER_TRANSACTION_CONFLICT);
        assertThat(stateOf(second)).isEqualTo(PaymentState.PENDING);
    }

    @Test
    void shouldPersistTheReviewCaseForAnUnknownPaymentIntent() {
        UUID unknown = UUID.randomUUID();

        WebhookResult result = webhook(unknown, "evt-014", "provider-tx-014", ProviderStatus.SUCCESS, "1000");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.SENT_TO_REVIEW);
        assertThat(result.paymentIntent()).isNull();
        assertThat(openReviewReasons()).containsExactly(ReviewReason.PAYMENT_INTENT_NOT_FOUND);
        assertThat(providerEventRepository.findByProviderEventId("evt-014")).isPresent();
    }

    @Test
    void shouldRejectAnInvalidSignatureWithoutWritingAnything() {
        PaymentIntent intent = startedIntent("ORDER-1015", "1000");
        long auditBefore = auditEventRepository.count();

        assertThatThrownBy(() -> providerWebhookService.process(new ProviderWebhookRequest(
                "evt-015", "provider-tx-015", intent.getId(), ProviderStatus.SUCCESS, new BigDecimal("1000"), "IDR"),
                "forged"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_SIGNATURE);

        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
        assertThat(openCases()).isEmpty();
        assertThat(providerEventRepository.count()).isZero();
        assertThat(auditEventRepository.count()).isEqualTo(auditBefore);
    }

    @Test
    void shouldApplyAnEventDeliveredConcurrentlyExactlyOnce() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-1016", "42000");

        List<Object> results = race(6, () ->
                webhook(intent.getId(), "evt-016", "provider-tx-016", ProviderStatus.SUCCESS, "42000").outcome());

        assertThat(results).filteredOn(WebhookOutcome.APPLIED::equals).hasSize(1);
        assertThat(results).filteredOn(r -> r != WebhookOutcome.APPLIED).containsOnly(WebhookOutcome.DUPLICATE_IGNORED);
        assertThat(paymentTransactionRepository.count()).isEqualTo(1);
        assertThat(auditFor(intent)).filteredOn(event -> "PENDING".equals(event.getPreviousState())).hasSize(1);
    }

    @Test
    void shouldSerializeConflictingEventsForOnePayment() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-1017", "42000");

        AtomicInteger turn = new AtomicInteger();
        List<Object> results = race(2, () -> {
            boolean succeed = turn.getAndIncrement() == 0;
            return webhook(intent.getId(), succeed ? "evt-017-success" : "evt-017-failed", "provider-tx-017",
                    succeed ? ProviderStatus.SUCCESS : ProviderStatus.FAILED, "42000").outcome();
        });

        // Whichever event is processed first is applied; the second contradicts it. SUCCESS-then-FAILED holds
        // the captured payment; FAILED-then-SUCCESS leaves the final FAILED alone. Either way: one transition, one case.
        assertThat(results).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(results).containsExactlyInAnyOrder(WebhookOutcome.APPLIED, WebhookOutcome.SENT_TO_REVIEW);
        assertThat(stateOf(intent)).isIn(PaymentState.MANUAL_REVIEW, PaymentState.FAILED);
        assertThat(auditFor(intent)).filteredOn(event -> "PENDING".equals(event.getPreviousState())).hasSize(1);
        assertThat(openReviewReasons()).containsExactly(ReviewReason.INVALID_STATE_TRANSITION);
    }
}
