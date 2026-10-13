package id.fatarc.portfolio.payflowreliability.providersim;

import id.fatarc.portfolio.payflowreliability.common.WebhookSignatures;
import id.fatarc.portfolio.payflowreliability.provider.LocalServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The provider's webhook sender: signs each payload with the shared secret and retries with
 * backoff on 409 (payment on hold), 5xx and network errors, like a real provider does.
 */
@Component
@ConditionalOnProperty(name = "payflow.provider-sim.enabled", havingValue = "true")
public class SimWebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(SimWebhookDispatcher.class);

    private final SimWebhookDeliveryRepository deliveries;
    private final SimulatorProperties properties;
    private final LocalServer localServer;
    private final RestClient.Builder builder;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private volatile RestClient client;

    public SimWebhookDispatcher(SimWebhookDeliveryRepository deliveries, SimulatorProperties properties,
                                LocalServer localServer, RestClient.Builder builder, Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.properties = properties;
        this.localServer = localServer;
        this.builder = builder;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Sends due webhooks once; returns how many delivery attempts were made. */
    public synchronized int runOnce() {
        List<UUID> due = transactions.execute(status -> deliveries.findDue(clock.instant(), PageRequest.of(0, 50)));
        int attempts = 0;
        for (UUID id : due) {
            SimWebhookDelivery delivery = transactions.execute(status -> deliveries.findById(id).orElse(null));
            if (delivery == null || delivery.getStatus() != SimWebhookDelivery.Status.PENDING) {
                continue;
            }
            attempts++;
            Outcome outcome = send(delivery);
            transactions.executeWithoutResult(status -> deliveries.findById(id).ifPresent(current -> apply(current, outcome)));
        }
        return attempts;
    }

    private Outcome send(SimWebhookDelivery delivery) {
        byte[] body = delivery.getPayload().getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignatures.sign(properties.webhookSecret(), clock.instant().getEpochSecond(), body);
        try {
            var response = client().post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Provider-Signature", signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return new Outcome(response.getStatusCode().value(), null);
        } catch (RestClientResponseException ex) {
            return new Outcome(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (RuntimeException ex) {
            return new Outcome(null, ex.getMessage());
        }
    }

    private void apply(SimWebhookDelivery delivery, Outcome outcome) {
        Instant now = clock.instant();
        Integer status = outcome.status();
        if (status != null && status >= 200 && status < 300) {
            delivery.delivered(status, now);
            return;
        }
        boolean retryable = status == null || status == 409 || status == 429 || status >= 500;
        if (!retryable || delivery.getAttempts() + 1 >= properties.maxDeliveryAttempts()) {
            delivery.failed(status, outcome.error(), now);
            log.warn("Simulated provider gave up on webhook {} after {} attempts (status {})",
                    delivery.getEventId(), delivery.getAttempts(), status);
            return;
        }
        delivery.retry(status, outcome.error(), now.plus(backoff(delivery.getAttempts() + 1)), now);
    }

    private Duration backoff(int attempts) {
        long exponential = properties.deliveryBaseBackoff().toMillis() * (1L << Math.min(attempts - 1, 20));
        long capped = Math.min(exponential, properties.deliveryMaxBackoff().toMillis());
        long half = capped / 2;
        return Duration.ofMillis(half + (half == 0 ? 0 : ThreadLocalRandom.current().nextLong(half + 1)));
    }

    private RestClient client() {
        if (client == null) {
            String url = properties.webhookUrl() == null || properties.webhookUrl().isBlank()
                    ? localServer.baseUrl() + "/fake-provider/webhook"
                    : properties.webhookUrl();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
            requestFactory.setReadTimeout(Duration.ofSeconds(10));
            client = builder.clone().baseUrl(url).requestFactory(requestFactory).build();
        }
        return client;
    }

    private record Outcome(Integer status, String error) {
    }
}
