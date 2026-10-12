package id.fatarc.portfolio.payflowreliability.review;

/** The decision an operator records when resolving a review case. */
public enum ReviewAction {
    /** Close the case without changing any payment or refund. */
    ACKNOWLEDGE,
    /** Return a held payment to the state it was held from. */
    RELEASE_PAYMENT,
    /** Fail a held payment that was never captured. */
    FAIL_PAYMENT,
    /** Mark a held, authorized or captured payment for reversal. */
    REQUIRE_REVERSAL,
    /** Let a refund on a reconciled payment proceed. */
    APPROVE_REFUND,
    /** Decline a refund on a reconciled payment. */
    REJECT_REFUND
}
