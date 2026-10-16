package id.fatarc.portfolio.payflowreliability.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.provider.LocalServer;
import id.fatarc.portfolio.payflowreliability.providersim.SimLatency;
import id.fatarc.portfolio.payflowreliability.providersim.SimulatorProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Simulated customers checking out at a simulated shop that uses this payment system's public API, against the
 * provider simulator. Every customer's amount, patience and choices come from the run's seed, so the same seed
 * gives the same customers and the same decisions, however the threads interleave.
 *
 * <p>One customer:
 * <ol>
 *   <li>the shop creates the payment (with an idempotency key) and starts it;</li>
 *   <li>the customer opens the provider's payment page. If it takes longer than their patience they leave
 *       (with the configured probability; the shop cancels the payment as ABANDONED) or reload, which makes the shop
 *       send the same request again;</li>
 *   <li>the customer pays. If the provider's answer takes longer than their patience they press pay again and the
 *       shop re-submits: with one key per checkout that is answered with the original payment and the provider
 *       refuses to charge it twice; with a key per attempt it is a new payment, charged again.</li>
 * </ol>
 * Decisions use the latency the provider declares, so they do not depend on machine speed.
 */
@Service
@ConditionalOnProperty(name = "payflow.loadgen.checkout.enabled", havingValue = "true")
public class CheckoutLoadGenerator {
    static final String DISCLAIMER = "Simulated customers. Patience, abandonment and retry behaviour are model parameters "
            + "(payflow.loadgen.checkout.*), not observed data; the money figures show what this model costs, not what any real shop loses.";

    private final CheckoutLoadProperties properties;
    private final SimLatency latency;
    private final SimulatorProperties simulator;
    private final LocalServer localServer;
    private final RestClient.Builder builder;

    public CheckoutLoadGenerator(CheckoutLoadProperties properties, SimLatency latency, SimulatorProperties simulator,
                                 LocalServer localServer, RestClient.Builder builder) {
        this.properties = properties;
        this.latency = latency;
        this.simulator = simulator;
        this.localServer = localServer;
        this.builder = builder;
    }

    public CheckoutRun.Report run(CheckoutRun.Request request) {
        boolean hold = request.holdWebhooks() == null || request.holdWebhooks();
        if (request.providerSlow()) {
            latency.slow(new SimLatency.Mode(properties.slowCheckoutPage().toMillis(), properties.slowPay().toMillis(), hold));
        } else {
            latency.recover();
        }
        int customers = request.customers() == null ? properties.customers() : request.customers();
        KeyStrategy keys = request.keyStrategy() == null ? KeyStrategy.PER_CHECKOUT : request.keyStrategy();
        RestClient client = builder.clone().baseUrl(localServer.baseUrl()).build();

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, properties.concurrency()));
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < customers; index++) {
                Customer customer = customer(request, index);
                futures.add(pool.submit(() -> checkout(client, request, keys, customer)));
            }
            CheckoutRun.Tally paid = CheckoutRun.Tally.zero();
            CheckoutRun.Tally abandoned = CheckoutRun.Tally.zero();
            CheckoutRun.Tally duplicates = CheckoutRun.Tally.zero();
            long retries = 0;
            long absorbed = 0;
            for (Future<Outcome> future : futures) {
                Outcome outcome = future.get();
                if (outcome.charges() > 0) {
                    paid = paid.plus(outcome.amount());
                } else {
                    abandoned = abandoned.plus(outcome.amount());
                }
                for (int extra = 1; extra < outcome.charges(); extra++) {
                    duplicates = duplicates.plus(outcome.amount());
                }
                retries += outcome.retries();
                absorbed += outcome.replays();
            }
            return new CheckoutRun.Report(request.phase(), request.seed(), request.providerSlow(), keys, customers, paid, abandoned,
                    retries, absorbed, duplicates, parameters(), DISCLAIMER);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Checkout run interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("A simulated checkout failed: " + ex.getCause().getMessage(), ex.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Everything random about a customer, drawn up front from the seed so threads cannot change it. */
    private Customer customer(CheckoutRun.Request request, int index) {
        SplittableRandom random = new SplittableRandom(request.seed() * 1_000_003L + request.phase().hashCode() * 31L + index);
        long minThousands = properties.amountMin().longValue() / 1000;
        long maxThousands = properties.amountMax().longValue() / 1000;
        BigDecimal amount = BigDecimal.valueOf((minThousands + random.nextLong(maxThousands - minThousands + 1)) * 1000);
        long patience = properties.patienceMin().toMillis()
                + random.nextLong(properties.patienceMax().toMillis() - properties.patienceMin().toMillis() + 1);
        double[] leaveDraws = new double[properties.maxAttempts() + 1];
        for (int attempt = 0; attempt < leaveDraws.length; attempt++) {
            leaveDraws[attempt] = random.nextDouble();
        }
        return new Customer(request.phase() + "-" + request.seed() + "-" + index, amount, patience, leaveDraws);
    }

    private Outcome checkout(RestClient client, CheckoutRun.Request request, KeyStrategy keys, Customer customer) {
        int replays = 0;
        int retries = 0;
        String paymentId = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            if (attempt > 1) {
                retries++;
            }
            Created created = create(client, request.merchantApiKey(), key(keys, customer, attempt), customer);
            replays += created.replayed() ? 1 : 0;
            paymentId = created.id();
            start(client, request.merchantApiKey(), paymentId);
            long page = openCheckoutPage(client, paymentId);
            if (page <= customer.patience()) {
                return pay(client, request, keys, customer, paymentId, attempt, replays, retries);
            }
            boolean leaves = customer.leaveDraws()[attempt] < properties.abandonProbability() || attempt == properties.maxAttempts();
            if (leaves) {
                cancelAbandoned(client, request.merchantApiKey(), paymentId);
                return new Outcome(customer.amount(), 0, retries, replays);
            }
        }
        cancelAbandoned(client, request.merchantApiKey(), paymentId);
        return new Outcome(customer.amount(), 0, retries, replays);
    }

    private Outcome pay(RestClient client, CheckoutRun.Request request, KeyStrategy keys, Customer customer, String paymentId,
                        int attempt, int replays, int retries) {
        Paid first = payAtProvider(client, paymentId, customer.amount());
        int charges = first.charged() ? 1 : 0;
        if (first.latencyMillis() > customer.patience()) {
            // The spinner outlasted the customer's patience: they press pay again and the shop re-submits the checkout.
            retries++;
            Created again = create(client, request.merchantApiKey(), key(keys, customer, attempt + 1), customer);
            replays += again.replayed() ? 1 : 0;
            start(client, request.merchantApiKey(), again.id());
            charges += payAtProvider(client, again.id(), customer.amount()).charged() ? 1 : 0;
        }
        return new Outcome(customer.amount(), charges, retries, replays);
    }

    private static String key(KeyStrategy keys, Customer customer, int attempt) {
        return keys == KeyStrategy.PER_CHECKOUT ? "checkout-" + customer.reference() : "checkout-" + customer.reference() + "-try-" + attempt;
    }

    // ---- the shop's calls to this payment system's public API -----------------------------------------

    private Created create(RestClient client, String merchantKey, String idempotencyKey, Customer customer) {
        ResponseEntity<JsonNode> response = client.post().uri("/api/payment-intents")
                .header("Authorization", "Bearer " + merchantKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("merchantReference", customer.reference(), "amount", customer.amount(), "currency", "IDR"))
                .retrieve().toEntity(JsonNode.class);
        return new Created(response.getBody().path("id").asText(), "true".equals(response.getHeaders().getFirst("Idempotent-Replayed")));
    }

    private void start(RestClient client, String merchantKey, String paymentId) {
        client.post().uri("/api/payment-intents/{id}/start", paymentId).header("Authorization", "Bearer " + merchantKey)
                .retrieve().toBodilessEntity();
    }

    private void cancelAbandoned(RestClient client, String merchantKey, String paymentId) {
        client.post().uri("/api/payment-intents/{id}/cancel", paymentId).header("Authorization", "Bearer " + merchantKey)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "ABANDONED"))
                .retrieve().toBodilessEntity();
    }

    // ---- the customer's calls to the provider ---------------------------------------------------------

    private long openCheckoutPage(RestClient client, String paymentId) {
        ResponseEntity<Void> response = client.post().uri("/provider-sim/v1/checkout/{id}", paymentId)
                .header("Authorization", "Bearer " + simulator.apiKey()).retrieve().toBodilessEntity();
        return declaredLatency(response);
    }

    /** The provider charges, then answers (slowly when it is slow). A 409 means it already charged this payment. */
    private Paid payAtProvider(RestClient client, String paymentId, BigDecimal amount) {
        ResponseEntity<Void> response = client.post().uri("/provider-sim/v1/payments/{id}/pay", paymentId)
                .header("Authorization", "Bearer " + simulator.apiKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", amount, "currency", "IDR"))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { })
                .toBodilessEntity();
        return new Paid(response.getStatusCode().is2xxSuccessful(), declaredLatency(response));
    }

    private static long declaredLatency(ResponseEntity<?> response) {
        String declared = response.getHeaders().getFirst("X-Provider-Latency-Ms");
        return declared == null ? 0 : Long.parseLong(declared);
    }

    private Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("amountMin", properties.amountMin());
        parameters.put("amountMax", properties.amountMax());
        parameters.put("patienceMillis", properties.patienceMin().toMillis() + "-" + properties.patienceMax().toMillis());
        parameters.put("abandonProbability", properties.abandonProbability());
        parameters.put("maxAttempts", properties.maxAttempts());
        parameters.put("slowCheckoutPageMillis", properties.slowCheckoutPage().toMillis());
        parameters.put("slowPayMillis", properties.slowPay().toMillis());
        return parameters;
    }

    private record Customer(String reference, BigDecimal amount, long patience, double[] leaveDraws) {
    }

    private record Created(String id, boolean replayed) {
    }

    private record Paid(boolean charged, long latencyMillis) {
    }

    private record Outcome(BigDecimal amount, int charges, int retries, int replays) {
    }
}
