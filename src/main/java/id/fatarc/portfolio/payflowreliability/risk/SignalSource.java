package id.fatarc.portfolio.payflowreliability.risk;

/** Where a signal came from, recorded with every decision so it can be explained and weighed later. */
public enum SignalSource {
    /** The app's own heuristic checks on the device (root, emulator, debugger, installer). Can be hidden by an attacker. */
    DEVICE_CHECK,
    /** The app's backend, from the device key's attestation chain (for example an unattested key). */
    KEY_ATTESTATION,
    /** A simulated device profile, used in the app's lab mode and by the traffic generator. */
    SIMULATED_PROFILE
}
