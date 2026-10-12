package id.fatarc.portfolio.payflowreliability.review;

import jakarta.persistence.LockModeType;
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

public interface ReviewCaseRepository extends JpaRepository<ReviewCase, UUID>, JpaSpecificationExecutor<ReviewCase> {
    List<ReviewCase> findByStatusOrderByCreatedAtAsc(ReviewStatus status);

    /** Reads only the case's payment id, without loading (and caching) the case itself. */
    @Query("select c.id as id, c.paymentIntentId as paymentIntentId from ReviewCase c where c.id = :id")
    Optional<PaymentRef> findPaymentRefById(@Param("id") UUID id);

    Optional<ReviewCase> findFirstByStatusAndPaymentIntentIdAndReasonOrderByCreatedAtAsc(
            ReviewStatus status, UUID paymentIntentId, ReviewReason reason);

    /** Serializes resolution of one case: a second operator waits, then sees it resolved. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReviewCase c where c.id = :id")
    Optional<ReviewCase> findForUpdate(@Param("id") UUID id);

    List<ReviewCase> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    List<ReviewCase> findByStatusAndPaymentIntentIdIn(ReviewStatus status, Collection<UUID> paymentIntentIds);

    long countByStatusAndPaymentIntentId(ReviewStatus status, UUID paymentIntentId);

    Optional<ReviewCase> findFirstByStatusAndReasonAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
            ReviewStatus status,
            ReviewReason reason,
            String referenceType,
            String referenceId
    );

    @Query("select c.reason as reason, count(c) as caseCount from ReviewCase c where c.status = :status group by c.reason order by c.reason")
    List<ReasonCount> countByReason(@Param("status") ReviewStatus status);

    @Query("select min(c.createdAt) from ReviewCase c where c.status = :status")
    Optional<Instant> oldestCreatedAt(@Param("status") ReviewStatus status);

    interface PaymentRef {
        UUID getId();
        UUID getPaymentIntentId();
    }

    interface ReasonCount {
        ReviewReason getReason();
        long getCaseCount();
    }
}
