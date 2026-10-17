package id.fatarc.portfolio.payflowreliability.payment;

import java.util.UUID;

/** Published inside the transaction that created a payment (not on an idempotent replay). */
public record PaymentCreated(UUID paymentIntentId) {
}
