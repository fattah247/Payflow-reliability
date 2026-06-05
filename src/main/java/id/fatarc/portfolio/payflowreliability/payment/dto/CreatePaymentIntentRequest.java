package id.fatarc.portfolio.payflowreliability.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreatePaymentIntentRequest(
        @NotBlank @Size(max = 100) String merchantId,
        @NotBlank @Size(max = 100) String merchantReference,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency
) {
}
