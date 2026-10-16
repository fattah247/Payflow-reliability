package id.fatarc.portfolio.payflowreliability.risk;

/** Which rule produced a decision, so every decision can be explained, not only shown. */
public enum RiskRule {
    /** At least one high-severity device signal: blocked whatever the total (as in trustgate-android). */
    SEVERE_SIGNAL,
    /** Total points reached the block threshold. */
    SCORE_BLOCK,
    /** Total points reached the step-up threshold. */
    SCORE_STEP_UP,
    /** Nothing reached a threshold. */
    BELOW_THRESHOLDS
}
