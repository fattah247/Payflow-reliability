package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

import id.fatarc.portfolio.payflowreliability.bank.BankStatementLine;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLineRepository;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Keyset;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageSlice;
import id.fatarc.portfolio.payflowreliability.reconciliation.PaymentReconciler;
import id.fatarc.portfolio.payflowreliability.reconciliation.PayoutMatcher;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operator decisions on breaks. Money held in suspense is only released by explaining it, never by a click. */
@Service
public class BreakResolutionService {
    private final ReconciliationBreakRepository repository;
    private final BankStatementLineRepository lineRepository;
    private final PayoutMatcher matcher;
    private final BreakService breakService;
    private final Clock clock;

    public BreakResolutionService(ReconciliationBreakRepository repository, BankStatementLineRepository lineRepository,
                                  PayoutMatcher matcher, BreakService breakService, Clock clock) {
        this.repository = repository;
        this.lineRepository = lineRepository;
        this.matcher = matcher;
        this.breakService = breakService;
        this.clock = clock;
    }

    /**
     * One page of breaks: oldest first when filtered by status (a queue), newest first otherwise, with the category and
     * batch filters applied in the query.
     */
    @Transactional(readOnly = true)
    public PageSlice<BreakView> page(BreakStatus status, BreakCategory category, UUID batchId, PageCursor after, Integer limit) {
        Specification<ReconciliationBreak> filter = Keyset.all();
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (category != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        if (batchId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("settlementBatchId"), batchId));
        }
        Instant now = clock.instant();
        return Keyset.page(repository, filter, "openedAt", status != null, after, limit,
                        open -> new PageCursor(open.getOpenedAt(), open.getId()))
                .map(rows -> rows.stream().map(open -> BreakView.of(open, now)).toList());
    }

    @Transactional(readOnly = true)
    public List<BreakView> list(BreakStatus status, BreakCategory category, UUID batchId) {
        return (status == null ? repository.findAllByOrderByOpenedAtDesc() : repository.findByStatusOrderByOpenedAtAsc(status))
                .stream()
                .filter(b -> category == null || b.getCategory() == category)
                .filter(b -> batchId == null || batchId.equals(b.getSettlementBatchId()))
                .map(b -> BreakView.of(b, clock.instant()))
                .toList();
    }

    @Transactional(readOnly = true)
    public BreakView get(UUID breakId) {
        return BreakView.of(repository.findById(breakId).orElseThrow(() -> notFound(breakId)), clock.instant());
    }

    @Transactional
    public BreakView resolve(UUID breakId, ResolveBreakRequest request, Caller caller) {
        ReconciliationBreak open = repository.findForUpdate(breakId).orElseThrow(() -> notFound(breakId));
        List<BreakAction> allowed = BreakAction.allowedFor(open);
        if (!allowed.contains(request.action())) {
            throw new BusinessException(ErrorCode.BREAK_ACTION_NOT_APPLICABLE, request.action() + " is not applicable to "
                    + open.getStatus() + " " + open.getCategory() + " break " + breakId + "; allowed: " + allowed
                    + (open.getCategory().holdsSuspense() && allowed.isEmpty()
                    ? ". It holds " + open.getSuspenseAmount().toPlainString() + " " + open.getCurrency()
                    + " in suspense, which is released only when the money is explained (a matching bank line or an assignment)."
                    : "."));
        }
        if (request.action() == BreakAction.ACKNOWLEDGE) {
            breakService.resolve(open, BreakResolution.ACKNOWLEDGED, caller.name(), request.note());
        } else {
            if (request.batchId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "batchId is required for ASSIGN_TO_BATCH.");
            }
            BankStatementLine line = lineRepository.findById(open.getBankLineId()).orElseThrow();
            try {
                matcher.assign(line, open, request.batchId(), caller.name(), request.note());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.BREAK_ACTION_NOT_APPLICABLE, ex.getMessage());
            }
        }
        return BreakView.of(open, clock.instant());
    }

    private static BusinessException notFound(UUID breakId) {
        return new BusinessException(ErrorCode.BREAK_NOT_FOUND, "Reconciliation break not found: " + breakId);
    }
}
