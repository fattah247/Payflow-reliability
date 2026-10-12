package id.fatarc.portfolio.payflowreliability.payment.dto;

import id.fatarc.portfolio.payflowreliability.payment.WebhookOutcome;
import id.fatarc.portfolio.payflowreliability.payment.WebhookResult;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;

import java.util.UUID;

public record WebhookResponse(
        String providerEventId,
        WebhookOutcome outcome,
        String detail,
        ReviewReason reviewReason,
        UUID reviewCaseId,
        WebhookOutcome originalOutcome,
        PaymentIntentResponse paymentIntent
) {
    public static WebhookResponse from(String providerEventId, WebhookResult result) {
        return new WebhookResponse(
                providerEventId,
                result.outcome(),
                result.detail(),
                result.reviewReason(),
                result.reviewCaseId(),
                result.originalOutcome(),
                result.paymentIntent() == null ? null : PaymentIntentResponse.from(result.paymentIntent())
        );
    }
}
