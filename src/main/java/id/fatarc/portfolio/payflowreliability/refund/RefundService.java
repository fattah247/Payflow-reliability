package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Refund rules:
 * <ul>
 *   <li>Full refunds only: a partial amount is rejected instead of silently marking the whole payment refunded.</li>
 *   <li>One live refund per payment. Repeating a refund request returns the existing refund, so client retries are safe.</li>
 *   <li>A refund on a reconciled payment is accepted as PENDING_REVIEW with a review case; an operator approves or rejects it.</li>
 *   <li>A refund is not completed while the provider contradicts the payment (open INVALID_STATE_TRANSITION case).</li>
 * </ul>
 */
@Service
public class RefundService {
    private static final String MERCHANT_API = "merchant-api";
    private static final String OPERATIONS = "operations";
    private static final Set<RefundStatus> LIVE_STATUSES =
            EnumSet.of(RefundStatus.PENDING_REVIEW, RefundStatus.REQUESTED, RefundStatus.COMPLETED);
    private static final Set<PaymentState> REFUNDABLE_STATES =
            EnumSet.of(PaymentState.SUCCESS, PaymentState.SETTLED, PaymentState.RECONCILED);

    private final PaymentIntentService paymentIntentService;
    private final RefundRequestRepository refundRequestRepository;
    private final ReviewService reviewService;
    private final AuditService auditService;

    public RefundService(PaymentIntentService paymentIntentService,
                         RefundRequestRepository refundRequestRepository,
                         ReviewService reviewService,
                         AuditService auditService) {
        this.paymentIntentService = paymentIntentService;
        this.refundRequestRepository = refundRequestRepository;
        this.reviewService = reviewService;
        this.auditService = auditService;
    }

    @Transactional
    public RefundSubmission requestRefund(UUID paymentIntentId, CreateRefundRequest request) {
        PaymentIntent intent = paymentIntentService.lockRequired(paymentIntentId);

        var existing = refundRequestRepository
                .findFirstByPaymentIntentIdAndStatusInOrderByCreatedAtDesc(paymentIntentId, LIVE_STATUSES);
        if (existing.isPresent()) {
            RefundRequest refund = existing.get();
            if (refund.getAmount().compareTo(request.amount()) != 0) {
                throw new BusinessException(ErrorCode.REFUND_AMOUNT_NOT_ALLOWED,
                        "Payment already has refund " + refund.getId() + " for " + refund.getAmount().toPlainString()
                                + " " + refund.getCurrency() + " (" + refund.getStatus() + ").");
            }
            auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), paymentIntentId,
                    "REFUND_REQUEST_REPLAYED", MERCHANT_API, "Existing refund returned for a repeated request.",
                    "status=" + refund.getStatus());
            return new RefundSubmission(refund, true);
        }

        if (!REFUNDABLE_STATES.contains(intent.getState())) {
            throw new InvalidStateTransitionException(
                    "Only successful, settled, or reconciled payments can be refunded. Current state: " + intent.getState());
        }
        if (request.amount().compareTo(intent.getAmount()) != 0) {
            throw new BusinessException(ErrorCode.REFUND_AMOUNT_NOT_ALLOWED,
                    "Only full refunds are supported: amount must equal the payment amount "
                            + intent.getAmount().toPlainString() + " " + intent.getCurrency() + ".");
        }

        if (intent.getState() == PaymentState.RECONCILED) {
            RefundRequest refund = refundRequestRepository.save(new RefundRequest(
                    paymentIntentId, request.amount(), intent.getCurrency(), request.reason(), RefundStatus.PENDING_REVIEW));
            reviewService.openCase(ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION, AggregateType.REFUND_REQUEST,
                    refund.getId().toString(), paymentIntentId,
                    "Refund on a reconciled payment needs operator approval. Reason given: " + request.reason(),
                    MERCHANT_API);
            auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), paymentIntentId,
                    "REFUND_REVIEW_REQUIRED", MERCHANT_API,
                    "Refund requires manual review after reconciliation.", "refundId=" + refund.getId());
            return new RefundSubmission(refund, false);
        }

        PaymentState previousState = intent.requestRefund();
        RefundRequest refund = refundRequestRepository.save(new RefundRequest(
                paymentIntentId, request.amount(), intent.getCurrency(), request.reason(), RefundStatus.REQUESTED));
        auditService.recordStateChange(paymentIntentId, previousState, intent.getState(), MERCHANT_API,
                request.reason(), "refundId=" + refund.getId());
        return new RefundSubmission(refund, false);
    }

    /** Completing an already completed refund returns it unchanged, so a retried call is safe. */
    @Transactional
    public RefundRequest completeRefund(UUID refundId) {
        PaymentIntent intent = lockOwningPayment(refundId);
        RefundRequest refund = getRequired(refundId);

        switch (refund.getStatus()) {
            case COMPLETED -> {
                return refund;
            }
            case PENDING_REVIEW -> throw new BusinessException(ErrorCode.REFUND_NOT_COMPLETABLE,
                    "Refund " + refundId + " is waiting for approval in manual review.");
            case REJECTED -> throw new BusinessException(ErrorCode.REFUND_NOT_COMPLETABLE,
                    "Refund " + refundId + " was rejected in manual review.");
            case REQUESTED -> {
            }
        }
        // A contradicting provider event (for example a reversal) on a refund-requested payment is not a hold
        // state, but money must not go back to the customer twice: an operator has to look first.
        reviewService.findOpenCase(intent.getId(), ReviewReason.INVALID_STATE_TRANSITION).ifPresent(reviewCase -> {
            throw new BusinessException(ErrorCode.REFUND_NOT_COMPLETABLE,
                    "The provider reported a status that contradicts payment " + intent.getId() + " (review case "
                            + reviewCase.getId() + "). Resolve the case before completing this refund.");
        });

        refund.complete();
        PaymentState previousState = intent.refund();
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), OPERATIONS,
                "Refund completed for request " + refund.getId(), "refundId=" + refund.getId());
        return refund;
    }

    /** Operator approved a refund on a reconciled payment. Runs inside the review resolution transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundRequest approveAfterReview(UUID refundId, String actor, String note) {
        PaymentIntent intent = lockOwningPayment(refundId);
        RefundRequest refund = getRequired(refundId);
        refund.approve();
        PaymentState previousState = intent.requestReviewedRefund();
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), actor,
                "Refund approved in manual review: " + note, "refundId=" + refund.getId());
        return refund;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RefundRequest rejectAfterReview(UUID refundId, String actor, String note) {
        lockOwningPayment(refundId);
        RefundRequest refund = getRequired(refundId);
        refund.reject();
        auditService.record(AggregateType.REFUND_REQUEST, refund.getId().toString(), refund.getPaymentIntentId(),
                "REFUND_REJECTED", actor, "Refund rejected in manual review: " + note, "refundId=" + refund.getId());
        return refund;
    }

    @Transactional(readOnly = true)
    public RefundRequest getRequired(UUID refundId) {
        return refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND, "Refund request not found: " + refundId));
    }

    /**
     * Locks the refund's payment before the refund is loaded, so the refund is read after any
     * concurrent change to it has committed. Every refund mutation is serialized per payment.
     */
    private PaymentIntent lockOwningPayment(UUID refundId) {
        UUID paymentIntentId = refundRequestRepository.findPaymentIntentIdById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND, "Refund request not found: " + refundId));
        return paymentIntentService.lockRequired(paymentIntentId);
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> refundsForPayment(UUID paymentIntentId) {
        return refundRequestRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
    }
}
