package id.fatarc.portfolio.payflowreliability.risk;

/** How a step-up must be confirmed; same values as trustgate-android {@code RequiredConfirmation}. */
public enum RequiredConfirmation {
    NONE,
    /** Signed by the device's step-up key, which only signs after the person authenticates (biometrics or screen lock). */
    STEP_UP_KEY,
    /** A signed tap confirmation, the weaker fallback for a device without a screen lock. */
    TAP
}
