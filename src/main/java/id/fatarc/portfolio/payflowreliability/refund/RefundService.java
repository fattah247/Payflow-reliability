package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerService;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommand;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxService;
import id.fatarc.portfolio.payflowreliability.payment.IdempotencyKeys;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Refund rules:
 * <ul>
 *   <li>Partial refunds are allowed up to the refundable amount: captured minus refunded minus refunds
 *       already in progress. Precision follows the payment's currency.</li>
 *   <li>Every refund request carries an Idempotency-Key stored with the refund, so a client retry
 *       returns the same refund instead of creating a second one.</li>
 *   <li>A refund on a reconciled payment waits for an operator decision (two-person approval).</li>
 *   <li>Money only moves when the provider confirms: the refund is executed through the outbox, and
 *       the ledger journal is posted with the confirmation.</li>
 *   <li>A refund can be cancelled until it is sent to the provider.</li>
 * </ul>
 * Every mutation locks the payment first, so refunds of one payment are serialized.
 */
@Service
public class RefundService {
    private final PaymentIntentService paymentIntentService;
    private final RefundRequestRepository refundRequestRepository;
    private final OutboxService outboxService;
    private final LedgerService ledgerService;
    private final ReviewService reviewService;
    private final AuditService auditService;
    private final Clock clock;

    public RefundService(PaymentIntentService paymentIntentService,
                         RefundRequestRepository refundRequestRepository,
                         OutboxService outboxService,
                         LedgerService ledgerService,
                         ReviewService reviewService,
                         AuditService auditService,
                         Clock clock) {
        this.paymentIntentService = paymentIntentService;
        this.refundRequestRepository = refundRequestRepository;
        this.outboxService = outboxService;
        this.ledgerService = ledgerService;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public RefundSubmission requestRefund(UUID paymentIntentId, CreateRefundRequest request, String idempotencyKey,
                                          Caller caller) {
        IdempotencyKeys.requireValid(idempotencyKey);
        PaymentIntent intent = paymentIntentService.lockAccessible(paymentIntentId, caller);

        var existing = refundRequestRepository.findByPaymentIntentIdAndIdempotencyKey(paymentIntentId, idempotencyKey);
        if (existing.isPresent()) {
            RefundRequest refund = existing.get();
            if (!refund.matchesFingerprint(RefundRequest.fingerprintOf(paymentIntentId, request.amount(), request.reason()))) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key " + idempotencyKey + " was already used for refund " + refund.getId()
                                + " with a different amount or reason.");
            }
            auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), paymentIntentId,
                    "REFUND_REQUEST_REPLAYED", caller.name(), "Existing refund returned for a repeated request.",
                    "status=" + refund.getStatus());
            return new RefundSubmission(refund, true);
        }

        Money.amountProblem(request.amount(), intent.getCurrency()).ifPresent(problem -> {
            throw new BusinessException(ErrorCode.REFUND_AMOUNT_NOT_ALLOWED, "amount: " + problem);
        });
        if (!intent.canRequestRefund()) {
            throw new InvalidStateTransitionException(
                    "Only captured payments that are not on hold, reversed or fully refunded can be refunded. Current state: "
                            + intent.getState());
        }
        BigDecimal outstanding = refundRequestRepository.outstandingAmount(paymentIntentId);
        BigDecimal refundable = intent.getAmount().subtract(intent.getRefundedAmount()).subtract(outstanding);
        if (request.amount().compareTo(refundable) > 0) {
            throw new BusinessException(ErrorCode.REFUND_AMOUNT_NOT_ALLOWED,
                    "Refund of " + plain(request.amount()) + " " + intent.getCurrency() + " exceeds the refundable "
                            + plain(refundable) + " (paid " + plain(intent.getAmount()) + ", refunded "
                            + plain(intent.getRefundedAmount()) + ", in progress " + plain(outstanding) + ").");
        }

        if (intent.getState() == PaymentState.RECONCILED) {
            RefundRequest refund = refundRequestRepository.save(new RefundRequest(paymentIntentId, request.amount(),
                    intent.getCurrency(), request.reason(), RefundStatus.PENDING_REVIEW, idempotencyKey, caller.name()));
            reviewService.openCase(ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION, AggregateType.REFUND_REQUEST,
                    refund.getId().toString(), paymentIntentId,
                    "Refund of " + plain(refund.getAmount()) + " " + refund.getCurrency()
                            + " on a reconciled payment needs operator approval. Reason given: " + request.reason(),
                    caller.name());
            auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), paymentIntentId,
                    "REFUND_REVIEW_REQUIRED", caller.name(), "Refund requires manual review after reconciliation.",
                    "refundId=" + refund.getId() + ",amount=" + plain(refund.getAmount()));
            return new RefundSubmission(refund, false);
        }

        RefundRequest refund = refundRequestRepository.save(new RefundRequest(paymentIntentId, request.amount(),
                intent.getCurrency(), request.reason(), RefundStatus.REQUESTED, idempotencyKey, caller.name()));
        outboxService.enqueueRefund(paymentIntentId, refund.getId());
        auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), paymentIntentId,
                "REFUND_REQUESTED", caller.name(), request.reason(),
                "refundId=" + refund.getId() + ",amount=" + plain(refund.getAmount()));
        return new RefundSubmission(refund, false);
    }

    /** Withdraws a refund that has not been sent to the provider. Cancelling twice is a no-op. */
    @Transactional
    public RefundRequest cancel(UUID refundId, Caller caller) {
        PaymentIntent intent = lockOwningPayment(refundId, caller);
        RefundRequest refund = getRequired(refundId);
        switch (refund.getStatus()) {
            case CANCELLED -> {
                return refund;
            }
            case PENDING_REVIEW -> {
                refund.cancel();
                reviewService.closeForSubject(AggregateType.REFUND_REQUEST, refundId.toString(), caller.name(),
                        "Refund cancelled by " + caller.name() + " before a decision was made.");
            }
            case REQUESTED -> {
                OutboxCommand command = outboxService.findForRefund(refundId)
                        .filter(candidate -> candidate.isUnsent(clock.instant()))
                        .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_CANCELLABLE,
                                "Refund " + refundId + " was already handed to the provider; it may have been applied."));
                command.cancel(clock.instant());
                refund.cancel();
            }
            default -> throw new BusinessException(ErrorCode.REFUND_NOT_CANCELLABLE,
                    "Refund " + refundId + " is " + refund.getStatus() + " and can no longer be cancelled.");
        }
        auditService.record(AggregateType.REFUND_REQUEST, refundId.toString(), intent.getId(), "REFUND_CANCELLED",
                caller.name(), "Refund cancelled before reaching the provider.", "refundId=" + refundId);
        return refund;
    }

    /**
     * Operator-approved refund on a reconciled payment. Runs inside the review resolution transaction, and
     * re-checks the payment as it is now: it may have been put on hold, reversed or refunded since the request.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundRequest approveAfterReview(UUID refundId, String actor, String note) {
        PaymentIntent intent = lockOwningPayment(refundId, null);
        RefundRequest refund = getRequired(refundId);
        if (!intent.canRequestRefund()) {
            throw new InvalidStateTransitionException("Payment " + intent.getId() + " is " + intent.getState()
                    + " and can no longer be refunded; reject the refund instead.");
        }
        BigDecimal otherOutstanding = refundRequestRepository.outstandingAmount(intent.getId()).subtract(refund.getAmount());
        BigDecimal refundable = intent.getAmount().subtract(intent.getRefundedAmount()).subtract(otherOutstanding);
        if (refund.getAmount().compareTo(refundable) > 0) {
            throw new BusinessException(ErrorCode.REFUND_AMOUNT_NOT_ALLOWED, "Refund of " + plain(refund.getAmount())
                    + " exceeds the " + plain(refundable) + " still refundable; reject it instead.");
        }
        refund.approve();
        outboxService.enqueueRefund(intent.getId(), refund.getId());
        auditService.record(AggregateType.REFUND_REQUEST, refundId.toString(), intent.getId(), "REFUND_APPROVED", actor,
                "Refund approved in manual review: " + note, "refundId=" + refundId);
        return refund;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RefundRequest rejectAfterReview(UUID refundId, String actor, String note) {
        PaymentIntent intent = lockOwningPayment(refundId, null);
        RefundRequest refund = getRequired(refundId);
        refund.reject();
        auditService.record(AggregateType.REFUND_REQUEST, refundId.toString(), intent.getId(), "REFUND_REJECTED", actor,
                "Refund rejected in manual review: " + note, "refundId=" + refundId);
        return refund;
    }

    /** The provider confirmed the refund. Caller holds the payment lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProviderSucceeded(RefundRequest refund, PaymentIntent intent, String providerRefundId, String actor) {
        refund.complete(providerRefundId, clock.instant());
        PaymentState previousState = intent.recordRefund(refund.getAmount());
        ledgerService.refund(refund.getId(), intent.getId(), intent.getMerchantId(), intent.getCurrency(), refund.getAmount());
        if (previousState != null) {
            auditService.recordStateChange(intent.getId(), previousState, intent.getState(), actor,
                    "Payment fully refunded.", "refundId=" + refund.getId());
        }
        auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), intent.getId(), "REFUND_COMPLETED",
                actor, "Provider confirmed the refund.",
                "refundId=" + refund.getId() + ",providerRefundId=" + providerRefundId + ",amount=" + plain(refund.getAmount()));
    }

    /** The payment was reversed before this refund was sent: the reversal returns what is left. Caller holds the payment lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentReversedBeforeSending(RefundRequest refund, PaymentIntent intent, String actor) {
        refund.fail("The payment was reversed before this refund was sent; the reversal returns the unrefunded amount.");
        auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), intent.getId(), "REFUND_FAILED",
                actor, "Refund not sent: the payment was reversed.", "refundId=" + refund.getId());
    }

    /** The provider refused the refund. Caller holds the payment lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProviderRejected(RefundRequest refund, PaymentIntent intent, String reason, String actor) {
        refund.fail(reason);
        reviewService.openCase(ReviewReason.PROVIDER_REJECTED_OPERATION, AggregateType.REFUND_REQUEST,
                refund.getId().toString(), intent.getId(), "Provider refused refund " + refund.getId() + ": " + reason, actor);
        auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), intent.getId(), "REFUND_FAILED",
                actor, "Provider refused the refund: " + reason, "refundId=" + refund.getId());
    }

    @Transactional(readOnly = true)
    public RefundRequest getRequired(UUID refundId) {
        return refundRequestRepository.findById(refundId)
                .orElseThrow(() -> notFound(refundId));
    }

    @Transactional(readOnly = true)
    public RefundRequest getAccessible(UUID refundId, Caller caller) {
        RefundRequest refund = getRequired(refundId);
        paymentIntentService.getAccessible(refund.getPaymentIntentId(), caller);
        return refund;
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> refundsForPayment(UUID paymentIntentId, Caller caller) {
        paymentIntentService.getAccessible(paymentIntentId, caller);
        return refundRequestRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
    }

    /**
     * Locks the refund's payment before the refund is loaded, so the refund is read after any
     * concurrent change to it has committed. A caller without access gets the same 404 as a missing refund.
     */
    private PaymentIntent lockOwningPayment(UUID refundId, Caller caller) {
        UUID paymentIntentId = refundRequestRepository.findPaymentIntentIdById(refundId).orElseThrow(() -> notFound(refundId));
        PaymentIntent intent = paymentIntentService.lockRequired(paymentIntentId);
        if (caller != null && !caller.canAccessMerchant(intent.getMerchantId())) {
            throw notFound(refundId);
        }
        return intent;
    }

    private static BusinessException notFound(UUID refundId) {
        return new BusinessException(ErrorCode.REFUND_NOT_FOUND, "Refund request not found: " + refundId);
    }

    private static String plain(BigDecimal amount) {
        return amount.toPlainString();
    }
}
