package id.fatarc.portfolio.payflowreliability.payment.dto;

import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record ProviderWebhookRequest(
        @NotBlank @Size(max = 100) String providerEventId,
        @NotBlank @Size(max = 100) String providerTransactionId,
        @NotNull UUID paymentIntentId,
        @NotNull ProviderStatus status,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency
) {
}
