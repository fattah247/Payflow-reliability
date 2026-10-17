package id.fatarc.portfolio.payflowreliability.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reference policy, without Spring. Its contract with the device app (trustgate-android) is that, with amount and
 * action points switched off, it decides exactly as the app's own gate does; everything else it adds is the
 * server's knowledge (how much, what for), never a different reading of the device.
 */
class DeviceRiskPolicyTest {
    /** The app's signals, with the category and severity the app assigns them. */
    private static final List<RiskEvidence.Signal> APP_SIGNALS = List.of(
            signal("root_su_path", "ROOT", SignalSeverity.HIGH),
            signal("root_package", "ROOT", SignalSeverity.HIGH),
            signal("test_keys", "ROOT", SignalSeverity.MEDIUM),
            signal("system_partition_writable", "ROOT", SignalSeverity.MEDIUM),
            signal("emulator_fingerprint", "EMULATOR", SignalSeverity.MEDIUM),
            signal("debugger_connected", "DEBUGGER", SignalSeverity.HIGH),
            signal("app_debuggable", "DEBUGGER", SignalSeverity.MEDIUM),
            signal("installer_source_unknown", "INSTALLER", SignalSeverity.LOW));

    private static final RiskPolicy DEVICE_ONLY = new RiskPolicy(new RiskPolicyProperties(2, 5, true, List.of(),
            Map.of(), 0, null, null, null));
    private static final RiskPolicy DEFAULT = new RiskPolicy(new RiskPolicyProperties(null, null, null, null, null,
            null, null, null, null));

    private static RiskEvidence.Signal signal(String id, String category, SignalSeverity severity) {
        return new RiskEvidence.Signal(id, category, severity, SignalSource.DEVICE_CHECK);
    }

    private static RiskEvidence evidence(boolean stepUpKey, RiskEvidence.Signal... signals) {
        return new RiskEvidence("device-1", null, false, stepUpKey, List.of(signals));
    }

    /** trustgate-android RiskScorer, as written in the app: any HIGH signal is HIGH, 5+ points HIGH, 2+ MEDIUM. */
    private static DeviceRiskLevel appLevel(List<RiskEvidence.Signal> signals) {
        int score = signals.stream().mapToInt(signal -> signal.severity().points()).sum();
        if (signals.stream().anyMatch(signal -> signal.severity() == SignalSeverity.HIGH) || score >= 5) {
            return DeviceRiskLevel.HIGH;
        }
        return score >= 2 ? DeviceRiskLevel.MEDIUM : DeviceRiskLevel.LOW;
    }

    /** trustgate-android SensitiveActionGate: LOW allows, MEDIUM asks for confirmation, HIGH blocks. */
    private static RiskDecisionType appDecision(DeviceRiskLevel level) {
        return switch (level) {
            case LOW -> RiskDecisionType.ALLOW;
            case MEDIUM -> RiskDecisionType.REQUIRE_CONFIRMATION;
            case HIGH -> RiskDecisionType.BLOCK;
        };
    }

    @Test
    void shouldDecideLikeTheAppForEveryCombinationOfItsSignals() {
        for (int mask = 0; mask < 1 << APP_SIGNALS.size(); mask++) {
            List<RiskEvidence.Signal> detected = new ArrayList<>();
            for (int bit = 0; bit < APP_SIGNALS.size(); bit++) {
                if ((mask & 1 << bit) != 0) {
                    detected.add(APP_SIGNALS.get(bit));
                }
            }
            RiskEvidence evidence = new RiskEvidence("device-1", null, false, true, detected);
            for (RiskAction action : RiskAction.values()) {
                RiskPolicy.Outcome outcome = DEVICE_ONLY.decide(evidence, new BigDecimal("9000000"), "IDR", action);
                assertThat(outcome.deviceLevel()).as("level for %s", detected).isEqualTo(appLevel(detected));
                assertThat(outcome.decision()).as("decision for %s", detected).isEqualTo(appDecision(appLevel(detected)));
            }
        }
    }

    @Test
    void shouldAskForTheSameConfirmationTheAppWould() {
        RiskEvidence.Signal emulator = APP_SIGNALS.get(4);
        assertThat(DEFAULT.decide(evidence(true, emulator), new BigDecimal("100000"), "IDR", RiskAction.PHYSICAL_GOODS)
                .requiredConfirmation()).as("a device with a step-up key signs with it").isEqualTo(RequiredConfirmation.STEP_UP_KEY);
        assertThat(DEFAULT.decide(evidence(false, emulator), new BigDecimal("100000"), "IDR", RiskAction.PHYSICAL_GOODS)
                .requiredConfirmation()).as("without one, a signed tap").isEqualTo(RequiredConfirmation.TAP);
        assertThat(DEFAULT.decide(evidence(true), new BigDecimal("100000"), "IDR", RiskAction.PHYSICAL_GOODS)
                .requiredConfirmation()).isEqualTo(RequiredConfirmation.NONE);
        assertThat(DEFAULT.decide(evidence(true, APP_SIGNALS.get(0)), new BigDecimal("100000"), "IDR", RiskAction.PHYSICAL_GOODS)
                .requiredConfirmation()).as("a block asks for nothing").isEqualTo(RequiredConfirmation.NONE);
    }

    @Test
    void shouldNeverTrustTheLevelTheDeviceReportsForItself() {
        RiskEvidence lying = new RiskEvidence("device-1", DeviceRiskLevel.LOW, false, true, List.of(APP_SIGNALS.get(1)));
        RiskPolicy.Outcome outcome = DEFAULT.decide(lying, new BigDecimal("10000"), "IDR", RiskAction.PHYSICAL_GOODS);

        assertThat(outcome.deviceLevel()).isEqualTo(DeviceRiskLevel.HIGH);
        assertThat(outcome.decision()).isEqualTo(RiskDecisionType.BLOCK);
        assertThat(outcome.rule()).isEqualTo(RiskRule.SEVERE_SIGNAL);
    }

    @Test
    void shouldAddWhatOnlyTheServerKnowsTheAmountAndWhatIsBought() {
        RiskEvidence clean = evidence(true);

        RiskPolicy.Outcome small = DEFAULT.decide(clean, new BigDecimal("250000"), "IDR", RiskAction.PHYSICAL_GOODS);
        assertThat(small.points().total()).isZero();
        assertThat(small.decision()).isEqualTo(RiskDecisionType.ALLOW);
        assertThat(small.rule()).isEqualTo(RiskRule.BELOW_THRESHOLDS);

        RiskPolicy.Outcome topUp = DEFAULT.decide(clean, new BigDecimal("1500000"), "IDR", RiskAction.STORED_VALUE);
        assertThat(topUp.points()).isEqualTo(new RiskPolicy.Points(0, 1, 2, 0, false));
        assertThat(topUp.decision()).as("a clean phone buying 1.5M of stored value").isEqualTo(RiskDecisionType.REQUIRE_CONFIRMATION);
        assertThat(topUp.rule()).isEqualTo(RiskRule.SCORE_STEP_UP);

        RiskPolicy.Outcome large = DEFAULT.decide(evidence(true, APP_SIGNALS.get(7)), new BigDecimal("6000000"), "IDR",
                RiskAction.STORED_VALUE);
        assertThat(large.points()).isEqualTo(new RiskPolicy.Points(1, 2, 2, 0, false));
        assertThat(large.decision()).isEqualTo(RiskDecisionType.BLOCK);
        assertThat(large.rule()).isEqualTo(RiskRule.SCORE_BLOCK);

        assertThat(DEFAULT.decide(clean, new BigDecimal("6000000"), "USD", RiskAction.PHYSICAL_GOODS).points().amount())
                .as("amount steps are in IDR; other currencies add none").isZero();
    }

    @Test
    void shouldNotPenaliseShopsThatSendNoEvidenceUnlessConfiguredTo() {
        RiskPolicy.Outcome serverToServer = DEFAULT.decide(null, new BigDecimal("100000"), "IDR", RiskAction.PHYSICAL_GOODS);
        assertThat(serverToServer.decision()).isEqualTo(RiskDecisionType.ALLOW);
        assertThat(serverToServer.deviceLevel()).isNull();

        RiskPolicy strict = new RiskPolicy(new RiskPolicyProperties(null, null, null, null, null, 2, null, null, null));
        RiskPolicy.Outcome outcome = strict.decide(null, new BigDecimal("100000"), "IDR", RiskAction.PHYSICAL_GOODS);
        assertThat(outcome.points().missingEvidence()).isEqualTo(2);
        assertThat(outcome.decision()).isEqualTo(RiskDecisionType.REQUIRE_CONFIRMATION);
        assertThat(outcome.requiredConfirmation()).as("no device, no step-up key").isEqualTo(RequiredConfirmation.TAP);
    }

    @Test
    void shouldLetASevereSignalCountAsPointsWhenTheHardBlockIsSwitchedOff() {
        RiskPolicy pointsOnly = new RiskPolicy(new RiskPolicyProperties(null, null, false, null, null, null, null, null, null));
        RiskPolicy.Outcome outcome = pointsOnly.decide(evidence(true, APP_SIGNALS.get(5)), new BigDecimal("100000"), "IDR",
                RiskAction.PHYSICAL_GOODS);

        assertThat(outcome.points().severeSignal()).isTrue();
        assertThat(outcome.decision()).as("3 points: a step-up, not a block").isEqualTo(RiskDecisionType.REQUIRE_CONFIRMATION);
    }

    @Test
    void shouldGiveTheSameDecisionForTheSamePointsWhateverTheThresholdsAreCalled() {
        RiskPolicy.Points four = new RiskPolicy.Points(2, 1, 1, 0, false);
        assertThat(RiskPolicy.decide(four, new RiskPolicy.Thresholds(2, 5, true))).isEqualTo(RiskDecisionType.REQUIRE_CONFIRMATION);
        assertThat(RiskPolicy.decide(four, new RiskPolicy.Thresholds(5, 6, true))).isEqualTo(RiskDecisionType.ALLOW);
        assertThat(RiskPolicy.decide(four, new RiskPolicy.Thresholds(2, 4, true))).isEqualTo(RiskDecisionType.BLOCK);
    }
}
