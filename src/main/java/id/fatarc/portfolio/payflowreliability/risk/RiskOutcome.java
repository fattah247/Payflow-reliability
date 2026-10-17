package id.fatarc.portfolio.payflowreliability.risk;

/** What became of a decision. A step-up ends confirmed, expired or cancelled; the others end where they started. */
public enum RiskOutcome {
    ALLOWED,
    AWAITING_CONFIRMATION,
    CONFIRMED,
    /** Nobody confirmed the step-up in time: the payment expired. */
    CONFIRMATION_EXPIRED,
    /** The step-up was abandoned explicitly (the customer or shop cancelled). */
    CANCELLED_UNCONFIRMED,
    BLOCKED;

    /** A step-up that ended without a confirmation: friction if the customer was legitimate, a stop if not. */
    public boolean stepUpAbandoned() {
        return this == CONFIRMATION_EXPIRED || this == CANCELLED_UNCONFIRMED;
    }
}
