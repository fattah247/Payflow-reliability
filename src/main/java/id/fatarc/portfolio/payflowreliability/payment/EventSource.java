package id.fatarc.portfolio.payflowreliability.payment;

/** How a provider event reached us. Both go through exactly the same decision rules. */
public enum EventSource {
    /** Pushed by the provider and authenticated by its HMAC signature. */
    WEBHOOK,
    /** Recovered by the status sweeper asking the provider (for example after a lost webhook). */
    POLL
}
