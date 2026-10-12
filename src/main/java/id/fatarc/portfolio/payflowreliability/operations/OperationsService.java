package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.operations.dto.OperationsSummary;
import id.fatarc.portfolio.payflowreliability.operations.dto.PaymentCaseFile;
import id.fatarc.portfolio.payflowreliability.payment.*;
import id.fatarc.portfolio.payflowreliability.payment.dto.PaymentIntentResponse;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequestRepository;
import id.fatarc.portfolio.payflowreliability.refund.RefundStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewCaseRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewWorkflowService;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchItem;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only views that answer operators' questions in one call. */
@Service
public class OperationsService {
    private final PaymentIntentService paymentIntentService;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ProviderEventRepository providerEventRepository;
    private final SettlementBatchItemRepository settlementBatchItemRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final ReviewService reviewService;
    private final ReviewWorkflowService reviewWorkflowService;
    private final ReviewCaseRepository reviewCaseRepository;
    private final AuditService auditService;

    public OperationsService(PaymentIntentService paymentIntentService,
                             PaymentIntentRepository paymentIntentRepository,
                             PaymentTransactionRepository paymentTransactionRepository,
                             ProviderEventRepository providerEventRepository,
                             SettlementBatchItemRepository settlementBatchItemRepository,
                             RefundRequestRepository refundRequestRepository,
                             ReviewService reviewService,
                             ReviewWorkflowService reviewWorkflowService,
                             ReviewCaseRepository reviewCaseRepository,
                             AuditService auditService) {
        this.paymentIntentService = paymentIntentService;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.providerEventRepository = providerEventRepository;
        this.settlementBatchItemRepository = settlementBatchItemRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.reviewService = reviewService;
        this.reviewWorkflowService = reviewWorkflowService;
        this.reviewCaseRepository = reviewCaseRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PaymentCaseFile caseFile(UUID paymentIntentId) {
        PaymentIntent intent = paymentIntentService.getRequired(paymentIntentId);
        List<PaymentTransaction> transactions = paymentTransactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
        Map<UUID, PaymentTransaction> transactionsById = transactions.stream()
                .collect(Collectors.toMap(PaymentTransaction::getId, Function.identity()));
        List<PaymentCaseFile.SettlementEntry> settlements = transactionsById.isEmpty()
                ? List.of()
                : settlementBatchItemRepository.findByTransactionIdIn(transactionsById.keySet()).stream()
                        .map(item -> settlementEntry(item, transactionsById.get(item.getTransactionId())))
                        .toList();
        List<RefundRequest> refunds = refundRequestRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
        List<ReviewCaseView> reviewCases = reviewWorkflowService.describe(reviewService.casesForPayment(paymentIntentId));

        return new PaymentCaseFile(
                PaymentIntentResponse.from(intent),
                attention(intent, refunds, reviewCases),
                transactions,
                providerEventRepository.findByPaymentIntentIdOrderByReceivedAtAsc(paymentIntentId),
                settlements,
                refunds,
                reviewCases,
                auditService.paymentHistory(paymentIntentId)
        );
    }

    @Transactional(readOnly = true)
    public OperationsSummary summary() {
        Map<PaymentState, Long> paymentsByState = new EnumMap<>(PaymentState.class);
        for (PaymentState state : PaymentState.values()) {
            paymentsByState.put(state, 0L);
        }
        paymentIntentRepository.countByState()
                .forEach(count -> paymentsByState.put(count.getState(), count.getPaymentCount()));

        Map<ReviewReason, Long> openByReason = new EnumMap<>(ReviewReason.class);
        reviewCaseRepository.countByReason(ReviewStatus.OPEN)
                .forEach(count -> openByReason.put(count.getReason(), count.getCaseCount()));
        long openCases = openByReason.values().stream().mapToLong(Long::longValue).sum();

        List<OperationsSummary.SettlementBacklog> backlog = paymentTransactionRepository
                .summarizeSettleable(ProviderStatus.SUCCESS, PaymentState.SUCCESS).stream()
                .map(total -> new OperationsSummary.SettlementBacklog(
                        total.getMerchantId(), total.getCurrency(), total.getTransactionCount(), total.getTotalAmount()))
                .toList();

        return new OperationsSummary(
                Instant.now(),
                paymentsByState,
                new OperationsSummary.ReviewQueue(
                        openCases,
                        openByReason,
                        paymentsByState.get(PaymentState.MANUAL_REVIEW),
                        reviewCaseRepository.oldestCreatedAt(ReviewStatus.OPEN).orElse(null)),
                new OperationsSummary.Refunds(
                        refundRequestRepository.countByStatus(RefundStatus.PENDING_REVIEW),
                        refundRequestRepository.countByStatus(RefundStatus.REQUESTED)),
                backlog
        );
    }

    private static PaymentCaseFile.SettlementEntry settlementEntry(SettlementBatchItem item, PaymentTransaction transaction) {
        return new PaymentCaseFile.SettlementEntry(item.getBatchId(), item.getTransactionId(), item.getAmount(),
                transaction.getSettledOn());
    }

    static PaymentCaseFile.Attention attention(PaymentIntent intent, List<RefundRequest> refunds, List<ReviewCaseView> reviewCases) {
        List<UUID> openCaseIds = reviewCases.stream()
                .filter(reviewCase -> reviewCase.status() == ReviewStatus.OPEN)
                .map(ReviewCaseView::id)
                .toList();
        Optional<RefundRequest> refundInReview = refunds.stream()
                .filter(refund -> refund.getStatus() == RefundStatus.PENDING_REVIEW)
                .findFirst();
        Optional<RefundRequest> refundInFlight = refunds.stream()
                .filter(refund -> refund.getStatus() == RefundStatus.REQUESTED)
                .findFirst();

        String waitingOn = switch (intent.getState()) {
            case CREATED -> "Merchant to start the payment.";
            case PENDING -> "Provider webhook with the payment result.";
            case AUTHORIZED -> "Provider capture (SUCCESS) or failure.";
            case SUCCESS -> "Next settlement batch for merchant " + intent.getMerchantId() + " in " + intent.getCurrency() + ".";
            case SETTLED -> "Provider settlement report for reconciliation.";
            case RECONCILED -> refundInReview
                    .map(refund -> "Operator decision on refund " + refund.getId() + " (APPROVE_REFUND or REJECT_REFUND).")
                    .orElse("Nothing: the payment is reconciled.");
            case REFUND_REQUESTED -> refundInFlight
                    .map(refund -> "Operations to complete refund " + refund.getId() + ".")
                    .orElse("Operations to complete the requested refund.");
            case MANUAL_REVIEW -> "Operator decision on the hold (held from " + intent.getStateBeforeReview()
                    + "): resolve an open review case with RELEASE_PAYMENT, FAIL_PAYMENT or REQUIRE_REVERSAL.";
            case REVERSAL_REQUIRED -> "Operations to reverse the payment with the provider.";
            case FAILED, EXPIRED, REFUNDED -> "Nothing: " + intent.getState() + " is final.";
        };
        List<String> openReasons = reviewCases.stream()
                .filter(reviewCase -> reviewCase.status() == ReviewStatus.OPEN)
                .map(reviewCase -> reviewCase.reason().name())
                .distinct()
                .toList();
        if (!openReasons.isEmpty() && intent.getState() != PaymentState.MANUAL_REVIEW && refundInReview.isEmpty()) {
            waitingOn = waitingOn + " Also: " + openReasons.size() + " open review reason(s) need an operator: "
                    + String.join(", ", openReasons) + ".";
        }
        boolean needsAction = !openCaseIds.isEmpty()
                || refundInReview.isPresent()
                || intent.getState() == PaymentState.MANUAL_REVIEW
                || intent.getState() == PaymentState.REVERSAL_REQUIRED;
        return new PaymentCaseFile.Attention(needsAction, waitingOn, openCaseIds);
    }
}
