package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.review.ReviewReason;

import java.util.UUID;

/**
 * @param paymentIntent   the payment after processing; null when the webhook referenced an unknown payment
 * @param originalOutcome for redeliveries: the outcome the first delivery received
 */
public record WebhookResult(
        WebhookOutcome outcome,
        String detail,
        ReviewReason reviewReason,
        UUID reviewCaseId,
        PaymentIntent paymentIntent,
        WebhookOutcome originalOutcome
) {
}
