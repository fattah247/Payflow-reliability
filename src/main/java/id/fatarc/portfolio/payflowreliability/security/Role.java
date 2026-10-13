package id.fatarc.portfolio.payflowreliability.security;

public enum Role {
    /** A merchant integration, limited to its own payments and refunds. */
    MERCHANT,
    /** A named operations person: review queue, settlement, reconciliation, merchant onboarding. */
    OPERATOR,
    /** An internal job or the payment provider; never authenticated over the API. */
    SYSTEM
}
