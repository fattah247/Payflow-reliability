package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The local stand-in for the payment provider. Off unless {@code enabled=true} (application.yml turns it
 * on for local runs); it refuses to run next to a real provider (see ProviderConfigurationGuard).
 *
 * @param webhookUrl   where to deliver webhooks; empty means this application's own webhook endpoint
 * @param faultDelay   how long a simulated "timeout after commit" sleeps; longer than the client read timeout
 * @param keyRetention how long the provider remembers an idempotency key; afterwards a request with that key
 *                     is a new request (real providers prune keys, commonly after 24 hours)
 */
@ConfigurationProperties("payflow.provider-sim")
public record SimulatorProperties(boolean enabled, String apiKey, String webhookUrl, String webhookSecret,
                                  int maxDeliveryAttempts, Duration deliveryBaseBackoff, Duration deliveryMaxBackoff,
                                  Duration faultDelay, Duration keyRetention) {
    public SimulatorProperties {
        maxDeliveryAttempts = maxDeliveryAttempts <= 0 ? 8 : maxDeliveryAttempts;
        deliveryBaseBackoff = deliveryBaseBackoff == null ? Duration.ofSeconds(2) : deliveryBaseBackoff;
        deliveryMaxBackoff = deliveryMaxBackoff == null ? Duration.ofMinutes(5) : deliveryMaxBackoff;
        faultDelay = faultDelay == null ? Duration.ofSeconds(10) : faultDelay;
        keyRetention = keyRetention == null ? Duration.ofHours(24) : keyRetention;
    }
}
