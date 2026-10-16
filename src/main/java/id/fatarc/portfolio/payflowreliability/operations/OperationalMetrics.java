package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.bank.BankStatementImportService;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerInvariantService;
import id.fatarc.portfolio.payflowreliability.reconciliation.ThreeWayReconciliationService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreakRepository;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import id.fatarc.portfolio.payflowreliability.outbox.CommandStatus;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommandRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewCaseRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gauges that alert rules read (see ops/prometheus/alerts.yml). Values come from a snapshot refreshed
 * on a schedule, so a scrape never queries the database and still works while the database is slow.
 */
@Component
public class OperationalMetrics {
    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);

    private final ReviewCaseRepository reviewCaseRepository;
    private final OutboxCommandRepository outboxCommandRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final LedgerInvariantService ledgerInvariantService;
    private final ReconciliationBreakRepository breakRepository;
    private final SettlementBatchRepository batchRepository;
    private final ThreeWayReconciliationService threeWayService;
    private final BankStatementImportService statementImportService;
    private final MoneyAtRiskService moneyAtRiskService;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;
    private final MultiGauge moneyAtRiskAmount;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration stuckAfter;
    private final Instant startedAt;
    private final AtomicReference<Snapshot> snapshot;

    public OperationalMetrics(MeterRegistry registry,
                              ReviewCaseRepository reviewCaseRepository,
                              OutboxCommandRepository outboxCommandRepository,
                              PaymentIntentRepository paymentIntentRepository,
                              LedgerInvariantService ledgerInvariantService,
                              ReconciliationBreakRepository breakRepository,
                              SettlementBatchRepository batchRepository,
                              ThreeWayReconciliationService threeWayService,
                              BankStatementImportService statementImportService,
                              MoneyAtRiskService moneyAtRiskService,
                              ObjectProvider<HealthEndpoint> healthEndpoint,
                              PlatformTransactionManager transactionManager,
                              Clock clock,
                              @Value("${payflow.sweeper.stuck-after:15m}") Duration stuckAfter) {
        this.reviewCaseRepository = reviewCaseRepository;
        this.outboxCommandRepository = outboxCommandRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.ledgerInvariantService = ledgerInvariantService;
        this.breakRepository = breakRepository;
        this.batchRepository = batchRepository;
        this.threeWayService = threeWayService;
        this.statementImportService = statementImportService;
        this.moneyAtRiskService = moneyAtRiskService;
        this.healthEndpoint = healthEndpoint;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        this.startedAt = clock.instant();
        this.snapshot = new AtomicReference<>(Snapshot.empty(startedAt));

        gauge(registry, "payflow.review.queue.unresolved", "Unresolved review cases", null, s -> s.unresolvedCases);
        gauge(registry, "payflow.review.queue.awaiting.approval", "Proposals waiting for a second operator", null,
                s -> s.awaitingApproval);
        gauge(registry, "payflow.review.queue.oldest.age", "Age of the oldest unresolved review case", "seconds",
                s -> s.oldestCaseAgeSeconds);
        gauge(registry, "payflow.outbox.pending", "Provider calls waiting to be sent or retried", null, s -> s.outboxPending);
        gauge(registry, "payflow.outbox.needs.review", "Provider calls with an unknown outcome after all retries", null,
                s -> s.outboxNeedsReview);
        gauge(registry, "payflow.outbox.oldest.pending.age", "Age of the oldest pending provider call", "seconds",
                s -> s.oldestPendingAgeSeconds);
        gauge(registry, "payflow.payments.stuck", "Payments waiting on the provider longer than the sweeper threshold", null,
                s -> s.stuckPayments);
        gauge(registry, "payflow.ledger.invariant.violations", "Ledger inconsistencies found by the last check", null,
                s -> s.ledgerViolations);
        // Dead man's switches: alert when the checks themselves stop running, not only when they find something.
        gauge(registry, "payflow.ledger.invariant.check.age", "Time since the ledger was last checked", "seconds",
                s -> s.ledgerCheckAgeSeconds);
        gauge(registry, "payflow.metrics.snapshot.age", "Time since these gauges were last refreshed", "seconds",
                s -> Duration.between(s.refreshedAt, clock.instant()).toSeconds());
        // Settlement certainty (ADR 025): disagreements between ledger, provider and bank, and whether the
        // jobs that find them are still running.
        gauge(registry, "payflow.reconciliation.breaks.open", "Open reconciliation breaks", null, s -> s.openBreaks);
        gauge(registry, "payflow.reconciliation.breaks.overdue", "Open reconciliation breaks past their due time", null,
                s -> s.overdueBreaks);
        gauge(registry, "payflow.reconciliation.breaks.oldest.age", "Age of the oldest open reconciliation break", "seconds",
                s -> s.oldestBreakAgeSeconds);
        gauge(registry, "payflow.payouts.broken", "Payouts that are BROKEN (refused, credit missing, returned)", null,
                s -> s.brokenPayouts);
        gauge(registry, "payflow.reconciliation.check.age", "Time since the three-way reconciliation last ran", "seconds",
                s -> s.reconciliationCheckAgeSeconds);
        gauge(registry, "payflow.bank.statement.import.age", "Time since a bank statement was last imported", "seconds",
                s -> s.statementImportAgeSeconds);
        // Money at risk: what slowness, fraud controls and breaks are costing now, whatever the health checks say.
        for (MoneyAtRiskService.Kind kind : MoneyAtRiskService.Kind.values()) {
            Gauge.builder("payflow.money.at.risk.count", snapshot, ref -> ref.get().atRiskCount.getOrDefault(kind, 0L))
                    .description("Payments or items at risk, by kind").tag("kind", kind.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        this.moneyAtRiskAmount = MultiGauge.builder("payflow.money.at.risk.amount")
                .description("Money at risk, by kind and currency").register(registry);
        gauge(registry, "payflow.health.up", "1 when the application's health checks report UP", null, s -> s.healthUp);
    }

    public void refresh() {
        try {
            Snapshot fresh = transactions.execute(status -> {
                Instant now = clock.instant();
                long unresolved = reviewCaseRepository.countByReason(ReviewStatus.UNRESOLVED).stream()
                        .mapToLong(row -> row.getCaseCount()).sum();
                double oldestCase = reviewCaseRepository.oldestCreatedAt(ReviewStatus.UNRESOLVED)
                        .map(created -> (double) Duration.between(created, now).toSeconds()).orElse(0.0);
                double oldestPending = outboxCommandRepository.oldestPendingCreatedAt()
                        .map(created -> (double) Duration.between(created, now).toSeconds()).orElse(0.0);
                var report = ledgerInvariantService.lastReport();
                double ledgerCheckAge = Duration.between(report == null ? startedAt : report.checkedAt(), now).toSeconds();
                List<ReconciliationBreak> openBreaks = breakRepository.findByStatusOrderByOpenedAtAsc(BreakStatus.OPEN);
                double oldestBreak = openBreaks.isEmpty() ? 0 : Duration.between(openBreaks.getFirst().getOpenedAt(), now).toSeconds();
                Instant lastThreeWay = threeWayService.lastRun().map(ThreeWayReconciliationService.RunReport::checkedAt)
                        .orElse(startedAt);
                Instant lastStatement = statementImportService.lastImport().orElse(startedAt);
                MoneyAtRiskService.MoneyAtRisk atRisk = moneyAtRiskService.compute();
                Map<MoneyAtRiskService.Kind, Long> atRiskCount = new EnumMap<>(MoneyAtRiskService.Kind.class);
                List<MultiGauge.Row<?>> amountRows = new ArrayList<>();
                for (MoneyAtRiskService.Item item : atRisk.items()) {
                    atRiskCount.put(item.kind(), item.count());
                    item.amount().forEach((currency, amount) -> amountRows.add(MultiGauge.Row.of(
                            Tags.of("kind", item.kind().name().toLowerCase(Locale.ROOT), "currency", currency), amount.doubleValue())));
                }
                moneyAtRiskAmount.register(amountRows, true);
                boolean up = healthEndpoint.getIfAvailable() != null
                        && Status.UP.equals(healthEndpoint.getObject().health().getStatus());
                return new Snapshot(now, unresolved,
                        reviewCaseRepository.countByStatus(ReviewStatus.AWAITING_APPROVAL),
                        oldestCase,
                        outboxCommandRepository.countByStatus(CommandStatus.PENDING),
                        outboxCommandRepository.countByStatus(CommandStatus.NEEDS_REVIEW),
                        oldestPending,
                        paymentIntentRepository.countByStateInAndUpdatedAtBefore(OperationsService.WAITING_ON_PROVIDER,
                                now.minus(stuckAfter)),
                        report == null ? 0 : report.violations().size(),
                        ledgerCheckAge,
                        openBreaks.size(),
                        openBreaks.stream().filter(open -> open.isOverdue(now)).count(),
                        oldestBreak,
                        batchRepository.findByPayoutStatusInOrderByCreatedAtAsc(EnumSet.of(PayoutStatus.BROKEN)).size(),
                        Duration.between(lastThreeWay, now).toSeconds(),
                        Duration.between(lastStatement, now).toSeconds(),
                        atRiskCount,
                        up ? 1 : 0);
            });
            snapshot.set(fresh);
        } catch (RuntimeException ex) {
            log.warn("Could not refresh operational metrics: {}", ex.getMessage());
        }
    }

    private void gauge(MeterRegistry registry, String name, String description, String unit,
                       java.util.function.ToDoubleFunction<Snapshot> value) {
        Gauge.Builder<AtomicReference<Snapshot>> builder = Gauge.builder(name, snapshot, ref -> value.applyAsDouble(ref.get()))
                .description(description);
        if (unit != null) {
            builder.baseUnit(unit);
        }
        builder.register(registry);
    }

    private record Snapshot(Instant refreshedAt, double unresolvedCases, double awaitingApproval,
                            double oldestCaseAgeSeconds, double outboxPending, double outboxNeedsReview,
                            double oldestPendingAgeSeconds, double stuckPayments, double ledgerViolations,
                            double ledgerCheckAgeSeconds, double openBreaks, double overdueBreaks,
                            double oldestBreakAgeSeconds, double brokenPayouts, double reconciliationCheckAgeSeconds,
                            double statementImportAgeSeconds, Map<MoneyAtRiskService.Kind, Long> atRiskCount,
                            double healthUp) {
        static Snapshot empty(Instant now) {
            return new Snapshot(now, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0);
        }
    }
}
