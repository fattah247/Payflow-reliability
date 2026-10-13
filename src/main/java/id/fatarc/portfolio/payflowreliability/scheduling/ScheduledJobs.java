package id.fatarc.portfolio.payflowreliability.scheduling;

import id.fatarc.portfolio.payflowreliability.idempotency.IdempotencyService;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerInvariantService;
import id.fatarc.portfolio.payflowreliability.operations.OperationalMetrics;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxWorker;
import id.fatarc.portfolio.payflowreliability.payment.PaymentStatusSweeper;
import id.fatarc.portfolio.payflowreliability.providersim.SimWebhookDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Every background job in one place, switched on and off together with {@code payflow.workers.enabled}
 * (tests turn it off and run jobs explicitly, so outcomes are deterministic). Each job calls a proxied
 * service, so its own transactions apply.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payflow.workers.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {
    private final OutboxWorker outboxWorker;
    private final ObjectProvider<SimWebhookDispatcher> simDispatcher;
    private final PaymentStatusSweeper sweeper;
    private final LedgerInvariantService ledgerInvariants;
    private final IdempotencyService idempotency;
    private final OperationalMetrics metrics;

    public ScheduledJobs(OutboxWorker outboxWorker, ObjectProvider<SimWebhookDispatcher> simDispatcher,
                         PaymentStatusSweeper sweeper, LedgerInvariantService ledgerInvariants,
                         IdempotencyService idempotency, OperationalMetrics metrics) {
        this.outboxWorker = outboxWorker;
        this.simDispatcher = simDispatcher;
        this.sweeper = sweeper;
        this.ledgerInvariants = ledgerInvariants;
        this.idempotency = idempotency;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval:1s}")
    void runOutbox() {
        outboxWorker.runOnce();
    }

    @Scheduled(fixedDelayString = "${payflow.provider-sim.dispatch-interval:1s}")
    void dispatchSimulatedWebhooks() {
        simDispatcher.ifAvailable(SimWebhookDispatcher::runOnce);
    }

    @Scheduled(fixedDelayString = "${payflow.sweeper.interval:5m}", initialDelayString = "${payflow.sweeper.initial-delay:1m}")
    void sweepStuckPayments() {
        sweeper.runOnce();
    }

    @Scheduled(fixedDelayString = "${payflow.ledger.invariant-check-interval:5m}", initialDelayString = "${payflow.ledger.initial-delay:20s}")
    void checkLedger() {
        ledgerInvariants.check();
    }

    @Scheduled(fixedDelayString = "${payflow.idempotency.cleanup-interval:1h}")
    void deleteExpiredIdempotencyRecords() {
        idempotency.deleteExpired();
    }

    @Scheduled(fixedDelayString = "${payflow.metrics.refresh-interval:30s}", initialDelay = 5000)
    void refreshMetrics() {
        metrics.refresh();
    }
}
