package id.fatarc.portfolio.payflowreliability.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    Optional<PaymentTransaction> findByProviderTransactionId(String providerTransactionId);

    List<PaymentTransaction> findByProviderTransactionIdIn(Collection<String> providerTransactionIds);

    List<PaymentTransaction> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    List<PaymentTransaction> findBySettledOn(LocalDate settledOn);
}
