package id.fatarc.portfolio.payflowreliability.settlement;

import org.springframework.data.domain.Pageable;
import id.fatarc.portfolio.payflowreliability.common.ListLimit;
import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerService;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxService;
import id.fatarc.portfolio.payflowreliability.payment.*;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequestRepository;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pays merchants out, net: each batch adds captured payments and deducts completed refunds and
 * confirmed reversals that have not been paid out yet, in one currency.
 *
 * <ul>
 *   <li>A payment's unsettled items settle together, and only when the payment is not on hold or
 *       mid-reversal. Held payments wait; they never fail the batch.</li>
 *   <li>A negative net (deductions exceed captures) is not paid; it is carried forward.</li>
 *   <li>Candidate payments are locked in id order and re-checked before anything is written, the
 *       same payment-first lock order as every other flow, so concurrent runs queue instead of
 *       deadlocking and a second run finds nothing left to settle.</li>
 *   <li>Each capture, refund and reversal can be settled at most once (unique constraints), and the
 *       payout journal is posted in the same transaction.</li>
 *   <li>The payout instruction to the provider is queued in the same transaction (outbox, key
 *       {@code payout-<batchId>}). The batch is SENT; only a matched bank credit makes it LANDED (ADR 025).</li>
 * </ul>
 */
@Service
public class SettlementService {
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    static final Set<PaymentState> PAYOUT_STATES = EnumSet.of(PaymentState.SUCCESS, PaymentState.SETTLED,
            PaymentState.RECONCILED, PaymentState.REFUNDED, PaymentState.REVERSED);
    private static final Set<ProviderStatus> CAPTURED_TX = EnumSet.of(ProviderStatus.SUCCESS, ProviderStatus.REVERSAL_REQUIRED);

    private final EntityManager entityManager;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementBatchItemRepository settlementBatchItemRepository;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final Clock clock;

    public SettlementService(EntityManager entityManager,
                             PaymentIntentRepository paymentIntentRepository,
                             PaymentTransactionRepository paymentTransactionRepository,
                             RefundRequestRepository refundRequestRepository,
                             SettlementBatchRepository settlementBatchRepository,
                             SettlementBatchItemRepository settlementBatchItemRepository,
                             LedgerService ledgerService,
                             OutboxService outboxService,
                             AuditService auditService,
                             Clock clock) {
        this.entityManager = entityManager;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.settlementBatchItemRepository = settlementBatchItemRepository;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * @param currency which currency to settle; may be omitted when the merchant has payable items in only one currency
     */
    @Transactional
    public SettlementBatch createBatch(String merchantId, String currency, Caller caller) {
        List<UUID> candidates = candidatePaymentIds(merchantId);
        List<PaymentIntent> locked = candidates.isEmpty() ? List.of() : paymentIntentRepository.findAllForUpdate(candidates);
        List<Payout> payouts = locked.stream()
                .filter(SettlementService::eligible)
                .map(this::payoutFor)
                .filter(payout -> !payout.isEmpty())
                .toList();

        SortedSet<String> currencies = payouts.stream().map(payout -> payout.intent().getCurrency())
                .collect(Collectors.toCollection(TreeSet::new));
        String batchCurrency = chooseCurrency(merchantId, currency, currencies);
        List<Payout> batchPayouts = payouts.stream()
                .filter(payout -> payout.intent().getCurrency().equals(batchCurrency))
                .toList();

        BigDecimal captures = batchPayouts.stream().map(Payout::captureAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deductions = batchPayouts.stream().map(Payout::deductionAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = captures.subtract(deductions);
        if (net.signum() <= 0) {
            throw new BusinessException(ErrorCode.NOTHING_TO_SETTLE,
                    "Net payout for merchant " + merchantId + " in " + batchCurrency + " would be "
                            + Money.normalize(net, batchCurrency).toPlainString()
                            + ": refunds and reversals exceed captures, so nothing is paid and they carry forward.");
        }

        Instant now = clock.instant();
        LocalDate settledOn = LocalDate.ofInstant(now, ZoneId.systemDefault());
        int itemCount = batchPayouts.stream().mapToInt(Payout::itemCount).sum();
        SettlementBatch batch = settlementBatchRepository.save(
                new SettlementBatch(merchantId, itemCount, captures, deductions, batchCurrency, now));

        for (Payout payout : batchPayouts) {
            PaymentIntent intent = payout.intent();
            payout.capture().ifPresent(transaction -> {
                transaction.markSettled(settledOn);
                settlementBatchItemRepository.save(SettlementBatchItem.capture(batch.getId(), intent.getId(),
                        transaction.getId(), intent.getAmount(), batchCurrency, now));
            });
            for (RefundRequest refund : payout.refunds()) {
                settlementBatchItemRepository.save(SettlementBatchItem.refund(batch.getId(), intent.getId(),
                        refund.getId(), refund.getAmount(), batchCurrency, now));
            }
            if (payout.reversal()) {
                settlementBatchItemRepository.save(SettlementBatchItem.reversal(batch.getId(), intent.getId(),
                        intent.getReversedAmount(), batchCurrency, now));
            }
            if (payout.capture().isPresent() && intent.getState() == PaymentState.SUCCESS) {
                PaymentState previousState = intent.settle();
                auditService.recordStateChange(intent.getId(), previousState, intent.getState(), caller.name(),
                        "Payment included in settlement batch " + batch.getId(), "batchId=" + batch.getId());
            }
        }
        ledgerService.payout(batch.getId(), merchantId, batchCurrency, batch.getTotalAmount());
        // The payout instruction is written with the batch: one transaction, so neither exists without the other.
        outboxService.enqueuePayout(batch.getId());

        auditService.record(AggregateType.SETTLEMENT_BATCH, batch.getId().toString(), null, "SETTLEMENT_CREATED",
                caller.name(),
                "Settlement batch for merchant " + merchantId + ": captures " + batch.getCaptureAmount().toPlainString()
                        + ", deductions " + batch.getDeductionAmount().toPlainString() + ", net "
                        + batch.getTotalAmount().toPlainString() + " " + batchCurrency + ".",
                "items=" + itemCount + ",currency=" + batchCurrency);
        log.info("Settlement batch {} merchantId={} currency={} items={} captures={} deductions={} net={}",
                batch.getId(), merchantId, batchCurrency, itemCount, batch.getCaptureAmount(), batch.getDeductionAmount(),
                batch.getTotalAmount());
        return batch;
    }

    /** What the next batches would pay, per merchant and currency, without changing anything. */
    @Transactional(readOnly = true)
    public List<Backlog> backlog() {
        Map<String, Backlog> totals = new TreeMap<>();
        for (UUID paymentId : candidatePaymentIds(null)) {
            PaymentIntent intent = paymentIntentRepository.findById(paymentId).orElseThrow();
            if (!eligible(intent)) {
                continue;
            }
            Payout payout = payoutFor(intent);
            if (payout.isEmpty()) {
                continue;
            }
            totals.merge(intent.getMerchantId() + "\u0000" + intent.getCurrency(),
                    new Backlog(intent.getMerchantId(), intent.getCurrency(), 1, payout.captureAmount(),
                            payout.deductionAmount(), payout.captureAmount().subtract(payout.deductionAmount())),
                    Backlog::plus);
        }
        return totals.values().stream().map(Backlog::atScale).toList();
    }

    /** Newest first; optionally for one merchant. */
    @Transactional(readOnly = true)
    public List<SettlementBatch> listBatches(String merchantId, Integer limit) {
        Pageable newestFirst = ListLimit.first(limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return merchantId == null || merchantId.isBlank()
                ? settlementBatchRepository.findAll(newestFirst).getContent()
                : settlementBatchRepository.findByMerchantId(merchantId, newestFirst);
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchItem> getItems(UUID batchId) {
        if (!settlementBatchRepository.existsById(batchId)) {
            throw new BusinessException(ErrorCode.SETTLEMENT_BATCH_NOT_FOUND, "Settlement batch not found: " + batchId);
        }
        return settlementBatchItemRepository.findByBatchId(batchId);
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchItem> itemsForPayment(UUID paymentIntentId) {
        return settlementBatchItemRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
    }

    /** Captured payments with anything not yet paid out; scalars only, so nothing is cached before locking. */
    private List<UUID> candidatePaymentIds(String merchantId) {
        String jpql = """
                select p.id from PaymentIntent p
                where p.capturedAt is not null
                  and p.state in :states
                  %s
                  and (not exists (select 1 from SettlementBatchItem c where c.capturePaymentIntentId = p.id)
                       or exists (select 1 from RefundRequest r where r.paymentIntentId = p.id
                                    and r.status = id.fatarc.portfolio.payflowreliability.refund.RefundStatus.COMPLETED
                                    and not exists (select 1 from SettlementBatchItem i where i.refundId = r.id))
                       or (p.state = id.fatarc.portfolio.payflowreliability.payment.PaymentState.REVERSED
                           and p.reversedAmount > 0
                           and not exists (select 1 from SettlementBatchItem i where i.reversalPaymentIntentId = p.id)))
                order by p.id
                """.formatted(merchantId == null ? "" : "and p.merchantId = :merchantId");
        var query = entityManager.createQuery(jpql, UUID.class)
                .setParameter("states", PAYOUT_STATES);
        if (merchantId != null) {
            query.setParameter("merchantId", merchantId);
        }
        return query.getResultList();
    }

    private static boolean eligible(PaymentIntent intent) {
        return intent.isCaptured() && PAYOUT_STATES.contains(intent.getState());
    }

    /**
     * The capture is the payment's, not a transaction row's: a chargeback can arrive under a new provider
     * transaction id, and that row must never be paid out as a second capture.
     */
    private Payout payoutFor(PaymentIntent intent) {
        Optional<PaymentTransaction> capture = settlementBatchItemRepository.existsByCapturePaymentIntentId(intent.getId())
                ? Optional.empty()
                : paymentTransactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()).stream()
                .filter(transaction -> CAPTURED_TX.contains(transaction.getProviderStatus()))
                .findFirst();
        List<RefundRequest> refunds = refundRequestRepository.findUnsettledCompleted(List.of(intent.getId()));
        boolean reversal = intent.getState() == PaymentState.REVERSED
                && intent.getReversedAmount().signum() > 0
                && !settlementBatchItemRepository.existsByReversalPaymentIntentId(intent.getId());
        return new Payout(intent, capture, refunds, reversal);
    }

    private static String chooseCurrency(String merchantId, String requested, SortedSet<String> available) {
        if (requested != null) {
            if (!available.contains(requested)) {
                throw new BusinessException(ErrorCode.NOTHING_TO_SETTLE,
                        "Nothing to settle in " + requested + " for merchant " + merchantId
                                + (available.isEmpty() ? "." : ". Currencies with payable items: " + available + "."));
            }
            return requested;
        }
        if (available.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTHING_TO_SETTLE, "Nothing to settle for merchant " + merchantId + ".");
        }
        if (available.size() > 1) {
            throw new BusinessException(ErrorCode.SETTLEMENT_CURRENCY_REQUIRED,
                    "Merchant " + merchantId + " has payable items in " + available
                            + ". A batch holds one currency: pass currency=<code>.");
        }
        return available.first();
    }

    private record Payout(PaymentIntent intent, Optional<PaymentTransaction> capture, List<RefundRequest> refunds,
                          boolean reversal) {
        boolean isEmpty() {
            return capture.isEmpty() && refunds.isEmpty() && !reversal;
        }

        int itemCount() {
            return (capture.isPresent() ? 1 : 0) + refunds.size() + (reversal ? 1 : 0);
        }

        BigDecimal captureAmount() {
            return capture.isPresent() ? intent.getAmount() : BigDecimal.ZERO;
        }

        BigDecimal deductionAmount() {
            BigDecimal refunded = refunds.stream().map(RefundRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return reversal ? refunded.add(intent.getReversedAmount()) : refunded;
        }
    }

    /**
     * @param netAmount what would be paid out now; zero or negative carries forward
     */
    public record Backlog(String merchantId, String currency, int paymentCount, BigDecimal captureAmount,
                          BigDecimal deductionAmount, BigDecimal netAmount) {
        Backlog plus(Backlog other) {
            return new Backlog(merchantId, currency, paymentCount + other.paymentCount,
                    captureAmount.add(other.captureAmount), deductionAmount.add(other.deductionAmount),
                    netAmount.add(other.netAmount));
        }

        Backlog atScale() {
            return new Backlog(merchantId, currency, paymentCount, Money.atScale(captureAmount, currency),
                    Money.atScale(deductionAmount, currency), Money.atScale(netAmount, currency));
        }
    }
}
