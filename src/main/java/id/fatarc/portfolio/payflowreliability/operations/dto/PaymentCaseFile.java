package id.fatarc.portfolio.payflowreliability.operations.dto;

import id.fatarc.portfolio.payflowreliability.audit.AuditEvent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.ProviderEvent;
import id.fatarc.portfolio.payflowreliability.payment.dto.PaymentIntentResponse;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything known about one payment, assembled for an operator investigating it.
 *
 * @param attention      whether a person needs to act, and what the payment is waiting on
 * @param providerEvents every authenticated webhook delivery for this payment and the outcome it received
 * @param timeline       all audit events about the payment, across aggregates, oldest first
 */
public record PaymentCaseFile(
        PaymentIntentResponse payment,
        Attention attention,
        List<PaymentTransaction> providerTransactions,
        List<ProviderEvent> providerEvents,
        List<SettlementEntry> settlements,
        List<RefundRequest> refunds,
        List<ReviewCaseView> reviewCases,
        List<AuditEvent> timeline
) {
    /**
     * @param needsAction      true when a person (operator or operations team) must act
     * @param waitingOn        plain-language description of the next expected step
     * @param openReviewCaseIds open cases about this payment
     */
    public record Attention(boolean needsAction, String waitingOn, List<UUID> openReviewCaseIds) {
    }

    public record SettlementEntry(UUID batchId, UUID transactionId, BigDecimal amount, LocalDate settledOn) {
    }
}
