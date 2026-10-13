package id.fatarc.portfolio.payflowreliability.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * How this service talks to the payment provider.
 *
 * @param baseUrl        provider API root; empty means the local provider simulator on this server
 * @param apiKey         credential this service presents to the provider API
 * @param webhookSecrets secrets accepted on incoming webhooks; the first signs, older ones keep working during rotation
 * @param signatureTolerance how far a webhook timestamp may be from now (replay window)
 */
@ConfigurationProperties("payflow.provider")
public record ProviderProperties(String baseUrl, String apiKey, List<String> webhookSecrets,
                                 Duration signatureTolerance, Duration connectTimeout, Duration readTimeout) {
    public ProviderProperties {
        webhookSecrets = webhookSecrets == null ? List.of() : List.copyOf(webhookSecrets);
        signatureTolerance = signatureTolerance == null ? Duration.ofMinutes(5) : signatureTolerance;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }
}
