package id.fatarc.portfolio.payflowreliability.providersim;

import id.fatarc.portfolio.payflowreliability.common.Hashing;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The provider's HTTP API (as a real provider would expose it) plus simulation controls for what
 * happens in the outside world. Authenticated with the provider API key, not the platform's keys.
 */
@RestController
@RequestMapping("/provider-sim/v1")
@ConditionalOnProperty(name = "payflow.provider-sim.enabled", havingValue = "true")
public class SimController {
    private final ProviderSimulator simulator;
    private final SimWebhookDispatcher dispatcher;
    private final SimFaults faults;
    private final SimulatorProperties properties;
    private final SimRequestLog requestLog;
    private final SimLatency latency;

    public SimController(ProviderSimulator simulator, SimWebhookDispatcher dispatcher, SimFaults faults,
                         SimulatorProperties properties, SimRequestLog requestLog, SimLatency latency) {
        this.latency = latency;
        this.simulator = simulator;
        this.dispatcher = dispatcher;
        this.faults = faults;
        this.properties = properties;
        this.requestLog = requestLog;
    }

    public record CustomerAction(BigDecimal amount, String currency, Boolean deliverWebhook, Integer deliveries,
                                 BigDecimal webhookAmount, Boolean authorizeFirst) {
        ProviderSimulator.CustomerOptions options() {
            return new ProviderSimulator.CustomerOptions(deliverWebhook, deliveries, webhookAmount, authorizeFirst);
        }
    }

    public record RefundCall(String providerTransactionId, BigDecimal amount, String currency, String reference) {
    }

    public record ReversalCall(String providerTransactionId, String reference) {
    }

    public record PayoutCall(String reference, String merchantId, BigDecimal amount, String currency,
                             List<String> providerTransactionIds) {
    }

    public record FaultRequest(SimFaults.Operation operation, SimFaults.Mode mode, Integer times) {
    }

    /**
     * @param checkoutPageMillis how long the hosted payment page takes to load
     * @param payMillis          how long the provider takes to answer a payment (it has already charged)
     * @param holdWebhooks       keep webhooks in the provider's backlog until it recovers
     */
    public record SlowRequest(Long checkoutPageMillis, Long payMillis, Boolean holdWebhooks) {
    }

    // ---- simulation controls: the customer and the outside world -------------------------------

    /** The customer opens the provider's hosted payment page. Slow when the provider is slow; nothing is charged. */
    @PostMapping("/checkout/{paymentIntentId}")
    public ResponseEntity<Map<String, Object>> checkoutPage(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                            @PathVariable UUID paymentIntentId) throws InterruptedException {
        authenticate(authorization);
        long declared = latency.checkoutPage();
        return ResponseEntity.ok().header("X-Provider-Latency-Ms", String.valueOf(declared))
                .body(Map.of("paymentIntentId", paymentIntentId, "latencyMillis", declared));
    }

    @PutMapping("/latency")
    public Map<String, Object> slowDown(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody SlowRequest request) {
        authenticate(authorization);
        latency.slow(new SimLatency.Mode(request.checkoutPageMillis() == null ? 0 : request.checkoutPageMillis(),
                request.payMillis() == null ? 0 : request.payMillis(), Boolean.TRUE.equals(request.holdWebhooks())));
        return latencyView();
    }

    /** Back to normal; webhooks held in the backlog are sent on the next dispatch. */
    @DeleteMapping("/latency")
    public Map<String, Object> recover(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        latency.recover();
        return latencyView();
    }

    private Map<String, Object> latencyView() {
        SimLatency.Mode mode = latency.mode();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("slow", mode.slow());
        view.put("checkoutPageMillis", mode.checkoutPageMillis());
        view.put("payMillis", mode.payMillis());
        view.put("holdWebhooks", mode.holdWebhooks());
        return view;
    }

    @PostMapping("/payments/{paymentIntentId}/{action}")
    public ResponseEntity<Map<String, Object>> customer(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                        @PathVariable UUID paymentIntentId, @PathVariable String action,
                                                        @RequestBody(required = false) CustomerAction body) throws InterruptedException {
        authenticate(authorization);
        CustomerAction request = body == null ? new CustomerAction(null, null, null, null, null, null) : body;
        SimPayment payment = switch (action) {
            case "pay" -> simulator.pay(paymentIntentId, required(request.amount()), requiredCurrency(request.currency()), request.options());
            case "authorize" -> simulator.authorize(paymentIntentId, required(request.amount()), requiredCurrency(request.currency()), request.options());
            case "capture" -> simulator.capture(paymentIntentId, request.options());
            case "decline" -> simulator.decline(paymentIntentId, required(request.amount()), requiredCurrency(request.currency()), request.options());
            case "expire" -> simulator.expire(paymentIntentId, request.options());
            case "chargeback" -> simulator.chargeback(paymentIntentId, request.options());
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown action " + action);
        };
        // The provider has already charged; a slow provider is only slow to say so.
        long declared = "pay".equals(action) ? latency.pay() : 0;
        return ResponseEntity.ok().header("X-Provider-Latency-Ms", String.valueOf(declared)).body(view(payment));
    }

    @GetMapping("/payments/{paymentIntentId}/webhooks")
    public List<SimWebhookDelivery> webhooks(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable UUID paymentIntentId) {
        authenticate(authorization);
        return simulator.deliveries(paymentIntentId);
    }

    @PostMapping("/webhooks/dispatch")
    public Map<String, Object> dispatch(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        return Map.of("attempts", dispatcher.runOnce());
    }

    @PostMapping("/faults")
    public Map<String, Object> injectFault(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody FaultRequest request) {
        authenticate(authorization);
        int times = request.times() == null ? 1 : request.times();
        faults.inject(request.operation(), request.mode(), times);
        return Map.of("operation", request.operation(), "mode", request.mode(), "times", times);
    }

    @DeleteMapping("/faults")
    public ResponseEntity<Void> clearFaults(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        faults.clear();
        return ResponseEntity.noContent().build();
    }

    // ---- the provider API this platform calls ----------------------------------------------------

    @GetMapping("/payments/{paymentIntentId}")
    public Map<String, Object> payment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable UUID paymentIntentId) {
        authenticate(authorization);
        if (faults.next(SimFaults.Operation.STATUS) == SimFaults.Mode.UNAVAILABLE) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated outage");
        }
        return simulator.payment(paymentIntentId).map(SimController::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown payment"));
    }

    @PostMapping("/refunds")
    public ResponseEntity<Map<String, Object>> refund(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody RefundCall call) throws InterruptedException {
        authenticate(authorization);
        requireKey(idempotencyKey);
        SimFaults.Mode fault = faults.next(SimFaults.Operation.REFUND);
        String hash = Hashing.sha256Hex("refund|" + call.providerTransactionId() + "|" + call.amount().stripTrailingZeros().toPlainString()
                + "|" + call.currency() + "|" + call.reference());
        unavailableIf(fault, "REFUND", idempotencyKey, hash);
        SimOperation operation = logged("REFUND", idempotencyKey, hash, () -> simulator.refund(idempotencyKey, hash,
                call.providerTransactionId(), call.amount(), call.currency(), fault == SimFaults.Mode.REJECT));
        lateIf(fault);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(operation));
    }

    @GetMapping("/refunds/{idempotencyKey}")
    public Map<String, Object> findRefund(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String idempotencyKey) {
        authenticate(authorization);
        lookupFault();
        return simulator.find(SimOperation.Type.REFUND, idempotencyKey).map(SimController::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown refund"));
    }

    @PostMapping("/reversals")
    public ResponseEntity<Map<String, Object>> reverse(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReversalCall call) throws InterruptedException {
        authenticate(authorization);
        requireKey(idempotencyKey);
        SimFaults.Mode fault = faults.next(SimFaults.Operation.REVERSAL);
        String hash = Hashing.sha256Hex("reversal|" + call.providerTransactionId() + "|" + call.reference());
        unavailableIf(fault, "REVERSAL", idempotencyKey, hash);
        SimOperation operation = logged("REVERSAL", idempotencyKey, hash, () -> simulator.reverse(idempotencyKey, hash,
                call.providerTransactionId(), fault == SimFaults.Mode.REJECT));
        lateIf(fault);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(operation));
    }

    @GetMapping("/reversals/{idempotencyKey}")
    public Map<String, Object> findReversal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable String idempotencyKey) {
        authenticate(authorization);
        lookupFault();
        return simulator.find(SimOperation.Type.REVERSAL, idempotencyKey).map(SimController::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown reversal"));
    }

    /** Pays a settlement batch out to the platform's settlement account, net of the provider's fee. */
    @PostMapping("/payouts")
    public ResponseEntity<Map<String, Object>> payout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PayoutCall call) throws InterruptedException {
        authenticate(authorization);
        requireKey(idempotencyKey);
        SimFaults.Mode fault = faults.next(SimFaults.Operation.PAYOUT);
        List<String> transactions = call.providerTransactionIds() == null ? List.of()
                : call.providerTransactionIds().stream().sorted().toList();
        String hash = Hashing.sha256Hex("payout|" + call.reference() + "|" + call.merchantId() + "|"
                + call.amount().stripTrailingZeros().toPlainString() + "|" + call.currency() + "|" + String.join(",", transactions));
        if (fault == SimFaults.Mode.UNAVAILABLE) {
            requestLog.record("PAYOUT", idempotencyKey, hash, SimApiRequest.Outcome.UNAVAILABLE);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated outage");
        }
        SimPayout payout;
        try {
            payout = simulator.payout(idempotencyKey, hash, call.merchantId(), call.reference(), call.amount(),
                    call.currency(), transactions, fault == SimFaults.Mode.REJECT);
        } catch (ResponseStatusException ex) {
            requestLog.record("PAYOUT", idempotencyKey, hash, SimApiRequest.Outcome.KEY_CONFLICT);
            throw ex;
        }
        requestLog.record("PAYOUT", idempotencyKey, hash, payout.wasReplayed() ? SimApiRequest.Outcome.REPLAYED
                : payout.getStatus() == SimPayout.Status.FAILED ? SimApiRequest.Outcome.REFUSED : SimApiRequest.Outcome.APPLIED);
        lateIf(fault);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(payout));
    }

    @GetMapping("/payouts/{idempotencyKey}")
    public Map<String, Object> findPayout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String idempotencyKey) {
        authenticate(authorization);
        lookupFault();
        return simulator.findPayout(idempotencyKey).map(SimController::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown payout"));
    }

    /**
     * The provider's settlement report for one day, in the format the platform imports
     * ({@code POST /api/reconciliation/reports}): every transaction settled by that day's payouts, and the
     * payouts themselves with gross, fee, net and value date.
     */
    @GetMapping("/reports/settlement")
    public Map<String, Object> settlementReport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestParam java.time.LocalDate date) {
        authenticate(authorization);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        List<Map<String, Object>> payoutRows = new java.util.ArrayList<>();
        for (SimPayout payout : simulator.payoutsOn(date)) {
            if (payout.getStatus() != SimPayout.Status.SUCCEEDED) {
                continue;
            }
            for (String transactionId : payout.getProviderTransactionIds()) {
                simulator.paymentByTransaction(transactionId).ifPresent(payment -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("providerTransactionId", payment.getProviderTransactionId());
                    row.put("amount", payment.getAmount());
                    row.put("currency", payment.getCurrency());
                    row.put("providerStatus", reportedStatus(payment.getStatus()));
                    row.put("settlementDate", payout.getPayoutDate());
                    row.put("refundedAmount", payment.getRefundedAmount());
                    rows.add(row);
                });
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("payoutReference", payout.getReference());
            row.put("providerPayoutId", payout.getId());
            row.put("grossAmount", payout.getGrossAmount());
            row.put("feeAmount", payout.getFeeAmount());
            row.put("netAmount", payout.getNetAmount());
            row.put("currency", payout.getCurrency());
            row.put("valueDate", payout.getValueDate());
            payoutRows.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("settlementDate", date);
        report.put("rows", rows);
        report.put("payouts", payoutRows);
        return report;
    }

    private static String reportedStatus(SimPayment.Status status) {
        return switch (status) {
            case AUTHORIZED -> "AUTHORIZED";
            case SUCCESS -> "SUCCESS";
            case FAILED -> "FAILED";
            case EXPIRED -> "EXPIRED";
            case VOIDED, REVERSED -> "REVERSAL_REQUIRED";
        };
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", String.valueOf(ex.getReason())));
    }

    private void authenticate(String authorization) {
        String expected = "Bearer " + properties.apiKey();
        if (!Hashing.constantTimeEquals(expected, authorization)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid provider API key");
        }
    }

    private void lookupFault() {
        switch (faults.next(SimFaults.Operation.LOOKUP)) {
            case UNAVAILABLE -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated outage");
            case REJECT -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Simulated credential refusal");
            default -> {
            }
        }
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
    }

    private void unavailableIf(SimFaults.Mode fault, String operation, String idempotencyKey, String hash) {
        if (fault == SimFaults.Mode.UNAVAILABLE) {
            requestLog.record(operation, idempotencyKey, hash, SimApiRequest.Outcome.UNAVAILABLE);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated outage");
        }
    }

    /** Runs a money-moving operation and records in the provider's request log what happened to its key. */
    private SimOperation logged(String operation, String idempotencyKey, String hash, Supplier<SimOperation> action) {
        SimOperation result;
        try {
            result = action.get();
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                requestLog.record(operation, idempotencyKey, hash, SimApiRequest.Outcome.KEY_CONFLICT);
            }
            throw ex;
        }
        requestLog.record(operation, idempotencyKey, hash, result.wasReplayed() ? SimApiRequest.Outcome.REPLAYED
                : result.getStatus() == SimOperation.Status.FAILED ? SimApiRequest.Outcome.REFUSED : SimApiRequest.Outcome.APPLIED);
        return result;
    }

    /** The operation is committed; answering late makes the caller time out without knowing it succeeded. */
    private void lateIf(SimFaults.Mode fault) throws InterruptedException {
        if (fault == SimFaults.Mode.TIMEOUT_AFTER_COMMIT) {
            Thread.sleep(properties.faultDelay().toMillis());
        }
    }

    private static BigDecimal required(BigDecimal amount) {
        if (amount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount is required");
        }
        return amount;
    }

    private static String requiredCurrency(String currency) {
        if (currency == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency is required");
        }
        return currency;
    }

    private static Map<String, Object> view(SimPayment payment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("paymentIntentId", payment.getPaymentIntentId());
        view.put("providerTransactionId", payment.getProviderTransactionId());
        view.put("status", payment.getStatus());
        view.put("amount", payment.getAmount());
        view.put("currency", payment.getCurrency());
        view.put("refundedAmount", payment.getRefundedAmount());
        return view;
    }

    private static Map<String, Object> view(SimPayout payout) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", payout.getId());
        view.put("type", "PAYOUT");
        view.put("status", payout.getStatus());
        view.put("reference", payout.getReference());
        view.put("grossAmount", payout.getGrossAmount());
        view.put("feeAmount", payout.getFeeAmount());
        view.put("netAmount", payout.getNetAmount());
        view.put("currency", payout.getCurrency());
        view.put("valueDate", payout.getValueDate());
        view.put("failureReason", payout.getFailureReason());
        return view;
    }

    private static Map<String, Object> view(SimOperation operation) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", operation.getId());
        view.put("type", operation.getOperationType());
        view.put("status", operation.getStatus());
        view.put("amount", operation.getAmount());
        view.put("currency", operation.getCurrency());
        view.put("providerTransactionId", operation.getProviderTransactionId());
        view.put("failureReason", operation.getFailureReason());
        return view;
    }
}
