package id.fatarc.portfolio.payflowreliability.reconciliation.dto;

import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;

import java.util.List;
import java.util.UUID;

/**
 * @param mismatchCount      number of individual discrepancies found (a row can have several)
 * @param reconciledCount    payments moved from SETTLED to RECONCILED by this import (their payout already landed)
 * @param awaitingBankCount  rows that matched, for payments whose payout has not landed in the bank yet
 * @param missingCount       payouts recorded for the settlement date but absent from the report
 * @param rows               one result per report row, in report order, then one per missing payout
 * @param payouts            one result per payout row, then one per payout we sent that the report omits
 */
public record ReconciliationReportResponse(
        int checkedRows,
        int mismatchCount,
        int reconciledCount,
        int awaitingBankCount,
        int missingCount,
        List<RowResult> rows,
        List<PayoutResult> payouts
) {
    public enum RowOutcome {
        /** Matched, and the payout that paid it landed in the bank: the payment moved SETTLED to RECONCILED. */
        RECONCILED,
        /**
         * Matched, but the payout has not landed in the bank yet. The payment stays SETTLED and is reconciled
         * the moment a bank credit lands its payout: no payment counts as reconciled without a bank line.
         */
        MATCHED_AWAITING_BANK,
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

    public enum PayoutOutcome {
        /** The provider's gross, fee and net agree with the ledger and with what it said when it accepted the payout. */
        CONSISTENT,
        /** Reported before with the same values. */
        ALREADY_REPORTED,
        /** Something disagrees; a break was opened. */
        MISMATCH,
        /** A payout reference we never sent. */
        UNKNOWN_REFERENCE,
        /** We sent this payout on the settlement date, but the report does not list it. */
        MISSING_FROM_REPORT
    }

    public record PayoutResult(String payoutReference, PayoutOutcome outcome, UUID settlementBatchId,
                               BreakCategory breakCategory, UUID breakId, String detail) {
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
