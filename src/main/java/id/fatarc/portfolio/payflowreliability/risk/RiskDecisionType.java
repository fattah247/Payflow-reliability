package id.fatarc.portfolio.payflowreliability.risk;

/** Same three outcomes as trustgate-android {@code SensitiveActionDecision}. */
public enum RiskDecisionType {
    ALLOW,
    /** Step-up: the payment waits in REQUIRES_CONFIRMATION until the customer confirms or it expires. */
    REQUIRE_CONFIRMATION,
    /** The payment is recorded as BLOCKED and never handed to the provider. */
    BLOCK
}
