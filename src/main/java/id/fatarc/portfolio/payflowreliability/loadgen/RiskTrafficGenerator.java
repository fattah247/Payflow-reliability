package id.fatarc.portfolio.payflowreliability.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.provider.LocalServer;
import id.fatarc.portfolio.payflowreliability.providersim.SimulatorProperties;
import id.fatarc.portfolio.payflowreliability.risk.RiskAction;
import id.fatarc.portfolio.payflowreliability.risk.RiskLabel;
import id.fatarc.portfolio.payflowreliability.risk.RiskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Labelled traffic for the device-risk gate: seeded customers, each known to the generator to be honest or a
 * fraudster, with the device evidence a trustgate-android app would send, through the public API. After the run the
 * generator discloses its ground truth as labels (source SIMULATION_GROUND_TRUTH), so the payment system can measure
 * precision, recall, fraud stopped and friction from its own decisions.
 *
 * <p>Honest customers mostly have clean phones, ordinary amounts and physical goods; fraudsters more often have rooted
 * or emulated devices, unattested keys, larger amounts and goods that turn into cash. The groups overlap on purpose:
 * much fraud comes from clean phones buying ordinary goods (account takeover), some honest customers have rooted
 * phones, and a quarter of fraudsters get through a step-up (a victim talked into confirming). That overlap is what
 * makes every threshold a trade-off. The weights below are illustrative, not measured.
 */
@Service
@ConditionalOnProperty(name = "payflow.loadgen.checkout.enabled", havingValue = "true")
public class RiskTrafficGenerator {
    static final String DISCLAIMER = "Simulated, labelled traffic. Device profiles, amounts, fraud share and step-up completion "
            + "are model assumptions (payflow.loadgen.risk.* and RiskTrafficGenerator), not observed fraud data.";

    /** A device as the app reports it: the detected signals only (id, category, severity, source). */
    enum Profile {
        CLEAN,
        UNKNOWN_INSTALLER("installer_source_unknown:INSTALLER:LOW:DEVICE_CHECK"),
        DEBUGGABLE_BUILD("app_debuggable:DEBUGGER:MEDIUM:DEVICE_CHECK"),
        UNATTESTED_KEY("key_unattested:ATTESTATION:MEDIUM:KEY_ATTESTATION"),
        EMULATOR("emulator_fingerprint:EMULATOR:MEDIUM:DEVICE_CHECK", "installer_source_unknown:INSTALLER:LOW:DEVICE_CHECK"),
        ROOTED("root_package:ROOT:HIGH:DEVICE_CHECK", "test_keys:ROOT:MEDIUM:DEVICE_CHECK"),
        DEBUGGER_ATTACHED("debugger_connected:DEBUGGER:HIGH:DEVICE_CHECK");

        private final List<String> signals;

        Profile(String... signals) {
            this.signals = List.of(signals);
        }
    }

    private static final Map<Profile, Integer> LEGITIMATE_DEVICES = weights(
            Profile.CLEAN, 72, Profile.UNKNOWN_INSTALLER, 16, Profile.UNATTESTED_KEY, 5, Profile.EMULATOR, 3,
            Profile.DEBUGGABLE_BUILD, 2, Profile.ROOTED, 2);
    private static final Map<Profile, Integer> FRAUD_DEVICES = weights(
            Profile.CLEAN, 30, Profile.UNKNOWN_INSTALLER, 12, Profile.UNATTESTED_KEY, 12, Profile.EMULATOR, 18,
            Profile.DEBUGGABLE_BUILD, 4, Profile.ROOTED, 18, Profile.DEBUGGER_ATTACHED, 6);
    private static final Map<RiskAction, Integer> LEGITIMATE_ACTIONS = weights(
            RiskAction.PHYSICAL_GOODS, 70, RiskAction.DIGITAL_GOODS, 20, RiskAction.STORED_VALUE, 10);
    private static final Map<RiskAction, Integer> FRAUD_ACTIONS = weights(
            RiskAction.PHYSICAL_GOODS, 35, RiskAction.DIGITAL_GOODS, 30, RiskAction.STORED_VALUE, 35);

    /**
     * @param seed           same seed, same customers and choices
     * @param phase          names the run; references are {@code <phase>-<seed>-<n>}
     * @param merchantApiKey the merchant the simulated shop uses
     * @param customers      overrides the configured number
     */
    public record Request(long seed, String phase, String merchantApiKey, Integer customers) {
    }

    public record Tally(long count, BigDecimal amount) {
        static Tally zero() {
            return new Tally(0, BigDecimal.ZERO);
        }

        Tally plus(BigDecimal value) {
            return new Tally(count + 1, amount.add(value));
        }
    }

    /** What the generator knows: who was honest, who was not, and what happened to each group. */
    public record Report(String phase, long seed, int customers, Tally legitimate, Tally fraud, Map<String, Tally> legitimateBy,
                         Map<String, Tally> fraudBy, Map<String, Object> parameters, String disclaimer) {
    }

    private final RiskTrafficProperties properties;
    private final RiskService riskService;
    private final SimulatorProperties simulator;
    private final LocalServer localServer;
    private final RestClient.Builder builder;

    public RiskTrafficGenerator(RiskTrafficProperties properties, RiskService riskService, SimulatorProperties simulator,
                                LocalServer localServer, RestClient.Builder builder) {
        this.properties = properties;
        this.riskService = riskService;
        this.simulator = simulator;
        this.localServer = localServer;
        this.builder = builder;
    }

    public Report run(Request request) {
        int customers = request.customers() == null ? properties.customers() : request.customers();
        RestClient client = builder.clone().baseUrl(localServer.baseUrl()).build();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, properties.concurrency()));
        List<Future<Result>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < customers; index++) {
                Customer customer = customer(request, index);
                futures.add(pool.submit(() -> checkout(client, request.merchantApiKey(), customer)));
            }
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get());
            }
            // Time passes: step-ups nobody confirmed expire. Then the generator discloses its ground truth.
            riskService.expireUnconfirmed(Duration.ZERO);
            Tally legitimate = Tally.zero();
            Tally fraud = Tally.zero();
            Map<String, Tally> legitimateBy = new LinkedHashMap<>();
            Map<String, Tally> fraudBy = new LinkedHashMap<>();
            for (Result result : results) {
                boolean isFraud = result.customer().fraud();
                riskService.label(result.paymentId(), isFraud ? RiskLabel.FRAUD : RiskLabel.LEGITIMATE,
                        RiskLabel.Source.SIMULATION_GROUND_TRUTH, "system:traffic-generator");
                BigDecimal amount = result.customer().amount();
                if (isFraud) {
                    fraud = fraud.plus(amount);
                    fraudBy.merge(result.what(), Tally.zero().plus(amount), (a, b) -> new Tally(a.count() + 1, a.amount().add(amount)));
                } else {
                    legitimate = legitimate.plus(amount);
                    legitimateBy.merge(result.what(), Tally.zero().plus(amount), (a, b) -> new Tally(a.count() + 1, a.amount().add(amount)));
                }
            }
            return new Report(request.phase(), request.seed(), customers, legitimate, fraud, legitimateBy, fraudBy, parameters(),
                    DISCLAIMER);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Risk traffic interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("A simulated customer failed: " + ex.getCause().getMessage(), ex.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private Customer customer(Request request, int index) {
        SplittableRandom random = new SplittableRandom(request.seed() * 7_919L + request.phase().hashCode() * 31L + index);
        boolean fraud = random.nextDouble() < properties.fraudRate();
        Profile profile = pick(random, fraud ? FRAUD_DEVICES : LEGITIMATE_DEVICES);
        RiskAction action = pick(random, fraud ? FRAUD_ACTIONS : LEGITIMATE_ACTIONS);
        long thousands;
        if (fraud) {
            thousands = 100 + random.nextLong(4_901);
        } else if (random.nextDouble() < 0.10) {
            thousands = 1_000 + random.nextLong(5_001);
        } else {
            thousands = 30 + random.nextLong(1_171);
        }
        boolean stepUpKey = random.nextDouble() < (fraud ? 0.30 : 0.80);
        boolean wouldConfirm = random.nextDouble() < (fraud ? properties.fraudulentConfirmRate() : properties.legitimateConfirmRate());
        return new Customer(request.phase() + "-" + request.seed() + "-" + index, fraud, profile, action,
                BigDecimal.valueOf(thousands * 1000), stepUpKey, wouldConfirm);
    }

    /** The shop sends the device's evidence; what happens next depends only on the decision and the customer. */
    private Result checkout(RestClient client, String merchantKey, Customer customer) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("level", null);
        evidence.put("simulated", true);
        evidence.put("stepUpKeyAvailable", customer.stepUpKey());
        evidence.put("signals", customer.profile().signals.stream().map(signal -> {
            String[] parts = signal.split(":");
            return Map.of("id", parts[0], "category", parts[1], "severity", parts[2], "source", parts[3]);
        }).toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantReference", customer.reference());
        body.put("amount", customer.amount());
        body.put("currency", "IDR");
        body.put("action", customer.action());
        body.put("riskEvidence", evidence);
        JsonNode created = client.post().uri("/api/payment-intents")
                .header("Authorization", "Bearer " + merchantKey)
                .header("Idempotency-Key", "checkout-" + customer.reference())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        UUID paymentId = UUID.fromString(created.path("id").asText());
        String state = created.path("state").asText();
        if ("BLOCKED".equals(state)) {
            return new Result(customer, paymentId, "blocked");
        }
        String what = "allowed";
        if ("REQUIRES_CONFIRMATION".equals(state)) {
            if (!customer.wouldConfirm()) {
                return new Result(customer, paymentId, "stepped up, not confirmed");
            }
            client.post().uri("/api/payment-intents/{id}/confirm", paymentId).header("Authorization", "Bearer " + merchantKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("method", customer.stepUpKey() ? "STEP_UP_KEY" : "TAP")).retrieve().toBodilessEntity();
            what = "stepped up, confirmed";
        }
        client.post().uri("/api/payment-intents/{id}/start", paymentId).header("Authorization", "Bearer " + merchantKey)
                .retrieve().toBodilessEntity();
        client.post().uri("/provider-sim/v1/payments/{id}/pay", paymentId).header("Authorization", "Bearer " + simulator.apiKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", customer.amount(), "currency", "IDR"))
                .retrieve().toBodilessEntity();
        return new Result(customer, paymentId, what);
    }

    private Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("fraudRate", properties.fraudRate());
        parameters.put("legitimateConfirmRate", properties.legitimateConfirmRate());
        parameters.put("fraudulentConfirmRate", properties.fraudulentConfirmRate());
        parameters.put("legitimateDevices", LEGITIMATE_DEVICES);
        parameters.put("fraudDevices", FRAUD_DEVICES);
        parameters.put("legitimateActions", LEGITIMATE_ACTIONS);
        parameters.put("fraudActions", FRAUD_ACTIONS);
        return parameters;
    }

    private static <T> T pick(SplittableRandom random, Map<T, Integer> weights) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int draw = random.nextInt(total);
        for (Map.Entry<T, Integer> entry : weights.entrySet()) {
            draw -= entry.getValue();
            if (draw < 0) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("weights");
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<T, Integer> weights(Object... pairs) {
        Map<T, Integer> weights = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            weights.put((T) pairs[i], (Integer) pairs[i + 1]);
        }
        return weights;
    }

    private record Customer(String reference, boolean fraud, Profile profile, RiskAction action, BigDecimal amount,
                            boolean stepUpKey, boolean wouldConfirm) {
    }

    private record Result(Customer customer, UUID paymentId, String what) {
    }
}
