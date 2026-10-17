package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SimPayoutRepository extends JpaRepository<SimPayout, String> {
    Optional<SimPayout> findByIdempotencyKey(String idempotencyKey);

    List<SimPayout> findByPayoutDateOrderByCreatedAtAsc(LocalDate payoutDate);

    Optional<SimPayout> findByReference(String reference);
}
