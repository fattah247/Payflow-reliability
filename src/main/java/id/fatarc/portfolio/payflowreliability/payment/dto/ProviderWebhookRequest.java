package id.fatarc.portfolio.payflowreliability.payment.dto;

import id.fatarc.portfolio.payflowreliability.common.MonetaryAmount;
import id.fatarc.portfolio.payflowreliability.common.ValidMoney;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

@ValidMoney
public record ProviderWebhookRequest(
        @NotBlank @Size(max = 100) String providerEventId,
        @NotBlank @Size(max = 100) String providerTransactionId,
        @NotNull UUID paymentIntentId,
        @NotNull ProviderStatus status,
        @NotNull BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency
) implements MonetaryAmount {
}
