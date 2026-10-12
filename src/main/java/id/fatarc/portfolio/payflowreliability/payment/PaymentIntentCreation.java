package id.fatarc.portfolio.payflowreliability.payment;

/**
 * Result of a create call.
 *
 * @param replayed true when an earlier request with the same idempotency key created the intent
 */
public record PaymentIntentCreation(PaymentIntent intent, boolean replayed) {
}
