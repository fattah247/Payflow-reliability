package id.fatarc.portfolio.payflowreliability.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {
    Optional<RefundRequest> findFirstByPaymentIntentIdAndStatusInOrderByCreatedAtDesc(UUID paymentIntentId,
                                                                                      Collection<RefundStatus> statuses);

    List<RefundRequest> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    long countByStatus(RefundStatus status);

    /** Reads only the owning payment id, without loading (and caching) the refund itself. */
    @Query("select r.paymentIntentId from RefundRequest r where r.id = :id")
    Optional<UUID> findPaymentIntentIdById(@Param("id") UUID id);
}
