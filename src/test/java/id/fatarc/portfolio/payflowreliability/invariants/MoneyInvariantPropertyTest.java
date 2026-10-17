package id.fatarc.portfolio.payflowreliability.invariants;

import id.fatarc.portfolio.payflowreliability.bank.BankStatementRepository;
import id.fatarc.portfolio.payflowreliability.banksim.BankFaults;
import id.fatarc.portfolio.payflowreliability.banksim.BankSimStatement;
import id.fatarc.portfolio.payflowreliability.operations.MoneyInvariantService;
import id.fatarc.portfolio.payflowreliability.payment.CancellationReason;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.providersim.ProviderSimulator;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.providersim.SimLatency;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakAction;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakResolutionService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ResolveBreakRequest;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.risk.RiskAction;
import id.fatarc.portfolio.payflowreliability.risk.RiskDecision;
import id.fatarc.portfolio.payflowreliability.risk.RiskDecisionRepository;
import id.fatarc.portfolio.payflowreliability.risk.RiskDecisionType;
import id.fatarc.portfolio.payflowreliability.risk.RiskEvidence;
import id.fatarc.portfolio.payflowreliability.risk.RiskOutcome;
import id.fatarc.portfolio.payflowreliability.risk.SignalSeverity;
import id.fatarc.portfolio.payflowreliability.risk.SignalSource;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.support.CrashableProviderClient;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.statistics.Statistics;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long random sequences of everything that goes wrong in payments, run against the real application (real
 * transactions, the provider and bank simulators over real HTTP), followed by one question: do the money rules
 * still hold? jqwik generates the sequences, and when one breaks a rule it shrinks it to the shortest sequence
 * that still does, and prints the seed to replay it.
 *
 * <p>The alphabet: payment creation with retries under one key, customers paying with duplicated, lost and
 * reordered webhooks, status sweeps, partial refunds with provider outages, commit-then-timeout and refusals,
 * workers that crash right after the provider acted, racing settlement runs, chargebacks before and after payout,
 * every bank fault, statements re-imported or arriving out of order, the provider's report, operators assigning
 * unmatched credits, a provider that turns slow (webhooks held back while it stays up) and shops that give up on
 * a checkout, device-risk decisions (step-ups confirmed or left to expire, blocks, a provider that charges a blocked
 * or unconfirmed payment anyway), and time passing.
 */
@JqwikSpringSupport
class MoneyInvariantPropertyTest extends IntegrationTest {
    private static final String[] MERCHANTS = {MERCHANT_A, MERCHANT_B};

    @Autowired
    private MoneyInvariantService moneyInvariantService;
    @Autowired
    private BankStatementRepository bankStatementRepository;
    @Autowired
    private BreakResolutionService breakResolutionService;
    @Autowired
    private RiskDecisionRepository riskDecisionRepository;

    private final List<PaymentIntent> payments = new ArrayList<>();
    private int sequence;
    private boolean crashed;
    private boolean crashPending;
    private boolean reissued;
    private boolean rogueCharge;
    private boolean slowed;
    private final List<Integer> deferredReissues = new ArrayList<>();

    // ---- the alphabet -----------------------------------------------------------------------------

    sealed interface Action permits Create, RiskyCreate, Pay, Deliver, Sweep, Refund, RunOutbox, Settle, Chargeback,
            BankFault, CloseDays, ImportStatements, Reimport, Reissue, ProviderReport, ThreeWay, AssignUnmatched, Slow,
            Abandon, Wait {
    }

    /** What the customer's device reports: nothing wrong, enough for a step-up, or a high-severity signal. */
    enum Device { CLEAN, STEP_UP, BLOCK }

    /** A checkout: created once, retried {@code retries} times under the same key. */
    record Create(int merchant, int amount, int retries) implements Action { }

    /**
     * A checkout with device evidence. A step-up is confirmed or left to expire; with {@code rogue} the provider
     * charges the payment anyway while it is blocked or unconfirmed (a stale page, a misbehaving integration).
     */
    record RiskyCreate(int merchant, int amount, Device device, boolean confirm, boolean rogue) implements Action { }

    record Pay(int payment, int duplicates, boolean lost, boolean authorizeFirst) implements Action { }

    record Deliver() implements Action { }

    record Sweep() implements Action { }

    record Refund(int payment, int percent, SimFaults.Mode fault) implements Action { }

    /** Runs the outbox; with {@code crash} the worker dies right after the provider answers its first call. */
    record RunOutbox(boolean crash) implements Action { }

    record Settle(int merchant, int racers) implements Action { }

    record Chargeback(int payment) implements Action { }

    record BankFault(BankFaults.Mode mode) implements Action { }

    record CloseDays(int days) implements Action { }

    record ImportStatements(boolean reverseOrder) implements Action { }

    record Reimport(int statement) implements Action { }

    /** The bank sends the entries of an earlier statement again, under a new statement id and number (a bank error). */
    record Reissue(int statement) implements Action { }

    record ProviderReport() implements Action { }

    record ThreeWay() implements Action { }

    record AssignUnmatched() implements Action { }

    /** The provider turns slow (its webhooks held back while it stays up), or recovers. */
    record Slow(boolean on) implements Action { }

    /** The shop gives up on a checkout still waiting for its result, perhaps after the customer already paid. */
    record Abandon(int payment) implements Action { }

    record Wait(int minutes) implements Action { }

    @Provide
    Arbitrary<List<Action>> sequences() {
        Arbitrary<Integer> index = Arbitraries.integers().between(0, 30);
        Arbitrary<Action> action = Arbitraries.frequencyOf(
                Tuple.of(14, Combinators.combine(Arbitraries.integers().between(0, 1), Arbitraries.integers().between(1, 40),
                        Arbitraries.integers().between(0, 2)).as((m, a, r) -> (Action) new Create(m, a * 500, r))),
                Tuple.of(7, Combinators.combine(Arbitraries.integers().between(0, 1), Arbitraries.integers().between(1, 40),
                        Arbitraries.of(Device.CLEAN, Device.STEP_UP, Device.STEP_UP, Device.BLOCK), Arbitraries.of(true, true, false),
                        Arbitraries.of(false, false, false, true))
                        .as((m, a, d, c, r) -> (Action) new RiskyCreate(m, a * 500, d, c, r))),
                Tuple.of(12, Combinators.combine(index, Arbitraries.integers().between(0, 2), Arbitraries.of(false, false, true),
                        Arbitraries.of(false, true)).as((p, d, l, auth) -> (Action) new Pay(p, d, l, auth))),
                Tuple.of(6, Arbitraries.just((Action) new Deliver())),
                Tuple.of(3, Arbitraries.just((Action) new Sweep())),
                Tuple.of(6, Combinators.combine(index, Arbitraries.integers().between(10, 100),
                        Arbitraries.of(SimFaults.Mode.NONE, SimFaults.Mode.NONE, SimFaults.Mode.UNAVAILABLE,
                                SimFaults.Mode.TIMEOUT_AFTER_COMMIT, SimFaults.Mode.REJECT))
                        .as((p, pct, fault) -> (Action) new Refund(p, pct, fault))),
                Tuple.of(8, Arbitraries.of(false, false, true).map(crash -> (Action) new RunOutbox(crash))),
                Tuple.of(9, Combinators.combine(Arbitraries.integers().between(0, 1), Arbitraries.integers().between(1, 3))
                        .as((m, r) -> (Action) new Settle(m, r))),
                Tuple.of(2, index.map(p -> (Action) new Chargeback(p))),
                Tuple.of(6, Arbitraries.of(BankFaults.Mode.class).map(mode -> (Action) new BankFault(mode))),
                Tuple.of(6, Arbitraries.integers().between(1, 3).map(days -> (Action) new CloseDays(days))),
                Tuple.of(6, Arbitraries.of(false, false, true).map(reverse -> (Action) new ImportStatements(reverse))),
                Tuple.of(2, index.map(statement -> (Action) new Reimport(statement))),
                Tuple.of(2, index.map(statement -> (Action) new Reissue(statement))),
                Tuple.of(3, Arbitraries.just((Action) new ProviderReport())),
                Tuple.of(3, Arbitraries.just((Action) new ThreeWay())),
                Tuple.of(2, Arbitraries.just((Action) new AssignUnmatched())),
                Tuple.of(5, Arbitraries.of(true, true, false).map(on -> (Action) new Slow(on))),
                Tuple.of(2, index.map(p -> (Action) new Abandon(p))),
                Tuple.of(2, Arbitraries.integers().between(1, 180).map(minutes -> (Action) new Wait(minutes))));
        return action.list().ofMinSize(20).ofMaxSize(60);
    }

    @BeforeTry
    void freshWorld() {
        resetWorld();
        payments.clear();
        sequence = 0;
        crashed = false;
        crashPending = false;
        reissued = false;
        rogueCharge = false;
        slowed = false;
        deferredReissues.clear();
    }

    @Property(tries = 50, shrinking = ShrinkingMode.BOUNDED)
    void moneyRulesHoldWhateverHappens(@ForAll("sequences") List<Action> actions) {
        for (Action action : actions) {
            apply(action);
        }
        settleDown();

        MoneyInvariantService.Report report = moneyInvariantService.check();
        assertThat(report.rules()).as("money rules after %s", actions)
                .allSatisfy(rule -> assertThat(rule.violations()).as(rule.name() + ": " + rule.statement()).isEmpty());
        collectCoverage();
    }

    /**
     * What the random sequences actually reached, printed with every run and checked: a property that only ever
     * exercised the happy path would prove nothing.
     */
    private void collectCoverage() {
        List<SettlementBatch> batches = settlementBatchRepository.findAll();
        boolean landed = batches.stream().anyMatch(batch -> batch.getPayoutStatus() == PayoutStatus.LANDED);
        boolean broken = batches.stream().anyMatch(batch -> batch.getPayoutStatus() == PayoutStatus.BROKEN);
        boolean reconciled = payments.stream().anyMatch(intent -> reload(intent).getState() == PaymentState.RECONCILED);
        boolean suspense = breakRepository.findAll().stream().anyMatch(open -> open.getCategory().holdsSuspense());
        Statistics.label("a payout landed").collect(landed);
        Statistics.label("a payout broke").collect(broken);
        Statistics.label("money went to suspense").collect(suspense);
        Statistics.label("a payment reconciled three ways").collect(reconciled);
        Statistics.label("a worker crashed after the provider acted").collect(crashed);
        Statistics.label("the bank re-issued a statement").collect(reissued);
        List<RiskDecision> decisions = riskDecisionRepository.findAll();
        Statistics.label("a payment was blocked").collect(decisions.stream().anyMatch(d -> d.getDecision() == RiskDecisionType.BLOCK));
        Statistics.label("a step-up was confirmed and captured").collect(decisions.stream()
                .anyMatch(d -> d.getOutcome() == RiskOutcome.CONFIRMED && paymentIntentRepository.findById(d.getPaymentIntentId())
                        .map(PaymentIntent::isCaptured).orElse(false)));
        Statistics.label("the provider charged a blocked or unconfirmed payment").collect(rogueCharge);
        Statistics.label("the provider was slow").collect(slowed);
        breakRepository.findAll().stream().map(ReconciliationBreak::getCategory).distinct()
                .forEach(category -> Statistics.label("break categories").collect(category));

        Statistics.label("a payout landed").coverage(coverage -> coverage.check(true).count(count -> count >= 15));
        Statistics.label("a payout broke").coverage(coverage -> coverage.check(true).count(count -> count >= 1));
        Statistics.label("money went to suspense").coverage(coverage -> coverage.check(true).count(count -> count >= 3));
        Statistics.label("a payment reconciled three ways").coverage(coverage -> coverage.check(true).count(count -> count >= 1));
        Statistics.label("a worker crashed after the provider acted").coverage(coverage -> coverage.check(true).count(count -> count >= 3));
        Statistics.label("the bank re-issued a statement").coverage(coverage -> coverage.check(true).count(count -> count >= 3));
        Statistics.label("a payment was blocked").coverage(coverage -> coverage.check(true).count(count -> count >= 10));
        Statistics.label("a step-up was confirmed and captured").coverage(coverage -> coverage.check(true).count(count -> count >= 3));
        Statistics.label("the provider charged a blocked or unconfirmed payment").coverage(coverage -> coverage.check(true).count(count -> count >= 5));
        Statistics.label("the provider was slow").coverage(coverage -> coverage.check(true).count(count -> count >= 10));
    }

    // ---- what each action does --------------------------------------------------------------------

    private void apply(Action action) {
        try {
            switch (action) {
                case Create create -> create(create);
                case RiskyCreate create -> riskyCreate(create);
                case Pay pay -> pay(pay);
                case Deliver ignored -> deliverWebhooks();
                case Sweep ignored -> sweeper.runOnce(Duration.ZERO);
                case Refund refund -> refund(refund);
                case RunOutbox run -> runOutbox(run.crash());
                case Settle settle -> settleRacing(settle);
                case Chargeback chargeback -> chargeback(chargeback);
                case BankFault fault -> bankFaults.inject(fault.mode(), 1, fault.mode() == BankFaults.Mode.LATE_CREDIT ? 1 + sequence % 4 : null,
                        fault.mode() == BankFaults.Mode.NET_OF_FEES ? new BigDecimal("6.50") : null);
                case CloseDays close -> closeBankDays("IDR", bankSimulator.businessDate("IDR").plusDays(close.days() - 1));
                case ImportStatements imports -> importStatements(imports.reverseOrder());
                case Reimport reimport -> reimport(reimport.statement());
                case Reissue reissue -> reissue(reissue.statement());
                case ProviderReport ignored -> providerReport();
                case ThreeWay ignored -> threeWayReconciliationService.run(OPS_ANA.name());
                case AssignUnmatched ignored -> assignUnmatched();
                case Slow slow -> slow(slow.on());
                case Abandon abandon -> abandon(abandon.payment());
                case Wait wait -> clock.advance(Duration.ofMinutes(wait.minutes()));
            }
        } catch (CrashableProviderClient.SimulatedCrash crash) {
            throw new AssertionError("A crash escaped its action: " + action, crash);
        } catch (RuntimeException expectedRefusal) {
            // A refused request (nothing to settle, not refundable, already paid...) is part of the story, not a failure.
        } finally {
            sequence++;
        }
    }

    private void create(Create create) {
        String merchant = MERCHANTS[create.merchant()];
        String reference = "PROP-" + sequence;
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(null, reference, new BigDecimal(create.amount()), "IDR");
        PaymentIntent intent = null;
        // One key per business operation, generated before the first attempt and reused for every retry.
        for (int attempt = 0; attempt <= create.retries(); attempt++) {
            intent = paymentIntentService.create(request, "checkout-" + reference, Caller.merchant(merchant)).intent();
        }
        payments.add(paymentIntentService.start(intent.getId(), Caller.merchant(merchant)));
    }

    private void riskyCreate(RiskyCreate create) {
        String merchant = MERCHANTS[create.merchant()];
        String reference = "PROP-" + sequence;
        List<RiskEvidence.Signal> signals = switch (create.device()) {
            case CLEAN -> List.of();
            case STEP_UP -> List.of(new RiskEvidence.Signal("emulator_fingerprint", "EMULATOR", SignalSeverity.MEDIUM, SignalSource.DEVICE_CHECK));
            case BLOCK -> List.of(new RiskEvidence.Signal("root_su_path", "ROOT", SignalSeverity.HIGH, SignalSource.DEVICE_CHECK));
        };
        PaymentIntent intent = paymentIntentService.create(new CreatePaymentIntentRequest(null, reference,
                new BigDecimal(create.amount()), "IDR", RiskAction.PHYSICAL_GOODS,
                new RiskEvidence("device-" + sequence, null, true, sequence % 2 == 0, signals)),
                "checkout-" + reference, Caller.merchant(merchant)).intent();
        if (intent.getState() == PaymentState.REQUIRES_CONFIRMATION && create.confirm() && !create.rogue()) {
            intent = paymentIntentService.confirm(intent.getId(), intent.getRequiredConfirmation(), Caller.merchant(merchant));
        }
        if (create.rogue() && intent.getState() != PaymentState.CREATED) {
            providerSimulator.pay(intent.getId(), intent.getAmount(), "IDR", null);
            deliverWebhooks();
            rogueCharge = true;
        }
        // The shop tries to start whatever it got back; a hold or a block refuses, and nothing reaches the provider.
        PaymentIntent started = paymentIntentService.start(intent.getId(), Caller.merchant(merchant));
        payments.add(started);
        if (started.getConfirmedAt() != null) {
            // Having confirmed, the customer goes on to pay.
            providerSimulator.pay(started.getId(), started.getAmount(), "IDR", null);
            deliverWebhooks();
        }
    }

    private void slow(boolean on) {
        if (on) {
            simLatency.slow(new SimLatency.Mode(4000, 7000, true));
            slowed = true;
        } else {
            simLatency.recover();
        }
    }

    private void abandon(int index) {
        List<PaymentIntent> waiting = payments.stream().map(this::reload)
                .filter(intent -> intent.getState() == PaymentState.PENDING).toList();
        if (!waiting.isEmpty()) {
            PaymentIntent intent = waiting.get(index % waiting.size());
            paymentIntentService.cancel(intent.getId(), CancellationReason.ABANDONED, Caller.merchant(intent.getMerchantId()));
        }
    }

    private PaymentIntent payment(int index) {
        return payments.isEmpty() ? null : reload(payments.get(index % payments.size()));
    }

    /** A customer pays one of the checkouts still waiting for payment. */
    private void pay(Pay pay) {
        List<PaymentIntent> waiting = payments.stream().map(this::reload)
                .filter(intent -> intent.getState() == PaymentState.PENDING && providerSimulator.payment(intent.getId()).isEmpty())
                .toList();
        if (waiting.isEmpty()) {
            return;
        }
        PaymentIntent intent = waiting.get(pay.payment() % waiting.size());
        providerSimulator.pay(intent.getId(), intent.getAmount(), "IDR",
                new ProviderSimulator.CustomerOptions(!pay.lost(), 1 + pay.duplicates(), null, pay.authorizeFirst()));
        deliverWebhooks();
    }

    private void refund(Refund refund) {
        PaymentIntent intent = payment(refund.payment());
        if (intent == null || !intent.canRequestRefund()) {
            return;
        }
        BigDecimal amount = intent.getAmount().subtract(intent.getRefundedAmount())
                .multiply(BigDecimal.valueOf(refund.percent())).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        if (amount.signum() <= 0) {
            return;
        }
        if (refund.fault() != SimFaults.Mode.NONE) {
            simFaults.inject(SimFaults.Operation.REFUND, refund.fault(), refund.fault() == SimFaults.Mode.UNAVAILABLE ? 2 : 1);
        }
        refundService.requestRefund(intent.getId(), new CreateRefundRequest(amount, "Customer request"),
                "refund-" + sequence, Caller.merchant(intent.getMerchantId()));
    }

    /**
     * Runs the outbox. A crash with nothing due to crash on is kept for the next run that has something: the
     * point is a worker dying right after the provider acted, not an idle worker dying.
     */
    private void runOutbox(boolean crash) {
        if (!crash && !crashPending) {
            drainOutbox();
            return;
        }
        providerClient.crashAfterNextCall();
        int processed = 0;
        try {
            processed = outboxWorker.runOnce();
        } catch (CrashableProviderClient.SimulatedCrash died) {
            // The worker died after the provider acted; its lease expires and another worker carries on.
            crashed = true;
            processed = 1;
        } finally {
            providerClient.reset();
        }
        crashPending = !crashed && processed == 0;
        clock.advance(Duration.ofSeconds(3));
    }

    /** {@code racers} settlement runs for one merchant released at the same instant. */
    private void settleRacing(Settle settle) {
        try {
            race(settle.racers(), () -> settle(MERCHANTS[settle.merchant()]));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void chargeback(Chargeback chargeback) {
        PaymentIntent intent = payment(chargeback.payment());
        if (intent == null || !intent.isCaptured()) {
            return;
        }
        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();
    }

    private void importStatements(boolean reverseOrder) {
        if (!reverseOrder) {
            importStatements();
            return;
        }
        List<BankSimStatement> waiting = new ArrayList<>();
        for (var account : bankSimulator.accounts()) {
            bankSimulator.statementsAfter(account.getAccountId(), 0).stream()
                    .filter(statement -> bankStatementRepository.findByStatementId(statement.getStatementId()).isEmpty())
                    .distinct()
                    .forEach(waiting::add);
        }
        Collections.reverse(waiting);
        for (BankSimStatement statement : waiting) {
            bankStatementImportService.importStatement(statement.getContent().getBytes(StandardCharsets.UTF_8), OPS_ANA.name());
        }
    }

    private void reimport(int index) {
        List<BankSimStatement> issued = new ArrayList<>();
        bankSimulator.accounts().forEach(account -> issued.addAll(bankSimulator.statementsAfter(account.getAccountId(), 0)));
        if (!issued.isEmpty()) {
            BankSimStatement statement = issued.get(index % issued.size());
            bankStatementImportService.importStatement(statement.getContent().getBytes(StandardCharsets.UTF_8), OPS_ANA.name());
        }
    }

    private void reissue(int index) {
        List<BankSimStatement> issued = new ArrayList<>();
        bankSimulator.accounts().forEach(account -> bankSimulator.statementsAfter(account.getAccountId(), 0).stream()
                .filter(statement -> statement.getContent().contains("<Ntry>"))
                .forEach(issued::add));
        if (issued.isEmpty()) {
            // Nothing with entries to re-send yet: the bank does it once there is.
            deferredReissues.add(index);
            return;
        }
        BankSimStatement original = issued.get(index % issued.size());
        // A number far from the real sequence, so the copy never collides with a statement still to come.
        String copy = original.getContent().replace(original.getStatementId(), original.getStatementId() + "-C" + sequence)
                .replace("<ElctrncSeqNb>" + original.getSequenceNumber() + "<", "<ElctrncSeqNb>" + (900 + sequence) + "<");
        bankStatementImportService.importStatement(copy.getBytes(StandardCharsets.UTF_8), OPS_ANA.name());
        reissued = true;
    }

    private void providerReport() throws RuntimeException {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
        try {
            String report = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/provider-sim/v1/reports/settlement?date=" + today)
                            .header("Authorization", "Bearer sim_test_provider_key"))
                    .andReturn().getResponse().getContentAsString();
            mockMvc.perform(json(as(OPS_ANA_KEY, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/reconciliation/reports")), report));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** An operator matches an unmatched or late credit to the in-transit payout it fits, as treasury would after asking the bank. */
    private void assignUnmatched() {
        for (ReconciliationBreak open : openBreaks()) {
            if (!EnumSet.of(BreakCategory.UNMATCHED_CREDIT, BreakCategory.LATE_CREDIT).contains(open.getCategory())) {
                continue;
            }
            for (SettlementBatch batch : settlementBatchRepository.findByPayoutStatusInOrderByCreatedAtAsc(
                    EnumSet.of(PayoutStatus.IN_TRANSIT, PayoutStatus.BROKEN))) {
                if (batch.getExpectedNetAmount() != null && batch.getExpectedNetAmount().compareTo(open.getAmount()) == 0) {
                    try {
                        breakResolutionService.resolve(open.getId(), new ResolveBreakRequest(BreakAction.ASSIGN_TO_BATCH,
                                batch.getId(), "Bank confirmed the transfer belongs to " + batch.getPayoutReference()), OPS_ANA);
                        return;
                    } catch (RuntimeException notApplicable) {
                        // try the next candidate
                    }
                }
            }
        }
    }

    /**
     * Lets every pending effect play out: webhooks and provider calls, an end-of-day settlement for each merchant,
     * the bank through two days past every payout's value-date window (so each payout lands, is declared missing,
     * or its credit arrives late), the provider's report, then the reconciliation job.
     */
    private void settleDown() {
        providerClient.reset();
        simFaults.clear();
        simLatency.recover();
        deliverWebhooks();
        runOutbox(false);
        drainOutbox();
        for (String merchant : MERCHANTS) {
            try {
                settle(merchant);
            } catch (RuntimeException nothingToSettle) {
                // nothing captured and unpaid for this merchant
            }
        }
        runOutbox(false);
        drainOutbox();
        LocalDate through = bankSimulator.businessDate("IDR");
        for (SettlementBatch batch : settlementBatchRepository.findByPayoutStatusInOrderByCreatedAtAsc(EnumSet.of(PayoutStatus.IN_TRANSIT))) {
            // Two days past the window: credits that come late, after the payout was declared missing, arrive too.
            LocalDate windowEnd = batch.getExpectedValueDate().plusDays(4);
            through = windowEnd.isAfter(through) ? windowEnd : through;
        }
        closeBankDays("IDR", through);
        importStatements();
        List<Integer> reissues = List.copyOf(deferredReissues);
        deferredReissues.clear();
        reissues.forEach(this::reissue);
        providerReport();
        threeWayReconciliationService.run(OPS_ANA.name());
    }
}
