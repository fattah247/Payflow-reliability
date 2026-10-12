package id.fatarc.portfolio.payflowreliability.audit;

import id.fatarc.portfolio.payflowreliability.common.RequestId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Audit events join the caller's transaction on purpose: an audit row commits exactly when
 * the change it describes commits, so the trail never claims something that was rolled back.
 */
@Service
public class AuditService {
    public static final String STATE_CHANGED = "TRANSACTION_STATE_CHANGED";

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional
    public void record(String aggregateType,
                       String aggregateId,
                       UUID paymentIntentId,
                       String eventType,
                       String actor,
                       String reason,
                       String metadata) {
        auditEventRepository.save(new AuditEvent(
                aggregateType,
                aggregateId,
                paymentIntentId,
                eventType,
                null,
                null,
                actor,
                reason,
                metadata,
                RequestId.current()
        ));
    }

    /** Records a payment lifecycle transition. */
    @Transactional
    public void recordStateChange(UUID paymentIntentId,
                                  Enum<?> previousState,
                                  Enum<?> newState,
                                  String actor,
                                  String reason,
                                  String metadata) {
        auditEventRepository.save(new AuditEvent(
                AggregateType.PAYMENT_INTENT,
                paymentIntentId.toString(),
                paymentIntentId,
                STATE_CHANGED,
                previousState.name(),
                newState.name(),
                actor,
                reason,
                metadata,
                RequestId.current()
        ));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> history(String aggregateType, String aggregateId) {
        return auditEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(aggregateType, aggregateId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> paymentHistory(UUID paymentIntentId) {
        return auditEventRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
    }
}
