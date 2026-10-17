package id.fatarc.portfolio.payflowreliability.audit;

import id.fatarc.portfolio.payflowreliability.common.RequestId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Audit events join the caller's transaction on purpose: an audit row commits exactly when the
 * change it describes commits, so the trail never claims something that was rolled back.
 *
 * <p>Each event is appended to a hash chain (one chain per payment, otherwise per aggregate).
 * Writers of payment events hold that payment's row lock, so a chain never forks; the unique
 * (chain, sequence) constraint turns any unexpected race into a retryable 409 instead of a fork.
 *
 * <p>Every payment state change passes through {@link #recordStateChange}, which also publishes it
 * ({@link PaymentStateChanged}) so the shop is told in the same transaction.
 */
@Service
public class AuditService {
    public static final String STATE_CHANGED = "TRANSACTION_STATE_CHANGED";

    private final AuditEventRepository auditEventRepository;
    private final ApplicationEventPublisher events;

    public AuditService(AuditEventRepository auditEventRepository, ApplicationEventPublisher events) {
        this.auditEventRepository = auditEventRepository;
        this.events = events;
    }

    @Transactional
    public void record(String aggregateType,
                       String aggregateId,
                       UUID paymentIntentId,
                       String eventType,
                       String actor,
                       String reason,
                       String metadata) {
        append(new AuditEvent(aggregateType, aggregateId, paymentIntentId, eventType, null, null,
                actor, reason, metadata, RequestId.current()));
    }

    /** Records a payment lifecycle transition. */
    @Transactional
    public void recordStateChange(UUID paymentIntentId,
                                  Enum<?> previousState,
                                  Enum<?> newState,
                                  String actor,
                                  String reason,
                                  String metadata) {
        append(new AuditEvent(AggregateType.PAYMENT_INTENT, paymentIntentId.toString(), paymentIntentId, STATE_CHANGED,
                previousState.name(), newState.name(), actor, reason, metadata, RequestId.current()));
        events.publishEvent(new PaymentStateChanged(paymentIntentId, previousState.name(), newState.name()));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> history(String aggregateType, String aggregateId) {
        return auditEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(aggregateType, aggregateId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> paymentHistory(UUID paymentIntentId) {
        return auditEventRepository.findByPaymentIntentIdOrderBySequenceNumberAsc(paymentIntentId);
    }

    private void append(AuditEvent event) {
        var head = auditEventRepository.findFirstByChainKeyOrderBySequenceNumberDesc(event.getChainKey());
        event.seal(head.map(previous -> previous.getSequenceNumber() + 1).orElse(1L),
                head.map(AuditEvent::getEventHash).orElse(AuditHash.GENESIS));
        auditEventRepository.save(event);
    }
}
