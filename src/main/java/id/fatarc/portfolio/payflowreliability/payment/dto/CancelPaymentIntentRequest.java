package id.fatarc.portfolio.payflowreliability.payment.dto;

import id.fatarc.portfolio.payflowreliability.payment.CancellationReason;
import jakarta.validation.constraints.NotNull;

public record CancelPaymentIntentRequest(@NotNull CancellationReason reason) {
}
