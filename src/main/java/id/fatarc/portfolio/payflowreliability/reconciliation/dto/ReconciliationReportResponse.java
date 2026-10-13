package id.fatarc.portfolio.payflowreliability.reconciliation.dto;

import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;

import java.util.List;
import java.util.UUID;

/**
 * @param mismatchCount   number of individual discrepancies found (a row can have several)
 * @param reconciledCount payments moved from SETTLED to RECONCILED by this import
 * @param missingCount    payouts recorded for the settlement date but absent from the report
 * @param rows            one result per report row, in report order, then one per missing payout
 */
public record ReconciliationReportResponse(
        int checkedRows,
        int mismatchCount,
        int reconciledCount,
        int missingCount,
        List<RowResult> rows
) {
    public enum RowOutcome {
        /** Matched; the payment moved SETTLED to RECONCILED. */
        RECONCILED,
        /** Matched; the payment was already reconciled (for example, the same report imported twice). */
        ALREADY_RECONCILED,
        /** Matched, but the payment is not in SETTLED, so nothing changed; see paymentState. */
        MATCHED_NO_ACTION,
        /** One or more discrepancies; review cases were opened. */
        MISMATCH,
        /** No local transaction has this provider reference. */
        NOT_FOUND_LOCALLY,
        /** The provider reference appeared earlier in the same report. */
        DUPLICATE_ROW,
        /** We paid this out on the settlement date, but the provider's report for that date does not list it. */
        MISSING_FROM_REPORT
    }

    /**
     * @param rowNumber position in the report; null for MISSING_FROM_REPORT results
     */
    public record RowResult(
            Integer rowNumber,
            String providerTransactionId,
            RowOutcome outcome,
            UUID paymentIntentId,
            PaymentState paymentState,
            List<ReviewReason> mismatches,
            List<UUID> reviewCaseIds,
            String detail
    ) {
    }
}
