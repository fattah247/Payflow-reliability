package id.fatarc.portfolio.payflowreliability.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskDecisionRepository extends JpaRepository<RiskDecision, UUID> {
    Optional<RiskDecision> findByPaymentIntentId(UUID paymentIntentId);

    List<RiskDecision> findByOutcomeAndDecidedAtBefore(RiskOutcome outcome, Instant decidedBefore);

    List<RiskDecision> findByCurrency(String currency);

    List<RiskDecision> findByMerchantIdAndCurrency(String merchantId, String currency);
}
