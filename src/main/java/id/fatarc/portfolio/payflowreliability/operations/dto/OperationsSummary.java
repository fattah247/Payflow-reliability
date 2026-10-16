package id.fatarc.portfolio.payflowreliability.operations.dto;

import id.fatarc.portfolio.payflowreliability.ledger.dto.InvariantReport;
import id.fatarc.portfolio.payflowreliability.operations.MoneyAtRiskService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What needs attention right now, computed from live data on every call.
 *
 * @param paymentsByState    every payment state, including zero counts
 * @param stuckPayments      payments waiting on the provider longer than the sweeper threshold
 * @param awaitingSettlement net amounts the next payouts would pay, per merchant and currency
 * @param ledger             the most recent books-versus-operations consistency check
 * @param moneyAtRisk        what is at risk right now, as amounts and counts, by kind
 */
public record OperationsSummary(
        Instant generatedAt,
        Map<PaymentState, Long> paymentsByState,
        long stuckPayments,
        ReviewQueue reviewQueue,
        Refunds refunds,
        ProviderCalls providerCalls,
        List<SettlementService.Backlog> awaitingSettlement,
        InvariantReport ledger,
        MoneyAtRiskService.MoneyAtRisk moneyAtRisk
) {
    /**
     * @param oldestOpenedAt creation time of the oldest unresolved case, or null when the queue is empty
     */
    public record ReviewQueue(long unresolved, long awaitingApproval, Map<ReviewReason, Long> unresolvedByReason,
                              long paymentsOnHold, Instant oldestOpenedAt) {
    }

    public record Refunds(long awaitingReview, long inProgress, long failed) {
    }

    /**
     * @param needsReview calls whose outcome is unknown after all retries
     */
    public record ProviderCalls(long pending, long needsReview, long failed, Instant oldestPendingSince) {
    }
}
