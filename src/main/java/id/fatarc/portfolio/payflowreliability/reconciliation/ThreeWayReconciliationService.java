package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.bank.BankAccountCursor;
import id.fatarc.portfolio.payflowreliability.bank.BankAccountCursorRepository;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLine;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLineRepository;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreakRepository;
import id.fatarc.portfolio.payflowreliability.settlement.BrokenReason;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The scheduled part of the three-way reconciliation (ledger, provider report, bank statement). Statement
 * imports match lines as they arrive; this job does what only time can tell:
 * <ol>
 *   <li>looks at suspense lines again (a credit that came before the provider's confirmation, a return that
 *       came before its credit);</li>
 *   <li>declares a payout's credit missing once every statement through the end of its value-date window has
 *       arrived without it (the batch is BROKEN, a break is opened; a later credit still lands it);</li>
 *   <li>reconciles payments whose two pieces of evidence (provider report, landed payout) are now both in.</li>
 * </ol>
 * Each step works in its own short transactions. The time of the last run feeds a dead man's switch.
 */
@Service
public class ThreeWayReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ThreeWayReconciliationService.class);
    private static final EnumSet<BreakCategory> RETRYABLE = EnumSet.of(BreakCategory.UNMATCHED_CREDIT, BreakCategory.UNMATCHED_DEBIT);

    private final ReconciliationBreakRepository breakRepository;
    private final BankStatementLineRepository lineRepository;
    private final BankAccountCursorRepository cursorRepository;
    private final SettlementBatchRepository batchRepository;
    private final PayoutMatcher matcher;
    private final PaymentReconciler paymentReconciler;
    private final BreakService breakService;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final int windowDays;
    private final AtomicReference<RunReport> lastRun = new AtomicReference<>();

    public ThreeWayReconciliationService(ReconciliationBreakRepository breakRepository, BankStatementLineRepository lineRepository,
                                         BankAccountCursorRepository cursorRepository, SettlementBatchRepository batchRepository,
                                         PayoutMatcher matcher, PaymentReconciler paymentReconciler, BreakService breakService,
                                         AuditService auditService, Clock clock, PlatformTransactionManager transactionManager,
                                         @Value("${payflow.reconciliation.value-date-window-days:2}") int windowDays) {
        this.breakRepository = breakRepository;
        this.lineRepository = lineRepository;
        this.cursorRepository = cursorRepository;
        this.batchRepository = batchRepository;
        this.matcher = matcher;
        this.paymentReconciler = paymentReconciler;
        this.breakService = breakService;
        this.auditService = auditService;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.windowDays = windowDays;
    }

    /**
     * @param rematched          suspense lines explained on this run
     * @param missingCredits     payouts declared missing on this run
     * @param reconciledPayments payments moved to RECONCILED on this run
     */
    public record RunReport(Instant checkedAt, int rematched, int missingCredits, int reconciledPayments, long openBreaks,
                            long overdueBreaks) {
    }

    public Optional<RunReport> lastRun() {
        return Optional.ofNullable(lastRun.get());
    }

    public RunReport run(String actor) {
        int rematched = 0;
        for (ReconciliationBreak open : breakRepository.findByStatusAndCategoryInOrderByOpenedAtAsc(BreakStatus.OPEN, RETRYABLE)) {
            if (open.getBankLineId() == null) {
                continue;
            }
            Boolean explained = transactions.execute(status -> {
                ReconciliationBreak current = breakRepository.findById(open.getId()).orElseThrow();
                BankStatementLine line = lineRepository.findById(current.getBankLineId()).orElseThrow();
                return current.isOpen() && matcher.retry(line, current, actor);
            });
            rematched += Boolean.TRUE.equals(explained) ? 1 : 0;
        }

        int missing = 0;
        for (SettlementBatch batch : batchRepository.findByPayoutStatusInOrderByCreatedAtAsc(EnumSet.of(PayoutStatus.IN_TRANSIT))) {
            Optional<LocalDate> coveredThrough = coveredThrough(batch.getCurrency());
            LocalDate windowEnd = batch.getExpectedValueDate().plusDays(windowDays);
            if (coveredThrough.isEmpty() || coveredThrough.get().isBefore(windowEnd)) {
                continue;
            }
            Boolean declared = transactions.execute(status -> declareMissing(batch.getId(), windowEnd, coveredThrough.get(), actor));
            missing += Boolean.TRUE.equals(declared) ? 1 : 0;
        }

        int reconciled = paymentReconciler.reconcileReady(actor);
        Instant now = clock.instant();
        RunReport report = new RunReport(now, rematched, missing, reconciled,
                breakRepository.countByStatusAndCategoryIn(BreakStatus.OPEN, EnumSet.allOf(BreakCategory.class)),
                breakRepository.countOverdue(BreakStatus.OPEN, now));
        lastRun.set(report);
        if (rematched + missing + reconciled > 0) {
            log.info("Three-way reconciliation {}", report);
        }
        return report;
    }

    private boolean declareMissing(UUID batchId, LocalDate windowEnd, LocalDate coveredThrough, String actor) {
        SettlementBatch batch = batchRepository.findForUpdate(batchId).orElseThrow();
        if (batch.getPayoutStatus() != PayoutStatus.IN_TRANSIT) {
            return false;
        }
        batch.markBroken(BrokenReason.MISSING_CREDIT, clock.instant());
        String detail = "No credit for payout " + batch.getPayoutReference() + " (net " + batch.getExpectedNetAmount().toPlainString()
                + " " + batch.getCurrency() + ", expected value date " + batch.getExpectedValueDate() + "): every statement through "
                + coveredThrough + " has arrived, past the window ending " + windowEnd + ". The money left the provider and is not in the bank.";
        breakService.open(BreakCategory.MISSING_CREDIT, BreakService.batchSubject(batch.getId()), batch.getId(), null, null,
                batch.getCurrency(), batch.getExpectedNetAmount(), BigDecimal.ZERO, detail, actor);
        auditService.record(AggregateType.SETTLEMENT_BATCH, batch.getId().toString(), null, "PAYOUT_CREDIT_MISSING", actor,
                detail, "reference=" + batch.getPayoutReference());
        return true;
    }

    /** Every statement through this date has arrived, for the account in this currency. */
    Optional<LocalDate> coveredThrough(String currency) {
        return cursorRepository.findByCurrency(currency).stream()
                .map(BankAccountCursor::getCoveredThrough)
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo);
    }

    List<BankStatementLine> linesOf(UUID batchId) {
        return lineRepository.findBySettlementBatchIdOrderByImportedAtAsc(batchId);
    }
}
