package id.fatarc.portfolio.payflowreliability.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How notifications reach shops.
 *
 * @param connectTimeout          how long to wait for the shop's server to accept a connection
 * @param readTimeout             how long to wait for its answer; a slow shop is a failed attempt, retried later
 * @param maxAttempts             attempts before an event is given up on (it stays readable, and can be redelivered)
 * @param baseBackoff             the wait after the first failure, doubled each time, with jitter
 * @param maxBackoff              the longest wait between attempts
 * @param lease                   how long one dispatcher owns an attempt before another may take it over
 * @param batchSize               events attempted per run
 * @param secretRotationOverlap   how long the previous signing secret keeps signing after a rotation
 * @param allowPrivateAddresses   allow endpoints on loopback and private networks (local demos only: the merchant
 *                                simulator listens on localhost). Refused at startup next to a real provider.
 */
@ConfigurationProperties("payflow.merchant-webhooks")
public record MerchantWebhookProperties(Duration connectTimeout, Duration readTimeout, Integer maxAttempts, Duration baseBackoff,
                                        Duration maxBackoff, Duration lease, Integer batchSize, Duration secretRotationOverlap,
                                        Boolean allowPrivateAddresses) {
    public MerchantWebhookProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        maxAttempts = maxAttempts == null ? 20 : maxAttempts;
        baseBackoff = baseBackoff == null ? Duration.ofSeconds(30) : baseBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofHours(1) : maxBackoff;
        lease = lease == null ? Duration.ofSeconds(30) : lease;
        batchSize = batchSize == null ? 20 : batchSize;
        secretRotationOverlap = secretRotationOverlap == null ? Duration.ofHours(24) : secretRotationOverlap;
        allowPrivateAddresses = allowPrivateAddresses != null && allowPrivateAddresses;
    }
}
