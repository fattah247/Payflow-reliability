package id.fatarc.portfolio.payflowreliability.settlement;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID>, JpaSpecificationExecutor<SettlementBatch> {

    /** Batch row lock: payout events for one batch (provider answer, bank credit, return) are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SettlementBatch b where b.id = :id")
    Optional<SettlementBatch> findForUpdate(@Param("id") UUID id);

    @Query("select b.id from SettlementBatch b where b.payoutReference = :reference")
    Optional<UUID> findIdByPayoutReference(@Param("reference") String reference);

    List<SettlementBatch> findByPayoutStatusInOrderByCreatedAtAsc(Collection<PayoutStatus> statuses);

    List<SettlementBatch> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(Instant from, Instant before);

    List<SettlementBatch> findByMerchantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(String merchantId,
                                                                                                         Instant from, Instant before);
}
