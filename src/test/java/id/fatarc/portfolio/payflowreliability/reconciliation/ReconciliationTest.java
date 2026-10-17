package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.RowOutcome;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two-sided: every reported row gets a verdict, and payouts the provider did not report are flagged. A clean row
 * is the provider's half of the evidence; RECONCILED also needs the bank to show the payout landed.
 */
class ReconciliationTest extends IntegrationTest {

    private ReconciliationReportResponse importRows(ReconciliationReportRequest.Row... rows) {
        return reconciliationService.importReport(new ReconciliationReportRequest(List.of(rows)), OPS_ANA);
    }

    private static ReconciliationReportRequest.Row row(String providerTransactionId, String amount, LocalDate settlementDate) {
        return new ReconciliationReportRequest.Row(providerTransactionId, new BigDecimal(amount), "IDR",
                ProviderStatus.SUCCESS, settlementDate);
    }

    private ReconciliationReportRequest.Row rowFor(PaymentIntent intent, String amount) {
        PaymentTransaction transaction = transactionOf(intent);
        return row(transaction.getProviderTransactionId(), amount, transaction.getSettledOn());
    }

    @Test
    void shouldReconcileOnlyOnceTheReportMatchesAndThePayoutHasLanded() {
        PaymentIntent intent = settledIntent("ORDER-3001", "64000");

        ReconciliationReportResponse result = importRows(rowFor(intent, "64000"));

        assertThat(result.mismatchCount()).isZero();
        assertThat(result.reconciledCount()).isZero();
        assertThat(result.awaitingBankCount()).isEqualTo(1);
        assertThat(result.rows()).singleElement().extracting(ReconciliationReportResponse.RowResult::outcome)
                .isEqualTo(RowOutcome.MATCHED_AWAITING_BANK);
        assertThat(stateOf(intent)).as("the provider's word is half the evidence").isEqualTo(PaymentState.SETTLED);

        landPayouts();

        assertThat(stateOf(intent)).as("the bank credit completes it").isEqualTo(PaymentState.RECONCILED);
        assertThat(auditFor(intent)).last().satisfies(event -> {
            assertThat(event.getActor()).isEqualTo("operator:ops-ana");
            assertThat(event.getReason()).contains("Three-way match");
        });
    }

    @Test
    void shouldReconcileAtOnceWhenThePayoutLandedBeforeTheReportArrived() {
        PaymentIntent intent = settledIntent("ORDER-3014", "64000");
        landPayouts();
        assertThat(stateOf(intent)).as("the bank alone is not enough either").isEqualTo(PaymentState.SETTLED);

        ReconciliationReportResponse result = importRows(rowFor(intent, "64000"));

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
                row(transaction.getProviderTransactionId(), "87000", transaction.getSettledOn().minusDays(1)));

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
    void shouldFlagPayoutsTheProviderDidNotReport() {
        PaymentIntent reported = paidIntent("ORDER-3003", "1000");
        PaymentIntent unreported = paidIntent("ORDER-3004", "2000");
        settle(MERCHANT_A);
        landPayouts();
        LocalDate payoutDate = transactionOf(reported).getSettledOn();

        ReconciliationReportResponse result = reconciliationService.importReport(
                new ReconciliationReportRequest(payoutDate, List.of(rowFor(reported, "1000"))), OPS_ANA);

        assertThat(result.reconciledCount()).isEqualTo(1);
        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.rows()).filteredOn(row -> row.outcome() == RowOutcome.MISSING_FROM_REPORT).singleElement()
                .satisfies(row -> {
                    assertThat(row.rowNumber()).isNull();
                    assertThat(row.paymentIntentId()).isEqualTo(unreported.getId());
                });
        assertThat(openReviewReasons()).containsExactly(ReviewReason.MISSING_FROM_PROVIDER_REPORT);
        assertThat(stateOf(unreported)).isEqualTo(PaymentState.SETTLED);
    }

    @Test
    void shouldOnlyExpectTheReportedMerchantsPayoutsWhenScopedToOneMerchant() {
        PaymentIntent ours = paidIntent("ORDER-3012", "1000");
        paidIntent(MERCHANT_B, "ORDER-3013", "2000", "IDR");
        settle(MERCHANT_A);
        settle(MERCHANT_B);
        LocalDate payoutDate = transactionOf(ours).getSettledOn();

        ReconciliationReportResponse scoped = reconciliationService.importReport(
                new ReconciliationReportRequest(payoutDate, MERCHANT_A, List.of(rowFor(ours, "1000"))), OPS_ANA);
        ReconciliationReportResponse platformWide = reconciliationService.importReport(
                new ReconciliationReportRequest(payoutDate, List.of(rowFor(ours, "1000"))), OPS_ANA);

        assertThat(scoped.missingCount()).isZero();
        assertThat(platformWide.missingCount()).isEqualTo(1);
    }

    @Test
    void shouldAcceptAnEmptyReportForADayWithNoProviderActivity() {
        PaymentIntent paid = settledIntent("ORDER-3005", "1000");

        ReconciliationReportResponse result = reconciliationService.importReport(
                new ReconciliationReportRequest(transactionOf(paid).getSettledOn(), List.of()), OPS_ANA);

        assertThat(result.checkedRows()).isZero();
        assertThat(result.missingCount()).isEqualTo(1);
    }

    @Test
    void shouldBeSafeToImportTheSameReportTwice() {
        PaymentIntent matched = settledIntent("ORDER-3006", "1000");
        landPayouts();
        ReconciliationReportRequest.Row good = rowFor(matched, "1000");
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
        PaymentIntent captured = capturedIntent("ORDER-3007", "2500");

        ReconciliationReportResponse result = importRows(row(transactionOf(captured).getProviderTransactionId(), "2500", null));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo(RowOutcome.MATCHED_NO_ACTION);
            assertThat(row.paymentState()).isEqualTo(PaymentState.SUCCESS);
        });
        assertThat(stateOf(captured)).isEqualTo(PaymentState.SUCCESS);
    }

    @Test
    void shouldCompareRefundedAmountsWhenTheProviderReportsThem() {
        PaymentIntent intent = settledIntent("ORDER-3008", "4000");
        landPayouts();
        requestRefund(reload(intent), "1500", "refund-3008");
        drainOutbox();
        PaymentTransaction transaction = transactionOf(intent);

        ReconciliationReportResponse wrong = importRows(new ReconciliationReportRequest.Row(transaction.getProviderTransactionId(),
                new BigDecimal("4000"), "IDR", ProviderStatus.SUCCESS, transaction.getSettledOn(), new BigDecimal("1000")));
        assertThat(wrong.rows()).singleElement().satisfies(row ->
                assertThat(row.mismatches()).containsExactly(ReviewReason.REFUND_AMOUNT_MISMATCH));

        ReconciliationReportResponse right = importRows(new ReconciliationReportRequest.Row(transaction.getProviderTransactionId(),
                new BigDecimal("4000"), "IDR", ProviderStatus.SUCCESS, transaction.getSettledOn(), new BigDecimal("1500")));
        assertThat(right.rows()).singleElement().extracting(ReconciliationReportResponse.RowResult::outcome)
                .isEqualTo(RowOutcome.RECONCILED);
    }

    @Test
    void shouldFlagAProviderSuccessForAFullyRefundedPayment() {
        PaymentIntent intent = settledIntent("ORDER-3009", "4000");
        requestRefund(reload(intent), "4000", "refund-3009");
        drainOutbox();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUNDED);

        ReconciliationReportResponse result = importRows(rowFor(intent, "4000"));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo(RowOutcome.MISMATCH);
            assertThat(row.mismatches()).containsExactly(ReviewReason.STATUS_MISMATCH);
        });
    }

    @Test
    void shouldFlagADuplicateRowInTheSameReport() {
        PaymentIntent intent = settledIntent("ORDER-3010", "3000");
        landPayouts();
        ReconciliationReportRequest.Row row = rowFor(intent, "3000");

        ReconciliationReportResponse result = importRows(row, row);

        assertThat(result.rows()).extracting(ReconciliationReportResponse.RowResult::outcome)
                .containsExactly(RowOutcome.RECONCILED, RowOutcome.DUPLICATE_ROW);
        assertThat(openReviewReasons()).containsExactly(ReviewReason.DUPLICATE_PROVIDER_REFERENCE);
    }

    @Test
    void shouldReuseOpenReviewCaseForRepeatedOperationalMismatch() {
        PaymentIntent intent = settledIntent("ORDER-3011", "48000");
        ReconciliationReportRequest.Row wrong = row(transactionOf(intent).getProviderTransactionId(), "47000", null);

        importRows(wrong);
        importRows(wrong);

        assertThat(openCases()).filteredOn(reviewCase -> reviewCase.getReason() == ReviewReason.AMOUNT_MISMATCH).hasSize(1);
    }
}
