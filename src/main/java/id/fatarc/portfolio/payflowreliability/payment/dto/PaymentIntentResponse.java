package id.fatarc.portfolio.payflowreliability.payment.dto;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * @param stateBeforeReview set only while the payment is in MANUAL_REVIEW: where releasing it would return to
 * @param refundedAmount    refunds confirmed by the provider (partial refunds keep the lifecycle state)
 * @param reversedAmount    captured money returned by a confirmed reversal
 */
public record PaymentIntentResponse(
        UUID id,
        String merchantId,
        String merchantReference,
        BigDecimal amount,
        String currency,
        PaymentState state,
        PaymentState stateBeforeReview,
        Instant capturedAt,
        BigDecimal refundedAmount,
        BigDecimal reversedAmount,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentIntentResponse from(PaymentIntent intent) {
        return new PaymentIntentResponse(
                intent.getId(),
                intent.getMerchantId(),
                intent.getMerchantReference(),
                intent.getAmount(),
                intent.getCurrency(),
                intent.getState(),
                intent.getStateBeforeReview(),
                intent.getCapturedAt(),
                intent.getRefundedAmount(),
                intent.getReversedAmount(),
                intent.getCreatedAt(),
                intent.getUpdatedAt()
        );
    }
}
