package id.fatarc.portfolio.payflowreliability.outbox;

public enum CommandStatus {
    /** Waiting to be sent, or to be retried after an unknown outcome. */
    PENDING,
    SUCCEEDED,
    /** The provider definitively refused the operation. */
    FAILED,
    /** Retries ran out without a definite answer; an operator decides (retrying is always safe). */
    NEEDS_REVIEW,
    /** Withdrawn before it was ever sent. */
    CANCELLED
}
