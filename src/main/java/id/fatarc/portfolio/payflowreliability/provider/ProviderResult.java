package id.fatarc.portfolio.payflowreliability.provider;

import java.math.BigDecimal;

/** The three outcomes that matter for a call that moves money. */
public sealed interface ProviderResult {
    /** The provider applied the operation. */
    record Succeeded(String providerReference, BigDecimal amount) implements ProviderResult {
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
