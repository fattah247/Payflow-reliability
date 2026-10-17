package id.fatarc.portfolio.payflowreliability.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEndpoint e where e.merchantId = :merchantId")
    Optional<WebhookEndpoint> findForUpdate(@Param("merchantId") String merchantId);
}
