package id.fatarc.portfolio.payflowreliability.provider;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Hashing;
import id.fatarc.portfolio.payflowreliability.common.WebhookSignatures;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Verifies {@code X-Provider-Signature: t=<unix>,v1=<hmac>} over the raw request body, before the
 * body is even parsed. The timestamp bounds replay; any configured secret is accepted so secrets
 * can be rotated without downtime.
 */
@Component
public class WebhookSignatureVerifier {
    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);

    private final ProviderProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public WebhookSignatureVerifier(ProviderProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        if (properties.webhookSecrets().isEmpty()) {
            throw new IllegalStateException("payflow.provider.webhook-secrets must contain at least one secret");
        }
    }

    public void verify(String header, byte[] body) {
        WebhookSignatures.Parsed parsed = WebhookSignatures.parse(header);
        if (parsed == null) {
            reject("missing_or_malformed", "Missing or malformed X-Provider-Signature header.");
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(parsed.timestamp()), clock.instant()).abs();
        if (skew.compareTo(properties.signatureTolerance()) > 0) {
            reject("timestamp_outside_tolerance", "Webhook timestamp is outside the allowed window; possible replay.");
        }
        for (String secret : properties.webhookSecrets()) {
            String expected = WebhookSignatures.expectedSignature(secret, parsed.timestamp(), body);
            for (String candidate : parsed.signatures()) {
                if (Hashing.constantTimeEquals(expected, candidate)) {
                    return;
                }
            }
        }
        reject("signature_mismatch", "Invalid provider signature.");
    }

    private void reject(String reason, String message) {
        // Nothing is persisted for unauthenticated requests: otherwise anyone could fill the review queue.
        meterRegistry.counter("payflow.webhook.rejected", "reason", reason).increment();
        log.warn("Webhook rejected: {}", reason);
        throw new BusinessException(ErrorCode.INVALID_SIGNATURE, message);
    }
}
