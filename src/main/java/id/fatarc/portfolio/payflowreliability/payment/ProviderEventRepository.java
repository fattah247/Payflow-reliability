package id.fatarc.portfolio.payflowreliability.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderEventRepository extends JpaRepository<ProviderEvent, UUID> {
    Optional<ProviderEvent> findByProviderEventId(String providerEventId);

    List<ProviderEvent> findByPaymentIntentIdOrderByReceivedAtAsc(UUID paymentIntentId);
}
