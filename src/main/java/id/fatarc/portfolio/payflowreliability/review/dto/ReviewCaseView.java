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
 * @param allowedActions decisions that {@code POST /api/review-cases/{id}/resolve} will accept; empty once resolved
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
        Set<ReviewAction> allowedActions,
        String resolvedBy,
        Instant resolvedAt,
        ReviewAction resolutionAction,
        String resolutionNote
) {
    public static ReviewCaseView from(ReviewCase reviewCase, PaymentState paymentState, Set<ReviewAction> allowedActions) {
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
                allowedActions,
                reviewCase.getResolvedBy(),
                reviewCase.getResolvedAt(),
                reviewCase.getResolutionAction(),
                reviewCase.getResolutionNote()
        );
    }
}
