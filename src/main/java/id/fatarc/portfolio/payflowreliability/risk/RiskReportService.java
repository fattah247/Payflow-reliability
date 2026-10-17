package id.fatarc.portfolio.payflowreliability.risk;

import id.fatarc.portfolio.payflowreliability.common.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the device-risk gate blocks, and what that costs, in money and counts, from the decisions and the labels
 * learned since. "Flagged" means stepped up or blocked: the gate intervened.
 *
 * <ul>
 *   <li><b>fraud stopped</b>: fraud that was blocked, or stepped up and never confirmed;</li>
 *   <li><b>fraud loss</b>: fraud that was allowed, or stepped up and confirmed anyway;</li>
 *   <li><b>legitimate lost to friction</b>: honest customers blocked, or stepped up who gave up;</li>
 *   <li><b>precision</b>: of the payments the gate flagged, the share that were fraud;</li>
 *   <li><b>recall</b>: of the fraud, the share the gate flagged.</li>
 * </ul>
 *
 * The threshold sweep re-decides every labelled payment from its stored points under other thresholds. Where the
 * counterfactual decision is a step-up the payment never had, whether it would have been confirmed is unknown, so
 * the observed confirmation rate for its label is used (or the configured assumption when nothing was observed):
 * those rows are expected values, not observations.
 */
@Service
public class RiskReportService {
    private final RiskDecisionRepository decisions;
    private final RiskPolicy policy;
    private final RiskPolicyProperties properties;

    public RiskReportService(RiskDecisionRepository decisions, RiskPolicy policy, RiskPolicyProperties properties) {
        this.decisions = decisions;
        this.policy = policy;
        this.properties = properties;
    }

    public record Tally(long count, BigDecimal amount) {
        static Tally zero() {
            return new Tally(0, BigDecimal.ZERO);
        }

        Tally plus(BigDecimal value) {
            return new Tally(count + 1, amount.add(value));
        }
    }

    public record RuleRow(RiskDecisionType decision, RiskRule rule, Tally total, Tally fraud, Tally legitimate) {
    }

    /**
     * @param stepUpConfirmed   stepped up, then confirmed
     * @param stepUpAbandoned   stepped up, never confirmed (expired or cancelled)
     * @param stepUpAwaiting    stepped up, still waiting
     * @param precision         flagged and fraud / flagged (labelled decisions only); null when nothing was flagged
     * @param recall            flagged and fraud / fraud; null when no fraud is labelled
     * @param recallByAmount    fraud amount stopped / fraud amount
     */
    public record Report(String currency, String merchantId, RiskPolicy.Thresholds thresholds, Tally decided, Tally allowed, Tally steppedUp,
                         Tally stepUpConfirmed, Tally stepUpAbandoned, Tally stepUpAwaiting, Tally blocked,
                         List<RuleRow> byRule, Tally labelled, Tally fraud, Tally legitimate, Tally fraudStopped,
                         Tally fraudLoss, Tally legitimateLostToFriction, Tally legitimateSteppedUp, Double precision,
                         Double recall, Double recallByAmount, String note) {
    }

    /**
     * @param fraudLoss                expected fraud that gets through
     * @param legitimateLostToFriction expected honest money lost to blocks and abandoned step-ups
     */
    public record SweepRow(int value, long allowed, long steppedUp, long blocked, BigDecimal fraudLoss,
                           BigDecimal fraudStopped, BigDecimal legitimateLostToFriction, long legitimateSteppedUp,
                           Double precision, Double recall, boolean current) {
    }

    public record Sweep(String parameter, String currency, String merchantId, long labelledDecisions, double legitimateConfirmRate,
                        double fraudulentConfirmRate, String confirmRateSource, List<SweepRow> rows, String note) {
    }

    public enum Parameter { STEP_UP, BLOCK }

    public Report report(String currency) {
        return report(currency, null);
    }

    /** @param merchantId one merchant's decisions only; null for all */
    @Transactional(readOnly = true)
    public Report report(String currency, String merchantId) {
        List<RiskDecision> all = decisions(currency, merchantId);
        Tally decided = Tally.zero(), allowed = Tally.zero(), steppedUp = Tally.zero(), confirmed = Tally.zero(),
                abandoned = Tally.zero(), awaiting = Tally.zero(), blocked = Tally.zero(), labelled = Tally.zero(),
                fraud = Tally.zero(), legitimate = Tally.zero(), stopped = Tally.zero(), loss = Tally.zero(),
                friction = Tally.zero(), legitimateSteppedUp = Tally.zero();
        long flagged = 0, flaggedFraud = 0;
        Map<String, RuleRow> rules = new TreeMap<>();
        for (RiskDecision d : all) {
            BigDecimal amount = d.getAmount();
            decided = decided.plus(amount);
            switch (d.getDecision()) {
                case ALLOW -> allowed = allowed.plus(amount);
                case BLOCK -> blocked = blocked.plus(amount);
                case REQUIRE_CONFIRMATION -> {
                    steppedUp = steppedUp.plus(amount);
                    if (d.getOutcome() == RiskOutcome.CONFIRMED) {
                        confirmed = confirmed.plus(amount);
                    } else if (d.getOutcome().stepUpAbandoned()) {
                        abandoned = abandoned.plus(amount);
                    } else {
                        awaiting = awaiting.plus(amount);
                    }
                }
            }
            String key = d.getDecision() + "/" + d.getRule();
            RuleRow row = rules.getOrDefault(key, new RuleRow(d.getDecision(), d.getRule(), Tally.zero(), Tally.zero(), Tally.zero()));
            rules.put(key, new RuleRow(row.decision(), row.rule(), row.total().plus(amount),
                    d.getLabel() == RiskLabel.FRAUD ? row.fraud().plus(amount) : row.fraud(),
                    d.getLabel() == RiskLabel.LEGITIMATE ? row.legitimate().plus(amount) : row.legitimate()));
            if (d.getLabel() == null) {
                continue;
            }
            labelled = labelled.plus(amount);
            boolean isFraud = d.getLabel() == RiskLabel.FRAUD;
            boolean flaggedNow = d.getDecision() != RiskDecisionType.ALLOW;
            flagged += flaggedNow ? 1 : 0;
            flaggedFraud += flaggedNow && isFraud ? 1 : 0;
            boolean stoppedNow = d.getDecision() == RiskDecisionType.BLOCK || d.getOutcome().stepUpAbandoned();
            boolean passed = d.getDecision() == RiskDecisionType.ALLOW || d.getOutcome() == RiskOutcome.CONFIRMED;
            if (isFraud) {
                fraud = fraud.plus(amount);
                stopped = stoppedNow ? stopped.plus(amount) : stopped;
                loss = passed ? loss.plus(amount) : loss;
            } else {
                legitimate = legitimate.plus(amount);
                friction = stoppedNow ? friction.plus(amount) : friction;
                legitimateSteppedUp = d.getDecision() == RiskDecisionType.REQUIRE_CONFIRMATION ? legitimateSteppedUp.plus(amount) : legitimateSteppedUp;
            }
        }
        return new Report(currency, merchantId, policy.configured(), scaled(decided, currency), scaled(allowed, currency),
                scaled(steppedUp, currency), scaled(confirmed, currency), scaled(abandoned, currency), scaled(awaiting, currency),
                scaled(blocked, currency), new ArrayList<>(rules.values()), scaled(labelled, currency), scaled(fraud, currency),
                scaled(legitimate, currency), scaled(stopped, currency), scaled(loss, currency), scaled(friction, currency),
                scaled(legitimateSteppedUp, currency),
                flagged == 0 ? null : round((double) flaggedFraud / flagged),
                fraud.count() == 0 ? null : round((double) flaggedFraud / fraud.count()),
                fraud.amount().signum() == 0 ? null : round(stopped.amount().doubleValue() / fraud.amount().doubleValue()),
                labelled.count() < decided.count()
                        ? "Only labelled decisions count toward fraud, loss, friction, precision and recall."
                        : "Every decision is labelled. Outside a simulation, blocked payments are rarely labelled.");
    }

    public Sweep sweep(Parameter parameter, int from, int to, String currency) {
        return sweep(parameter, from, to, currency, null);
    }

    /** Re-decides every labelled payment (of one merchant, or all) with one threshold moved from {@code from} to {@code to}. */
    @Transactional(readOnly = true)
    public Sweep sweep(Parameter parameter, int from, int to, String currency, String merchantId) {
        List<RiskDecision> labelled = decisions(currency, merchantId).stream().filter(d -> d.getLabel() != null).toList();
        Map<RiskLabel, long[]> observed = new EnumMap<>(RiskLabel.class);
        for (RiskDecision d : labelled) {
            if (d.getDecision() == RiskDecisionType.REQUIRE_CONFIRMATION && d.getOutcome() != RiskOutcome.AWAITING_CONFIRMATION) {
                long[] counts = observed.computeIfAbsent(d.getLabel(), label -> new long[2]);
                counts[0] += d.getOutcome() == RiskOutcome.CONFIRMED ? 1 : 0;
                counts[1]++;
            }
        }
        double legitimateRate = rate(observed.get(RiskLabel.LEGITIMATE), properties.assumedConfirmRateLegitimate());
        double fraudRate = rate(observed.get(RiskLabel.FRAUD), properties.assumedConfirmRateFraudulent());
        String source = (observed.containsKey(RiskLabel.LEGITIMATE) ? "observed" : "assumed") + " for legitimate, "
                + (observed.containsKey(RiskLabel.FRAUD) ? "observed" : "assumed") + " for fraud";

        RiskPolicy.Thresholds configured = policy.configured();
        List<SweepRow> rows = new ArrayList<>();
        for (int value = from; value <= to; value++) {
            RiskPolicy.Thresholds thresholds = parameter == Parameter.STEP_UP
                    ? new RiskPolicy.Thresholds(value, configured.block(), configured.blockOnSevereSignal())
                    : new RiskPolicy.Thresholds(configured.stepUp(), value, configured.blockOnSevereSignal());
            long allowed = 0, steppedUp = 0, blocked = 0, legitimateSteppedUp = 0, flagged = 0, flaggedFraud = 0, fraudCount = 0;
            double loss = 0, stopped = 0, friction = 0;
            for (RiskDecision d : labelled) {
                double amount = d.getAmount().doubleValue();
                boolean isFraud = d.getLabel() == RiskLabel.FRAUD;
                fraudCount += isFraud ? 1 : 0;
                RiskDecisionType decision = RiskPolicy.decide(d.points(), thresholds);
                switch (decision) {
                    case ALLOW -> {
                        allowed++;
                        loss += isFraud ? amount : 0;
                    }
                    case BLOCK -> {
                        blocked++;
                        stopped += isFraud ? amount : 0;
                        friction += isFraud ? 0 : amount;
                    }
                    case REQUIRE_CONFIRMATION -> {
                        steppedUp++;
                        legitimateSteppedUp += isFraud ? 0 : 1;
                        double confirms = d.getDecision() == RiskDecisionType.REQUIRE_CONFIRMATION
                                && d.getOutcome() != RiskOutcome.AWAITING_CONFIRMATION
                                ? (d.getOutcome() == RiskOutcome.CONFIRMED ? 1 : 0)
                                : (isFraud ? fraudRate : legitimateRate);
                        if (isFraud) {
                            loss += confirms * amount;
                            stopped += (1 - confirms) * amount;
                        } else {
                            friction += (1 - confirms) * amount;
                        }
                    }
                }
                if (decision != RiskDecisionType.ALLOW) {
                    flagged++;
                    flaggedFraud += isFraud ? 1 : 0;
                }
            }
            boolean current = parameter == Parameter.STEP_UP ? value == configured.stepUp() : value == configured.block();
            rows.add(new SweepRow(value, allowed, steppedUp, blocked, money(loss, currency), money(stopped, currency),
                    money(friction, currency), legitimateSteppedUp, flagged == 0 ? null : round((double) flaggedFraud / flagged),
                    fraudCount == 0 ? null : round((double) flaggedFraud / fraudCount), current));
        }
        return new Sweep(parameter.name(), currency, merchantId, labelled.size(), round(legitimateRate), round(fraudRate), source, rows,
                "Counterfactual: each labelled payment re-decided from its stored points. A step-up it never had is valued at the "
                        + "confirmation rate for its label, so those amounts are expected values.");
    }

    private List<RiskDecision> decisions(String currency, String merchantId) {
        return merchantId == null ? decisions.findByCurrency(currency) : decisions.findByMerchantIdAndCurrency(merchantId, currency);
    }

    private static double rate(long[] counts, double assumed) {
        return counts == null || counts[1] == 0 ? assumed : (double) counts[0] / counts[1];
    }

    private static Tally scaled(Tally tally, String currency) {
        return new Tally(tally.count(), Money.atScale(tally.amount(), currency));
    }

    private static BigDecimal money(double value, String currency) {
        return BigDecimal.valueOf(value).setScale(Money.fractionDigits(currency), RoundingMode.HALF_UP);
    }

    private static Double round(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
