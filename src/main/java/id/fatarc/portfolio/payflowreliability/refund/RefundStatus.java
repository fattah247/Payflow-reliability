package id.fatarc.portfolio.payflowreliability.refund;

public enum RefundStatus {
    /** Waiting for an operator decision (refunds on reconciled payments). */
    PENDING_REVIEW,
    /** Accepted; the payment is REFUND_REQUESTED until the refund completes. */
    REQUESTED,
    COMPLETED,
    /** Declined in manual review; the payment is unchanged. */
    REJECTED
}
