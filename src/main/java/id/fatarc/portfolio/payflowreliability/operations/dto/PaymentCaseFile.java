package id.fatarc.portfolio.payflowreliability.operations.dto;

import id.fatarc.portfolio.payflowreliability.audit.AuditChainVerifier;
import id.fatarc.portfolio.payflowreliability.audit.AuditEvent;
import id.fatarc.portfolio.payflowreliability.ledger.dto.JournalView;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommand;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.ProviderEvent;
import id.fatarc.portfolio.payflowreliability.payment.dto.PaymentIntentResponse;
import id.fatarc.portfolio.payflowreliability.reconciliation.SettlementCertaintyService;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import id.fatarc.portfolio.payflowreliability.risk.RiskDecision;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchItem;

import java.util.List;
import java.util.UUID;

/**
 * Everything known about one payment, assembled for an operator investigating it.
 *
 * @param attention      whether a person needs to act, and what the payment is waiting on
 * @param certainty      each stage of the money (authorized, captured, paid out by the provider, landed in the
 *                       bank, reconciled), whether it is known, and the evidence that proves it
 * @param riskDecision   the device-risk decision taken at creation, with its rule, points and outcome (null for
 *                       payments created before device risk existed)
 * @param providerEvents every authenticated provider event (webhook or poll) and the outcome it received
 * @param providerCalls  refunds and reversals sent (or queued) to the provider, with attempts and errors
 * @param ledger         the money movements, as balanced journals
 * @param auditChain     whether the payment's tamper-evident audit chain verifies
 * @param timeline       all audit events about the payment, oldest first
 */
public record PaymentCaseFile(
        PaymentIntentResponse payment,
        Attention attention,
        List<SettlementCertaintyService.Stage> certainty,
        RiskDecision riskDecision,
        List<PaymentTransaction> providerTransactions,
        List<ProviderEvent> providerEvents,
        List<OutboxCommand> providerCalls,
        List<RefundRequest> refunds,
        List<SettlementBatchItem> settlementItems,
        List<JournalView> ledger,
        List<ReviewCaseView> reviewCases,
        AuditChainVerifier.Verification auditChain,
        List<AuditEvent> timeline
) {
    /**
     * @param needsAction       true when a person (operator or operations team) must act
     * @param waitingOn         plain-language description of the next expected step
     * @param openReviewCaseIds unresolved cases about this payment
     */
    public record Attention(boolean needsAction, String waitingOn, List<UUID> openReviewCaseIds) {
    }
}
