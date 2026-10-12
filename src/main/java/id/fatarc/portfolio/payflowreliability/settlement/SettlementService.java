package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransactionRepository;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Groups captured payments into a payout batch.
 *
 * <p>Only payments still in SUCCESS are eligible. A payment that moved on before settlement
 * (refund requested, reversal required, on hold) is skipped instead of failing the whole batch.
 *
 * <p>Candidate payments are locked first, in id order, and their state is re-checked under the lock;
 * only then are their transactions loaded and changed. That is the same payment-then-transaction
 * order webhooks use, so settlement and webhooks queue instead of deadlocking, and a second
 * concurrent run waits for the first and then finds nothing left to settle. Versions and the
 * one-item-per-transaction constraint remain as the last guard against double payout.
 */
@Service
public class SettlementService {
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final String SETTLEMENT_ACTOR = "settlement-job";

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

    /**
     * @param currency which currency to settle; may be omitted when the merchant has eligible
     *                 payments in only one currency
     */
    @Transactional
    public SettlementBatch createBatch(String merchantId, String currency) {
        List<UUID> candidateIds = paymentTransactionRepository
                .findSettleablePaymentIntentIds(merchantId, ProviderStatus.SUCCESS, PaymentState.SUCCESS);
        Map<UUID, PaymentIntent> intents = candidateIds.isEmpty()
                ? Map.of()
                : paymentIntentRepository.findAllForUpdate(candidateIds).stream()
                        .filter(intent -> intent.getState() == PaymentState.SUCCESS)
                        .collect(Collectors.toMap(PaymentIntent::getId, Function.identity()));
        List<PaymentTransaction> eligible = paymentTransactionRepository
                .findSettleable(merchantId, ProviderStatus.SUCCESS, PaymentState.SUCCESS).stream()
                .filter(tx -> intents.containsKey(tx.getPaymentIntentId()))
                .toList();

        SortedSet<String> currencies = eligible.stream()
                .map(PaymentTransaction::getCurrency)
                .collect(Collectors.toCollection(TreeSet::new));
        String batchCurrency = chooseCurrency(merchantId, currency, currencies);
        List<PaymentTransaction> batchTransactions = eligible.stream()
                .filter(tx -> tx.getCurrency().equals(batchCurrency))
                .toList();

        BigDecimal batchTotal = batchTransactions.stream()
                .map(PaymentTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        SettlementBatch batch = settlementBatchRepository.save(new SettlementBatch(
                merchantId, batchTransactions.size(), batchTotal, batchCurrency));

        LocalDate settledOn = LocalDate.now();
        for (PaymentTransaction transaction : batchTransactions) {
            transaction.markSettled(settledOn);
            settlementBatchItemRepository.save(new SettlementBatchItem(batch.getId(), transaction.getId(), transaction.getAmount()));

            PaymentIntent intent = intents.get(transaction.getPaymentIntentId());
            PaymentState previousState = intent.settle();
            auditService.recordStateChange(intent.getId(), previousState, intent.getState(), SETTLEMENT_ACTOR,
                    "Payment included in settlement batch " + batch.getId(), "batchId=" + batch.getId());
        }

        auditService.record(AggregateType.SETTLEMENT_BATCH, batch.getId().toString(), null, "SETTLEMENT_CREATED",
                SETTLEMENT_ACTOR,
                "Settlement batch created for merchant " + merchantId + " with " + batchTransactions.size()
                        + " transaction(s) totalling " + batchTotal.toPlainString() + " " + batchCurrency + ".",
                "transactionCount=" + batchTransactions.size() + ",currency=" + batchCurrency);
        log.info("Settlement batch {} created merchantId={} currency={} transactions={} total={}",
                batch.getId(), merchantId, batchCurrency, batchTransactions.size(), batchTotal.toPlainString());
        return batch;
    }

    /** Newest first; optionally for one merchant. */
    @Transactional(readOnly = true)
    public List<SettlementBatch> listBatches(String merchantId) {
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt");
        return merchantId == null || merchantId.isBlank()
                ? settlementBatchRepository.findAll(newestFirst)
                : settlementBatchRepository.findByMerchantId(merchantId, newestFirst);
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchItem> getItems(UUID batchId) {
        if (!settlementBatchRepository.existsById(batchId)) {
            throw new BusinessException(ErrorCode.SETTLEMENT_BATCH_NOT_FOUND, "Settlement batch not found: " + batchId);
        }
        return settlementBatchItemRepository.findByBatchId(batchId);
    }

    private static String chooseCurrency(String merchantId, String requested, SortedSet<String> available) {
        if (requested != null) {
            if (!available.contains(requested)) {
                throw new BusinessException(ErrorCode.NOTHING_TO_SETTLE,
                        "No unsettled successful " + requested + " transactions found for merchant " + merchantId
                                + (available.isEmpty() ? "." : ". Eligible currencies: " + available + "."));
            }
            return requested;
        }
        if (available.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTHING_TO_SETTLE,
                    "No unsettled successful transactions found for merchant " + merchantId + ".");
        }
        if (available.size() > 1) {
            throw new BusinessException(ErrorCode.SETTLEMENT_CURRENCY_REQUIRED,
                    "Merchant " + merchantId + " has eligible transactions in " + available
                            + ". A batch holds one currency: pass currency=<code>.");
        }
        return available.first();
    }
}
