package id.fatarc.portfolio.payflowreliability.payment;

public enum PaymentState {
    CREATED,
    PENDING,
    AUTHORIZED,
    SUCCESS,
    FAILED,
    EXPIRED,
    SETTLED,
    RECONCILED,
    /** Fully refunded. Partial refunds leave the lifecycle state unchanged and show in refundedAmount. */
    REFUNDED,
    /** A reversal is being executed with the provider (outbox). */
    REVERSAL_REQUIRED,
    /** The provider confirmed the reversal. */
    REVERSED,
    /**
     * Withdrawn before the customer paid, for example because the customer abandoned the checkout. Final: a
     * capture arriving afterwards contradicts it and goes to review instead of being applied.
     */
    CANCELLED,
    MANUAL_REVIEW
}
