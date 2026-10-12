package id.fatarc.portfolio.payflowreliability.review;

public enum ReviewReason {
    PROVIDER_TRANSACTION_NOT_FOUND,
    PAYMENT_INTENT_NOT_FOUND,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    STATUS_MISMATCH,
    SETTLEMENT_DATE_MISMATCH,
    /** A reconciliation report lists the same provider reference more than once. */
    DUPLICATE_PROVIDER_REFERENCE,
    /** The provider re-sent an event id with a different payload. */
    PROVIDER_EVENT_CONFLICT,
    /** A provider transaction id already belongs to a different payment. */
    PROVIDER_TRANSACTION_CONFLICT,
    /** A provider event contradicts the payment's current state (for example FAILED after SUCCESS). */
    INVALID_STATE_TRANSITION,
    REFUND_BLOCKED_AFTER_RECONCILIATION
}
