package id.fatarc.portfolio.payflowreliability.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.fatarc.portfolio.payflowreliability.audit.PaymentStateChanged;
import id.fatarc.portfolio.payflowreliability.common.Text;
import id.fatarc.portfolio.payflowreliability.payment.PaymentCreated;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.refund.RefundChanged;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequestRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the event a shop should hear about, in the transaction of the change itself: a transactional outbox, so a
 * committed change always has its event and a rolled-back one never does. Delivery happens later
 * ({@link MerchantWebhookDispatcher}).
 *
 * <p>Events are numbered per payment. Every writer holds the payment's row lock (the same rule as the audit chain),
 * so numbers never collide; the unique (payment, sequence) constraint would turn a collision into a retryable 409.
 */
@Component
public class MerchantEventRecorder {
    private final MerchantEventRepository events;
    private final WebhookEndpointRepository endpoints;
    private final PaymentIntentRepository payments;
    private final RefundRequestRepository refunds;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MerchantEventRecorder(MerchantEventRepository events, WebhookEndpointRepository endpoints,
                                 PaymentIntentRepository payments, RefundRequestRepository refunds, ObjectMapper objectMapper,
                                 Clock clock) {
        this.events = events;
        this.endpoints = endpoints;
        this.payments = payments;
        this.refunds = refunds;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PaymentCreated created) {
        PaymentIntent payment = payments.findById(created.paymentIntentId()).orElseThrow();
        record(payment, MerchantEvent.PAYMENT_CREATED, null, payment.getState().name(), paymentData(payment, payment.getState().name(), null));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PaymentStateChanged changed) {
        PaymentIntent payment = payments.findById(changed.paymentIntentId()).orElseThrow();
        record(payment, MerchantEvent.PAYMENT_UPDATED, null, changed.newState(),
                paymentData(payment, changed.newState(), changed.previousState()));
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(RefundChanged changed) {
        RefundRequest refund = refunds.findById(changed.refundId()).orElseThrow();
        PaymentIntent payment = payments.findById(refund.getPaymentIntentId()).orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", refund.getId());
        data.put("paymentIntentId", payment.getId());
        data.put("merchantReference", payment.getMerchantReference());
        data.put("status", refund.getStatus().name());
        data.put("amount", refund.getAmount().toPlainString());
        data.put("currency", refund.getCurrency());
        data.put("failureReason", Text.fit(refund.getFailureReason(), 500));
        record(payment, changed.created() ? MerchantEvent.REFUND_CREATED : MerchantEvent.REFUND_UPDATED, refund.getId(),
                refund.getStatus().name(), data);
    }

    private static Map<String, Object> paymentData(PaymentIntent payment, String state, String previousState) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentIntentId", payment.getId());
        data.put("merchantReference", payment.getMerchantReference());
        data.put("state", state);
        data.put("previousState", previousState);
        data.put("amount", payment.getAmount().toPlainString());
        data.put("currency", payment.getCurrency());
        data.put("refundedAmount", payment.getRefundedAmount().toPlainString());
        data.put("reversedAmount", payment.getReversedAmount().toPlainString());
        return data;
    }

    private void record(PaymentIntent payment, String type, UUID refundId, String state, Map<String, Object> data) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        long sequence = events.lastSequence(payment.getId()) + 1;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("type", type);
        body.put("createdAt", now);
        body.put("merchantId", payment.getMerchantId());
        body.put("sequence", sequence);
        body.put("data", data);
        boolean deliverable = endpoints.findById(payment.getMerchantId()).filter(WebhookEndpoint::isEnabled).isPresent();
        events.save(new MerchantEvent(id, payment.getMerchantId(), type, payment.getId(), refundId, sequence, state,
                json(body), deliverable, now));
    }

    private String json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not write a merchant event", ex);
        }
    }
}
