package id.fatarc.portfolio.payflowreliability.payment.dto;

import id.fatarc.portfolio.payflowreliability.common.MonetaryAmount;
import id.fatarc.portfolio.payflowreliability.common.ValidMoney;
import id.fatarc.portfolio.payflowreliability.risk.RiskAction;
import id.fatarc.portfolio.payflowreliability.risk.RiskEvidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param merchantId   optional: the merchant is taken from the API key; if sent, it must match
 * @param action       optional: what the money buys (PHYSICAL_GOODS when omitted); weighs into the device-risk decision
 * @param riskEvidence optional: what the customer's device reports about itself (the contract with trustgate-android).
 *                     Not part of the request fingerprint: a retry may carry fresh evidence and gets the first decision.
 */
@ValidMoney
public record CreatePaymentIntentRequest(
        @Size(max = 100) String merchantId,
        @NotBlank @Size(max = 100) String merchantReference,
        @NotNull BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency,
        RiskAction action,
        @Valid RiskEvidence riskEvidence
) implements MonetaryAmount {
    public CreatePaymentIntentRequest(String merchantId, String merchantReference, BigDecimal amount, String currency) {
        this(merchantId, merchantReference, amount, currency, null, null);
    }
}
