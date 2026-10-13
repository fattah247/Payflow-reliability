package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.audit.AuditChainVerifier;
import id.fatarc.portfolio.payflowreliability.audit.AuditHash;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerInvariantService;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerQueryService;
import id.fatarc.portfolio.payflowreliability.operations.dto.OperationsSummary;
import id.fatarc.portfolio.payflowreliability.operations.dto.PaymentCaseFile;
import id.fatarc.portfolio.payflowreliability.outbox.CommandStatus;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommand;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommandRepository;
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
import id.fatarc.portfolio.payflowreliability.settlement.SettlementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Read-only views that answer operators' questions in one call. */
@Service
public class OperationsService {
    static final Set<PaymentState> WAITING_ON_PROVIDER = EnumSet.of(PaymentState.PENDING, PaymentState.AUTHORIZED);

    private final PaymentIntentService paymentIntentService;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ProviderEventRepository providerEventRepository;
    private final OutboxCommandRepository outboxCommandRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final SettlementService settlementService;
    private final LedgerQueryService ledgerQueryService;
    private final LedgerInvariantService ledgerInvariantService;
    private final ReviewService reviewService;
    private final ReviewWorkflowService reviewWorkflowService;
    private final ReviewCaseRepository reviewCaseRepository;
    private final AuditService auditService;
    private final AuditChainVerifier auditChainVerifier;
    private final Clock clock;
    private final Duration stuckAfter;

    public OperationsService(PaymentIntentService paymentIntentService,
                             PaymentIntentRepository paymentIntentRepository,
                             PaymentTransactionRepository paymentTransactionRepository,
                             ProviderEventRepository providerEventRepository,
                             OutboxCommandRepository outboxCommandRepository,
                             RefundRequestRepository refundRequestRepository,
                             SettlementService settlementService,
                             LedgerQueryService ledgerQueryService,
                             LedgerInvariantService ledgerInvariantService,
                             ReviewService reviewService,
                             ReviewWorkflowService reviewWorkflowService,
                             ReviewCaseRepository reviewCaseRepository,
                             AuditService auditService,
                             AuditChainVerifier auditChainVerifier,
                             Clock clock,
                             @Value("${payflow.sweeper.stuck-after:15m}") Duration stuckAfter) {
        this.paymentIntentService = paymentIntentService;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.providerEventRepository = providerEventRepository;
        this.outboxCommandRepository = outboxCommandRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.settlementService = settlementService;
        this.ledgerQueryService = ledgerQueryService;
        this.ledgerInvariantService = ledgerInvariantService;
        this.reviewService = reviewService;
        this.reviewWorkflowService = reviewWorkflowService;
        this.reviewCaseRepository = reviewCaseRepository;
        this.auditService = auditService;
        this.auditChainVerifier = auditChainVerifier;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
    }

    @Transactional(readOnly = true)
    public PaymentCaseFile caseFile(UUID paymentIntentId) {
        PaymentIntent intent = paymentIntentService.getRequired(paymentIntentId);
        List<RefundRequest> refunds = refundRequestRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
        List<OutboxCommand> calls = outboxCommandRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
        List<ReviewCaseView> reviewCases = reviewWorkflowService.describe(reviewService.casesForPayment(paymentIntentId));

        return new PaymentCaseFile(
                PaymentIntentResponse.from(intent),
                attention(intent, refunds, calls, reviewCases),
                paymentTransactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId),
                providerEventRepository.findByPaymentIntentIdOrderByReceivedAtAsc(paymentIntentId),
                calls,
                refunds,
                settlementService.itemsForPayment(paymentIntentId),
                ledgerQueryService.journalsForPayment(paymentIntentId),
                reviewCases,
                auditChainVerifier.verify(AuditHash.chainKey(paymentIntentId, null, null)),
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

        Map<ReviewReason, Long> byReason = new EnumMap<>(ReviewReason.class);
        reviewCaseRepository.countByReason(ReviewStatus.UNRESOLVED)
                .forEach(count -> byReason.put(count.getReason(), count.getCaseCount()));
        long unresolved = byReason.values().stream().mapToLong(Long::longValue).sum();

        return new OperationsSummary(
                clock.instant(),
                paymentsByState,
                paymentIntentRepository.countByStateInAndUpdatedAtBefore(WAITING_ON_PROVIDER, clock.instant().minus(stuckAfter)),
                new OperationsSummary.ReviewQueue(
                        unresolved,
                        reviewCaseRepository.countByStatus(ReviewStatus.AWAITING_APPROVAL),
                        byReason,
                        paymentsByState.get(PaymentState.MANUAL_REVIEW),
                        reviewCaseRepository.oldestCreatedAt(ReviewStatus.UNRESOLVED).orElse(null)),
                new OperationsSummary.Refunds(
                        refundRequestRepository.countByStatus(RefundStatus.PENDING_REVIEW),
                        refundRequestRepository.countByStatus(RefundStatus.REQUESTED),
                        refundRequestRepository.countByStatus(RefundStatus.FAILED)),
                new OperationsSummary.ProviderCalls(
                        outboxCommandRepository.countByStatus(CommandStatus.PENDING),
                        outboxCommandRepository.countByStatus(CommandStatus.NEEDS_REVIEW),
                        outboxCommandRepository.countByStatus(CommandStatus.FAILED),
                        outboxCommandRepository.oldestPendingCreatedAt().orElse(null)),
                settlementService.backlog(),
                ledgerInvariantService.lastReport()
        );
    }

    static PaymentCaseFile.Attention attention(PaymentIntent intent, List<RefundRequest> refunds,
                                               List<OutboxCommand> calls, List<ReviewCaseView> reviewCases) {
        List<UUID> openCaseIds = reviewCases.stream()
                .filter(reviewCase -> reviewCase.status() != ReviewStatus.RESOLVED)
                .map(ReviewCaseView::id)
                .toList();
        Optional<RefundRequest> refundInReview = refunds.stream()
                .filter(refund -> refund.getStatus() == RefundStatus.PENDING_REVIEW).findFirst();
        List<RefundRequest> refundsInFlight = refunds.stream()
                .filter(refund -> refund.getStatus() == RefundStatus.REQUESTED).toList();
        Optional<OutboxCommand> stuckCall = calls.stream()
                .filter(call -> call.getStatus() == CommandStatus.NEEDS_REVIEW).findFirst();

        String waitingOn = switch (intent.getState()) {
            case CREATED -> "Merchant to start the payment.";
            case PENDING -> "Provider event with the payment result (the status sweeper asks the provider if it never arrives).";
            case AUTHORIZED -> "Provider capture (SUCCESS) or failure.";
            case SUCCESS -> "Next settlement batch for merchant " + intent.getMerchantId() + " in " + intent.getCurrency() + ".";
            case SETTLED -> "Provider settlement report for reconciliation.";
            case RECONCILED -> "Nothing: the payment is reconciled.";
            case REFUNDED -> "Nothing: the payment is fully refunded.";
            case REVERSAL_REQUIRED -> "Provider to confirm the reversal.";
            case REVERSED -> "Nothing: the payment was reversed.";
            case MANUAL_REVIEW -> "Operator decision on the hold (held from " + intent.getStateBeforeReview()
                    + "): resolve an open review case with RELEASE_PAYMENT, FAIL_PAYMENT or REQUIRE_REVERSAL.";
            case FAILED, EXPIRED -> "Nothing: " + intent.getState() + " is final.";
        };
        List<String> also = new ArrayList<>();
        refundInReview.ifPresent(refund -> also.add("refund " + refund.getId() + " needs an operator decision"));
        if (!refundsInFlight.isEmpty()) {
            also.add(refundsInFlight.size() + " refund(s) being executed by the provider");
        }
        stuckCall.ifPresent(call -> also.add("provider call " + call.getId() + " has an unknown outcome and needs a retry decision"));
        List<String> openReasons = reviewCases.stream()
                .filter(reviewCase -> reviewCase.status() != ReviewStatus.RESOLVED)
                .map(reviewCase -> reviewCase.reason().name())
                .distinct()
                .toList();
        if (!openReasons.isEmpty() && intent.getState() != PaymentState.MANUAL_REVIEW) {
            also.add(openReasons.size() + " open review reason(s) need an operator: " + String.join(", ", openReasons));
        }
        if (!also.isEmpty()) {
            waitingOn = waitingOn + " Also: " + String.join("; ", also) + ".";
        }
        boolean needsAction = !openCaseIds.isEmpty()
                || refundInReview.isPresent()
                || stuckCall.isPresent()
                || intent.getState() == PaymentState.MANUAL_REVIEW;
        return new PaymentCaseFile.Attention(needsAction, waitingOn, openCaseIds);
    }
}
