package id.fatarc.portfolio.payflowreliability.risk;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The server-side reference policy. Pure: the same evidence, amount and action always give the same decision, so
 * decisions can be re-evaluated later with other thresholds (the threshold sweep).
 *
 * <ol>
 *   <li>Device points: the sum of the detected signals' severity weights (LOW 1, MEDIUM 2, HIGH 3). The device's
 *       level is recomputed exactly as trustgate-android's RiskScorer does: any HIGH signal is HIGH, 5 or more
 *       points HIGH, 2 or more MEDIUM, else LOW.</li>
 *   <li>Amount points: one per configured step the amount reaches (IDR).</li>
 *   <li>Action points: from what is being bought.</li>
 *   <li>Decision: a high-severity signal blocks (SEVERE_SIGNAL); otherwise the total against the block and step-up
 *       thresholds (SCORE_BLOCK, SCORE_STEP_UP), else ALLOW (BELOW_THRESHOLDS).</li>
 * </ol>
 */
@Component
public class RiskPolicy {
    private final RiskPolicyProperties properties;

    public RiskPolicy(RiskPolicyProperties properties) {
        this.properties = properties;
    }

    /** The points behind a decision, kept so the decision can be explained and re-evaluated. */
    public record Points(int device, int amount, int action, int missingEvidence, boolean severeSignal) {
        public int total() {
            return device + amount + action + missingEvidence;
        }
    }

    public record Outcome(RiskDecisionType decision, RiskRule rule, Points points, DeviceRiskLevel deviceLevel,
                          RequiredConfirmation requiredConfirmation, String signals) {
    }

    public Outcome decide(RiskEvidence evidence, BigDecimal amount, String currency, RiskAction action) {
        Points points = points(evidence, amount, currency, action);
        Thresholds thresholds = new Thresholds(properties.stepUpThreshold(), properties.blockThreshold(), properties.blockOnSevereSignal());
        RiskDecisionType decision = decide(points, thresholds);
        RequiredConfirmation confirmation = decision != RiskDecisionType.REQUIRE_CONFIRMATION ? RequiredConfirmation.NONE
                : evidence != null && evidence.stepUpKeyAvailable() ? RequiredConfirmation.STEP_UP_KEY : RequiredConfirmation.TAP;
        return new Outcome(decision, rule(points, thresholds), points, deviceLevel(evidence), confirmation, describe(evidence));
    }

    public Points points(RiskEvidence evidence, BigDecimal amount, String currency, RiskAction action) {
        List<RiskEvidence.Signal> signals = evidence == null ? List.of() : evidence.signals();
        int device = signals.stream().mapToInt(signal -> signal.severity().points()).sum();
        boolean severe = signals.stream().anyMatch(signal -> signal.severity() == SignalSeverity.HIGH);
        int amountPoints = "IDR".equals(currency)
                ? (int) properties.amountSteps().stream().filter(step -> amount.compareTo(step) >= 0).count() : 0;
        int actionPoints = properties.actionPoints().getOrDefault(action == null ? RiskAction.PHYSICAL_GOODS : action, 0);
        return new Points(device, amountPoints, actionPoints, evidence == null ? properties.missingEvidencePoints() : 0, severe);
    }

    /** The thresholds a decision is taken against; the sweep varies them. */
    public record Thresholds(int stepUp, int block, boolean blockOnSevereSignal) {
    }

    public Thresholds configured() {
        return new Thresholds(properties.stepUpThreshold(), properties.blockThreshold(), properties.blockOnSevereSignal());
    }

    public static RiskDecisionType decide(Points points, Thresholds thresholds) {
        if (thresholds.blockOnSevereSignal() && points.severeSignal()) {
            return RiskDecisionType.BLOCK;
        }
        if (points.total() >= thresholds.block()) {
            return RiskDecisionType.BLOCK;
        }
        if (points.total() >= thresholds.stepUp()) {
            return RiskDecisionType.REQUIRE_CONFIRMATION;
        }
        return RiskDecisionType.ALLOW;
    }

    static RiskRule rule(Points points, Thresholds thresholds) {
        if (thresholds.blockOnSevereSignal() && points.severeSignal()) {
            return RiskRule.SEVERE_SIGNAL;
        }
        if (points.total() >= thresholds.block()) {
            return RiskRule.SCORE_BLOCK;
        }
        return points.total() >= thresholds.stepUp() ? RiskRule.SCORE_STEP_UP : RiskRule.BELOW_THRESHOLDS;
    }

    /** The device's level by trustgate-android's own rules, from the signals alone. */
    static DeviceRiskLevel deviceLevel(RiskEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        int score = evidence.signals().stream().mapToInt(signal -> signal.severity().points()).sum();
        if (evidence.signals().stream().anyMatch(signal -> signal.severity() == SignalSeverity.HIGH) || score >= 5) {
            return DeviceRiskLevel.HIGH;
        }
        return score >= 2 ? DeviceRiskLevel.MEDIUM : DeviceRiskLevel.LOW;
    }

    private static String describe(RiskEvidence evidence) {
        if (evidence == null || evidence.signals().isEmpty()) {
            return "";
        }
        return evidence.signals().stream()
                .map(signal -> signal.id() + ":" + signal.category() + ":" + signal.severity() + ":" + signal.source())
                .collect(Collectors.joining(";"));
    }
}
