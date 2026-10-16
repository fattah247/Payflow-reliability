package id.fatarc.portfolio.payflowreliability.risk;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Takes the device-risk decision when a payment is created and follows it to its end. Every decision, confirmation,
 * expiry and label is an audit event in the payment's hash chain, with the rule and the points behind it.
 */
@Service
public class RiskService {
    private final RiskPolicy policy;
    private final RiskPolicyProperties properties;
    private final RiskDecisionRepository decisions;
    private final PaymentIntentRepository payments;
    private final AuditService auditService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RiskService(RiskPolicy policy, RiskPolicyProperties properties, RiskDecisionRepository decisions,
                       PaymentIntentRepository payments, AuditService auditService, MeterRegistry meterRegistry, Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.policy = policy;
        this.properties = properties;
        this.decisions = decisions;
        this.payments = payments;
        this.auditService = auditService;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Decides for a payment just created (in its creating transaction): allow, hold for a step-up, or block. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RiskDecision decide(PaymentIntent intent, RiskEvidence evidence, String actor) {
        RiskPolicy.Outcome outcome = policy.decide(evidence, intent.getAmount(), intent.getCurrency(), intent.getRiskAction());
        Instant now = clock.instant();
        RiskDecision decision = decisions.save(new RiskDecision(intent.getId(), intent.getMerchantId(), intent.getMerchantReference(),
                intent.getAmount(), intent.getCurrency(), intent.getRiskAction(), evidence, outcome, now));
        PaymentState previous = switch (outcome.decision()) {
            case ALLOW -> null;
            case REQUIRE_CONFIRMATION -> intent.requireConfirmation(outcome.requiredConfirmation());
            case BLOCK -> intent.block();
        };
        RiskPolicy.Points points = outcome.points();
        String metadata = "decision=" + outcome.decision() + ",rule=" + outcome.rule() + ",points=" + points.total()
                + "(device " + points.device() + ", amount " + points.amount() + ", action " + points.action()
                + (points.missingEvidence() > 0 ? ", missing evidence " + points.missingEvidence() : "") + ")"
                + ",deviceLevel=" + outcome.deviceLevel() + ",reportedLevel=" + (evidence == null ? null : evidence.level())
                + ",signals=" + outcome.signals() + ",confirmation=" + outcome.requiredConfirmation();
        auditService.record(AggregateType.PAYMENT_INTENT, intent.getId().toString(),
                intent.getId(), "RISK_DECISION", actor, explain(outcome), metadata);
        if (previous != null) {
            auditService.recordStateChange(intent.getId(), previous, intent.getState(), actor, explain(outcome), metadata);
        }
        meterRegistry.counter("payflow.risk.decisions", "decision", outcome.decision().name(), "rule", outcome.rule().name())
                .increment();
        meterRegistry.counter("payflow.risk.decided.amount", "decision", outcome.decision().name(), "currency", intent.getCurrency())
                .increment(intent.getAmount().doubleValue());
        return decision;
    }

    /** The customer confirmed the step-up; repeating a confirmation is a no-op. Caller holds the payment lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(PaymentIntent intent, RequiredConfirmation method, String actor) {
        RiskDecision decision = decisions.findByPaymentIntentId(intent.getId()).orElseThrow();
        if (intent.getConfirmedAt() != null) {
            return;
        }
        if (intent.getState() != PaymentState.REQUIRES_CONFIRMATION) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Payment " + intent.getId() + " is " + intent.getState() + "; there is no step-up to confirm.");
        }
        if (method != decision.getRequiredConfirmation()) {
            throw new BusinessException(ErrorCode.CONFIRMATION_METHOD_MISMATCH, "This step-up must be confirmed with "
                    + decision.getRequiredConfirmation() + ", not " + method + ".");
        }
        Instant now = clock.instant();
        PaymentState previous = intent.confirm(method, now);
        decision.settle(RiskOutcome.CONFIRMED, now);
        auditService.recordStateChange(intent.getId(), previous, intent.getState(), actor,
                "Step-up confirmed with " + method + "; the payment may start.", "decisionId=" + decision.getId());
    }

    /** A payment waiting for a step-up was cancelled: the step-up ended without a confirmation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCancelledBeforeConfirmation(PaymentIntent intent) {
        decisions.findByPaymentIntentId(intent.getId())
                .filter(decision -> decision.getOutcome() == RiskOutcome.AWAITING_CONFIRMATION)
                .ifPresent(decision -> decision.settle(RiskOutcome.CANCELLED_UNCONFIRMED, clock.instant()));
    }

    /** Expires step-ups nobody confirmed within the timeout (or {@code olderThan}, when given). */
    public int expireUnconfirmed(Duration olderThan) {
        Instant cutoff = clock.instant().minus(olderThan == null ? properties.confirmationTimeout() : olderThan);
        List<UUID> stale = transactions.execute(status -> decisions
                .findByOutcomeAndDecidedAtBefore(RiskOutcome.AWAITING_CONFIRMATION, cutoff.plusNanos(1)).stream()
                .map(RiskDecision::getPaymentIntentId).toList());
        int expired = 0;
        for (UUID paymentId : stale) {
            Boolean done = transactions.execute(status -> {
                PaymentIntent intent = payments.findForUpdate(paymentId).orElseThrow();
                RiskDecision decision = decisions.findByPaymentIntentId(paymentId).orElseThrow();
                if (intent.getState() != PaymentState.REQUIRES_CONFIRMATION || decision.getOutcome() != RiskOutcome.AWAITING_CONFIRMATION) {
                    return false;
                }
                PaymentState previous = intent.expire();
                decision.settle(RiskOutcome.CONFIRMATION_EXPIRED, clock.instant());
                auditService.recordStateChange(intent.getId(), previous, intent.getState(), "system:risk-expiry",
                        "Nobody confirmed the step-up in time; the payment expired without reaching the provider.",
                        "decisionId=" + decision.getId());
                return true;
            });
            expired += Boolean.TRUE.equals(done) ? 1 : 0;
        }
        return expired;
    }

    /**
     * Records what a payment turned out to be. Labels from chargebacks and reports arrive long after the decision;
     * a simulation discloses its ground truth this way too (and only a simulation can label blocked payments).
     */
    @Transactional
    public Optional<RiskDecision> label(UUID paymentIntentId, RiskLabel label, RiskLabel.Source source, String actor) {
        return decisions.findByPaymentIntentId(paymentIntentId).map(decision -> {
            decision.label(label, source, clock.instant());
            auditService.record(AggregateType.PAYMENT_INTENT,
                    paymentIntentId.toString(), paymentIntentId, "RISK_LABELLED", actor,
                    "Labelled " + label + " (" + source + ")", "decision=" + decision.getDecision() + ",rule=" + decision.getRule());
            return decision;
        });
    }

    @Transactional(readOnly = true)
    public Optional<RiskDecision> decisionFor(UUID paymentIntentId) {
        return decisions.findByPaymentIntentId(paymentIntentId);
    }

    private static String explain(RiskPolicy.Outcome outcome) {
        return switch (outcome.rule()) {
            case SEVERE_SIGNAL -> "Blocked: a high-severity device signal (" + outcome.signals() + ").";
            case SCORE_BLOCK -> "Blocked: " + outcome.points().total() + " risk points reached the block threshold.";
            case SCORE_STEP_UP -> "Step-up required (" + outcome.requiredConfirmation() + "): " + outcome.points().total()
                    + " risk points reached the step-up threshold.";
            case BELOW_THRESHOLDS -> "Allowed: " + outcome.points().total() + " risk points, below every threshold.";
        };
    }
}
