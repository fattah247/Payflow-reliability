package id.fatarc.portfolio.payflowreliability.review.dto;

import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A review case plus what an operator can do about it right now.
 *
 * @param paymentState   current state of the affected payment, when there is one
 * @param dueAt          when a person should have acted (see {@link ReviewCase#DECIDE_WITHIN} and the others)
 * @param allowedActions decisions that {@code POST /api/review-cases/{id}/resolve} will accept; empty while a
 *                       proposal awaits approval and once resolved
 * @param approvalRequiredFor allowed actions that only take effect after a second operator approves
 */
public record ReviewCaseView(
        UUID id,
        ReviewReason reason,
        ReviewStatus status,
        String referenceType,
        String referenceId,
        UUID paymentIntentId,
        PaymentState paymentState,
        String details,
        String createdBy,
        Instant createdAt,
        Instant dueAt,
        boolean overdue,
        Set<ReviewAction> allowedActions,
        Set<ReviewAction> approvalRequiredFor,
        ReviewAction proposedAction,
        String proposedBy,
        Instant proposedAt,
        String proposalNote,
        String resolvedBy,
        String approvedBy,
        Instant resolvedAt,
        ReviewAction resolutionAction,
        String resolutionNote
) {
    public static ReviewCaseView from(ReviewCase reviewCase, PaymentState paymentState, Set<ReviewAction> allowedActions,
                                      Instant now) {
        return new ReviewCaseView(
                reviewCase.getId(),
                reviewCase.getReason(),
                reviewCase.getStatus(),
                reviewCase.getReferenceType(),
                reviewCase.getReferenceId(),
                reviewCase.getPaymentIntentId(),
                paymentState,
                reviewCase.getDetails(),
                reviewCase.getCreatedBy(),
                reviewCase.getCreatedAt(),
                reviewCase.getDueAt(),
                reviewCase.isOverdue(now),
                allowedActions,
                allowedActions.stream().filter(ReviewAction::requiresApproval)
                        .collect(java.util.stream.Collectors.toCollection(() -> java.util.EnumSet.noneOf(ReviewAction.class))),
                reviewCase.getProposedAction(),
                reviewCase.getProposedBy(),
                reviewCase.getProposedAt(),
                reviewCase.getProposalNote(),
                reviewCase.getResolvedBy(),
                reviewCase.getApprovedBy(),
                reviewCase.getResolvedAt(),
                reviewCase.getResolutionAction(),
                reviewCase.getResolutionNote()
        );
    }
}
