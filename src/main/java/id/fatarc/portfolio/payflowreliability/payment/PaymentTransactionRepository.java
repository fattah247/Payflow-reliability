package id.fatarc.portfolio.payflowreliability.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    Optional<PaymentTransaction> findByProviderTransactionId(String providerTransactionId);

    List<PaymentTransaction> findByProviderTransactionIdIn(Collection<String> providerTransactionIds);

    List<PaymentTransaction> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    /** Payments a settlement run would include, in id order; scalars only, so nothing is cached before locking. */
    @Query("""
            select distinct t.paymentIntentId from PaymentTransaction t, PaymentIntent i
            where i.id = t.paymentIntentId
              and t.merchantId = :merchantId
              and t.settled = false
              and t.providerStatus = :captured
              and i.state = :settleable
            order by t.paymentIntentId
            """)
    List<UUID> findSettleablePaymentIntentIds(@Param("merchantId") String merchantId,
                                              @Param("captured") ProviderStatus captured,
                                              @Param("settleable") PaymentState settleable);

    /**
     * Unsettled captured transactions whose payment is still plainly SUCCESS. Payments that moved
     * on (refund requested, reversal required, on hold) are excluded instead of failing the batch.
     */
    @Query("""
            select t from PaymentTransaction t, PaymentIntent i
            where i.id = t.paymentIntentId
              and t.merchantId = :merchantId
              and t.settled = false
              and t.providerStatus = :captured
              and i.state = :settleable
            order by t.createdAt, t.id
            """)
    List<PaymentTransaction> findSettleable(@Param("merchantId") String merchantId,
                                            @Param("captured") ProviderStatus captured,
                                            @Param("settleable") PaymentState settleable);

    @Query("""
            select t.merchantId as merchantId, t.currency as currency,
                   count(t) as transactionCount, sum(t.amount) as totalAmount
            from PaymentTransaction t, PaymentIntent i
            where i.id = t.paymentIntentId
              and t.settled = false
              and t.providerStatus = :captured
              and i.state = :settleable
            group by t.merchantId, t.currency
            order by t.merchantId, t.currency
            """)
    List<SettleableTotal> summarizeSettleable(@Param("captured") ProviderStatus captured,
                                              @Param("settleable") PaymentState settleable);

    interface SettleableTotal {
        String getMerchantId();
        String getCurrency();
        long getTransactionCount();
        BigDecimal getTotalAmount();
    }
}
