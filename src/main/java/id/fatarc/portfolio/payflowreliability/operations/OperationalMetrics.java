package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.ledger.LedgerInvariantService;
import id.fatarc.portfolio.payflowreliability.outbox.CommandStatus;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommandRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewCaseRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
                              PlatformTransactionManager transactionManager,
                              Clock clock,
                              @Value("${payflow.sweeper.stuck-after:15m}") Duration stuckAfter) {
        this.reviewCaseRepository = reviewCaseRepository;
        this.outboxCommandRepository = outboxCommandRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.ledgerInvariantService = ledgerInvariantService;
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
                return new Snapshot(now, unresolved,
                        reviewCaseRepository.countByStatus(ReviewStatus.AWAITING_APPROVAL),
                        oldestCase,
                        outboxCommandRepository.countByStatus(CommandStatus.PENDING),
                        outboxCommandRepository.countByStatus(CommandStatus.NEEDS_REVIEW),
                        oldestPending,
                        paymentIntentRepository.countByStateInAndUpdatedAtBefore(OperationsService.WAITING_ON_PROVIDER,
                                now.minus(stuckAfter)),
                        report == null ? 0 : report.violations().size(),
                        ledgerCheckAge);
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
                            double ledgerCheckAgeSeconds) {
        static Snapshot empty(Instant now) {
            return new Snapshot(now, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
