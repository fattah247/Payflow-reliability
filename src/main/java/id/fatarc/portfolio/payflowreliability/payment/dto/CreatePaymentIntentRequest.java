package id.fatarc.portfolio.payflowreliability.payment.dto;

import id.fatarc.portfolio.payflowreliability.common.MonetaryAmount;
import id.fatarc.portfolio.payflowreliability.common.ValidMoney;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param merchantId optional: the merchant is taken from the API key; if sent, it must match
 */
@ValidMoney
public record CreatePaymentIntentRequest(
        @Size(max = 100) String merchantId,
        @NotBlank @Size(max = 100) String merchantReference,
        @NotNull BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency
) implements MonetaryAmount {
}
