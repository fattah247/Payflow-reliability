package id.fatarc.portfolio.payflowreliability.providersim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.fatarc.portfolio.payflowreliability.banksim.BankSimulator;
import id.fatarc.portfolio.payflowreliability.common.Money;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider-side behavior: what happens at the provider when a customer pays, and how the provider
 * API answers refunds and reversals. Every operation is idempotent by the caller's key.
 */
@Service
@ConditionalOnProperty(name = "payflow.provider-sim.enabled", havingValue = "true")
public class ProviderSimulator {
    private final SimPaymentRepository payments;
    private final SimOperationRepository operations;
    private final SimWebhookDeliveryRepository deliveries;
    private final SimPayoutRepository payouts;
    private final ObjectProvider<BankSimulator> bank;
    private final ObjectMapper objectMapper;
    private final SimulatorProperties properties;
    private final Clock clock;

    public ProviderSimulator(SimPaymentRepository payments, SimOperationRepository operations,
                             SimWebhookDeliveryRepository deliveries, SimPayoutRepository payouts,
                             ObjectProvider<BankSimulator> bank, ObjectMapper objectMapper,
                             SimulatorProperties properties, Clock clock) {
        this.payments = payments;
        this.operations = operations;
        this.deliveries = deliveries;
        this.payouts = payouts;
        this.bank = bank;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * How the provider notifies about a customer action.
     *
     * @param deliverWebhook false simulates a lost webhook (recorded as DROPPED, never sent)
     * @param deliveries     how many times the same event is delivered (duplicates)
     * @param webhookAmount  overrides the amount in the webhook (a provider-side bug)
     * @param authorizeFirst for pay: send AUTHORIZED, then SUCCESS for the same provider transaction
     */
    public record CustomerOptions(Boolean deliverWebhook, Integer deliveries, BigDecimal webhookAmount,
                                  Boolean authorizeFirst) {
        static final CustomerOptions DEFAULT = new CustomerOptions(true, 1, null, false);

        boolean deliver() {
            return deliverWebhook == null || deliverWebhook;
        }

        int copies() {
            return deliveries == null || deliveries < 1 ? 1 : Math.min(deliveries, 20);
        }
    }

    @Transactional
    public SimPayment pay(UUID paymentIntentId, BigDecimal amount, String currency, CustomerOptions options) {
        SimPayment payment = create(paymentIntentId, amount, currency, SimPayment.Status.SUCCESS);
        if (options != null && Boolean.TRUE.equals(options.authorizeFirst())) {
            notify(payment, "AUTHORIZED", options);
        }
        notify(payment, "SUCCESS", options);
        return payment;
    }

    @Transactional
    public SimPayment authorize(UUID paymentIntentId, BigDecimal amount, String currency, CustomerOptions options) {
        SimPayment payment = create(paymentIntentId, amount, currency, SimPayment.Status.AUTHORIZED);
        notify(payment, "AUTHORIZED", options);
        return payment;
    }

    @Transactional
    public SimPayment capture(UUID paymentIntentId, CustomerOptions options) {
        SimPayment payment = require(paymentIntentId);
        requireStatus(payment, SimPayment.Status.AUTHORIZED);
        payment.moveTo(SimPayment.Status.SUCCESS, clock.instant());
        notify(payment, "SUCCESS", options);
        return payment;
    }

    @Transactional
    public SimPayment decline(UUID paymentIntentId, BigDecimal amount, String currency, CustomerOptions options) {
        SimPayment payment = payments.findById(paymentIntentId)
                .orElseGet(() -> create(paymentIntentId, amount, currency, SimPayment.Status.FAILED));
        payment.moveTo(SimPayment.Status.FAILED, clock.instant());
        notify(payment, "FAILED", options);
        return payment;
    }

    @Transactional
    public SimPayment expire(UUID paymentIntentId, CustomerOptions options) {
        SimPayment payment = require(paymentIntentId);
        payment.moveTo(SimPayment.Status.EXPIRED, clock.instant());
        notify(payment, "EXPIRED", options);
        return payment;
    }

    /** The provider asks the merchant platform to reverse a captured payment. */
    @Transactional
    public SimPayment chargeback(UUID paymentIntentId, CustomerOptions options) {
        SimPayment payment = require(paymentIntentId);
        requireStatus(payment, SimPayment.Status.SUCCESS);
        notify(payment, "REVERSAL_REQUIRED", options);
        return payment;
    }

    @Transactional
    public SimOperation refund(String idempotencyKey, String requestHash, String providerTransactionId, BigDecimal amount,
                               String currency, boolean forceReject) {
        Optional<SimOperation> earlier = earlier(SimOperation.Type.REFUND, idempotencyKey, requestHash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Optional<SimPayment> payment = payments.findByProviderTransactionId(providerTransactionId);
        String refusal = forceReject ? "Simulated refusal."
                : payment.isEmpty() ? "Unknown provider transaction."
                : !payment.get().getCurrency().equals(currency) ? "Currency does not match the payment."
                : payment.get().getStatus() != SimPayment.Status.SUCCESS ? "Payment is " + payment.get().getStatus() + "."
                : amount.compareTo(payment.get().refundable()) > 0
                ? "Refund exceeds the refundable " + payment.get().refundable().toPlainString() + "."
                : null;
        if (refusal == null) {
            payment.get().addRefund(amount, clock.instant());
        }
        return operations.save(new SimOperation(SimOperation.Type.REFUND, idempotencyKey, requestHash, providerTransactionId,
                amount, currency, refusal == null ? SimOperation.Status.SUCCEEDED : SimOperation.Status.FAILED,
                refusal, clock.instant()));
    }

    /** Voids an authorization, or returns the captured money not yet refunded. */
    @Transactional
    public SimOperation reverse(String idempotencyKey, String requestHash, String providerTransactionId, boolean forceReject) {
        Optional<SimOperation> earlier = earlier(SimOperation.Type.REVERSAL, idempotencyKey, requestHash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Optional<SimPayment> payment = payments.findByProviderTransactionId(providerTransactionId);
        Instant now = clock.instant();
        String refusal = null;
        BigDecimal amount = BigDecimal.ZERO;
        String currency = payment.map(SimPayment::getCurrency).orElse("XXX");
        if (forceReject) {
            refusal = "Simulated refusal.";
        } else if (payment.isEmpty()) {
            refusal = "Unknown provider transaction.";
        } else if (payment.get().getStatus() == SimPayment.Status.AUTHORIZED) {
            payment.get().moveTo(SimPayment.Status.VOIDED, now);
        } else if (payment.get().getStatus() == SimPayment.Status.SUCCESS) {
            amount = payment.get().refundable();
            payment.get().moveTo(SimPayment.Status.REVERSED, now);
        } else {
            refusal = "Payment is " + payment.get().getStatus() + ".";
        }
        return operations.save(new SimOperation(SimOperation.Type.REVERSAL, idempotencyKey, requestHash,
                providerTransactionId, amount, currency,
                refusal == null ? SimOperation.Status.SUCCEEDED : SimOperation.Status.FAILED, refusal, now));
    }

    /**
     * Pays a settlement batch out: the provider deducts its fee and sends the net to the platform's settlement
     * account, with the platform's reference on the transfer, valued {@code payoutValueDays} after today (or
     * after the bank's business date, if the bank is ahead). Idempotent by key, like every provider operation.
     */
    @Transactional
    public SimPayout payout(String idempotencyKey, String requestHash, String merchantId, String reference, BigDecimal amount,
                            String currency, Collection<String> providerTransactionIds, boolean forceReject) {
        Optional<SimPayout> earlier = rememberedPayout(idempotencyKey).map(existing -> {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Idempotency-Key " + idempotencyKey + " was used for a different request.");
            }
            return existing.asReplay();
        });
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        BankSimulator settlementBank = bank.getIfAvailable();
        LocalDate bankDate = settlementBank == null ? today : settlementBank.businessDate(currency);
        LocalDate valueDate = (bankDate.isAfter(today) ? bankDate : today).plusDays(properties.payoutValueDays());
        BigDecimal gross = Money.normalize(amount, currency);
        BigDecimal fee = gross.multiply(BigDecimal.valueOf(properties.payoutFeeBps()))
                .divide(BigDecimal.valueOf(10_000), Money.fractionDigits(currency), RoundingMode.HALF_UP);
        String id = "sim_po_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String refusal = forceReject ? "Simulated refusal." : amount.signum() <= 0 ? "Payout amount must be positive." : null;
        SimPayout payout = payouts.save(new SimPayout(id, idempotencyKey, requestHash, merchantId, reference, gross,
                refusal == null ? fee : Money.zero(currency), currency, today, valueDate,
                refusal == null ? SimPayout.Status.SUCCEEDED : SimPayout.Status.FAILED, refusal,
                new LinkedHashSet<>(providerTransactionIds), now));
        if (refusal == null && settlementBank != null) {
            settlementBank.receiveCredit(currency, payout.getNetAmount(), reference, "PAYOUT " + reference + " " + merchantId,
                    "Simulated Payment Provider", valueDate);
        }
        return payout;
    }

    @Transactional
    public Optional<SimPayout> findPayout(String idempotencyKey) {
        return rememberedPayout(idempotencyKey);
    }

    @Transactional(readOnly = true)
    public List<SimPayout> payoutsOn(LocalDate payoutDate) {
        return payouts.findByPayoutDateOrderByCreatedAtAsc(payoutDate);
    }

    @Transactional(readOnly = true)
    public Optional<SimPayment> paymentByTransaction(String providerTransactionId) {
        return payments.findByProviderTransactionId(providerTransactionId);
    }

    private Optional<SimPayout> rememberedPayout(String idempotencyKey) {
        Optional<SimPayout> existing = payouts.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent() && existing.get().getCreatedAt().isBefore(clock.instant().minus(properties.keyRetention()))) {
            existing.get().forgetKey();
            payouts.saveAndFlush(existing.get());
            return Optional.empty();
        }
        return existing;
    }

    /** Looks an operation up by idempotency key; a key older than the retention period is no longer known. */
    @Transactional
    public Optional<SimOperation> find(SimOperation.Type type, String idempotencyKey) {
        return remembered(type, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public Optional<SimPayment> payment(UUID paymentIntentId) {
        return payments.findById(paymentIntentId);
    }

    @Transactional(readOnly = true)
    public List<SimWebhookDelivery> deliveries(UUID paymentIntentId) {
        return deliveries.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
    }

    private Optional<SimOperation> earlier(SimOperation.Type type, String idempotencyKey, String requestHash) {
        return remembered(type, idempotencyKey).map(existing -> {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Idempotency-Key " + idempotencyKey + " was used for a different request.");
            }
            return existing.asReplay();
        });
    }

    private Optional<SimOperation> remembered(SimOperation.Type type, String idempotencyKey) {
        Optional<SimOperation> existing = operations.findByOperationTypeAndIdempotencyKey(type, idempotencyKey);
        if (existing.isPresent() && existing.get().getCreatedAt().isBefore(clock.instant().minus(properties.keyRetention()))) {
            existing.get().forgetKey();
            operations.saveAndFlush(existing.get());
            return Optional.empty();
        }
        return existing;
    }

    private SimPayment create(UUID paymentIntentId, BigDecimal amount, String currency, SimPayment.Status status) {
        if (payments.existsById(paymentIntentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The provider already has payment " + paymentIntentId);
        }
        String providerTransactionId = "sim_tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return payments.save(new SimPayment(paymentIntentId, providerTransactionId, status,
                Money.normalize(amount, currency), currency, clock.instant()));
    }

    private SimPayment require(UUID paymentIntentId) {
        return payments.findById(paymentIntentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "The provider has no payment " + paymentIntentId));
    }

    private static void requireStatus(SimPayment payment, SimPayment.Status expected) {
        if (payment.getStatus() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payment is " + payment.getStatus() + ", expected " + expected);
        }
    }

    private void notify(SimPayment payment, String status, CustomerOptions options) {
        CustomerOptions effective = options == null ? CustomerOptions.DEFAULT : options;
        String eventId = "sim_evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        BigDecimal amount = effective.webhookAmount() != null ? effective.webhookAmount() : payment.getAmount();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("providerEventId", eventId);
        payload.put("providerTransactionId", payment.getProviderTransactionId());
        payload.put("paymentIntentId", payment.getPaymentIntentId());
        payload.put("status", status);
        payload.put("amount", amount);
        payload.put("currency", payment.getCurrency());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        for (int copy = 0; copy < effective.copies(); copy++) {
            deliveries.save(new SimWebhookDelivery(eventId, payment.getPaymentIntentId(), json, effective.deliver(),
                    clock.instant()));
        }
    }
}
