package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequestRepository;
import id.fatarc.portfolio.payflowreliability.refund.RefundService;
import id.fatarc.portfolio.payflowreliability.refund.RefundStatus;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a review case resolution into a decision with real effects. Rules:
 * <ul>
 *   <li>A held payment (MANUAL_REVIEW) must be released, failed, or marked for reversal. It can only be
 *       acknowledged without a decision if another open case still covers the hold, so no payment is
 *       left in MANUAL_REVIEW with nobody responsible for it.</li>
 *   <li>A refund waiting for review must be approved or rejected.</li>
 *   <li>Anything else is acknowledged.</li>
 * </ul>
 * Each case reports these as {@code allowedActions}, so clients never have to guess.
 */
@Service
public class ReviewWorkflowService {
    private final ReviewService reviewService;
    private final ReviewCaseRepository reviewCaseRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentIntentService paymentIntentService;
    private final RefundRequestRepository refundRequestRepository;
    private final RefundService refundService;
    private final AuditService auditService;

    public ReviewWorkflowService(ReviewService reviewService,
                                 ReviewCaseRepository reviewCaseRepository,
                                 PaymentIntentRepository paymentIntentRepository,
                                 PaymentIntentService paymentIntentService,
                                 RefundRequestRepository refundRequestRepository,
                                 RefundService refundService,
                                 AuditService auditService) {
        this.reviewService = reviewService;
        this.reviewCaseRepository = reviewCaseRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentIntentService = paymentIntentService;
        this.refundRequestRepository = refundRequestRepository;
        this.refundService = refundService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ReviewCaseView> list(ReviewStatus status, ReviewReason reason, UUID paymentIntentId) {
        return describe(reviewService.findCases(status, reason, paymentIntentId));
    }

    @Transactional(readOnly = true)
    public ReviewCaseView get(UUID reviewCaseId) {
        return describe(List.of(findRequired(reviewCaseId))).getFirst();
    }

    @Transactional
    public ReviewCaseView resolve(UUID reviewCaseId, ResolveReviewCaseRequest request) {
        // Lock order is payment, then case, the same order webhook processing uses (it holds the payment lock
        // while its inbox row references the case). Today the foreign-key check does not conflict with the
        // payment lock Hibernate takes on PostgreSQL (FOR NO KEY UPDATE), but one global order keeps that true
        // regardless of lock strength. A case's payment never changes, so reading it before the locks is safe.
        UUID paymentIntentId = reviewCaseRepository.findPaymentRefById(reviewCaseId)
                .orElseThrow(() -> notFound(reviewCaseId))
                .getPaymentIntentId();
        PaymentIntent intent = paymentIntentId == null ? null : paymentIntentService.lockRequired(paymentIntentId);
        ReviewCase reviewCase = reviewCaseRepository.findForUpdate(reviewCaseId)
                .orElseThrow(() -> notFound(reviewCaseId));
        if (!reviewCase.isOpen()) {
            throw new BusinessException(ErrorCode.REVIEW_CASE_ALREADY_RESOLVED,
                    "Review case " + reviewCaseId + " was already resolved by " + reviewCase.getResolvedBy()
                            + " with " + reviewCase.getResolutionAction() + ".");
        }

        RefundRequest refund = refundUnderReview(reviewCase);
        long openCasesForPayment = paymentIntentId == null
                ? 1
                : reviewCaseRepository.countByStatusAndPaymentIntentId(ReviewStatus.OPEN, paymentIntentId);

        ReviewAction action = request.action() == null ? ReviewAction.ACKNOWLEDGE : request.action();
        Set<ReviewAction> allowed = allowedActions(reviewCase, intent, refund, openCasesForPayment);
        if (!allowed.contains(action)) {
            throw new BusinessException(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE,
                    explainRejectedAction(action, allowed, intent, refund));
        }

        String actor = request.actor();
        String note = request.note();
        String metadata = "reviewCaseId=" + reviewCase.getId() + ",action=" + action;
        switch (action) {
            case ACKNOWLEDGE -> {
            }
            case RELEASE_PAYMENT -> recordDecision(intent, intent.releaseFromReview(), actor,
                    "Released from manual review: " + note, metadata);
            case FAIL_PAYMENT -> recordDecision(intent, intent.failFromReview(), actor,
                    "Failed in manual review: " + note, metadata);
            case REQUIRE_REVERSAL -> recordDecision(intent, intent.reverseFromReview(), actor,
                    "Reversal required by manual review: " + note, metadata);
            case APPROVE_REFUND -> refundService.approveAfterReview(refund.getId(), actor, note);
            case REJECT_REFUND -> refundService.rejectAfterReview(refund.getId(), actor, note);
        }

        reviewCase.resolve(actor, note, action);
        auditService.record(AggregateType.MANUAL_REVIEW, reviewCase.getId().toString(), paymentIntentId,
                "MANUAL_REVIEW_RESOLVED", actor, note,
                metadata + ",reason=" + reviewCase.getReason()
                        + ",referenceType=" + reviewCase.getReferenceType()
                        + ",referenceId=" + reviewCase.getReferenceId());
        return describe(List.of(reviewCase)).getFirst();
    }

    /** Adds the current payment state and allowed actions to each case, loading related rows in bulk. */
    @Transactional(readOnly = true)
    public List<ReviewCaseView> describe(List<ReviewCase> cases) {
        Set<UUID> paymentIds = cases.stream()
                .map(ReviewCase::getPaymentIntentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, PaymentIntent> intents = paymentIntentRepository.findAllById(paymentIds).stream()
                .collect(Collectors.toMap(PaymentIntent::getId, Function.identity()));
        Map<UUID, Long> openCounts = paymentIds.isEmpty()
                ? Map.of()
                : reviewCaseRepository.findByStatusAndPaymentIntentIdIn(ReviewStatus.OPEN, paymentIds).stream()
                        .collect(Collectors.groupingBy(ReviewCase::getPaymentIntentId, Collectors.counting()));
        Set<UUID> refundIds = cases.stream()
                .map(ReviewWorkflowService::refundIdOf)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        Map<UUID, RefundRequest> refunds = refundRequestRepository.findAllById(refundIds).stream()
                .collect(Collectors.toMap(RefundRequest::getId, Function.identity()));

        return cases.stream().map(reviewCase -> {
            PaymentIntent intent = reviewCase.getPaymentIntentId() == null ? null : intents.get(reviewCase.getPaymentIntentId());
            RefundRequest refund = refundIdOf(reviewCase).map(refunds::get).orElse(null);
            long openCount = reviewCase.getPaymentIntentId() == null ? 1 : openCounts.getOrDefault(reviewCase.getPaymentIntentId(), 0L);
            PaymentState paymentState = intent == null ? null : intent.getState();
            return ReviewCaseView.from(reviewCase, paymentState, allowedActions(reviewCase, intent, refund, openCount));
        }).toList();
    }

    static Set<ReviewAction> allowedActions(ReviewCase reviewCase, PaymentIntent intent, RefundRequest refund,
                                            long openCasesForPayment) {
        if (!reviewCase.isOpen()) {
            return EnumSet.noneOf(ReviewAction.class);
        }
        if (refund != null && refund.getStatus() == RefundStatus.PENDING_REVIEW) {
            return EnumSet.of(ReviewAction.APPROVE_REFUND, ReviewAction.REJECT_REFUND);
        }
        if (intent != null && intent.isOnHold()) {
            EnumSet<ReviewAction> actions = EnumSet.of(ReviewAction.RELEASE_PAYMENT);
            if (intent.canFailFromReview()) {
                actions.add(ReviewAction.FAIL_PAYMENT);
            }
            if (intent.canReverseFromReview()) {
                actions.add(ReviewAction.REQUIRE_REVERSAL);
            }
            if (openCasesForPayment > 1) {
                actions.add(ReviewAction.ACKNOWLEDGE);
            }
            return actions;
        }
        return EnumSet.of(ReviewAction.ACKNOWLEDGE);
    }

    private void recordDecision(PaymentIntent intent, PaymentState previousState, String actor, String reason, String metadata) {
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), actor, reason, metadata);
    }

    private RefundRequest refundUnderReview(ReviewCase reviewCase) {
        return refundIdOf(reviewCase).flatMap(refundRequestRepository::findById).orElse(null);
    }

    private static Optional<UUID> refundIdOf(ReviewCase reviewCase) {
        if (!AggregateType.REFUND_REQUEST.equals(reviewCase.getReferenceType())) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(reviewCase.getReferenceId()));
    }

    private static String explainRejectedAction(ReviewAction action, Set<ReviewAction> allowed,
                                                PaymentIntent intent, RefundRequest refund) {
        String reason;
        if (refund != null && refund.getStatus() == RefundStatus.PENDING_REVIEW) {
            reason = "Refund " + refund.getId() + " is waiting for a decision.";
        } else if (intent != null && intent.isOnHold()) {
            reason = "Payment " + intent.getId() + " is on hold (held from " + intent.getStateBeforeReview()
                    + ") and this is the last open case for it, so it needs a decision.";
        } else {
            reason = "Nothing is waiting on this case except acknowledgement.";
        }
        return action + " is not applicable. " + reason + " Allowed actions: " + allowed + ".";
    }

    private ReviewCase findRequired(UUID reviewCaseId) {
        return reviewCaseRepository.findById(reviewCaseId).orElseThrow(() -> notFound(reviewCaseId));
    }

    private static BusinessException notFound(UUID reviewCaseId) {
        return new BusinessException(ErrorCode.REVIEW_CASE_NOT_FOUND, "Review case not found: " + reviewCaseId);
    }
}
