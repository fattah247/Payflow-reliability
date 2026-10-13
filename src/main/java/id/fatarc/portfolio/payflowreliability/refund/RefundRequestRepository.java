package id.fatarc.portfolio.payflowreliability.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {
    Optional<RefundRequest> findByPaymentIntentIdAndIdempotencyKey(UUID paymentIntentId, String idempotencyKey);

    List<RefundRequest> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    long countByStatus(RefundStatus status);

    /** Refunds that are accepted but not finished: they reserve part of the refundable amount. */
    @Query("""
            select coalesce(sum(r.amount), 0) from RefundRequest r
            where r.paymentIntentId = :paymentIntentId
              and r.status in (id.fatarc.portfolio.payflowreliability.refund.RefundStatus.PENDING_REVIEW,
                               id.fatarc.portfolio.payflowreliability.refund.RefundStatus.REQUESTED)
            """)
    BigDecimal outstandingAmount(@Param("paymentIntentId") UUID paymentIntentId);

    /** Completed refunds not yet deducted from a payout, for the given payments. */
    @Query("""
            select r from RefundRequest r
            where r.paymentIntentId in :paymentIntentIds
              and r.status = id.fatarc.portfolio.payflowreliability.refund.RefundStatus.COMPLETED
              and not exists (select 1 from SettlementBatchItem i where i.refundId = r.id)
            order by r.completedAt, r.id
            """)
    List<RefundRequest> findUnsettledCompleted(@Param("paymentIntentIds") Collection<UUID> paymentIntentIds);

    /** Reads only the owning payment id, without loading (and caching) the refund itself. */
    @Query("select r.paymentIntentId from RefundRequest r where r.id = :id")
    Optional<UUID> findPaymentIntentIdById(@Param("id") UUID id);
}
