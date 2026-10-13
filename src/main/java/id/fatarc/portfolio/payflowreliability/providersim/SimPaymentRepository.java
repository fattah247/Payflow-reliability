package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SimPaymentRepository extends JpaRepository<SimPayment, UUID> {
    Optional<SimPayment> findByProviderTransactionId(String providerTransactionId);
}
