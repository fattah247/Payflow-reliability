package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.ledger.LedgerInvariantService;
import id.fatarc.portfolio.payflowreliability.ledger.dto.InvariantReport;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The money rules the whole system must keep, whatever happened: checked in one snapshot, named one by one,
 * each with the evidence of every violation. The property-based test asserts these after every random sequence
 * of faults, and the chaos scenario after a live run. Rules that span us and the provider are checked against the
 * provider simulator's records when it runs here; with a real provider its reports play that role.
 */
@Service
public class MoneyInvariantService {
    private final LedgerInvariantService ledgerInvariantService;
    private final EntityManager entityManager;
    private final Clock clock;
    private final boolean providerSimulated;

    public MoneyInvariantService(LedgerInvariantService ledgerInvariantService, EntityManager entityManager, Clock clock,
                                 @Value("${payflow.provider-sim.enabled:false}") boolean providerSimulated) {
        this.ledgerInvariantService = ledgerInvariantService;
        this.entityManager = entityManager;
        this.clock = clock;
        this.providerSimulated = providerSimulated;
    }

    /**
     * @param holds      whether the rule holds right now
     * @param violations one line of evidence per violation (empty when it holds)
     */
    public record Rule(String name, String statement, boolean holds, List<String> violations) {
    }

    public record Report(Instant checkedAt, boolean allHold, List<Rule> rules) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report check() {
        List<Rule> rules = new ArrayList<>();
        InvariantReport ledger = ledgerInvariantService.check();
        rules.add(rule("BOOKS_BALANCE", "Every journal balances, and the books agree with payments, refunds, payouts, "
                        + "the bank statements and the open breaks, to the cent.",
                ledger.violations().stream().map(v -> v.kind() + ": " + v.detail()).toList()));

        rules.add(rule("CAPTURE_PAID_OUT_AT_MOST_ONCE", "Every captured payment is paid out at most once.",
                strings("""
                        select concat('payment ', cast(i.capturePaymentIntentId as string), ' is in ', cast(count(i) as string), ' batches')
                        from SettlementBatchItem i where i.capturePaymentIntentId is not null
                        group by i.capturePaymentIntentId having count(i) > 1""")));

        rules.add(rule("RECONCILED_ONLY_WITH_A_BANK_LINE", "No payment counts as reconciled without a bank credit that landed its payout.",
                strings("""
                        select concat('payment ', cast(p.id as string), ' is RECONCILED, payout ', b.payoutReference, ' is ', cast(b.payoutStatus as string))
                        from PaymentIntent p, SettlementBatchItem i, SettlementBatch b
                        where p.state = id.fatarc.portfolio.payflowreliability.payment.PaymentState.RECONCILED
                          and i.capturePaymentIntentId = p.id and b.id = i.batchId
                          and b.payoutStatus <> id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus.LEGACY
                          and b.landedAt is null""")));

        rules.add(rule("UNMATCHED_MONEY_HAS_A_BREAK", "Every bank line in suspense has an open break that says why.",
                strings("""
                        select concat('bank line ', l.accountServicerRef, ' (', cast(l.amount as string), ' ', l.currency, ') has no open break')
                        from BankStatementLine l
                        where l.matchStatus = id.fatarc.portfolio.payflowreliability.bank.BankLineStatus.SUSPENSE
                          and not exists (select 1 from ReconciliationBreak b where b.bankLineId = l.id
                              and b.status = id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus.OPEN)""")));

        rules.add(rule("BANK_ENTRY_BOOKED_ONCE", "Every bank entry (the bank's own reference on an account) is booked once, "
                        + "however often statements carrying it arrive.",
                strings("""
                        select concat('bank entry ', l.accountServicerRef, ' on ', l.accountId, ' is booked ', cast(count(l) as string), ' times')
                        from BankStatementLine l group by l.accountId, l.accountServicerRef having count(l) > 1""")));

        rules.add(rule("LANDING_IS_PROVEN", "Every landed payout is proven by a bank line carrying its reference, or by a named "
                        + "operator's assignment.",
                strings("""
                        select concat('payout ', b.payoutReference, ' landed on line ', l.accountServicerRef, ' with reference ',
                                      coalesce(l.endToEndId, 'none'))
                        from SettlementBatch b, BankStatementLine l
                        where b.landedAt is not null and l.id = b.bankLineId
                          and (l.endToEndId is null or l.endToEndId <> b.payoutReference)
                          and not exists (select 1 from ReconciliationBreak r where r.bankLineId = l.id
                              and r.resolution = id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakResolution.ASSIGNED_TO_BATCH)""")));

        rules.add(rule("BLOCKED_NEVER_REACHES_THE_PROVIDER", "A payment device risk blocked is never started, never captured "
                        + "and never the subject of a provider call.",
                strings("""
                        select concat('payment ', cast(p.id as string), ' was blocked (', cast(d.rule as string), ') but is ',
                                      cast(p.state as string), case when p.startedAt is not null then ', started' else '' end,
                                      case when p.capturedAt is not null then ', captured' else '' end)
                        from RiskDecision d, PaymentIntent p
                        where d.decision = id.fatarc.portfolio.payflowreliability.risk.RiskDecisionType.BLOCK
                          and p.id = d.paymentIntentId
                          and (p.state <> id.fatarc.portfolio.payflowreliability.payment.PaymentState.BLOCKED
                               or p.startedAt is not null or p.capturedAt is not null
                               or exists (select 1 from OutboxCommand c where c.paymentIntentId = p.id))""")));

        rules.add(rule("STEP_UP_CONFIRMED_BEFORE_CAPTURE", "A payment that needed a step-up is started, and so captured, only "
                        + "after the customer confirmed it with the required method.",
                strings("""
                        select concat('payment ', cast(p.id as string), ' needed ', cast(d.requiredConfirmation as string),
                                      ' but was ', case when p.capturedAt is not null then 'captured' else 'started' end,
                                      case when p.confirmedAt is null then ' without a confirmation' else ' before its confirmation' end)
                        from RiskDecision d, PaymentIntent p
                        where d.decision = id.fatarc.portfolio.payflowreliability.risk.RiskDecisionType.REQUIRE_CONFIRMATION
                          and p.id = d.paymentIntentId
                          and (p.startedAt is not null or p.capturedAt is not null)
                          and (p.confirmedAt is null or p.startedAt < p.confirmedAt
                               or p.confirmationMethod is null or p.confirmationMethod <> d.requiredConfirmation
                               or d.outcome <> id.fatarc.portfolio.payflowreliability.risk.RiskOutcome.CONFIRMED)""")));

        // Payments created before notifications existed (no payment.created event) have nothing to announce against.
        rules.add(rule("PAYMENT_CHANGES_ANNOUNCED", "Every payment's latest event tells the shop its current state, and every "
                        + "state change has its own event.",
                concat(strings("""
                        select concat('payment ', cast(p.id as string), ' is ', cast(p.state as string),
                                      ' but its latest event announces another state')
                        from PaymentIntent p
                        where exists (select 1 from MerchantEvent c where c.paymentIntentId = p.id
                                      and c.eventType = 'payment.created')
                          and not exists (select 1 from MerchantEvent e where e.paymentIntentId = p.id
                                          and e.eventType in ('payment.created', 'payment.updated')
                                          and e.state = cast(p.state as string)
                                          and e.sequence = (select max(l.sequence) from MerchantEvent l
                                                            where l.paymentIntentId = p.id
                                                              and l.eventType in ('payment.created', 'payment.updated')))"""),
                        strings("""
                        select concat('payment ', cast(p.id as string), ' changed state ',
                                      cast((select count(a) from AuditEvent a where a.paymentIntentId = p.id
                                            and a.eventType = 'TRANSACTION_STATE_CHANGED') as string),
                                      ' times but announced ',
                                      cast((select count(e) from MerchantEvent e where e.paymentIntentId = p.id
                                            and e.eventType = 'payment.updated') as string))
                        from PaymentIntent p
                        where exists (select 1 from MerchantEvent c where c.paymentIntentId = p.id
                                      and c.eventType = 'payment.created')
                          and (select count(a) from AuditEvent a where a.paymentIntentId = p.id
                               and a.eventType = 'TRANSACTION_STATE_CHANGED')
                              <> (select count(e) from MerchantEvent e where e.paymentIntentId = p.id
                                  and e.eventType = 'payment.updated')"""))));

        if (providerSimulated) {
            rules.add(rule("NO_RETRY_CHARGES_TWICE", "One business operation, one charge: no merchant reference is paid twice "
                            + "at the provider, and no idempotency key is applied twice.",
                    concat(strings("""
                            select concat('merchant reference ', p.merchantReference, ' of ', p.merchantId, ' was charged ',
                                          cast(count(s) as string), ' times')
                            from PaymentIntent p, SimPayment s
                            where s.paymentIntentId = p.id
                              and s.status in (id.fatarc.portfolio.payflowreliability.providersim.SimPayment.Status.SUCCESS,
                                               id.fatarc.portfolio.payflowreliability.providersim.SimPayment.Status.REVERSED)
                            group by p.merchantId, p.merchantReference having count(s) > 1"""), strings("""
                            select concat('key ', r.idempotencyKey, ' was applied ', cast(count(r) as string), ' times')
                            from SimApiRequest r
                            where r.outcome = id.fatarc.portfolio.payflowreliability.providersim.SimApiRequest.Outcome.APPLIED
                            group by r.idempotencyKey having count(r) > 1"""))));

            List<String> refundedTwice = new ArrayList<>();
            for (Object[] row : entityManager.createQuery("""
                    select p.id, p.refundedAmount, s.refundedAmount,
                           (select coalesce(sum(r.amount), 0) from RefundRequest r where r.paymentIntentId = p.id
                              and r.status = id.fatarc.portfolio.payflowreliability.refund.RefundStatus.REQUESTED)
                    from PaymentIntent p, SimPayment s where s.paymentIntentId = p.id""", Object[].class).getResultList()) {
                BigDecimal recorded = (BigDecimal) row[1];
                BigDecimal atProvider = (BigDecimal) row[2];
                BigDecimal inFlight = (BigDecimal) row[3];
                if (atProvider.compareTo(recorded) < 0 || atProvider.compareTo(recorded.add(inFlight)) > 0) {
                    refundedTwice.add("payment " + row[0] + ": provider refunded " + atProvider.toPlainString() + ", recorded "
                            + recorded.toPlainString() + ", in flight " + inFlight.toPlainString());
                }
            }
            rules.add(rule("REFUNDED_ONCE", "What the provider refunded equals what we recorded, plus refunds still in flight.",
                    refundedTwice));

            rules.add(rule("PAYOUT_SENT_ONCE", "The provider paid each batch out at most once.", strings("""
                    select concat('payout ', s.reference, ' was executed ', cast(count(s) as string), ' times')
                    from SimPayout s where s.status = id.fatarc.portfolio.payflowreliability.providersim.SimPayout.Status.SUCCEEDED
                    group by s.reference having count(s) > 1""")));
        }
        return new Report(clock.instant(), rules.stream().allMatch(Rule::holds), List.copyOf(rules));
    }

    private List<String> strings(String jpql) {
        return entityManager.createQuery(jpql, String.class).getResultList();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Rule rule(String name, String statement, List<String> violations) {
        return new Rule(name, statement, violations.isEmpty(), List.copyOf(violations));
    }
}
