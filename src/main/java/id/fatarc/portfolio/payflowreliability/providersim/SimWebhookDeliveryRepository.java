package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SimWebhookDeliveryRepository extends JpaRepository<SimWebhookDelivery, UUID> {
    @Query("""
            select d.id from SimWebhookDelivery d
            where d.status = id.fatarc.portfolio.payflowreliability.providersim.SimWebhookDelivery.Status.PENDING
              and d.nextAttemptAt <= :now
            order by d.nextAttemptAt, d.id
            """)
    List<UUID> findDue(@Param("now") Instant now, Pageable page);

    List<SimWebhookDelivery> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);
}
