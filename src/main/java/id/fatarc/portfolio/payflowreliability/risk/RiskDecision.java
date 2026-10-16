package id.fatarc.portfolio.payflowreliability.risk;

import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The device-risk decision taken when a payment was created, with everything behind it: the rule, the points from
 * the device, the amount, the action and missing evidence, the signals and their sources. Taken once per payment:
 * a retry under the same idempotency key, with whatever evidence, is answered with this decision.
 */
@Entity
@Table(name = "risk_decisions")
public class RiskDecision {
    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false)
    private UUID paymentIntentId;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false, length = 100)
    private String merchantReference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskAction riskAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskDecisionType decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskRule rule;

    @Column(nullable = false)
    private int devicePoints;

    @Column(nullable = false)
    private int amountPoints;

    @Column(nullable = false)
    private int actionPoints;

    @Column(nullable = false)
    private int missingEvidencePoints;

    @Column(nullable = false)
    private int totalPoints;

    @Column(nullable = false)
    private boolean severeSignal;

    @Enumerated(EnumType.STRING)
    private DeviceRiskLevel deviceLevel;

    @Enumerated(EnumType.STRING)
    private DeviceRiskLevel reportedLevel;

    @Column(nullable = false)
    private boolean evidencePresent;

    @Column(nullable = false)
    private boolean simulatedEvidence;

    @Column(length = 1000)
    private String signals;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequiredConfirmation requiredConfirmation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskOutcome outcome;

    @Column(nullable = false)
    private Instant decidedAt;

    private Instant outcomeAt;

    @Enumerated(EnumType.STRING)
    private RiskLabel label;

    @Enumerated(EnumType.STRING)
    private RiskLabel.Source labelSource;

    private Instant labelledAt;

    protected RiskDecision() {
    }

    RiskDecision(UUID paymentIntentId, String merchantId, String merchantReference, BigDecimal amount, String currency,
                 RiskAction action, RiskEvidence evidence, RiskPolicy.Outcome outcome, Instant now) {
        this.id = UUID.randomUUID();
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
        this.amount = Money.normalize(amount, currency);
        this.currency = currency;
        this.riskAction = action;
        this.decision = outcome.decision();
        this.rule = outcome.rule();
        this.devicePoints = outcome.points().device();
        this.amountPoints = outcome.points().amount();
        this.actionPoints = outcome.points().action();
        this.missingEvidencePoints = outcome.points().missingEvidence();
        this.totalPoints = outcome.points().total();
        this.severeSignal = outcome.points().severeSignal();
        this.deviceLevel = outcome.deviceLevel();
        this.reportedLevel = evidence == null ? null : evidence.level();
        this.evidencePresent = evidence != null;
        this.simulatedEvidence = evidence != null && evidence.simulated();
        this.signals = Text.fit(outcome.signals(), 1000);
        this.requiredConfirmation = outcome.requiredConfirmation();
        this.outcome = switch (outcome.decision()) {
            case ALLOW -> RiskOutcome.ALLOWED;
            case REQUIRE_CONFIRMATION -> RiskOutcome.AWAITING_CONFIRMATION;
            case BLOCK -> RiskOutcome.BLOCKED;
        };
        this.decidedAt = now;
        this.outcomeAt = this.outcome == RiskOutcome.AWAITING_CONFIRMATION ? null : now;
    }

    void settle(RiskOutcome outcome, Instant now) {
        if (this.outcome != RiskOutcome.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Decision " + id + " already ended " + this.outcome);
        }
        this.outcome = outcome;
        this.outcomeAt = now;
    }

    void label(RiskLabel label, RiskLabel.Source source, Instant now) {
        this.label = label;
        this.labelSource = source;
        this.labelledAt = now;
    }

    /** The points behind this decision, for re-evaluating it under other thresholds. */
    public RiskPolicy.Points points() {
        return new RiskPolicy.Points(devicePoints, amountPoints, actionPoints, missingEvidencePoints, severeSignal);
    }

    public UUID getId() { return id; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getMerchantId() { return merchantId; }
    public String getMerchantReference() { return merchantReference; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCurrency() { return currency; }
    public RiskAction getRiskAction() { return riskAction; }
    public RiskDecisionType getDecision() { return decision; }
    public RiskRule getRule() { return rule; }
    public int getDevicePoints() { return devicePoints; }
    public int getAmountPoints() { return amountPoints; }
    public int getActionPoints() { return actionPoints; }
    public int getMissingEvidencePoints() { return missingEvidencePoints; }
    public int getTotalPoints() { return totalPoints; }
    public boolean isSevereSignal() { return severeSignal; }
    public DeviceRiskLevel getDeviceLevel() { return deviceLevel; }
    public DeviceRiskLevel getReportedLevel() { return reportedLevel; }
    public boolean isEvidencePresent() { return evidencePresent; }
    public boolean isSimulatedEvidence() { return simulatedEvidence; }
    public String getSignals() { return signals; }
    public RequiredConfirmation getRequiredConfirmation() { return requiredConfirmation; }
    public RiskOutcome getOutcome() { return outcome; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getOutcomeAt() { return outcomeAt; }
    public RiskLabel getLabel() { return label; }
    public RiskLabel.Source getLabelSource() { return labelSource; }
    public Instant getLabelledAt() { return labelledAt; }
}
