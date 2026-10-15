package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SimApiRequestRepository extends JpaRepository<SimApiRequest, UUID> {
    List<SimApiRequest> findByIdempotencyKeyStartingWithOrderByReceivedAtAsc(String keyPrefix);

    List<SimApiRequest> findByOperationOrderByReceivedAtAsc(String operation);
}
