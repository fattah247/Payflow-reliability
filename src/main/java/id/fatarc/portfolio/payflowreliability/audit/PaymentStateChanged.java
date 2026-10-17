package id.fatarc.portfolio.payflowreliability.audit;

import java.util.UUID;

/**
 * Published inside the transaction that changed a payment's state, by the one place every such change is recorded.
 * Listeners run in that transaction: what they write commits or rolls back with the change.
 */
public record PaymentStateChanged(UUID paymentIntentId, String previousState, String newState) {
}
