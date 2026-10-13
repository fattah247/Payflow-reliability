package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
import id.fatarc.portfolio.payflowreliability.outbox.CommandStatus;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Refunds are executed by the provider through the outbox; money is only recorded as returned when it confirms. */
class RefundTest extends IntegrationTest {

    @Test
    void shouldRefundACapturedPaymentEndToEnd() {
        PaymentIntent intent = paidIntent("ORDER-R-1", "125000");

        RefundRequest refund = requestRefund(intent, "125000", "refund-1");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(stateOf(intent)).as("nothing changes until the provider confirms").isEqualTo(PaymentState.SUCCESS);

        drainOutbox();

        RefundRequest completed = reload(refund);
        assertThat(completed.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(completed.getProviderRefundId()).isNotBlank();
        assertThat(reload(intent).getState()).isEqualTo(PaymentState.REFUNDED);
        assertThat(reload(intent).getRefundedAmount()).isEqualByComparingTo("125000");
        assertThat(providerSimulator.payment(intent.getId()).orElseThrow().getRefundedAmount()).isEqualByComparingTo("125000");
        assertThat(ledgerJournalRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()))
                .extracting("journalType").containsExactly(JournalType.CAPTURE, JournalType.REFUND);
    }

    @Test
    void shouldAllowPartialRefundsUpToTheCapturedAmount() {
        PaymentIntent intent = paidIntent("ORDER-R-2", "100000");

        requestRefund(intent, "30000", "refund-2a");
        requestRefund(intent, "45000.50", "refund-2b");
        assertThatThrownBy(() -> requestRefund(intent, "24999.51", "refund-2c"))
                .as("refunds in progress count against the refundable amount")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("refundable 24999.50")
                .extracting("code").isEqualTo(ErrorCode.REFUND_AMOUNT_NOT_ALLOWED);

        drainOutbox();
        assertThat(reload(intent).getState()).isEqualTo(PaymentState.SUCCESS);
        assertThat(reload(intent).getRefundedAmount()).isEqualByComparingTo("75000.50");

        requestRefund(intent, "24999.50", "refund-2d");
        drainOutbox();
        assertThat(reload(intent).getState()).isEqualTo(PaymentState.REFUNDED);
        assertThatThrownBy(() -> requestRefund(intent, "1", "refund-2e")).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void shouldRespectTheCurrencyPrecision() {
        PaymentIntent intent = paidIntent(MERCHANT_A, "ORDER-R-3", "1500", "JPY");

        assertThatThrownBy(() -> requestRefund(intent, "10.5", "refund-3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no minor unit");
    }

    @Test
    void shouldReturnTheExistingRefundWhenTheRequestIsRetried() {
        PaymentIntent intent = paidIntent("ORDER-R-4", "50000");

        RefundRequest first = requestRefund(intent, "10000", "refund-4");
        RefundSubmission retry = refundService.requestRefund(intent.getId(),
                new CreateRefundRequest(new BigDecimal("10000.00"), "Customer request"), "refund-4", AS_MERCHANT_A);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.refund().getId()).isEqualTo(first.getId());
        assertThat(refundRequestRepository.count()).isEqualTo(1);
        assertThatThrownBy(() -> requestRefund(intent, "20000", "refund-4"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void shouldCreateOneRefundWhenTheSameRequestArrivesConcurrently() throws Exception {
        PaymentIntent intent = paidIntent("ORDER-R-5", "50000");

        List<Object> results = race(6, () -> requestRefund(intent, "50000", "refund-5").getId());

        assertThat(results).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(results).containsOnly(results.getFirst());
        assertThat(refundRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldNeverRefundMoreThanCapturedUnderConcurrentRequests() throws Exception {
        PaymentIntent intent = paidIntent("ORDER-R-6", "50000");
        var counter = new java.util.concurrent.atomic.AtomicInteger();

        List<Object> results = race(5, () -> requestRefund(intent, "20000", "refund-6-" + counter.incrementAndGet()));

        assertThat(results).filteredOn(RefundRequest.class::isInstance).hasSize(2);
        drainOutbox();
        assertThat(reload(intent).getRefundedAmount()).isEqualByComparingTo("40000");
        assertThat(providerSimulator.payment(intent.getId()).orElseThrow().getRefundedAmount()).isEqualByComparingTo("40000");
    }

    @Test
    void shouldRejectARefundOfAPaymentThatWasNeverCaptured() {
        PaymentIntent intent = startedIntent("ORDER-R-7", "50000");

        assertThatThrownBy(() -> requestRefund(intent, "100", "refund-7")).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(refundRequestRepository.count()).isZero();
    }

    @Test
    void shouldSendARefundOnAReconciledPaymentToReviewInsteadOfTheProvider() {
        PaymentIntent intent = reconciledIntent("ORDER-R-8", "50000");

        RefundRequest refund = requestRefund(intent, "50000", "refund-8");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(openReviewReasons()).containsExactly(ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION);
        assertThat(drainOutbox()).isZero();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.RECONCILED);
    }

    @Test
    void shouldCancelARefundThatHasNotBeenSent() {
        PaymentIntent intent = paidIntent("ORDER-R-9", "50000");
        RefundRequest refund = requestRefund(intent, "20000", "refund-9");

        RefundRequest cancelled = refundService.cancel(refund.getId(), AS_MERCHANT_A);
        RefundRequest again = refundService.cancel(refund.getId(), AS_MERCHANT_A);

        assertThat(cancelled.getStatus()).isEqualTo(RefundStatus.CANCELLED);
        assertThat(again.getStatus()).isEqualTo(RefundStatus.CANCELLED);
        assertThat(outboxCommandRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()))
                .singleElement().extracting("status").isEqualTo(CommandStatus.CANCELLED);
        assertThat(drainOutbox()).isZero();
        assertThat(simOperationRepository.count()).isZero();
        requestRefund(intent, "50000", "refund-9b");
    }

    @Test
    void shouldRefuseToCancelARefundTheProviderAlreadyHas() {
        PaymentIntent intent = paidIntent("ORDER-R-10", "50000");
        RefundRequest refund = requestRefund(intent, "20000", "refund-10");
        drainOutbox();

        assertThatThrownBy(() -> refundService.cancel(refund.getId(), AS_MERCHANT_A))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.REFUND_NOT_CANCELLABLE);
        assertThatThrownBy(() -> refundService.cancel(refund.getId(), AS_MERCHANT_B))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.REFUND_NOT_FOUND);
    }

    @Test
    void shouldFailTheRefundAndOpenACaseWhenTheProviderRefusesIt() {
        PaymentIntent intent = paidIntent("ORDER-R-11", "50000");
        simFaults.inject(SimFaults.Operation.REFUND, SimFaults.Mode.REJECT, 1);
        RefundRequest refund = requestRefund(intent, "20000", "refund-11");

        drainOutbox();

        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(reload(refund).getFailureReason()).contains("Simulated refusal");
        assertThat(reload(intent).getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(openReviewReasons()).containsExactly(ReviewReason.PROVIDER_REJECTED_OPERATION);
        assertThat(ledgerJournalRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()))
                .extracting("journalType").containsExactly(JournalType.CAPTURE);
        requestRefund(intent, "50000", "refund-11b");
    }

    @Test
    void shouldHoldRefundsWhileTheProviderContradictsThePayment() {
        PaymentIntent intent = paidIntent("ORDER-R-12", "50000");
        RefundRequest refund = requestRefund(intent, "50000", "refund-12");
        webhook(intent.getId(), "evt-r-12-failed", transactionOf(intent).getProviderTransactionId(), ProviderStatus.FAILED, "50000");

        drainOutbox();

        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(simOperationRepository.count()).as("no money goes back while the provider and we disagree").isZero();
    }
}
