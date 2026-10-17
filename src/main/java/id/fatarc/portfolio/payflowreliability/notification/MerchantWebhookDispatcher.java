package id.fatarc.portfolio.payflowreliability.notification;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.WebhookSignatures;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Delivers merchant events: at least once, signed, with backoff. Each attempt is taken under a lease in its own short
 * transaction, sent with no transaction or lock held (a slow shop never holds up payments), and its outcome written in
 * another. A crash between sending and writing the outcome sends the event again after the lease: shops deduplicate by
 * {@code Payflow-Event-Id}, and order by the payload's {@code sequence}.
 *
 * <p>Any 2xx is a delivery. Anything else, including redirects (never followed: the URL was checked, its target was not),
 * timeouts and refused connections, is retried with exponential backoff and jitter until {@code max-attempts}, then the
 * event is FAILED: still readable through the events API, and redeliverable.
 */
@Service
public class MerchantWebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MerchantWebhookDispatcher.class);

    private final MerchantEventRepository events;
    private final WebhookEndpointRepository endpoints;
    private final DestinationGuard guard;
    private final MerchantWebhookProperties properties;
    private final MeterRegistry registry;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final HttpClient http;

    public MerchantWebhookDispatcher(MerchantEventRepository events, WebhookEndpointRepository endpoints, DestinationGuard guard,
                                     MerchantWebhookProperties properties, MeterRegistry registry, Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.events = events;
        this.endpoints = endpoints;
        this.guard = guard;
        this.properties = properties;
        this.registry = registry;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** What one run did. */
    public record RunReport(int attempted, int delivered, int retrying, int failed, int skipped) {
    }

    /** Attempts the events that are due, oldest first, up to the batch size. */
    public synchronized RunReport runOnce() {
        List<UUID> due = transactions.execute(status -> events.findDue(clock.instant(), PageRequest.of(0, properties.batchSize())));
        int attempted = 0;
        int delivered = 0;
        int retrying = 0;
        int failed = 0;
        int skipped = 0;
        for (UUID id : due) {
            Attempt attempt = transactions.execute(status -> claim(id));
            if (attempt == null) {
                continue;
            }
            if (attempt.skipped()) {
                skipped++;
                continue;
            }
            attempted++;
            Outcome outcome = send(attempt);
            MerchantEvent.Status result = transactions.execute(status -> apply(id, outcome));
            if (result == MerchantEvent.Status.DELIVERED) {
                delivered++;
            } else if (result == MerchantEvent.Status.FAILED) {
                failed++;
            } else {
                retrying++;
            }
        }
        return new RunReport(attempted, delivered, retrying, failed, skipped);
    }

    private Attempt claim(UUID id) {
        MerchantEvent event = events.findForUpdate(id).orElse(null);
        Instant now = clock.instant();
        if (event == null || !event.claim(now, properties.lease())) {
            return null;
        }
        WebhookEndpoint endpoint = endpoints.findById(event.getMerchantId()).filter(WebhookEndpoint::isEnabled).orElse(null);
        if (endpoint == null) {
            event.skip("The shop has no enabled webhook endpoint; the event is readable through GET /api/events.");
            return Attempt.SKIPPED;
        }
        return new Attempt(false, event.getId(), event.getEventType(), event.getPayload(), endpoint.getUrl(),
                endpoint.signingSecrets(now));
    }

    private Outcome send(Attempt attempt) {
        URI uri;
        try {
            uri = guard.check(attempt.url());
        } catch (BusinessException ex) {
            return new Outcome(null, ex.getMessage());
        }
        byte[] body = attempt.payload().getBytes(StandardCharsets.UTF_8);
        long timestamp = clock.instant().getEpochSecond();
        StringBuilder signature = new StringBuilder("t=").append(timestamp);
        attempt.secrets().forEach(secret -> signature.append(",v1=").append(WebhookSignatures.expectedSignature(secret, timestamp, body)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "Payflow-Webhooks/1")
                .header("Payflow-Event-Id", attempt.eventId().toString())
                .header("Payflow-Event-Type", attempt.eventType())
                .header("Payflow-Signature", signature.toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return new Outcome(status, status >= 200 && status < 300 ? null : "The endpoint answered HTTP " + status + ".");
        } catch (HttpTimeoutException ex) {
            return new Outcome(null, "No answer within " + properties.readTimeout().toMillis() + " ms.");
        } catch (IOException ex) {
            return new Outcome(null, "Could not deliver: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : " " + ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Outcome(null, "Interrupted while delivering.");
        }
    }

    private MerchantEvent.Status apply(UUID id, Outcome outcome) {
        MerchantEvent event = events.findForUpdate(id).orElseThrow();
        if (event.getStatus() != MerchantEvent.Status.PENDING) {
            return event.getStatus();
        }
        Instant now = clock.instant();
        Integer status = outcome.status();
        if (status != null && status >= 200 && status < 300) {
            event.delivered(status, now);
            count("delivered");
        } else if (event.getAttempts() + 1 >= properties.maxAttempts()) {
            event.giveUp(status, outcome.error());
            count("failed");
            log.warn("Gave up delivering merchant event {} to {} after {} attempts: {}", event.getId(), event.getMerchantId(),
                    event.getAttempts(), outcome.error());
        } else {
            event.retry(status, outcome.error(), now.plus(backoff(event.getAttempts() + 1)));
            count("retrying");
        }
        return event.getStatus();
    }

    /** base × 2^(attempt−1), capped, then a random point in its upper half, so shops that failed together retry apart. */
    Duration backoff(int attempt) {
        long exponential = properties.baseBackoff().toMillis() * (1L << Math.min(attempt - 1, 20));
        long capped = Math.min(exponential, properties.maxBackoff().toMillis());
        long half = capped / 2;
        return Duration.ofMillis(half + (half == 0 ? 0 : ThreadLocalRandom.current().nextLong(half + 1)));
    }

    private void count(String outcome) {
        registry.counter("payflow.merchant.webhook.deliveries", "outcome", outcome).increment();
    }

    private record Attempt(boolean skipped, UUID eventId, String eventType, String payload, String url, List<String> secrets) {
        static final Attempt SKIPPED = new Attempt(true, null, null, null, null, List.of());
    }

    private record Outcome(Integer status, String error) {
    }
}
