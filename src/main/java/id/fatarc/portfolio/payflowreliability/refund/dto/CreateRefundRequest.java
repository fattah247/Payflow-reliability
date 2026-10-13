package id.fatarc.portfolio.payflowreliability.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** The amount is checked against the payment's currency precision and refundable balance by the service. */
public record CreateRefundRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 500) String reason
) {
}
