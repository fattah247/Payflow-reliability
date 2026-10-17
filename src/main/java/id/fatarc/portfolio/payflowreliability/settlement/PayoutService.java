package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransactionRepository;
import id.fatarc.portfolio.payflowreliability.provider.ProviderResult;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * What the provider's answer to a payout instruction means for the batch and the books. Called by the outbox
 * worker while it holds the batch lock, in the same transaction that records the provider call.
 */
@Service
public class PayoutService {
    private final SettlementBatchItemRepository itemRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final BreakService breakService;
    private final AuditService auditService;
    private final Clock clock;

    public PayoutService(SettlementBatchItemRepository itemRepository, PaymentTransactionRepository transactionRepository,
                         LedgerService ledgerService, BreakService breakService, AuditService auditService, Clock clock) {
        this.itemRepository = itemRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.breakService = breakService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** The provider transactions whose captures this payout settles, so the provider's report can list them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> providerTransactionIds(SettlementBatch batch) {
        List<java.util.UUID> transactionIds = itemRepository.findByBatchId(batch.getId()).stream()
                .filter(item -> item.getItemType() == SettlementItemType.CAPTURE)
                .map(SettlementBatchItem::getTransactionId)
                .filter(Objects::nonNull)
                .toList();
        return transactionRepository.findAllById(transactionIds).stream()
                .map(PaymentTransaction::getProviderTransactionId)
                .sorted()
                .toList();
    }

    /**
     * The provider accepted the payout. Its gross must be the batch total and its fee and net must add up;
     * anything else contradicts our books and is refused (the call is retried, then escalated).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProviderAccepted(SettlementBatch batch, ProviderResult.PayoutAccepted accepted, String actor) {
        if (batch.getPayoutStatus() != PayoutStatus.SENT) {
            return;
        }
        if (accepted.gross().compareTo(batch.getTotalAmount()) != 0) {
            throw new IllegalStateException("Provider reports paying out " + accepted.gross().toPlainString() + " for batch "
                    + batch.getId() + " of " + batch.getTotalAmount().toPlainString() + " " + batch.getCurrency());
        }
        if (accepted.gross().subtract(accepted.fee()).compareTo(accepted.net()) != 0 || accepted.fee().signum() < 0) {
            throw new IllegalStateException("Provider payout " + accepted.providerPayoutId() + ": gross "
                    + accepted.gross().toPlainString() + " minus fee " + accepted.fee().toPlainString()
                    + " is not net " + accepted.net().toPlainString());
        }
        batch.markInTransit(accepted.providerPayoutId(), accepted.fee(), accepted.net(), accepted.expectedValueDate(),
                clock.instant());
        ledgerService.payoutInTransit(batch.getId(), batch.getCurrency(), batch.getTotalAmount());
        auditService.record(AggregateType.SETTLEMENT_BATCH, batch.getId().toString(), null, "PAYOUT_IN_TRANSIT", actor,
                "Provider accepted the payout: gross " + plain(batch.getTotalAmount()) + ", fee " + plain(batch.getProviderFeeAmount())
                        + ", net " + plain(batch.getExpectedNetAmount()) + " " + batch.getCurrency() + ", expected value date "
                        + batch.getExpectedValueDate() + ".",
                "providerPayoutId=" + accepted.providerPayoutId() + ",reference=" + batch.getPayoutReference());
    }

    /** The provider refused the payout: the money stays in its balance and someone has to find out why. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProviderRefused(SettlementBatch batch, String reason, String actor) {
        if (batch.getPayoutStatus() != PayoutStatus.SENT) {
            return;
        }
        batch.markBroken(BrokenReason.PROVIDER_REFUSED, clock.instant());
        breakService.open(BreakCategory.PROVIDER_REFUSED_PAYOUT, BreakService.batchSubject(batch.getId()), batch.getId(),
                null, null, batch.getCurrency(), batch.getTotalAmount(), BigDecimal.ZERO,
                "Provider refused payout " + batch.getPayoutReference() + " of " + plain(batch.getTotalAmount()) + " "
                        + batch.getCurrency() + ": " + reason + ". The money is still in the provider balance.", actor);
        auditService.record(AggregateType.SETTLEMENT_BATCH, batch.getId().toString(), null, "PAYOUT_REFUSED", actor,
                "Provider refused the payout: " + reason, "reference=" + batch.getPayoutReference());
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "?" : amount.toPlainString();
    }
}
