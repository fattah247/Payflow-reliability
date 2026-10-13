package id.fatarc.portfolio.payflowreliability.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementBatchItemRepository extends JpaRepository<SettlementBatchItem, UUID> {
    List<SettlementBatchItem> findByBatchId(UUID batchId);

    List<SettlementBatchItem> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    boolean existsByReversalPaymentIntentId(UUID paymentIntentId);

    boolean existsByCapturePaymentIntentId(UUID paymentIntentId);
}
