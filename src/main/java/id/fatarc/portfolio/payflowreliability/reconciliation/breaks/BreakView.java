package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A break as an operator sees it: what, whose, how much, how old, whether it is overdue, and what can be done.
 *
 * @param suspenseAmount the money this break holds in bank_suspense while open (credits positive)
 * @param allowedActions what an operator may do now (breaks holding money can only be closed by explaining it)
 */
public record BreakView(UUID id, BreakCategory category, BreakOwner owner, BreakStatus status, String subjectKey,
                        UUID settlementBatchId, UUID bankLineId, String statementId, String currency, BigDecimal amount,
                        BigDecimal suspenseAmount, String detail, Instant openedAt, Instant dueAt, long ageSeconds,
                        boolean overdue, Instant resolvedAt, String resolvedBy, BreakResolution resolution,
                        String resolutionNote, List<BreakAction> allowedActions) {

    public static BreakView of(ReconciliationBreak b, Instant now) {
        return new BreakView(b.getId(), b.getCategory(), b.getOwner(), b.getStatus(), b.getSubjectKey(), b.getSettlementBatchId(),
                b.getBankLineId(), b.getStatementId(), b.getCurrency(), b.getAmount(), b.getSuspenseAmount(), b.getDetail(),
                b.getOpenedAt(), b.getDueAt(), b.age(now).toSeconds(), b.isOverdue(now), b.getResolvedAt(), b.getResolvedBy(),
                b.getResolution(), b.getResolutionNote(), BreakAction.allowedFor(b));
    }
}
