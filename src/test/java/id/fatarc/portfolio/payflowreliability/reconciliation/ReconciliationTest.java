package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.RowOutcome;
import id.fatarc.portfolio.payflowreliability.refund.RefundService;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationTest extends IntegrationTest {
    @Autowired
    private RefundService refundService;

    private ReconciliationReportResponse importRows(ReconciliationReportRequest.Row... rows) {
        return reconciliationService.importReport(new ReconciliationReportRequest(List.of(rows)));
    }

    private static ReconciliationReportRequest.Row row(String providerTransactionId, String amount, LocalDate settlementDate) {
        return new ReconciliationReportRequest.Row(providerTransactionId, new BigDecimal(amount), "IDR",
                ProviderStatus.SUCCESS, settlementDate);
    }

    private PaymentTransaction transactionOf(PaymentIntent intent) {
        return paymentTransactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()).getFirst();
    }

    @Test
    void shouldReconcileMatchingSettledTransaction() {
        PaymentIntent intent = settledIntent("ORDER-3001", "64000");
        PaymentTransaction transaction = transactionOf(intent);

        ReconciliationReportResponse result = importRows(row(transaction.getProviderTransactionId(), "64000", transaction.getSettledOn()));

        assertThat(result.mismatchCount()).isZero();
        assertThat(result.reconciledCount()).isEqualTo(1);
        assertThat(result.rows()).singleElement().extracting(ReconciliationReportResponse.RowResult::outcome)
                .isEqualTo(RowOutcome.RECONCILED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.RECONCILED);
    }

    @Test
    void shouldCreateManualReviewForReconciliationMismatch() {
        PaymentIntent intent = settledIntent("ORDER-3002", "88000");
        PaymentTransaction transaction = transactionOf(intent);

        ReconciliationReportResponse result = importRows(
                row(transaction.getProviderTransactionId(), "87000", LocalDate.now().minusDays(1)));

        assertThat(result.mismatchCount()).isEqualTo(2);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo(RowOutcome.MISMATCH);
            assertThat(row.mismatches()).containsExactly(ReviewReason.AMOUNT_MISMATCH, ReviewReason.SETTLEMENT_DATE_MISMATCH);
            assertThat(row.reviewCaseIds()).hasSize(2);
            assertThat(row.paymentIntentId()).isEqualTo(intent.getId());
        });
        assertThat(openReviewReasons()).containsExactlyInAnyOrder(ReviewReason.AMOUNT_MISMATCH, ReviewReason.SETTLEMENT_DATE_MISMATCH);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SETTLED);
    }

    @Test
    void shouldBeSafeToImportTheSameReportTwice() {
        PaymentIntent matched = settledIntent("ORDER-3003", "1000");
        PaymentTransaction matchedTx = transactionOf(matched);
        ReconciliationReportRequest.Row good = row(matchedTx.getProviderTransactionId(), "1000", matchedTx.getSettledOn());
        ReconciliationReportRequest.Row unknown = row("provider-tx-unknown", "5000", null);

        importRows(good, unknown);
        ReconciliationReportResponse second = importRows(good, unknown);

        assertThat(second.rows()).extracting(ReconciliationReportResponse.RowResult::outcome)
                .containsExactly(RowOutcome.ALREADY_RECONCILED, RowOutcome.NOT_FOUND_LOCALLY);
        assertThat(second.reconciledCount()).isZero();
        assertThat(openReviewReasons()).containsExactly(ReviewReason.PROVIDER_TRANSACTION_NOT_FOUND);
    }

    @Test
    void shouldReportAMatchedRowWhosePaymentIsNotSettledYet() {
        PaymentIntent captured = capturedIntent("ORDER-3004", "2500");

        ReconciliationReportResponse result = importRows(row(transactionOf(captured).getProviderTransactionId(), "2500", null));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo(RowOutcome.MATCHED_NO_ACTION);
            assertThat(row.paymentState()).isEqualTo(PaymentState.SUCCESS);
        });
        assertThat(stateOf(captured)).isEqualTo(PaymentState.SUCCESS);
    }

    @Test
    void shouldOnlyFlagAProviderSuccessForACompletedRefund() {
        PaymentIntent requested = settledIntent("ORDER-3007", "4000");
        refundService.requestRefund(requested.getId(), new CreateRefundRequest(new BigDecimal("4000"), "return"));
        PaymentTransaction requestedTx = transactionOf(requested);

        ReconciliationReportResponse beforeCompletion = importRows(
                row(requestedTx.getProviderTransactionId(), "4000", requestedTx.getSettledOn()));
        assertThat(beforeCompletion.rows()).singleElement()
                .extracting(ReconciliationReportResponse.RowResult::outcome).isEqualTo(RowOutcome.MATCHED_NO_ACTION);

        refundService.completeRefund(refundService.refundsForPayment(requested.getId()).getFirst().getId());
        ReconciliationReportResponse afterCompletion = importRows(
                row(requestedTx.getProviderTransactionId(), "4000", requestedTx.getSettledOn()));
        assertThat(afterCompletion.rows()).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo(RowOutcome.MISMATCH);
            assertThat(row.mismatches()).containsExactly(ReviewReason.STATUS_MISMATCH);
        });
    }

    @Test
    void shouldFlagADuplicateRowInTheSameReport() {
        PaymentIntent intent = settledIntent("ORDER-3005", "3000");
        PaymentTransaction transaction = transactionOf(intent);
        ReconciliationReportRequest.Row row = row(transaction.getProviderTransactionId(), "3000", transaction.getSettledOn());

        ReconciliationReportResponse result = importRows(row, row);

        assertThat(result.rows()).extracting(ReconciliationReportResponse.RowResult::outcome)
                .containsExactly(RowOutcome.RECONCILED, RowOutcome.DUPLICATE_ROW);
        assertThat(openReviewReasons()).containsExactly(ReviewReason.DUPLICATE_PROVIDER_REFERENCE);
    }

    @Test
    void shouldReuseOpenReviewCaseForRepeatedOperationalMismatch() {
        PaymentIntent intent = settledIntent("ORDER-3006", "48000");
        ReconciliationReportRequest.Row wrong = row(transactionOf(intent).getProviderTransactionId(), "47000", null);

        importRows(wrong);
        importRows(wrong);

        assertThat(openCases()).filteredOn(reviewCase -> reviewCase.getReason() == ReviewReason.AMOUNT_MISMATCH).hasSize(1);
    }
}
