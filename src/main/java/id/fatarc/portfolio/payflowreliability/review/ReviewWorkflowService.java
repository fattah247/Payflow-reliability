package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageSlice;
import id.fatarc.portfolio.payflowreliability.outbox.CommandStatus;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommand;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommandRepository;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxService;
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
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a review case into a decision with real effects. Rules:
 * <ul>
 *   <li>A held payment (MANUAL_REVIEW) must be released, failed, or reversed. It can only be
 *       acknowledged without a decision if another unresolved case still covers the hold.</li>
 *   <li>A refund waiting for review must be approved or rejected.</li>
 *   <li>A provider call with an unknown outcome is retried (safe thanks to its idempotency key).</li>
 *   <li>Decisions that move money (approve a refund, require a reversal) are maker-checker: the
 *       proposing operator cannot approve their own proposal, and the approval re-checks the
 *       payment's current state before applying.</li>
 * </ul>
 * Lock order is payment, then case, the same as webhook processing.
 */
@Service
public class ReviewWorkflowService {
    private final ReviewService reviewService;
    private final ReviewCaseRepository reviewCaseRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentIntentService paymentIntentService;
    private final RefundRequestRepository refundRequestRepository;
    private final RefundService refundService;
    private final OutboxCommandRepository outboxCommandRepository;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final Clock clock;

    public ReviewWorkflowService(ReviewService reviewService,
                                 ReviewCaseRepository reviewCaseRepository,
                                 PaymentIntentRepository paymentIntentRepository,
                                 PaymentIntentService paymentIntentService,
                                 RefundRequestRepository refundRequestRepository,
                                 RefundService refundService,
                                 OutboxCommandRepository outboxCommandRepository,
                                 OutboxService outboxService,
                                 AuditService auditService,
                                 Clock clock) {
        this.reviewService = reviewService;
        this.reviewCaseRepository = reviewCaseRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentIntentService = paymentIntentService;
        this.refundRequestRepository = refundRequestRepository;
        this.refundService = refundService;
        this.outboxCommandRepository = outboxCommandRepository;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ReviewCaseView> list(Collection<ReviewStatus> statuses, ReviewReason reason, UUID paymentIntentId) {
        return describe(reviewService.findCases(statuses, reason, paymentIntentId));
    }

    @Transactional(readOnly = true)
    public PageSlice<ReviewCaseView> page(Collection<ReviewStatus> statuses, ReviewReason reason, UUID paymentIntentId,
                                          PageCursor after, Integer limit) {
        return reviewService.pageCases(statuses, reason, paymentIntentId, after, limit).map(this::describe);
    }

    @Transactional(readOnly = true)
    public ReviewCaseView get(UUID reviewCaseId) {
        return describe(List.of(reviewCaseRepository.findById(reviewCaseId).orElseThrow(() -> notFound(reviewCaseId)))).getFirst();
    }

    /** Applies a decision, or records a proposal when the decision needs a second operator. */
    @Transactional
    public ReviewCaseView resolve(UUID reviewCaseId, ResolveReviewCaseRequest request, Caller caller) {
        Locked locked = lock(reviewCaseId);
        ReviewCase reviewCase = locked.reviewCase();
        if (reviewCase.getStatus() == ReviewStatus.RESOLVED) {
            throw alreadyResolved(reviewCase);
        }
        if (reviewCase.getStatus() == ReviewStatus.AWAITING_APPROVAL) {
            throw new BusinessException(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE,
                    reviewCase.getProposedAction() + " proposed by " + reviewCase.getProposedBy()
                            + " is awaiting approval. Approve it or reject the proposal first.");
        }
        ReviewAction action = request.action() == null ? ReviewAction.ACKNOWLEDGE : request.action();
        requireAllowed(action, reviewCase, locked);

        if (action.requiresApproval()) {
            reviewCase.propose(action, caller.name(), request.note(), clock.instant());
            auditService.record(AggregateType.MANUAL_REVIEW, reviewCase.getId().toString(), reviewCase.getPaymentIntentId(),
                    "MANUAL_REVIEW_PROPOSED", caller.name(), request.note(),
                    metadata(reviewCase, action) + ",awaiting=second-operator");
            return describe(List.of(reviewCase)).getFirst();
        }
        apply(action, reviewCase, locked, caller.name(), request.note());
        reviewCase.resolve(caller.name(), request.note(), action, null, clock.instant());
        auditService.record(AggregateType.MANUAL_REVIEW, reviewCase.getId().toString(), reviewCase.getPaymentIntentId(),
                "MANUAL_REVIEW_RESOLVED", caller.name(), request.note(), metadata(reviewCase, action));
        return describe(List.of(reviewCase)).getFirst();
    }

    /** Second operator confirms a proposed money-moving decision. */
    @Transactional
    public ReviewCaseView approve(UUID reviewCaseId, String note, Caller caller) {
        Locked locked = lock(reviewCaseId);
        ReviewCase reviewCase = locked.reviewCase();
        if (reviewCase.getStatus() == ReviewStatus.RESOLVED) {
            throw alreadyResolved(reviewCase);
        }
        if (reviewCase.getStatus() != ReviewStatus.AWAITING_APPROVAL) {
            throw new BusinessException(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE,
                    "Review case " + reviewCaseId + " has no proposal to approve.");
        }
        if (caller.name().equals(reviewCase.getProposedBy())) {
            throw new BusinessException(ErrorCode.FOUR_EYES_REQUIRED,
                    "A different operator must approve " + reviewCase.getProposedAction() + " proposed by "
                            + reviewCase.getProposedBy() + ".");
        }
        ReviewAction action = reviewCase.getProposedAction();
        requireAllowed(action, reviewCase, locked);
        apply(action, reviewCase, locked, caller.name(), note);
        String proposer = reviewCase.getProposedBy();
        reviewCase.resolve(caller.name(), note, action, caller.name(), clock.instant());
        auditService.record(AggregateType.MANUAL_REVIEW, reviewCase.getId().toString(), reviewCase.getPaymentIntentId(),
                "MANUAL_REVIEW_APPROVED", caller.name(), note, metadata(reviewCase, action) + ",proposedBy=" + proposer);
        return describe(List.of(reviewCase)).getFirst();
    }

    /** Withdraws (proposer) or turns down (another operator) a proposal; the case is open again. */
    @Transactional
    public ReviewCaseView rejectProposal(UUID reviewCaseId, String note, Caller caller) {
        ReviewCase reviewCase = lock(reviewCaseId).reviewCase();
        if (reviewCase.getStatus() != ReviewStatus.AWAITING_APPROVAL) {
            throw new BusinessException(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE,
                    "Review case " + reviewCaseId + " has no proposal to reject.");
        }
        String proposal = reviewCase.getProposedAction() + " by " + reviewCase.getProposedBy();
        reviewCase.withdrawProposal();
        auditService.record(AggregateType.MANUAL_REVIEW, reviewCase.getId().toString(), reviewCase.getPaymentIntentId(),
                "MANUAL_REVIEW_PROPOSAL_REJECTED", caller.name(), note, "proposal=" + proposal);
        return describe(List.of(reviewCase)).getFirst();
    }

    /** Adds the current payment state and allowed actions to each case, loading related rows in bulk. */
    @Transactional(readOnly = true)
    public List<ReviewCaseView> describe(List<ReviewCase> cases) {
        Set<UUID> paymentIds = cases.stream().map(ReviewCase::getPaymentIntentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, PaymentIntent> intents = paymentIntentRepository.findAllById(paymentIds).stream()
                .collect(Collectors.toMap(PaymentIntent::getId, Function.identity()));
        Map<UUID, Long> holdCaseCounts = paymentIds.isEmpty() ? Map.of()
                : reviewCaseRepository.findByStatusInAndPaymentIntentIdIn(ReviewStatus.UNRESOLVED, paymentIds).stream()
                .filter(ReviewWorkflowService::canDecideAHold)
                .collect(Collectors.groupingBy(ReviewCase::getPaymentIntentId, Collectors.counting()));
        return cases.stream().map(reviewCase -> {
            PaymentIntent intent = reviewCase.getPaymentIntentId() == null ? null : intents.get(reviewCase.getPaymentIntentId());
            OutboxCommand command = commandOf(reviewCase).orElse(null);
            Related related = new Related(intent, refundOf(reviewCase).orElse(null), command, retryable(command),
                    reviewCase.getPaymentIntentId() == null ? 1 : holdCaseCounts.getOrDefault(reviewCase.getPaymentIntentId(), 0L));
            PaymentState paymentState = intent == null ? null : intent.getState();
            Set<ReviewAction> allowed = reviewCase.getStatus() == ReviewStatus.OPEN
                    ? allowedActions(related) : EnumSet.noneOf(ReviewAction.class);
            return ReviewCaseView.from(reviewCase, paymentState, allowed, clock.instant());
        }).toList();
    }

    /**
     * The decisions valid right now. A held payment's last case that can decide the hold must decide it:
     * refund and provider-call cases cannot release, fail or reverse a payment, so they do not count.
     */
    static Set<ReviewAction> allowedActions(Related related) {
        PaymentIntent intent = related.intent();
        if (related.refund() != null && related.refund().getStatus() == RefundStatus.PENDING_REVIEW) {
            // A refund can only be approved while the payment can still be refunded (not held, reversed or refunded).
            return intent != null && intent.canRequestRefund()
                    ? EnumSet.of(ReviewAction.APPROVE_REFUND, ReviewAction.REJECT_REFUND)
                    : EnumSet.of(ReviewAction.REJECT_REFUND);
        }
        if (related.commandRetryable()) {
            return EnumSet.of(ReviewAction.RETRY_PROVIDER_CALL);
        }
        if (intent != null && intent.isOnHold()) {
            EnumSet<ReviewAction> actions = EnumSet.of(ReviewAction.RELEASE_PAYMENT);
            if (intent.canFailFromReview()) {
                actions.add(ReviewAction.FAIL_PAYMENT);
            }
            if (intent.canReverseFromReview()) {
                actions.add(ReviewAction.REQUIRE_REVERSAL);
            }
            if (related.holdDecidingCasesForPayment() > 1) {
                actions.add(ReviewAction.ACKNOWLEDGE);
            }
            return actions;
        }
        return EnumSet.of(ReviewAction.ACKNOWLEDGE);
    }

    /** Cases about the payment itself or its provider data; refund and provider-call cases are about something else. */
    static boolean canDecideAHold(ReviewCase reviewCase) {
        return !AggregateType.REFUND_REQUEST.equals(reviewCase.getReferenceType())
                && !OutboxService.AGGREGATE.equals(reviewCase.getReferenceType());
    }

    private boolean retryable(OutboxCommand command) {
        return command != null && (command.getStatus() == CommandStatus.NEEDS_REVIEW
                || outboxService.isRefusedReversalStillNeeded(command));
    }

    private void apply(ReviewAction action, ReviewCase reviewCase, Locked locked, String actor, String note) {
        PaymentIntent intent = locked.related().intent();
        String metadata = metadata(reviewCase, action);
        switch (action) {
            case ACKNOWLEDGE -> {
            }
            case RELEASE_PAYMENT -> recordDecision(intent, intent.releaseFromReview(), actor,
                    "Released from manual review: " + note, metadata);
            case FAIL_PAYMENT -> recordDecision(intent, intent.failFromReview(), actor,
                    "Failed in manual review: " + note, metadata);
            case REQUIRE_REVERSAL -> {
                recordDecision(intent, intent.reverseFromReview(), actor, "Reversal required by manual review: " + note, metadata);
                outboxService.enqueueReversal(intent.getId());
            }
            case APPROVE_REFUND -> refundService.approveAfterReview(locked.related().refund().getId(), actor, note);
            case REJECT_REFUND -> refundService.rejectAfterReview(locked.related().refund().getId(), actor, note);
            case RETRY_PROVIDER_CALL -> outboxService.retry(locked.related().command().getId(),
                    new Caller(actor, id.fatarc.portfolio.payflowreliability.security.Role.OPERATOR, null));
        }
    }

    private void requireAllowed(ReviewAction action, ReviewCase reviewCase, Locked locked) {
        Set<ReviewAction> allowed = allowedActions(locked.related());
        if (!allowed.contains(action)) {
            throw new BusinessException(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE,
                    explainRejectedAction(action, allowed, locked.related()));
        }
    }

    private Locked lock(UUID reviewCaseId) {
        // Payment first, then case: the order webhook processing uses. A case's payment never changes.
        UUID paymentIntentId = reviewCaseRepository.findPaymentRefById(reviewCaseId)
                .orElseThrow(() -> notFound(reviewCaseId))
                .getPaymentIntentId();
        PaymentIntent intent = paymentIntentId == null ? null : paymentIntentService.lockRequired(paymentIntentId);
        ReviewCase reviewCase = reviewCaseRepository.findForUpdate(reviewCaseId).orElseThrow(() -> notFound(reviewCaseId));
        long holdCases = paymentIntentId == null ? 1
                : reviewCaseRepository.findByStatusInAndPaymentIntentIdIn(ReviewStatus.UNRESOLVED, List.of(paymentIntentId))
                .stream().filter(ReviewWorkflowService::canDecideAHold).count();
        OutboxCommand command = commandOf(reviewCase).orElse(null);
        return new Locked(reviewCase, new Related(intent, refundOf(reviewCase).orElse(null), command, retryable(command),
                holdCases));
    }

    private void recordDecision(PaymentIntent intent, PaymentState previousState, String actor, String reason, String metadata) {
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), actor, reason, metadata);
    }

    private Optional<RefundRequest> refundOf(ReviewCase reviewCase) {
        if (!AggregateType.REFUND_REQUEST.equals(reviewCase.getReferenceType())) {
            return Optional.empty();
        }
        return refundRequestRepository.findById(UUID.fromString(reviewCase.getReferenceId()));
    }

    private Optional<OutboxCommand> commandOf(ReviewCase reviewCase) {
        if (!OutboxService.AGGREGATE.equals(reviewCase.getReferenceType())) {
            return Optional.empty();
        }
        return outboxCommandRepository.findById(UUID.fromString(reviewCase.getReferenceId()));
    }

    private static String metadata(ReviewCase reviewCase, ReviewAction action) {
        return "reviewCaseId=" + reviewCase.getId() + ",action=" + action + ",reason=" + reviewCase.getReason()
                + ",referenceType=" + reviewCase.getReferenceType() + ",referenceId=" + reviewCase.getReferenceId();
    }

    private static String explainRejectedAction(ReviewAction action, Set<ReviewAction> allowed, Related related) {
        String reason;
        if (related.refund() != null && related.refund().getStatus() == RefundStatus.PENDING_REVIEW) {
            reason = allowed.contains(ReviewAction.APPROVE_REFUND)
                    ? "Refund " + related.refund().getId() + " is waiting for a decision."
                    : "Refund " + related.refund().getId() + " can no longer be approved: the payment is "
                    + related.intent().getState() + ".";
        } else if (related.commandRetryable()) {
            reason = "Provider call " + related.command().getId() + " did not complete; sending it again is the safe decision.";
        } else if (related.intent() != null && related.intent().isOnHold()) {
            reason = "Payment " + related.intent().getId() + " is on hold (held from " + related.intent().getStateBeforeReview()
                    + ") and this is the last open case for it, so it needs a decision.";
        } else {
            reason = "Nothing is waiting on this case except acknowledgement.";
        }
        return action + " is not applicable. " + reason + " Allowed actions: " + allowed + ".";
    }

    private static BusinessException alreadyResolved(ReviewCase reviewCase) {
        return new BusinessException(ErrorCode.REVIEW_CASE_ALREADY_RESOLVED,
                "Review case " + reviewCase.getId() + " was already resolved by " + reviewCase.getResolvedBy()
                        + " with " + reviewCase.getResolutionAction() + ".");
    }

    private static BusinessException notFound(UUID reviewCaseId) {
        return new BusinessException(ErrorCode.REVIEW_CASE_NOT_FOUND, "Review case not found: " + reviewCaseId);
    }

    record Related(PaymentIntent intent, RefundRequest refund, OutboxCommand command, boolean commandRetryable,
                   long holdDecidingCasesForPayment) {
    }

    private record Locked(ReviewCase reviewCase, Related related) {
    }
}
