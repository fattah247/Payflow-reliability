package id.fatarc.portfolio.payflowreliability.refund.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateRefundRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @Size(max = 500) String reason
) {
}
