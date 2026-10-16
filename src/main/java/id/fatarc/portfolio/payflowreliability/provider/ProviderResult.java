package id.fatarc.portfolio.payflowreliability.provider;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The three outcomes that matter for a call that moves money. */
public sealed interface ProviderResult {
    /** The provider applied the operation. */
    record Succeeded(String providerReference, BigDecimal amount) implements ProviderResult {
    }

    /**
     * The provider accepted a payout and says what will reach the bank: gross minus its fee is the net it sends,
     * expected to arrive with the given value date. This is the provider's word, not proof of arrival.
     */
    record PayoutAccepted(String providerPayoutId, BigDecimal gross, BigDecimal fee, BigDecimal net,
                          LocalDate expectedValueDate) implements ProviderResult {
    }

    /**
     * The provider refused.
     *
     * @param operationFailed true when the provider processed the operation and reports it failed (final);
     *                        false when it refused the HTTP request itself (for example a validation error),
     *                        which is final only if no earlier attempt may have reached it
     */
    record Rejected(String reason, boolean operationFailed) implements ProviderResult {
    }

    /**
     * We do not know whether it was applied: timeout, connection reset, 5xx, rate limit. The call is
     * resent with the same idempotency key until the provider gives a definite answer.
     */
    record Unknown(String reason) implements ProviderResult {
    }
}
