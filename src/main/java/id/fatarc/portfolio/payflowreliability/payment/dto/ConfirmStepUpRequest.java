package id.fatarc.portfolio.payflowreliability.payment.dto;

import id.fatarc.portfolio.payflowreliability.risk.RequiredConfirmation;
import jakarta.validation.constraints.NotNull;

/**
 * @param method how the customer confirmed: STEP_UP_KEY (a signature only the device's biometric-bound key can make) or
 *               TAP (the weaker fallback). The shop's backend verifies the device's signature; this API records the result.
 */
public record ConfirmStepUpRequest(@NotNull RequiredConfirmation method) {
}
