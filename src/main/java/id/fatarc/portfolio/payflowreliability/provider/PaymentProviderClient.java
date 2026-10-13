package id.fatarc.portfolio.payflowreliability.provider;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** The provider's API as this service uses it. All money-moving calls carry an idempotency key. */
public interface PaymentProviderClient {
    ProviderResult refund(String idempotencyKey, String providerTransactionId, BigDecimal amount, String currency,
                          String reference);

    ProviderResult reverse(String idempotencyKey, String providerTransactionId, String reference);

    /** The recorded outcome of an earlier refund call, if the provider received it at all. */
    Optional<ProviderResult> findRefund(String idempotencyKey);

    Optional<ProviderResult> findReversal(String idempotencyKey);

    /**
     * @return empty when the provider has no record of the payment
     * @throws ProviderUnavailableException when the provider could not be asked
     */
    Optional<ProviderPaymentStatus> paymentStatus(UUID paymentIntentId);
}
