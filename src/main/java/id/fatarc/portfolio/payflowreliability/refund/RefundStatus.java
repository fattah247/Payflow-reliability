package id.fatarc.portfolio.payflowreliability.refund;

public enum RefundStatus {
    /** Waiting for an operator decision (refunds on reconciled payments). */
    PENDING_REVIEW,
    /** Accepted and queued for the provider (outbox). Reserves the amount against the payment. */
    REQUESTED,
    /** The provider confirmed the refund. */
    COMPLETED,
    /** The provider refused the refund. */
    FAILED,
    /** Declined in manual review. */
    REJECTED,
    /** Withdrawn by the requester before it reached the provider. */
    CANCELLED
}
