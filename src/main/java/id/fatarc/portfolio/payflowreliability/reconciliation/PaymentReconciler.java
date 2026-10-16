package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchItemRepository;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The last step of the three-way match for one payment: RECONCILED needs both the provider's settlement report
 * (it confirmed the transaction) and the bank (a credit landed the batch that paid it out). Whichever arrives
 * second triggers it. Payments are locked first, in id order, like every other flow that changes them.
 */
@Service
public class PaymentReconciler {
    private final PaymentIntentRepository paymentIntentRepository;
    private final SettlementBatchItemRepository itemRepository;
    private final SettlementBatchRepository batchRepository;
    private final AuditService auditService;

    public PaymentReconciler(PaymentIntentRepository paymentIntentRepository, SettlementBatchItemRepository itemRepository,
                             SettlementBatchRepository batchRepository, AuditService auditService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.itemRepository = itemRepository;
        this.batchRepository = batchRepository;
        this.auditService = auditService;
    }

    /** Reconciles every settled payment the provider confirmed whose payout has landed since. */
    @Transactional
    public int reconcileReady(String actor) {
        List<UUID> candidates = paymentIntentRepository.findSettledWithProviderConfirmation();
        if (candidates.isEmpty()) {
            return 0;
        }
        return reconcileLocked(paymentIntentRepository.findAllForUpdate(candidates), actor);
    }

    @Transactional
    public int reconcile(Collection<UUID> paymentIds, String actor) {
        return paymentIds.isEmpty() ? 0 : reconcileLocked(paymentIntentRepository.findAllForUpdate(paymentIds), actor);
    }

    /**
     * The batch that paid this payment out, if its money is in the bank: LANDED, or LEGACY (paid out before
     * bank statements were reconciled, and trusted as before; see ADR 025).
     */
    @Transactional(readOnly = true)
    public Optional<SettlementBatch> landedBatchOf(UUID paymentIntentId) {
        return itemRepository.findByCapturePaymentIntentId(paymentIntentId)
                .flatMap(item -> batchRepository.findById(item.getBatchId()))
                .filter(batch -> batch.getPayoutStatus() == PayoutStatus.LANDED || batch.getPayoutStatus() == PayoutStatus.LEGACY);
    }

    private int reconcileLocked(List<PaymentIntent> payments, String actor) {
        int reconciled = 0;
        for (PaymentIntent intent : payments) {
            if (intent.getState() != PaymentState.SETTLED || intent.getProviderReportMatchedAt() == null) {
                continue;
            }
            Optional<SettlementBatch> landed = landedBatchOf(intent.getId());
            if (landed.isEmpty()) {
                continue;
            }
            PaymentState previous = intent.reconcile();
            auditService.recordStateChange(intent.getId(), previous, intent.getState(), actor,
                    "Three-way match: the provider's report confirmed the transaction and payout "
                            + landed.get().getPayoutReference() + " landed in the bank.",
                    "batchId=" + landed.get().getId() + ",bankLineId=" + landed.get().getBankLineId());
            reconciled++;
        }
        return reconciled;
    }
}
