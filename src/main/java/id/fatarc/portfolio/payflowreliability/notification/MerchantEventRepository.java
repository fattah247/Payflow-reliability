package id.fatarc.portfolio.payflowreliability.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantEventRepository extends JpaRepository<MerchantEvent, UUID>, JpaSpecificationExecutor<MerchantEvent> {
    @Query("""
            select e.id from MerchantEvent e
            where e.status = id.fatarc.portfolio.payflowreliability.notification.MerchantEvent.Status.PENDING
              and e.nextAttemptAt <= :now and (e.leaseUntil is null or e.leaseUntil <= :now)
            order by e.nextAttemptAt, e.id""")
    List<UUID> findDue(@Param("now") Instant now, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from MerchantEvent e where e.id = :id")
    Optional<MerchantEvent> findForUpdate(@Param("id") UUID id);

    @Query("select coalesce(max(e.sequence), 0) from MerchantEvent e where e.paymentIntentId = :paymentIntentId")
    long lastSequence(@Param("paymentIntentId") UUID paymentIntentId);

    List<MerchantEvent> findByPaymentIntentIdOrderBySequenceAsc(UUID paymentIntentId);

    long countByStatus(MerchantEvent.Status status);

    @Query("""
            select min(e.createdAt) from MerchantEvent e
            where e.status = id.fatarc.portfolio.payflowreliability.notification.MerchantEvent.Status.PENDING""")
    Optional<Instant> oldestPending();
}
