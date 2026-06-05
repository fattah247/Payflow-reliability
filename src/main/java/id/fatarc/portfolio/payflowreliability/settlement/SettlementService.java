package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransactionRepository;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class SettlementService {
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementBatchItemRepository settlementBatchItemRepository;
    private final AuditService auditService;

    public SettlementService(PaymentIntentRepository paymentIntentRepository,
                             PaymentTransactionRepository paymentTransactionRepository,
                             SettlementBatchRepository settlementBatchRepository,
                             SettlementBatchItemRepository settlementBatchItemRepository,
                             AuditService auditService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.settlementBatchItemRepository = settlementBatchItemRepository;
        this.auditService = auditService;
    }

    @Transactional
    public SettlementBatch createBatch(String merchantId) {
        List<PaymentTransaction> pendingTransactions = paymentTransactionRepository
                .findByMerchantIdAndSettledFalseAndProviderStatus(merchantId, ProviderStatus.SUCCESS);

        if (pendingTransactions.isEmpty()) {
            throw new BusinessException("No unsettled successful transactions found for merchant: " + merchantId);
        }

        String currency = pendingTransactions.getFirst().getCurrency();
        boolean hasMixedCurrency = pendingTransactions.stream().anyMatch(tx -> !tx.getCurrency().equals(currency));
        if (hasMixedCurrency) {
            throw new BusinessException("Mixed currency settlement is not supported in this MVP.");
        }

        BigDecimal batchTotal = pendingTransactions.stream()
                .map(PaymentTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        SettlementBatch batch = settlementBatchRepository.save(new SettlementBatch(
                merchantId,
                pendingTransactions.size(),
                batchTotal,
                currency
        ));

        for (PaymentTransaction transaction : pendingTransactions) {
            transaction.markSettled(LocalDate.now());
            settlementBatchItemRepository.save(new SettlementBatchItem(
                    batch.getId(),
                    transaction.getId(),
                    transaction.getAmount()
            ));

            PaymentIntent intent = paymentIntentRepository.findById(transaction.getPaymentIntentId())
                    .orElseThrow(() -> new BusinessException("Payment intent missing for transaction: " + transaction.getId()));
            PaymentState previousState = intent.settle();
            auditService.recordStateChange("PAYMENT_INTENT", intent.getId().toString(), previousState.name(),
                    intent.getState().name(), "settlement-job",
                    "Payment included in settlement batch " + batch.getId(),
                    "batchId=" + batch.getId());
        }

        auditService.record("SETTLEMENT_BATCH", batch.getId().toString(), "SETTLEMENT_CREATED",
                "settlement-job",
                "Settlement batch created for merchant " + merchantId + " with " + pendingTransactions.size() + " transaction(s).",
                "transactionCount=" + pendingTransactions.size());

        return batch;
    }

    @Transactional(readOnly = true)
    public List<SettlementBatch> listBatches() {
        return settlementBatchRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchItem> getItems(java.util.UUID batchId) {
        return settlementBatchItemRepository.findByBatchId(batchId);
    }
}
