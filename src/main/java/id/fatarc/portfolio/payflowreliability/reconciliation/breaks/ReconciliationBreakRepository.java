package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationBreakRepository extends JpaRepository<ReconciliationBreak, UUID>,
        JpaSpecificationExecutor<ReconciliationBreak> {
    Optional<ReconciliationBreak> findByCategoryAndSubjectKeyAndStatus(BreakCategory category, String subjectKey, BreakStatus status);

    List<ReconciliationBreak> findByStatusOrderByOpenedAtAsc(BreakStatus status);

    List<ReconciliationBreak> findAllByOrderByOpenedAtDesc();

    List<ReconciliationBreak> findBySettlementBatchIdOrderByOpenedAtAsc(UUID settlementBatchId);

    List<ReconciliationBreak> findByBankLineIdAndStatus(UUID bankLineId, BreakStatus status);

    List<ReconciliationBreak> findBySettlementBatchIdAndCategoryAndStatusOrderByOpenedAtAsc(UUID settlementBatchId,
                                                                                          BreakCategory category,
                                                                                          BreakStatus status);

    List<ReconciliationBreak> findByStatusAndCategoryInOrderByOpenedAtAsc(BreakStatus status, Collection<BreakCategory> categories);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ReconciliationBreak b where b.id = :id")
    Optional<ReconciliationBreak> findForUpdate(@Param("id") UUID id);

    @Query("select b.currency as currency, sum(b.suspenseAmount) as amount from ReconciliationBreak b"
            + " where b.status = id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus.OPEN"
            + " and b.currency is not null group by b.currency")
    List<CurrencyAmount> openSuspenseByCurrency();

    long countByStatusAndCategoryIn(BreakStatus status, Collection<BreakCategory> categories);

    @Query("select count(b) from ReconciliationBreak b where b.status = :status and b.dueAt < :now")
    long countOverdue(@Param("status") BreakStatus status, @Param("now") Instant now);

    interface CurrencyAmount {
        String getCurrency();
        BigDecimal getAmount();
    }
}
