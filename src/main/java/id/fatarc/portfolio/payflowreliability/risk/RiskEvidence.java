package id.fatarc.portfolio.payflowreliability.risk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What the customer's device reports about itself, sent by the shop with the payment request. This is the
 * contract with trustgate-android: its {@code RiskEvidence} (level, flagged signal ids, simulated) plus, for each
 * flagged signal, the category and severity the app assigned and where the signal came from.
 *
 * <p>The device's own {@code level} is recorded but never trusted: the server recomputes the level from the
 * signals with the same rules, and adds what the device cannot know (amount and what is being bought).
 *
 * @param deviceKeyId        the device's registered key (trustgate {@code keyIdFor}), for correlation only
 * @param level              the level the device computed for itself
 * @param simulated          the app was in a simulated profile
 * @param stepUpKeyAvailable the device has a step-up key, so a step-up can be confirmed with it (otherwise a tap)
 * @param signals            the detected signals only
 */
public record RiskEvidence(
        @Size(max = 64) String deviceKeyId,
        DeviceRiskLevel level,
        boolean simulated,
        boolean stepUpKeyAvailable,
        @NotNull @Size(max = 20) List<@Valid @NotNull Signal> signals
) {
    /**
     * @param id       the signal's id in the app (for example {@code root_su_path})
     * @param category ROOT, EMULATOR, DEBUGGER, INSTALLER, or ATTESTATION for signals the app's backend adds
     */
    public record Signal(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9_]+") String id,
            @NotBlank @Size(max = 32) String category,
            @NotNull SignalSeverity severity,
            @NotNull SignalSource source
    ) {
    }
}
