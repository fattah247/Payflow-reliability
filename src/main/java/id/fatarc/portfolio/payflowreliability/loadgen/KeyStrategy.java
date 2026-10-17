package id.fatarc.portfolio.payflowreliability.loadgen;

/** How the simulated merchant client generates idempotency keys. */
public enum KeyStrategy {
    /** The contract: one key per checkout, generated before the first attempt and reused on every retry. */
    PER_CHECKOUT,
    /** The anti-pattern: a fresh key for every attempt. Shows what a retry costs when keys are wrong. */
    PER_ATTEMPT
}
