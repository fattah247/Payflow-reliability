package id.fatarc.portfolio.payflowreliability.provider;

import java.math.BigDecimal;
import java.util.UUID;

/** What the provider says about a payment, used by the status sweeper when webhooks went missing. */
public record ProviderPaymentStatus(UUID paymentIntentId, String providerTransactionId, String status,
                                    BigDecimal amount, String currency) {
}
