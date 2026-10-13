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
    List<ReviewCase> findByStatusInOrderByCreatedAtAsc(Collection<ReviewStatus> statuses);

    /** Reads only the case's payment id, without loading (and caching) the case itself. */
    @Query("select c.id as id, c.paymentIntentId as paymentIntentId from ReviewCase c where c.id = :id")
    Optional<PaymentRef> findPaymentRefById(@Param("id") UUID id);

    /** Serializes decisions on one case: a second operator waits, then sees the first decision. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReviewCase c where c.id = :id")
    Optional<ReviewCase> findForUpdate(@Param("id") UUID id);

    Optional<ReviewCase> findFirstByStatusInAndPaymentIntentIdAndReasonOrderByCreatedAtAsc(
            Collection<ReviewStatus> statuses, UUID paymentIntentId, ReviewReason reason);

    Optional<ReviewCase> findFirstByStatusInAndReasonAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
            Collection<ReviewStatus> statuses, ReviewReason reason, String referenceType, String referenceId);

    List<ReviewCase> findByStatusInAndReferenceTypeAndReferenceId(Collection<ReviewStatus> statuses,
                                                                  String referenceType, String referenceId);

    List<ReviewCase> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    List<ReviewCase> findByStatusInAndPaymentIntentIdIn(Collection<ReviewStatus> statuses, Collection<UUID> paymentIntentIds);

    long countByStatus(ReviewStatus status);

    @Query("select c.reason as reason, count(c) as caseCount from ReviewCase c where c.status in :statuses group by c.reason order by c.reason")
    List<ReasonCount> countByReason(@Param("statuses") Collection<ReviewStatus> statuses);

    @Query("select min(c.createdAt) from ReviewCase c where c.status in :statuses")
    Optional<Instant> oldestCreatedAt(@Param("statuses") Collection<ReviewStatus> statuses);

    interface PaymentRef {
        UUID getId();
        UUID getPaymentIntentId();
    }

    interface ReasonCount {
        ReviewReason getReason();
        long getCaseCount();
    }
}
