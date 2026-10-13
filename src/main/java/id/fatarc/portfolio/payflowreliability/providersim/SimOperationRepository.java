package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SimOperationRepository extends JpaRepository<SimOperation, UUID> {
    Optional<SimOperation> findByOperationTypeAndIdempotencyKey(SimOperation.Type type, String idempotencyKey);
}
