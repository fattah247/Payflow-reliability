package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.bank.BankLineStatus;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLine;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLineRepository;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakResolution;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.settlement.BrokenReason;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides what a bank line is, and books it exactly once.
 *
 * <p>A credit makes its payout LANDED only when all three hold:
 * <ol>
 *   <li><b>reference</b>: its end-to-end id is the payout reference the provider was given;</li>
 *   <li><b>amount</b>: what arrived plus the charges the bank itemized equals the net the provider said it
 *       sent, and that net plus the provider's fee equals the batch total;</li>
 *   <li><b>value date</b>: no later than the provider's expected value date plus the matching window.</li>
 * </ol>
 * Anything else is booked to {@code bank_suspense} with an open break that says why: no matching reference,
 * a second credit for a landed payout, an amount that does not add up, a credit outside the window. A return
 * debit for a landed payout puts the money back on the provider and breaks the payout.
 *
 * <p>Locks the batch row (never a payment), so it can run inside a statement import that already holds the
 * account cursor. Payments are reconciled afterwards, by {@link PaymentReconciler}, which locks payments first.
 */
@Service
public class PayoutMatcher {
    private final SettlementBatchRepository batchRepository;
    private final BankStatementLineRepository lineRepository;
    private final LedgerService ledgerService;
    private final BreakService breakService;
    private final AuditService auditService;
    private final Clock clock;
    private final int windowDays;

    public PayoutMatcher(SettlementBatchRepository batchRepository, BankStatementLineRepository lineRepository,
                         LedgerService ledgerService, BreakService breakService,
                         AuditService auditService, Clock clock,
                         @Value("${payflow.reconciliation.value-date-window-days:2}") int windowDays) {
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
        this.ledgerService = ledgerService;
        this.breakService = breakService;
        this.auditService = auditService;
        this.clock = clock;
        this.windowDays = windowDays;
    }

    /** The outcome for one new line: the batch it landed or returned, if any. */
    public record Outcome(BankLineStatus status, UUID batchId, BreakCategory breakCategory) {
    }

    /** A line seen for the first time. Books it once: to a payout, back to the provider, or to suspense. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome book(BankStatementLine line, String actor) {
        Optional<SettlementBatch> batch = batchFor(line);
        if (line.isCredit() && !line.isReversal()) {
            if (batch.isEmpty()) {
                return suspense(line, null, BreakCategory.UNMATCHED_CREDIT, "Credit of " + money(line)
                        + (line.getEndToEndId() == null ? " carries no reference" : " carries reference " + line.getEndToEndId()
                        + ", which matches no payout") + ".", actor);
            }
            return credit(line, batch.get(), actor);
        }
        if (line.isReversal() && batch.isPresent()) {
            return debitReversal(line, batch.get(), actor);
        }
        return suspense(line, batch.map(SettlementBatch::getId).orElse(null), BreakCategory.UNMATCHED_DEBIT,
                "Debit of " + money(line) + " that no payout explains.", actor);
    }

    /**
     * A line in suspense, looked at again: the provider may have confirmed its payout since (a credit that
     * arrived before the confirmation), or the credit a return refers to may have landed since (statements
     * imported out of order). Only these two cases resolve themselves; everything else needs a person.
     *
     * @return true when the line was explained and left suspense
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retry(BankStatementLine line, ReconciliationBreak open, String actor) {
        Optional<SettlementBatch> batch = batchFor(line);
        if (batch.isEmpty()) {
            return false;
        }
        SettlementBatch locked = batch.get();
        if (open.getCategory() == BreakCategory.UNMATCHED_CREDIT && line.isCredit()
                && locked.getPayoutStatus() == PayoutStatus.IN_TRANSIT && problem(line, locked) == null) {
            land(locked, line, true, actor);
            breakService.resolve(open, BreakResolution.MATCHED, actor,
                    "Matched once the provider confirmed payout " + locked.getPayoutReference() + ".");
            return true;
        }
        if (open.getCategory() == BreakCategory.UNMATCHED_DEBIT && line.isReversal()
                && locked.getPayoutStatus() == PayoutStatus.LANDED
                && line.getAmount().compareTo(locked.getLandedAmount()) == 0) {
            returnPayout(locked, line, true, actor);
            breakService.resolve(open, BreakResolution.MATCHED, actor,
                    "Return of payout " + locked.getPayoutReference() + ", which has landed since.");
            return true;
        }
        return false;
    }

    /**
     * An operator says which payout an unmatched or late credit belongs to. The reference and the window are
     * overridden by that decision; the amount is not: gross minus fees must still equal what arrived.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assign(BankStatementLine line, ReconciliationBreak open, UUID batchId, String actor, String note) {
        SettlementBatch batch = batchRepository.findForUpdate(batchId)
                .orElseThrow(() -> new IllegalArgumentException("No settlement batch " + batchId));
        boolean landable = batch.getPayoutStatus() == PayoutStatus.IN_TRANSIT
                || (batch.getPayoutStatus() == PayoutStatus.BROKEN && batch.getBrokenReason() == BrokenReason.MISSING_CREDIT);
        if (!landable) {
            throw new IllegalArgumentException("Payout " + batch.getPayoutReference() + " is " + batch.getPayoutStatus()
                    + "; only a payout in transit or missing its credit can receive one.");
        }
        String amountProblem = amountProblem(line, batch);
        if (amountProblem != null) {
            throw new IllegalArgumentException(amountProblem);
        }
        land(batch, line, true, actor);
        breakService.resolve(open, BreakResolution.ASSIGNED_TO_BATCH, actor, note);
    }

    // ---- credits ---------------------------------------------------------------------------------

    private Outcome credit(BankStatementLine line, SettlementBatch batch, String actor) {
        if (batch.getPayoutStatus() == PayoutStatus.LANDED || batch.getPayoutStatus() == PayoutStatus.BROKEN
                && batch.getBrokenReason() == BrokenReason.RETURNED) {
            return suspense(line, batch.getId(), BreakCategory.DUPLICATE_CREDIT, "Second credit of " + money(line)
                    + " for payout " + batch.getPayoutReference() + ", which already landed on " + batch.getLandedValueDate() + ".", actor);
        }
        if (batch.getPayoutStatus() == PayoutStatus.SENT || batch.getPayoutStatus() == PayoutStatus.LEGACY
                || batch.getBrokenReason() == BrokenReason.PROVIDER_REFUSED) {
            return suspense(line, batch.getId(), BreakCategory.UNMATCHED_CREDIT, "Credit of " + money(line)
                    + " for payout " + batch.getPayoutReference() + ", which the provider has not confirmed (" + batch.getPayoutStatus()
                    + "). Held until it does.", actor);
        }
        String problem = problem(line, batch);
        if (problem != null) {
            BreakCategory category = problem.startsWith("value date") ? BreakCategory.LATE_CREDIT : BreakCategory.AMOUNT_MISMATCH;
            return suspense(line, batch.getId(), category, "Credit for payout " + batch.getPayoutReference() + ": " + problem, actor);
        }
        land(batch, line, false, actor);
        return new Outcome(BankLineStatus.MATCHED, batch.getId(), null);
    }

    /** Why this credit cannot land its payout, or null when reference, amount and value date all match. */
    private String problem(BankStatementLine line, SettlementBatch batch) {
        String amountProblem = amountProblem(line, batch);
        if (amountProblem != null) {
            return amountProblem;
        }
        LocalDate latest = batch.getExpectedValueDate().plusDays(windowDays);
        if (line.getValueDate().isAfter(latest)) {
            return "value date " + line.getValueDate() + " is after " + latest + " (expected " + batch.getExpectedValueDate()
                    + " plus a " + windowDays + "-day window).";
        }
        return null;
    }

    private String amountProblem(BankStatementLine line, SettlementBatch batch) {
        if (!line.getCurrency().equals(batch.getCurrency())) {
            return "currency " + line.getCurrency() + " is not the payout's " + batch.getCurrency() + ".";
        }
        BigDecimal arrivedPlusCharges = line.getAmount().add(line.chargesOrZero());
        if (arrivedPlusCharges.compareTo(batch.getExpectedNetAmount()) != 0) {
            return "received " + line.getAmount().toPlainString() + " plus itemized charges " + line.chargesOrZero().toPlainString()
                    + " is " + arrivedPlusCharges.toPlainString() + ", but the provider sent " + batch.getExpectedNetAmount().toPlainString()
                    + " (gross " + batch.getTotalAmount().toPlainString() + " minus its fee " + batch.getProviderFeeAmount().toPlainString() + ").";
        }
        if (line.getInstructedAmount() != null && line.getInstructedAmount().compareTo(batch.getExpectedNetAmount()) != 0) {
            return "the bank says " + line.getInstructedAmount().toPlainString() + " was sent, the provider says "
                    + batch.getExpectedNetAmount().toPlainString() + ".";
        }
        return null;
    }

    /**
     * Gross leaves transit as cash, the provider's fee and the bank's charges: three lines that must add up to
     * the batch total, or the ledger refuses the journal.
     */
    private void land(SettlementBatch batch, BankStatementLine line, boolean fromSuspense, String actor) {
        BigDecimal charges = line.chargesOrZero();
        ledgerService.payoutLanded(batch.getId(), batch.getCurrency(), batch.getTotalAmount(), line.getAmount(),
                batch.getProviderFeeAmount(), charges, fromSuspense);
        boolean wasMissing = batch.getPayoutStatus() == PayoutStatus.BROKEN;
        batch.markLanded(line.getId(), line.getAmount(), charges, line.getValueDate(), clock.instant());
        if (fromSuspense) {
            line.cleared(batch.getId());
        } else {
            line.matched(batch.getId());
        }
        if (wasMissing) {
            breakService.resolveIfOpen(BreakCategory.MISSING_CREDIT, BreakService.batchSubject(batch.getId()),
                    BreakResolution.MATCHED, actor, "The credit arrived: bank line " + line.getAccountServicerRef()
                            + " in statement " + line.getStatementId() + ".");
        }
        auditService.record(AggregateType.SETTLEMENT_BATCH, batch.getId().toString(), null, "PAYOUT_LANDED", actor,
                "Bank credit " + line.getAccountServicerRef() + " of " + money(line) + " valued " + line.getValueDate()
                        + " matched payout " + batch.getPayoutReference() + ": gross " + batch.getTotalAmount().toPlainString()
                        + " = received " + line.getAmount().toPlainString() + " + provider fee "
                        + batch.getProviderFeeAmount().toPlainString() + " + bank charges " + charges.toPlainString() + ".",
                "statementId=" + line.getStatementId() + ",bankLineId=" + line.getId());
    }

    // ---- debits --------------------------------------------------------------------------------

    private Outcome debitReversal(BankStatementLine line, SettlementBatch batch, String actor) {
        Optional<ReconciliationBreak> duplicate = breakService.findOpenDuplicateCredit(batch.getId(), line.getAmount());
        if (duplicate.isPresent()) {
            // The bank took back the duplicate it credited: suspense goes down by the same amount, payout stays landed.
            ledgerService.bankUnmatched(line.getId(), line.getCurrency(), line.signedAmount());
            line.cleared(batch.getId());
            lineRepository.findById(duplicate.get().getBankLineId()).ifPresent(credit -> credit.cleared(batch.getId()));
            breakService.resolve(duplicate.get(), BreakResolution.MATCHED, actor,
                    "The bank returned the duplicate credit: line " + line.getAccountServicerRef() + ".");
            return new Outcome(BankLineStatus.CLEARED, batch.getId(), null);
        }
        if (batch.getPayoutStatus() == PayoutStatus.LANDED && line.getAmount().compareTo(batch.getLandedAmount()) == 0) {
            returnPayout(batch, line, false, actor);
            return new Outcome(BankLineStatus.RETURN, batch.getId(), BreakCategory.RETURNED_PAYOUT);
        }
        return suspense(line, batch.getId(), BreakCategory.UNMATCHED_DEBIT, "Return of " + money(line) + " for payout "
                + batch.getPayoutReference() + ", which has not landed (" + batch.getPayoutStatus() + ").", actor);
    }

    private void returnPayout(SettlementBatch batch, BankStatementLine line, boolean fromSuspense, String actor) {
        ledgerService.payoutReturned(line.getId(), line.getCurrency(), line.getAmount(), fromSuspense);
        batch.markReturned(line.getAmount(), clock.instant());
        line.returned(batch.getId());
        breakService.open(BreakCategory.RETURNED_PAYOUT, BreakService.batchSubject(batch.getId()), batch.getId(), line.getId(),
                line.getStatementId(), line.getCurrency(), line.getAmount(), BigDecimal.ZERO,
                "The bank returned payout " + batch.getPayoutReference() + " (" + money(line) + ") on " + line.getValueDate()
                        + ". The provider holds the money again; the provider fee of "
                        + batch.getProviderFeeAmount().toPlainString() + " stays booked until it is refunded.", actor);
        auditService.record(AggregateType.SETTLEMENT_BATCH, batch.getId().toString(), null, "PAYOUT_RETURNED", actor,
                "The bank returned the payout: " + money(line) + ".", "bankLineId=" + line.getId());
    }

    // ---- suspense ------------------------------------------------------------------------------

    private Outcome suspense(BankStatementLine line, UUID batchId, BreakCategory category, String detail, String actor) {
        ledgerService.bankUnmatched(line.getId(), line.getCurrency(), line.signedAmount());
        line.suspense(batchId);
        breakService.open(category, BreakService.lineSubject(line.getId()), batchId, line.getId(), line.getStatementId(),
                line.getCurrency(), line.getAmount(), line.signedAmount(),
                detail + " Bank line " + line.getAccountServicerRef() + " in statement " + line.getStatementId() + ".", actor);
        return new Outcome(BankLineStatus.SUSPENSE, batchId, category);
    }

    private Optional<SettlementBatch> batchFor(BankStatementLine line) {
        if (line.getEndToEndId() == null) {
            return Optional.empty();
        }
        return batchRepository.findIdByPayoutReference(line.getEndToEndId()).flatMap(batchRepository::findForUpdate);
    }

    private static String money(BankStatementLine line) {
        return line.getAmount().toPlainString() + " " + line.getCurrency();
    }
}
