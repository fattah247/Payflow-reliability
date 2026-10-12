package id.fatarc.portfolio.payflowreliability.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {
    Optional<PaymentIntent> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

    /**
     * Loads a payment with a row lock held until commit. Every flow that changes one payment
     * (webhooks, refunds, review decisions) goes through this, so changes to a single payment
     * are serialized and each one sees the result of the previous.
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

    @Query("select p.state as state, count(p) as paymentCount from PaymentIntent p group by p.state")
    List<StateCount> countByState();

    interface StateCount {
        PaymentState getState();
        long getPaymentCount();
    }
}
