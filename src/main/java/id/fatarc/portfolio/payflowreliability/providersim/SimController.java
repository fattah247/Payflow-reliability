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

    public SimController(ProviderSimulator simulator, SimWebhookDispatcher dispatcher, SimFaults faults,
                         SimulatorProperties properties, SimRequestLog requestLog) {
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

    public record FaultRequest(SimFaults.Operation operation, SimFaults.Mode mode, Integer times) {
    }

    // ---- simulation controls: the customer and the outside world -------------------------------

    @PostMapping("/payments/{paymentIntentId}/{action}")
    public Map<String, Object> customer(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable UUID paymentIntentId, @PathVariable String action,
                                        @RequestBody(required = false) CustomerAction body) {
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
        return view(payment);
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
