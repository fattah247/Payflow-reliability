package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTest extends IntegrationTest {
    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundRequestRepository refundRequestRepository;

    private static CreateRefundRequest full(String amount) {
        return new CreateRefundRequest(new BigDecimal(amount), "customer refund");
    }


    @Test
    void shouldRefundACapturedPaymentEndToEnd() {
        PaymentIntent intent = capturedIntent("ORDER-4001", "91000");

        RefundSubmission submission = refundService.requestRefund(intent.getId(), full("91000"));
        assertThat(submission.replayed()).isFalse();
        assertThat(submission.refund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUND_REQUESTED);

        RefundRequest completed = refundService.completeRefund(submission.refund().getId());
        assertThat(completed.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUNDED);
    }

    @Test
    void shouldRejectAPartialRefundInsteadOfMarkingTheWholePaymentRefunded() {
        PaymentIntent intent = capturedIntent("ORDER-4002", "100000");

        assertThatThrownBy(() -> refundService.requestRefund(intent.getId(), full("1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only full refunds are supported")
                .extracting("code").isEqualTo(ErrorCode.REFUND_AMOUNT_NOT_ALLOWED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
        assertThat(refundRequestRepository.count()).isZero();
    }

    @Test
    void shouldReturnTheExistingRefundWhenTheRequestIsRetried() {
        PaymentIntent intent = capturedIntent("ORDER-4003", "5000");

        RefundSubmission first = refundService.requestRefund(intent.getId(), full("5000"));
        RefundSubmission retry = refundService.requestRefund(intent.getId(), full("5000"));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.refund().getId()).isEqualTo(first.refund().getId());
        assertThat(refundRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldCreateOneRefundWhenTheSameRequestArrivesConcurrently() throws Exception {
        PaymentIntent intent = capturedIntent("ORDER-4004", "5000");

        List<Object> results = race(5, () -> refundService.requestRefund(intent.getId(), full("5000")).refund().getId());

        assertThat(results).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(results).containsOnly(results.getFirst());
        assertThat(refundRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldTreatARetriedCompletionAsANoOp() {
        PaymentIntent intent = capturedIntent("ORDER-4005", "5000");
        UUID refundId = refundService.requestRefund(intent.getId(), full("5000")).refund().getId();

        refundService.completeRefund(refundId);
        RefundRequest again = refundService.completeRefund(refundId);

        assertThat(again.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUNDED);
    }

    @Test
    void shouldNotCompleteARefundWhileTheProviderContradictsThePayment() {
        PaymentIntent intent = capturedIntent("ORDER-4008", "5000");
        UUID refundId = refundService.requestRefund(intent.getId(), full("5000")).refund().getId();
        webhook(intent.getId(), "evt-4008-chargeback", "tx-ORDER-4008", ProviderStatus.REVERSAL_REQUIRED, "5000");

        assertThatThrownBy(() -> refundService.completeRefund(refundId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("contradicts")
                .extracting("code").isEqualTo(ErrorCode.REFUND_NOT_COMPLETABLE);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUND_REQUESTED);

        reviewWorkflowService.resolve(openCases().getFirst().getId(), new ResolveReviewCaseRequest(
                "ops-ana", "Chargeback was withdrawn by the issuer", ReviewAction.ACKNOWLEDGE));
        assertThat(refundService.completeRefund(refundId).getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    void shouldRejectARefundOfAPaymentThatWasNeverCaptured() {
        PaymentIntent pending = startedIntent("ORDER-4006", "5000");

        assertThatThrownBy(() -> refundService.requestRefund(pending.getId(), full("5000")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void shouldAcceptARefundAfterReconciliationAsPendingReviewAndPersistTheCase() {
        PaymentIntent intent = reconciledIntent("ORDER-4007", "91000");

        RefundSubmission submission = refundService.requestRefund(intent.getId(), full("91000"));

        assertThat(submission.refund().getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.RECONCILED);
        assertThat(openCases()).singleElement().satisfies(reviewCase -> {
            assertThat(reviewCase.getReason()).isEqualTo(ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION);
            assertThat(reviewCase.getReferenceId()).isEqualTo(submission.refund().getId().toString());
            assertThat(reviewCase.getPaymentIntentId()).isEqualTo(intent.getId());
        });
        assertThat(auditFor(intent)).extracting("eventType").contains("REFUND_REVIEW_REQUIRED");

        assertThatThrownBy(() -> refundService.completeRefund(submission.refund().getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.REFUND_NOT_COMPLETABLE);
    }
}
