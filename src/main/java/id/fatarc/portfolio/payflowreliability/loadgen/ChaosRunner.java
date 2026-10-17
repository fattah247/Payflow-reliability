package id.fatarc.portfolio.payflowreliability.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementImportService;
import id.fatarc.portfolio.payflowreliability.banksim.BankFaults;
import id.fatarc.portfolio.payflowreliability.banksim.BankSimProperties;
import id.fatarc.portfolio.payflowreliability.operations.MoneyInvariantService;
import id.fatarc.portfolio.payflowreliability.outbox.CommandStatus;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommandRepository;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxWorker;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentStatusSweeper;
import id.fatarc.portfolio.payflowreliability.provider.LocalServer;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.providersim.SimulatorProperties;
import id.fatarc.portfolio.payflowreliability.reconciliation.ReconciliationService;
import id.fatarc.portfolio.payflowreliability.reconciliation.ThreeWayReconciliationService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreakRepository;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A seeded sequence of the same faults the property-based test throws at the system, played against this running
 * application: shoppers, the shop, the provider and the bank act through their HTTP APIs, and the jobs that
 * background workers run on a schedule (provider calls, status sweeps, settlement, statement import, reconciliation)
 * run when the sequence says. Then everything settles down and every money rule is checked.
 *
 * <p>Live, a worker cannot be killed mid-call on demand; the provider committing and then timing out is the same
 * unknown outcome seen from the worker, and is used instead. One seed always plays the same sequence; outcomes that
 * depend on background timing (which webhook retry lands first) can differ, the money rules may not.
 */
@Service
@ConditionalOnProperty(name = "payflow.loadgen.checkout.enabled", havingValue = "true")
public class ChaosRunner {
    static final String DISCLAIMER = "A seeded sequence of simulated faults against this running application. It shows the "
            + "money rules holding after this sequence, not after every possible one; the property-based test explores many more.";
    private static final String ACTOR = "system:chaos-run";
    private static final Duration SETTLE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DUE_SOON = Duration.ofSeconds(30);

    enum Action {
        CREATE(14), RISKY_CREATE(6), PAY(12), DELIVER(5), SWEEP(3), REFUND(6), RUN_OUTBOX(6), SETTLE(7), CHARGEBACK(2),
        BANK_FAULT(5), CLOSE_DAY(5), PULL_STATEMENTS(5), REIMPORT(2), PROVIDER_REPORT(2), THREE_WAY(3), SLOW(3), ABANDON(2);

        private final int weight;

        Action(int weight) {
            this.weight = weight;
        }
    }

    /**
     * @param seed           same seed, same sequence
     * @param steps          number of actions (default 150, at most 1000)
     * @param merchantApiKey the shop whose payments the sequence makes
     */
    public record Request(long seed, Integer steps, String merchantApiKey) {
    }

    /**
     * @param actions    how often each action was played (decided by the seed alone)
     * @param faults     the faults injected, by kind (decided by the seed alone)
     * @param payments   this run's payments by final state
     * @param payouts    this run's payouts by status (and reason, when broken)
     * @param breaks     reconciliation breaks about this run's payouts, by category and status
     * @param invariants every money rule, checked once everything settled down
     */
    public record Report(long seed, int steps, String merchantId, Map<String, Integer> actions, Map<String, Integer> faults,
                         Map<String, Long> payments, Map<String, Long> payouts, Map<String, Long> breaks,
                         MoneyInvariantService.Report invariants, String disclaimer) {
    }

    private final LocalServer localServer;
    private final RestClient.Builder builder;
    private final SimulatorProperties simulator;
    private final BankSimProperties bank;
    private final SettlementService settlementService;
    private final OutboxWorker outboxWorker;
    private final OutboxCommandRepository outboxCommands;
    private final PaymentStatusSweeper sweeper;
    private final BankStatementImportService statementImport;
    private final ReconciliationService reconciliationService;
    private final ThreeWayReconciliationService threeWay;
    private final MoneyInvariantService invariants;
    private final PaymentIntentRepository paymentIntents;
    private final SettlementBatchRepository batches;
    private final ReconciliationBreakRepository breaks;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChaosRunner(LocalServer localServer, RestClient.Builder builder, SimulatorProperties simulator, BankSimProperties bank,
                       SettlementService settlementService, OutboxWorker outboxWorker, OutboxCommandRepository outboxCommands,
                       PaymentStatusSweeper sweeper, BankStatementImportService statementImport,
                       ReconciliationService reconciliationService, ThreeWayReconciliationService threeWay,
                       MoneyInvariantService invariants, PaymentIntentRepository paymentIntents,
                       SettlementBatchRepository batches, ReconciliationBreakRepository breaks, ObjectMapper objectMapper,
                       Clock clock) {
        this.localServer = localServer;
        this.builder = builder;
        this.simulator = simulator;
        this.bank = bank;
        this.settlementService = settlementService;
        this.outboxWorker = outboxWorker;
        this.outboxCommands = outboxCommands;
        this.sweeper = sweeper;
        this.statementImport = statementImport;
        this.reconciliationService = reconciliationService;
        this.threeWay = threeWay;
        this.invariants = invariants;
        this.paymentIntents = paymentIntents;
        this.batches = batches;
        this.breaks = breaks;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Report run(Request request) {
        int steps = request.steps() == null ? 150 : Math.max(1, Math.min(1000, request.steps()));
        Run run = new Run(request.seed(), request.merchantApiKey(), builder.clone().baseUrl(localServer.baseUrl())
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build());
        SplittableRandom random = new SplittableRandom(request.seed());
        int totalWeight = 0;
        for (Action action : Action.values()) {
            totalWeight += action.weight;
        }
        try {
            for (int step = 0; step < steps; step++) {
                int draw = random.nextInt(totalWeight);
                Action action = Action.values()[0];
                for (Action candidate : Action.values()) {
                    draw -= candidate.weight;
                    if (draw < 0) {
                        action = candidate;
                        break;
                    }
                }
                run.actions.merge(action.name(), 1, Integer::sum);
                try {
                    run.play(action, step, random);
                } catch (RuntimeException refused) {
                    // A refusal (nothing to settle, not refundable, already paid) is part of the story, not a failure.
                }
            }
        } finally {
            run.settleDown();
        }
        return run.report(steps);
    }

    /** One run's state: what it created, paid and settled, and what it injected. */
    private final class Run {
        private final long seed;
        private final String merchantKey;
        private final RestClient http;
        private final Map<String, Integer> actions = new TreeMap<>();
        private final Map<String, Integer> faults = new TreeMap<>();
        private final List<UUID> payments = new ArrayList<>();
        private final List<UUID> unpaid = new ArrayList<>();
        private final List<UUID> paid = new ArrayList<>();
        private String merchantId;

        Run(long seed, String merchantKey, RestClient http) {
            this.seed = seed;
            this.merchantKey = merchantKey;
            this.http = http;
        }

        void play(Action action, int step, SplittableRandom random) {
            switch (action) {
                case CREATE -> create(step, 500L * (1 + random.nextInt(40)), random.nextInt(3), null, false, false);
                case RISKY_CREATE -> create(step, 500L * (1 + random.nextInt(40)), 0, new String[]{"CLEAN", "STEP_UP", "STEP_UP", "BLOCK"}[random.nextInt(4)],
                        random.nextInt(3) > 0, random.nextInt(4) == 0);
                case PAY -> pay(random.nextInt(1000), 1 + random.nextInt(3), random.nextInt(3) == 0, random.nextBoolean());
                case DELIVER -> deliver();
                case SWEEP -> sweeper.runOnce(Duration.ZERO);
                case REFUND -> refund(step, random.nextInt(1000), 10 + random.nextInt(91),
                        new SimFaults.Mode[]{SimFaults.Mode.NONE, SimFaults.Mode.NONE, SimFaults.Mode.UNAVAILABLE,
                                SimFaults.Mode.TIMEOUT_AFTER_COMMIT, SimFaults.Mode.REJECT}[random.nextInt(5)]);
                case RUN_OUTBOX -> outboxWorker.runOnce();
                case SETTLE -> settleRacing(1 + random.nextInt(3));
                case CHARGEBACK -> chargeback(random.nextInt(1000));
                case BANK_FAULT -> bankFault(BankFaults.Mode.values()[random.nextInt(BankFaults.Mode.values().length)], step);
                case CLOSE_DAY -> closeBankDays(null);
                case PULL_STATEMENTS -> statementImport.pull(ACTOR);
                case REIMPORT -> reimport(random.nextInt(1000));
                case PROVIDER_REPORT -> providerReport();
                case THREE_WAY -> threeWay.run(ACTOR);
                case SLOW -> slow(random.nextBoolean());
                case ABANDON -> abandon(random.nextInt(1000));
            }
        }

        // ---- the shop and its customers ----------------------------------------------------------

        private JsonNode asMerchant(String method, String path, Object body, String idempotencyKey) {
            RestClient.RequestBodySpec spec = http.method(HttpMethod.valueOf(method)).uri(path)
                    .header("Authorization", "Bearer " + merchantKey).contentType(MediaType.APPLICATION_JSON);
            if (idempotencyKey != null) {
                spec.header("Idempotency-Key", idempotencyKey);
            }
            if (body != null) {
                spec.body(body);
            }
            return spec.retrieve().body(JsonNode.class);
        }

        private JsonNode asProvider(String method, String path, Object body) {
            RestClient.RequestBodySpec spec = http.method(HttpMethod.valueOf(method)).uri(path)
                    .header("Authorization", "Bearer " + simulator.apiKey()).contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.body(body);
            }
            return spec.retrieve().body(JsonNode.class);
        }

        private String asBank(String method, String path, Object body) {
            RestClient.RequestBodySpec spec = http.method(HttpMethod.valueOf(method)).uri(path)
                    .header("Authorization", "Bearer " + bank.apiKey()).contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.body(body);
            }
            return spec.retrieve().body(String.class);
        }

        /** A checkout, created once and retried under the same key; with {@code device}, with device-risk evidence. */
        private void create(int step, long amount, int retries, String device, boolean confirm, boolean rogue) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("merchantReference", "CHAOS-" + seed + "-" + step);
            body.put("amount", amount);
            body.put("currency", "IDR");
            if (device != null) {
                List<Map<String, String>> signals = switch (device) {
                    case "STEP_UP" -> List.of(Map.of("id", "emulator_fingerprint", "category", "EMULATOR", "severity", "MEDIUM",
                            "source", "DEVICE_CHECK"));
                    case "BLOCK" -> List.of(Map.of("id", "root_su_path", "category", "ROOT", "severity", "HIGH", "source", "DEVICE_CHECK"));
                    default -> List.of();
                };
                body.put("riskEvidence", Map.of("simulated", true, "stepUpKeyAvailable", step % 2 == 0, "signals", signals));
            }
            JsonNode created = null;
            for (int attempt = 0; attempt <= retries; attempt++) {
                created = asMerchant("POST", "/api/payment-intents", body, "chaos-" + seed + "-" + step);
            }
            UUID id = UUID.fromString(created.path("id").asText());
            merchantId = created.path("merchantId").asText();
            payments.add(id);
            String state = created.path("state").asText();
            if (device != null && !"CREATED".equals(state)) {
                faults.merge("device risk: " + state, 1, Integer::sum);
                if (rogue) {
                    // The provider charges it anyway: the capture must go to review, never be applied.
                    asProvider("POST", "/provider-sim/v1/payments/" + id + "/pay", Map.of("amount", amount, "currency", "IDR"));
                    faults.merge("provider charged a blocked or unconfirmed payment", 1, Integer::sum);
                    deliver();
                    return;
                }
                if (!"REQUIRES_CONFIRMATION".equals(state) || !confirm) {
                    return;
                }
                asMerchant("POST", "/api/payment-intents/" + id + "/confirm",
                        Map.of("method", created.path("requiredConfirmation").asText()), null);
            }
            JsonNode started = asMerchant("POST", "/api/payment-intents/" + id + "/start", null, null);
            if ("PENDING".equals(started.path("state").asText())) {
                unpaid.add(id);
            }
        }

        private void pay(int pick, int deliveries, boolean lost, boolean authorizeFirst) {
            if (unpaid.isEmpty()) {
                return;
            }
            UUID id = unpaid.remove(pick % unpaid.size());
            PaymentIntent intent = paymentIntents.findById(id).orElseThrow();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", intent.getAmount());
            body.put("currency", "IDR");
            body.put("deliverWebhook", !lost);
            body.put("deliveries", deliveries);
            body.put("authorizeFirst", authorizeFirst);
            asProvider("POST", "/provider-sim/v1/payments/" + id + "/pay", body);
            paid.add(id);
            if (lost) {
                faults.merge("webhook lost", 1, Integer::sum);
            }
            if (deliveries > 1) {
                faults.merge("webhook duplicated", 1, Integer::sum);
            }
            deliver();
        }

        private void deliver() {
            asProvider("POST", "/provider-sim/v1/webhooks/dispatch", null);
        }

        private void refund(int step, int pick, int percent, SimFaults.Mode fault) {
            if (paid.isEmpty()) {
                return;
            }
            PaymentIntent intent = paymentIntents.findById(paid.get(pick % paid.size())).orElseThrow();
            BigDecimal amount = intent.getAmount().multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 0,
                    RoundingMode.DOWN);
            if (fault != SimFaults.Mode.NONE) {
                asProvider("POST", "/provider-sim/v1/faults", Map.of("operation", "REFUND", "mode", fault,
                        "times", fault == SimFaults.Mode.UNAVAILABLE ? 2 : 1));
                faults.merge("provider refund " + fault, 1, Integer::sum);
            }
            asMerchant("POST", "/api/refunds/payment-intents/" + intent.getId(),
                    Map.of("amount", amount, "reason", "Customer request"), "chaos-refund-" + seed + "-" + step);
        }

        private void chargeback(int pick) {
            if (paid.isEmpty()) {
                return;
            }
            asProvider("POST", "/provider-sim/v1/payments/" + paid.get(pick % paid.size()) + "/chargeback", null);
            faults.merge("chargeback", 1, Integer::sum);
            deliver();
        }

        private void abandon(int pick) {
            List<UUID> candidates = new ArrayList<>(unpaid);
            candidates.addAll(paid);
            if (candidates.isEmpty()) {
                return;
            }
            UUID id = candidates.get(pick % candidates.size());
            unpaid.remove(id);
            faults.merge("checkout abandoned", 1, Integer::sum);
            asMerchant("POST", "/api/payment-intents/" + id + "/cancel", Map.of("reason", "ABANDONED"), null);
        }

        // ---- the platform's jobs -----------------------------------------------------------------

        /** Settlement runs for this merchant released together; the payout goes out at once. */
        private void settleRacing(int racers) {
            if (merchantId == null) {
                return;
            }
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            try {
                List<Future<?>> runs = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    runs.add(pool.submit(() -> settlementService.createBatch(merchantId, "IDR", Caller.system("chaos-settlement"))));
                }
                for (Future<?> settled : runs) {
                    try {
                        settled.get();
                    } catch (Exception nothingToSettle) {
                        // only one run can win; the others find nothing left
                    }
                }
            } finally {
                pool.shutdown();
            }
            if (racers > 1) {
                faults.merge("racing settlement runs", 1, Integer::sum);
            }
            outboxWorker.runOnce();
        }

        private void providerReport() {
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
            JsonNode report = asProvider("GET", "/provider-sim/v1/reports/settlement?date=" + today, null);
            reconciliationService.importReport(objectMapper.convertValue(report, ReconciliationReportRequest.class),
                    Caller.system("chaos-provider-report"));
        }

        // ---- the bank and the provider -----------------------------------------------------------

        private void bankFault(BankFaults.Mode mode, int step) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", mode);
            body.put("times", 1);
            if (mode == BankFaults.Mode.LATE_CREDIT) {
                body.put("days", 1 + step % 4);
            }
            if (mode == BankFaults.Mode.NET_OF_FEES) {
                body.put("amount", new BigDecimal("6.50"));
            }
            asBank("POST", "/bank-sim/v1/faults", body);
            faults.merge("bank " + mode, 1, Integer::sum);
        }

        private void closeBankDays(LocalDate through) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("currency", "IDR");
            body.put("through", through);
            asBank("POST", "/bank-sim/v1/days/close", body);
        }

        private void reimport(int pick) {
            JsonNode accounts;
            try {
                accounts = objectMapper.readTree(asBank("GET", "/bank-sim/v1/accounts", null));
            } catch (Exception ex) {
                return;
            }
            List<String> issued = new ArrayList<>();
            for (JsonNode account : accounts) {
                try {
                    objectMapper.readTree(asBank("GET", "/bank-sim/v1/accounts/" + account.path("accountId").asText() + "/statements", null))
                            .forEach(statement -> issued.add(statement.path("statementId").asText()));
                } catch (Exception ex) {
                    return;
                }
            }
            if (issued.isEmpty()) {
                return;
            }
            String xml = asBank("GET", "/bank-sim/v1/statements/" + issued.get(pick % issued.size()), null);
            statementImport.importStatement(xml.getBytes(StandardCharsets.UTF_8), ACTOR);
            faults.merge("statement imported again", 1, Integer::sum);
        }

        private void slow(boolean on) {
            if (on) {
                asProvider("PUT", "/provider-sim/v1/latency", Map.of("checkoutPageMillis", 4000, "payMillis", 7000, "holdWebhooks", true));
                faults.merge("provider slow, webhooks held", 1, Integer::sum);
            } else {
                asProvider("DELETE", "/provider-sim/v1/latency", null);
            }
        }

        // ---- settling down -----------------------------------------------------------------------

        /**
         * Lets every pending effect play out: the provider recovers, webhooks and provider calls finish, lost results
         * are swept, the shop's captures are settled and paid out, the bank closes through two days past every payout's
         * value-date window, statements are imported, the provider's report arrives, and reconciliation runs.
         */
        void settleDown() {
            asProvider("DELETE", "/provider-sim/v1/latency", null);
            asProvider("DELETE", "/provider-sim/v1/faults", null);
            asBank("DELETE", "/bank-sim/v1/faults", null);
            deliver();
            drainProviderCalls();
            sweeper.runOnce(Duration.ZERO);
            deliver();
            if (merchantId != null) {
                try {
                    settlementService.createBatch(merchantId, "IDR", Caller.system("chaos-settlement"));
                } catch (RuntimeException nothingToSettle) {
                    // everything captured is already in a batch
                }
            }
            drainProviderCalls();
            LocalDate through = null;
            for (SettlementBatch batch : ownBatches()) {
                if (batch.getPayoutStatus() == PayoutStatus.IN_TRANSIT && batch.getExpectedValueDate() != null) {
                    LocalDate windowEnd = batch.getExpectedValueDate().plusDays(4);
                    through = through == null || windowEnd.isAfter(through) ? windowEnd : through;
                }
            }
            closeBankDays(through);
            statementImport.pull(ACTOR);
            providerReport();
            threeWay.run(ACTOR);
        }

        /**
         * Runs provider calls until none is due soon. A call parked behind a review hold waits for a person, not for
         * time, so it is left for the review.
         */
        private void drainProviderCalls() {
            Instant deadline = Instant.now().plus(SETTLE_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                outboxWorker.runOnce();
                Instant soon = clock.instant().plus(DUE_SOON);
                if (outboxCommands.findByStatusOrderByCreatedAtAsc(CommandStatus.PENDING).stream()
                        .noneMatch(command -> command.getNextAttemptAt() == null || command.getNextAttemptAt().isBefore(soon))) {
                    return;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private List<SettlementBatch> ownBatches() {
            return merchantId == null ? List.of() : batches.findByPayoutStatusInOrderByCreatedAtAsc(EnumSet.allOf(PayoutStatus.class))
                    .stream().filter(batch -> batch.getMerchantId().equals(merchantId)).toList();
        }

        Report report(int steps) {
            Map<String, Long> byState = new TreeMap<>();
            for (UUID id : payments) {
                paymentIntents.findById(id).ifPresent(intent -> byState.merge(intent.getState().name(), 1L, Long::sum));
            }
            Map<String, Long> byPayout = new TreeMap<>();
            List<UUID> batchIds = new ArrayList<>();
            for (SettlementBatch batch : ownBatches()) {
                batchIds.add(batch.getId());
                String status = batch.getPayoutStatus() + (batch.getBrokenReason() == null ? "" : " (" + batch.getBrokenReason() + ")");
                byPayout.merge(status, 1L, Long::sum);
            }
            Map<String, Long> byBreak = new TreeMap<>();
            for (ReconciliationBreak open : breaks.findAll()) {
                if (open.getSettlementBatchId() != null && batchIds.contains(open.getSettlementBatchId())) {
                    byBreak.merge(open.getCategory() + " " + open.getStatus(), 1L, Long::sum);
                }
            }
            return new Report(seed, steps, merchantId, actions, faults, byState, byPayout, byBreak, invariants.check(), DISCLAIMER);
        }
    }
}
