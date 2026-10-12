package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundService;
import id.fatarc.portfolio.payflowreliability.refund.RefundStatus;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewWorkflowTest extends IntegrationTest {
    @Autowired
    private RefundService refundService;

    private ReviewCaseView resolve(UUID caseId, ReviewAction action) {
        return reviewWorkflowService.resolve(caseId, new ResolveReviewCaseRequest("ops-ana", "checked with provider", action));
    }

    private PaymentIntent heldIntent(String reference) {
        PaymentIntent intent = startedIntent(reference, "60000");
        webhook(intent.getId(), "evt-" + reference, "tx-" + reference, ProviderStatus.SUCCESS, "59000");
        return reload(intent);
    }

    @Test
    void shouldOfferHoldDecisionsAndNotAPlainAcknowledgeForAHeldPayment() {
        PaymentIntent intent = heldIntent("ORDER-5001");

        ReviewCaseView reviewCase = reviewWorkflowService.list(ReviewStatus.OPEN, null, intent.getId()).getFirst();

        assertThat(reviewCase.paymentState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(reviewCase.allowedActions()).containsExactlyInAnyOrder(ReviewAction.RELEASE_PAYMENT, ReviewAction.FAIL_PAYMENT);
        assertThatThrownBy(() -> resolve(reviewCase.id(), ReviewAction.ACKNOWLEDGE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("on hold")
                .extracting("code").isEqualTo(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.MANUAL_REVIEW);
    }

    @Test
    void shouldReleaseAHeldPaymentToWhereItWasHeldFrom() {
        PaymentIntent intent = heldIntent("ORDER-5002");
        UUID caseId = openCases().getFirst().getId();

        ReviewCaseView resolved = resolve(caseId, ReviewAction.RELEASE_PAYMENT);

        assertThat(resolved.status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(resolved.resolutionAction()).isEqualTo(ReviewAction.RELEASE_PAYMENT);
        assertThat(resolved.allowedActions()).isEmpty();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
        assertThat(auditFor(intent)).extracting("eventType").contains("MANUAL_REVIEW_RESOLVED");
    }

    @Test
    void shouldFailAHeldPaymentThatWasNeverCaptured() {
        PaymentIntent intent = heldIntent("ORDER-5003");

        resolve(openCases().getFirst().getId(), ReviewAction.FAIL_PAYMENT);

        assertThat(stateOf(intent)).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void shouldRequireReversalForAPaymentHeldAfterCapture() {
        PaymentIntent intent = settledIntent("ORDER-5004", "60000");
        webhook(intent.getId(), "evt-5004-wrong", "tx-ORDER-5004", ProviderStatus.SUCCESS, "1");
        ReviewCaseView reviewCase = reviewWorkflowService.list(ReviewStatus.OPEN, null, intent.getId()).getFirst();

        assertThat(reviewCase.allowedActions())
                .containsExactlyInAnyOrder(ReviewAction.RELEASE_PAYMENT, ReviewAction.REQUIRE_REVERSAL);
        resolve(reviewCase.id(), ReviewAction.REQUIRE_REVERSAL);

        assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
    }

    @Test
    void shouldApproveARefundOnAReconciledPaymentAndLetItComplete() {
        PaymentIntent intent = reconciledIntent("ORDER-5005", "91000");
        RefundRequest refund = refundService.requestRefund(intent.getId(),
                new CreateRefundRequest(new BigDecimal("91000"), "damaged goods")).refund();
        ReviewCaseView reviewCase = reviewWorkflowService.list(ReviewStatus.OPEN, ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION, null).getFirst();
        assertThat(reviewCase.allowedActions()).containsExactlyInAnyOrder(ReviewAction.APPROVE_REFUND, ReviewAction.REJECT_REFUND);

        resolve(reviewCase.id(), ReviewAction.APPROVE_REFUND);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUND_REQUESTED);

        RefundRequest completed = refundService.completeRefund(refund.getId());
        assertThat(completed.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUNDED);
    }

    @Test
    void shouldRejectARefundOnAReconciledPaymentAndLeaveThePaymentAlone() {
        PaymentIntent intent = reconciledIntent("ORDER-5006", "91000");
        RefundRequest refund = refundService.requestRefund(intent.getId(),
                new CreateRefundRequest(new BigDecimal("91000"), "late claim")).refund();

        resolve(openCases().getFirst().getId(), ReviewAction.REJECT_REFUND);

        assertThat(refundService.getRequired(refund.getId()).getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.RECONCILED);
    }

    @Test
    void shouldAcknowledgeACaseThatHoldsNothing() {
        PaymentIntent intent = startedIntent("ORDER-5007", "1000");
        webhook(intent.getId(), "evt-5007-failed", "tx-5007", ProviderStatus.FAILED, "1000");
        webhook(intent.getId(), "evt-5007-success", "tx-5007", ProviderStatus.SUCCESS, "1000");

        ReviewCaseView resolved = resolve(openCases().getFirst().getId(), null);

        assertThat(resolved.resolutionAction()).isEqualTo(ReviewAction.ACKNOWLEDGE);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void shouldLetAnOperatorReverseAPaymentTheProviderChargedBackAfterSettlement() {
        PaymentIntent intent = settledIntent("ORDER-5010", "60000");

        webhook(intent.getId(), "evt-5010-chargeback", "tx-ORDER-5010", ProviderStatus.REVERSAL_REQUIRED, "60000");
        ReviewCaseView reviewCase = reviewWorkflowService.list(ReviewStatus.OPEN, null, intent.getId()).getFirst();

        assertThat(reviewCase.paymentState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(reviewCase.allowedActions()).contains(ReviewAction.REQUIRE_REVERSAL);
        resolve(reviewCase.id(), ReviewAction.REQUIRE_REVERSAL);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
    }

    @Test
    void shouldResolveCleanlyWhileTheProviderKeepsContradictingThePayment() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-5011", "1000");
        webhook(intent.getId(), "evt-5011-failed", "tx-5011", ProviderStatus.FAILED, "1000");

        for (int round = 0; round < 5; round++) {
            webhook(intent.getId(), "evt-5011-open-" + round, "tx-5011", ProviderStatus.SUCCESS, "1000");
            UUID caseId = openCases().getFirst().getId();
            int currentRound = round;
            AtomicInteger turn = new AtomicInteger();

            List<Object> results = race(2, () -> turn.getAndIncrement() == 0
                    ? resolve(caseId, ReviewAction.ACKNOWLEDGE)
                    : webhook(intent.getId(), "evt-5011-race-" + currentRound, "tx-5011", ProviderStatus.SUCCESS, "1000"));

            assertThat(results).as("round %d", round).doesNotHaveAnyElementsOfTypes(Throwable.class);
            jdbcTemplate.update("update review_cases set status = 'RESOLVED', resolved_at = now(), resolved_by = 'test',"
                    + " resolution_action = 'ACKNOWLEDGE' where status = 'OPEN'");
        }
        assertThat(stateOf(intent)).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void shouldRefuseToResolveACaseTwice() {
        heldIntent("ORDER-5008");
        UUID caseId = openCases().getFirst().getId();
        resolve(caseId, ReviewAction.RELEASE_PAYMENT);

        assertThatThrownBy(() -> resolve(caseId, ReviewAction.RELEASE_PAYMENT))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.REVIEW_CASE_ALREADY_RESOLVED);
    }

    @Test
    void shouldApplyOnlyOneDecisionWhenTwoOperatorsResolveConcurrently() throws Exception {
        PaymentIntent intent = heldIntent("ORDER-5009");
        UUID caseId = openCases().getFirst().getId();

        List<Object> results = race(2, () -> resolve(caseId, ReviewAction.RELEASE_PAYMENT));

        assertThat(results).filteredOn(ReviewCaseView.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(BusinessException.class::isInstance)
                .singleElement().extracting("code").isEqualTo(ErrorCode.REVIEW_CASE_ALREADY_RESOLVED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
        assertThat(auditFor(intent)).filteredOn(event -> "MANUAL_REVIEW_RESOLVED".equals(event.getEventType())).hasSize(1);
    }

}
