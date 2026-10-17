package id.fatarc.portfolio.payflowreliability.merchantsim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.fatarc.portfolio.payflowreliability.common.WebhookSignatures;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shops that receive our webhooks, for local demos and tests. Each does what docs/merchant-webhooks.md asks of a real
 * shop: verify the signature (any {@code v1} against any secret it holds, within five minutes), process each event id
 * once, and apply a payment's events in sequence order, ignoring one older than what it already applied. Faults make
 * it answer with an error or slowly. In memory: a test and demo control, not a store.
 */
@Component
@ConditionalOnProperty(name = "payflow.merchant-sim.enabled", havingValue = "true")
public class MerchantSimulator {
    static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    /** What the shop did with a delivery. */
    public enum Outcome {
        /** Verified and applied. */
        APPLIED,
        /** An event id it had already processed: acknowledged, not applied again. */
        DUPLICATE,
        /** Older than the payment's latest applied event (a delivery overtaken by a newer one): acknowledged, ignored. */
        STALE,
        /** The signature did not verify against any secret it holds, or was too old: refused with 401. */
        BAD_SIGNATURE,
        /** A fault was injected: answered with an error, nothing recorded as processed. */
        FAULT
    }

    public record Delivery(String eventId, String type, long sequence, String paymentIntentId, String state, int signatures,
                           Outcome outcome, Instant receivedAt) {
    }

    public record Applied(String state, long sequence) {
    }

    /** The answer the simulated server gives. */
    public record Answer(int status, long delayMillis) {
    }

    private static final class Shop {
        final List<String> secrets = new ArrayList<>();
        final List<Delivery> deliveries = new ArrayList<>();
        final Set<String> processed = new LinkedHashSet<>();
        final Map<String, Applied> payments = new LinkedHashMap<>();
        final Map<String, Applied> refunds = new LinkedHashMap<>();
        int failNext;
        int failStatus = 500;
        long delayMillis;
        int slowNext;
    }

    private final Map<String, Shop> shops = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MerchantSimulator(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The shop stores the secret it was shown at registration or rotation (replacing what it had). */
    public void setSecret(String shopId, String secret) {
        Shop shop = shop(shopId);
        synchronized (shop) {
            shop.secrets.clear();
            shop.secrets.add(secret);
        }
    }

    /** The next {@code times} deliveries are answered with {@code status}; the next {@code slowTimes} wait {@code delayMillis} first. */
    public void inject(String shopId, int times, Integer status, int slowTimes, long delayMillis) {
        Shop shop = shop(shopId);
        synchronized (shop) {
            shop.failNext = times;
            shop.failStatus = status == null ? 500 : status;
            shop.slowNext = slowTimes;
            shop.delayMillis = delayMillis;
        }
    }

    public Answer receive(String shopId, String signatureHeader, byte[] body) {
        Shop shop = shop(shopId);
        synchronized (shop) {
            long delay = 0;
            if (shop.slowNext > 0) {
                shop.slowNext--;
                delay = shop.delayMillis;
            }
            JsonNode event = parse(body);
            String eventId = event.path("id").asText(null);
            String type = event.path("type").asText(null);
            long sequence = event.path("sequence").asLong();
            JsonNode data = event.path("data");
            String paymentIntentId = data.path("paymentIntentId").asText(null);
            boolean refund = data.hasNonNull("refundId");
            String state = refund ? data.path("status").asText(null) : data.path("state").asText(null);
            WebhookSignatures.Parsed signature = WebhookSignatures.parse(signatureHeader);
            int signatures = signature == null ? 0 : signature.signatures().size();
            Outcome outcome;
            int status;
            if (shop.failNext > 0) {
                shop.failNext--;
                outcome = Outcome.FAULT;
                status = shop.failStatus;
            } else if (!verified(shop.secrets, signature, body)) {
                outcome = Outcome.BAD_SIGNATURE;
                status = 401;
            } else if (!shop.processed.add(eventId)) {
                outcome = Outcome.DUPLICATE;
                status = 200;
            } else {
                Map<String, Applied> latest = refund ? shop.refunds : shop.payments;
                String key = refund ? data.path("refundId").asText() : paymentIntentId;
                Applied current = latest.get(key);
                if (current != null && current.sequence() > sequence) {
                    outcome = Outcome.STALE;
                } else {
                    latest.put(key, new Applied(state, sequence));
                    outcome = Outcome.APPLIED;
                }
                status = 200;
            }
            shop.deliveries.add(new Delivery(eventId, type, sequence, paymentIntentId, state, signatures, outcome, clock.instant()));
            return new Answer(status, delay);
        }
    }

    public Map<String, Object> view(String shopId) {
        Shop shop = shop(shopId);
        synchronized (shop) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("shop", shopId);
            view.put("secrets", shop.secrets.size());
            view.put("deliveries", List.copyOf(shop.deliveries));
            view.put("payments", Map.copyOf(shop.payments));
            view.put("refunds", Map.copyOf(shop.refunds));
            return view;
        }
    }

    public List<Delivery> deliveries(String shopId) {
        Shop shop = shop(shopId);
        synchronized (shop) {
            return List.copyOf(shop.deliveries);
        }
    }

    public Map<String, Applied> payments(String shopId) {
        Shop shop = shop(shopId);
        synchronized (shop) {
            return Map.copyOf(shop.payments);
        }
    }

    public void forget(String shopId) {
        shops.remove(shopId);
    }

    public void reset() {
        shops.clear();
    }

    private boolean verified(List<String> secrets, WebhookSignatures.Parsed signature, byte[] body) {
        if (signature == null || secrets.isEmpty()) {
            return false;
        }
        long age = clock.instant().getEpochSecond() - signature.timestamp();
        if (Math.abs(age) > SIGNATURE_TOLERANCE.toSeconds()) {
            return false;
        }
        for (String secret : secrets) {
            byte[] expected = WebhookSignatures.expectedSignature(secret, signature.timestamp(), body).getBytes(StandardCharsets.UTF_8);
            for (String candidate : signature.signatures()) {
                if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode parse(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException ex) {
            return objectMapper.createObjectNode();
        }
    }

    private Shop shop(String shopId) {
        return shops.computeIfAbsent(shopId, id -> new Shop());
    }
}
