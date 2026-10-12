package id.fatarc.portfolio.payflowreliability.operations.dto;

import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What needs attention right now, computed from live data on every call.
 *
 * @param paymentsByState     every payment state, including zero counts
 * @param awaitingSettlement  captured payments not yet in a batch, per merchant and currency; each line is one
 *                            {@code POST /api/settlements/batches} call away from settling
 */
public record OperationsSummary(
        Instant generatedAt,
        Map<PaymentState, Long> paymentsByState,
        ReviewQueue reviewQueue,
        Refunds refunds,
        List<SettlementBacklog> awaitingSettlement
) {
    /**
     * @param oldestOpenedAt creation time of the oldest open case, or null when the queue is empty
     */
    public record ReviewQueue(long openCases, Map<ReviewReason, Long> openByReason, long paymentsOnHold,
                              Instant oldestOpenedAt) {
    }

    public record Refunds(long awaitingReview, long awaitingCompletion) {
    }

    public record SettlementBacklog(String merchantId, String currency, long transactionCount, BigDecimal totalAmount) {
    }
}
