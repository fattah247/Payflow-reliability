package id.fatarc.portfolio.payflowreliability.scheduling;

import id.fatarc.portfolio.payflowreliability.bank.BankStatementImportService;
import id.fatarc.portfolio.payflowreliability.banksim.BankSimulator;
import id.fatarc.portfolio.payflowreliability.idempotency.IdempotencyService;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerInvariantService;
import id.fatarc.portfolio.payflowreliability.notification.MerchantWebhookDispatcher;
import id.fatarc.portfolio.payflowreliability.operations.OperationalMetrics;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxWorker;
import id.fatarc.portfolio.payflowreliability.payment.PaymentStatusSweeper;
import id.fatarc.portfolio.payflowreliability.providersim.SimWebhookDispatcher;
import id.fatarc.portfolio.payflowreliability.reconciliation.ThreeWayReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final OutboxWorker outboxWorker;
    private final ObjectProvider<SimWebhookDispatcher> simDispatcher;
    private final PaymentStatusSweeper sweeper;
    private final LedgerInvariantService ledgerInvariants;
    private final IdempotencyService idempotency;
    private final OperationalMetrics metrics;
    private final ObjectProvider<BankSimulator> bankSim;
    private final BankStatementImportService statements;
    private final ThreeWayReconciliationService threeWay;
    private final MerchantWebhookDispatcher merchantWebhooks;

    public ScheduledJobs(OutboxWorker outboxWorker, ObjectProvider<SimWebhookDispatcher> simDispatcher,
                         PaymentStatusSweeper sweeper, LedgerInvariantService ledgerInvariants,
                         IdempotencyService idempotency, OperationalMetrics metrics, ObjectProvider<BankSimulator> bankSim,
                         BankStatementImportService statements, ThreeWayReconciliationService threeWay,
                         MerchantWebhookDispatcher merchantWebhooks) {
        this.outboxWorker = outboxWorker;
        this.simDispatcher = simDispatcher;
        this.sweeper = sweeper;
        this.ledgerInvariants = ledgerInvariants;
        this.idempotency = idempotency;
        this.metrics = metrics;
        this.bankSim = bankSim;
        this.statements = statements;
        this.threeWay = threeWay;
        this.merchantWebhooks = merchantWebhooks;
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval:1s}")
    void runOutbox() {
        outboxWorker.runOnce();
    }

    @Scheduled(fixedDelayString = "${payflow.provider-sim.dispatch-interval:1s}")
    void dispatchSimulatedWebhooks() {
        simDispatcher.ifAvailable(SimWebhookDispatcher::runOnce);
    }

    /** Tells shops what changed: signed webhooks, retried with backoff. */
    @Scheduled(fixedDelayString = "${payflow.merchant-webhooks.poll-interval:1s}")
    void deliverMerchantWebhooks() {
        merchantWebhooks.runOnce();
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

    /** The simulated bank closes elapsed business days (local only); then new statements are downloaded and imported. */
    @Scheduled(fixedDelayString = "${payflow.bank.statement-poll-interval:1m}", initialDelayString = "${payflow.bank.initial-delay:30s}")
    void importBankStatements() {
        bankSim.ifAvailable(BankSimulator::closeElapsedDays);
        try {
            statements.pull("system:statement-import");
        } catch (RuntimeException ex) {
            log.warn("Bank statements could not be pulled: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${payflow.reconciliation.three-way-interval:5m}", initialDelayString = "${payflow.reconciliation.initial-delay:1m}")
    void reconcileThreeWay() {
        threeWay.run("system:three-way-reconciliation");
    }

    @Scheduled(fixedDelayString = "${payflow.metrics.refresh-interval:30s}", initialDelay = 5000)
    void refreshMetrics() {
        metrics.refresh();
    }
}
