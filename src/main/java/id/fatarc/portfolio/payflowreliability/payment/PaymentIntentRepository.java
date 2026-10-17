package id.fatarc.portfolio.payflowreliability.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID>, JpaSpecificationExecutor<PaymentIntent> {
    /** Locked, so concurrent replays of one key are serialized (each writes to the payment's audit chain). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentIntent p where p.merchantId = :merchantId and p.idempotencyKey = :key")
    Optional<PaymentIntent> findForUpdateByMerchantIdAndIdempotencyKey(@Param("merchantId") String merchantId,
                                                                       @Param("key") String idempotencyKey);

    /**
     * Loads a payment with a row lock held until commit. Every flow that changes one payment goes
     * through this (or {@link #findAllForUpdate}), so changes to a single payment are serialized and
     * each one sees the result of the previous. Lock order everywhere: payment first, then the rows
     * that belong to it (transactions, refunds, review cases, outbox commands).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentIntent p where p.id = :id")
    Optional<PaymentIntent> findForUpdate(@Param("id") UUID id);

    /**
     * Locks several payments in id order. A fixed order means two callers locking overlapping
     * sets queue behind each other instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentIntent p where p.id in :ids order by p.id")
    List<PaymentIntent> findAllForUpdate(@Param("ids") Collection<UUID> ids);

    /** Payments waiting on the provider for too long, oldest first. */
    @Query("""
            select p.id from PaymentIntent p
            where p.state in :states and p.updatedAt < :updatedBefore
              and (p.lastProviderCheckAt is null or p.lastProviderCheckAt < :checkedBefore)
            order by p.updatedAt
            """)
    List<UUID> findStuck(@Param("states") Collection<PaymentState> states, @Param("updatedBefore") Instant updatedBefore,
                         @Param("checkedBefore") Instant checkedBefore, org.springframework.data.domain.Pageable page);

    long countByStateInAndUpdatedAtBefore(Collection<PaymentState> states, Instant updatedBefore);

    /** Settled payments the provider's report has confirmed: reconciled as soon as their payout lands. */
    @Query("""
            select p.id from PaymentIntent p
            where p.state = id.fatarc.portfolio.payflowreliability.payment.PaymentState.SETTLED
              and p.providerReportMatchedAt is not null
            order by p.id""")
    List<UUID> findSettledWithProviderConfirmation();

    @Query("select p.state as state, count(p) as paymentCount from PaymentIntent p group by p.state")
    List<StateCount> countByState();

    interface StateCount {
        PaymentState getState();
        long getPaymentCount();
    }
}
