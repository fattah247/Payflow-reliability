package id.fatarc.portfolio.payflowreliability.review;

public enum ReviewReason {
    PROVIDER_TRANSACTION_NOT_FOUND,
    PAYMENT_INTENT_NOT_FOUND,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    STATUS_MISMATCH,
    SETTLEMENT_DATE_MISMATCH,
    /** The provider's report and our records disagree on how much was refunded. */
    REFUND_AMOUNT_MISMATCH,
    /** A payout we recorded for a settlement date is absent from the provider's report for that date. */
    MISSING_FROM_PROVIDER_REPORT,
    /** A reconciliation report lists the same provider reference more than once. */
    DUPLICATE_PROVIDER_REFERENCE,
    /** The provider re-sent an event id with a different payload. */
    PROVIDER_EVENT_CONFLICT,
    /** A provider transaction id already belongs to a different payment. */
    PROVIDER_TRANSACTION_CONFLICT,
    /** A provider event contradicts the payment's current state (for example FAILED after SUCCESS). */
    INVALID_STATE_TRANSITION,
    REFUND_BLOCKED_AFTER_RECONCILIATION,
    /** A refund or reversal call never got a definite answer from the provider. */
    PROVIDER_OUTCOME_UNKNOWN,
    /** The provider definitively refused a refund or reversal. */
    PROVIDER_REJECTED_OPERATION
}
