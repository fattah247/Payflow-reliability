package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Opens and closes breaks inside the caller's transaction. Opening is idempotent per (category, subject), so
 * the importer and the reconciliation job can both find the same problem without creating two breaks.
 */
@Service
public class BreakService {
    public static final String AGGREGATE = "RECONCILIATION_BREAK";
    private static final Logger log = LoggerFactory.getLogger(BreakService.class);

    private final ReconciliationBreakRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public BreakService(ReconciliationBreakRepository repository, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    public static String batchSubject(UUID batchId) {
        return "batch:" + batchId;
    }

    public static String lineSubject(UUID lineId) {
        return "line:" + lineId;
    }

    public static String statementSubject(String statementId) {
        return "statement:" + statementId;
    }

    /**
     * @param suspenseAmount signed amount this break keeps in bank_suspense (zero when no money is held)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReconciliationBreak open(BreakCategory category, String subjectKey, UUID batchId, UUID bankLineId,
                                    String statementId, String currency, BigDecimal amount, BigDecimal suspenseAmount,
                                    String detail, String actor) {
        Optional<ReconciliationBreak> existing = repository.findByCategoryAndSubjectKeyAndStatus(category, subjectKey, BreakStatus.OPEN);
        if (existing.isPresent()) {
            return existing.get();
        }
        ReconciliationBreak opened = repository.save(new ReconciliationBreak(category, subjectKey, batchId, bankLineId,
                statementId, currency, amount, suspenseAmount, detail, clock.instant()));
        auditService.record(AGGREGATE, opened.getId().toString(), null, "RECONCILIATION_BREAK_OPENED", actor, detail,
                "category=" + category + ",owner=" + category.owner() + ",subject=" + subjectKey
                        + (amount == null ? "" : ",amount=" + amount.toPlainString() + " " + currency));
        log.warn("Reconciliation break opened id={} category={} owner={} subject={} amount={} {}", opened.getId(), category,
                category.owner(), subjectKey, amount, currency);
        return opened;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReconciliationBreak> findOpen(BreakCategory category, String subjectKey) {
        return repository.findByCategoryAndSubjectKeyAndStatus(category, subjectKey, BreakStatus.OPEN);
    }

    /** Closes the open break of this category about this subject, if there is one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolveIfOpen(BreakCategory category, String subjectKey, BreakResolution resolution, String actor, String note) {
        findOpen(category, subjectKey).ifPresent(open -> resolve(open, resolution, actor, note));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(ReconciliationBreak open, BreakResolution resolution, String actor, String note) {
        open.resolve(resolution, actor, note, clock.instant());
        auditService.record(AGGREGATE, open.getId().toString(), null, "RECONCILIATION_BREAK_RESOLVED", actor, note,
                "category=" + open.getCategory() + ",resolution=" + resolution + ",subject=" + open.getSubjectKey());
    }

    /** An open duplicate-credit break on this payout for exactly this amount (what a bank return would net off). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReconciliationBreak> findOpenDuplicateCredit(UUID batchId, BigDecimal amount) {
        return repository.findBySettlementBatchIdAndCategoryAndStatusOrderByOpenedAtAsc(batchId, BreakCategory.DUPLICATE_CREDIT,
                        BreakStatus.OPEN).stream()
                .filter(open -> open.getAmount().compareTo(amount) == 0)
                .findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<ReconciliationBreak> openForLine(UUID bankLineId) {
        return repository.findByBankLineIdAndStatus(bankLineId, BreakStatus.OPEN);
    }
}
