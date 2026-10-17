package id.fatarc.portfolio.payflowreliability.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProviderPayoutReportRepository extends JpaRepository<ProviderPayoutReport, UUID> {
    Optional<ProviderPayoutReport> findBySettlementBatchId(UUID settlementBatchId);
}
