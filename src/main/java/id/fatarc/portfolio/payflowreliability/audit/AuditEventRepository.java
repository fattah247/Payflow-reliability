package id.fatarc.portfolio.payflowreliability.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, String aggregateId);

    List<AuditEvent> findByPaymentIntentIdOrderBySequenceNumberAsc(UUID paymentIntentId);

    List<AuditEvent> findByChainKeyOrderBySequenceNumberAsc(String chainKey);

    Optional<AuditEvent> findFirstByChainKeyOrderBySequenceNumberDesc(String chainKey);
}
