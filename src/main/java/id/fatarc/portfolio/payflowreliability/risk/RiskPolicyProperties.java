package id.fatarc.portfolio.payflowreliability.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The reference policy's knobs. With amount and action points at zero it decides exactly like the device app's own
 * gate (trustgate-android: step-up at 2 points, block at 5, block on any high-severity signal).
 *
 * @param stepUpThreshold       total points from which a payment needs a step-up confirmation
 * @param blockThreshold        total points from which a payment is blocked
 * @param blockOnSevereSignal   block whenever a single high-severity device signal is present
 * @param amountSteps           IDR amount from which 1, 2, ... points are added (ascending)
 * @param actionPoints          points per action
 * @param missingEvidencePoints points when the shop sends no device evidence at all (0: server-to-server shops are not penalised)
 * @param confirmationTimeout   how long a step-up waits for its confirmation before the payment expires
 * @param assumedConfirmRateLegitimate for the threshold sweep only: how often a legitimate customer completes a step-up
 *                              when the data has none to observe
 * @param assumedConfirmRateFraudulent the same for fraudsters
 */
@ConfigurationProperties("payflow.risk")
public record RiskPolicyProperties(Integer stepUpThreshold, Integer blockThreshold, Boolean blockOnSevereSignal,
                                   List<BigDecimal> amountSteps, Map<RiskAction, Integer> actionPoints,
                                   Integer missingEvidencePoints, Duration confirmationTimeout,
                                   Double assumedConfirmRateLegitimate, Double assumedConfirmRateFraudulent) {
    public RiskPolicyProperties {
        stepUpThreshold = stepUpThreshold == null ? 2 : stepUpThreshold;
        blockThreshold = blockThreshold == null ? 5 : blockThreshold;
        blockOnSevereSignal = blockOnSevereSignal == null || blockOnSevereSignal;
        amountSteps = amountSteps == null ? List.of(new BigDecimal("1000000"), new BigDecimal("5000000")) : amountSteps;
        actionPoints = actionPoints == null
                ? Map.of(RiskAction.PHYSICAL_GOODS, 0, RiskAction.DIGITAL_GOODS, 1, RiskAction.STORED_VALUE, 2) : actionPoints;
        missingEvidencePoints = missingEvidencePoints == null ? 0 : missingEvidencePoints;
        confirmationTimeout = confirmationTimeout == null ? Duration.ofMinutes(10) : confirmationTimeout;
        assumedConfirmRateLegitimate = assumedConfirmRateLegitimate == null ? 0.85 : assumedConfirmRateLegitimate;
        assumedConfirmRateFraudulent = assumedConfirmRateFraudulent == null ? 0.25 : assumedConfirmRateFraudulent;
    }
}
