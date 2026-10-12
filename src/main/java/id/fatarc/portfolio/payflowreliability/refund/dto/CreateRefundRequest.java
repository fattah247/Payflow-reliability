package id.fatarc.portfolio.payflowreliability.refund.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateRefundRequest(
        @NotNull @Digits(integer = 17, fraction = 2, message = "must have at most 17 integer digits and 2 decimal places") @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @Size(max = 500) String reason
) {
}
