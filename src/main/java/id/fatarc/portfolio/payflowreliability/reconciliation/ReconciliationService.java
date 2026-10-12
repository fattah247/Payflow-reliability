package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransactionRepository;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.RowOutcome;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.RowResult;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares a provider settlement report against local records. Only clean matches change state
 * (SETTLED to RECONCILED); every discrepancy becomes a review case. Re-importing the same report
 * is safe: reconciled rows report ALREADY_RECONCILED and open review cases are reused.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String RECONCILIATION_ACTOR = "reconciliation-job";

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final ReviewService reviewService;
    private final AuditService auditService;

    public ReconciliationService(PaymentTransactionRepository paymentTransactionRepository,
                                 PaymentIntentRepository paymentIntentRepository,
                                 ReviewService reviewService,
                                 AuditService auditService) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.reviewService = reviewService;
        this.auditService = auditService;
    }

    @Transactional
    public ReconciliationReportResponse importReport(ReconciliationReportRequest report) {
        List<ReconciliationReportRequest.Row> rows = report.rows();
        Map<String, PaymentTransaction> transactions = paymentTransactionRepository
                .findByProviderTransactionIdIn(rows.stream().map(ReconciliationReportRequest.Row::providerTransactionId).toList())
                .stream()
                .collect(Collectors.toMap(PaymentTransaction::getProviderTransactionId, Function.identity()));
        Map<UUID, PaymentIntent> intents = paymentIntentRepository
                .findAllById(transactions.values().stream().map(PaymentTransaction::getPaymentIntentId).toList())
                .stream()
                .collect(Collectors.toMap(PaymentIntent::getId, Function.identity()));

        Set<String> seenReferences = new HashSet<>();
        List<RowResult> results = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            ReconciliationReportRequest.Row row = rows.get(index);
            int rowNumber = index + 1;
            String reference = row.providerTransactionId();

            if (!seenReferences.add(reference)) {
                ReviewCase reviewCase = openReview(ReviewReason.DUPLICATE_PROVIDER_REFERENCE,
                        AggregateType.PROVIDER_TRANSACTION, reference, null,
                        "Provider report lists provider reference " + reference + " more than once (row " + rowNumber + ").");
                results.add(new RowResult(rowNumber, reference, RowOutcome.DUPLICATE_ROW, null, null,
                        List.of(ReviewReason.DUPLICATE_PROVIDER_REFERENCE), List.of(reviewCase.getId()),
                        "Reference already appeared earlier in this report; row ignored."));
                continue;
            }

            PaymentTransaction transaction = transactions.get(reference);
            if (transaction == null) {
                ReviewCase reviewCase = openReview(ReviewReason.PROVIDER_TRANSACTION_NOT_FOUND,
                        AggregateType.PROVIDER_TRANSACTION, reference, null,
                        "Provider report contains transaction " + reference + ", which is not found locally.");
                results.add(new RowResult(rowNumber, reference, RowOutcome.NOT_FOUND_LOCALLY, null, null,
                        List.of(ReviewReason.PROVIDER_TRANSACTION_NOT_FOUND), List.of(reviewCase.getId()),
                        "No local transaction has this provider reference."));
                continue;
            }

            PaymentIntent intent = intents.get(transaction.getPaymentIntentId());
            results.add(reconcileRow(rowNumber, row, transaction, intent));
        }

        int mismatchCount = results.stream().mapToInt(result -> result.mismatches().size()).sum();
        int reconciledCount = (int) results.stream().filter(result -> result.outcome() == RowOutcome.RECONCILED).count();
        log.info("Reconciliation import checkedRows={} reconciled={} mismatches={}", rows.size(), reconciledCount, mismatchCount);
        return new ReconciliationReportResponse(rows.size(), mismatchCount, reconciledCount, results);
    }

    private RowResult reconcileRow(int rowNumber, ReconciliationReportRequest.Row row,
                                   PaymentTransaction transaction, PaymentIntent intent) {
        String reference = row.providerTransactionId();
        List<ReviewReason> mismatches = new ArrayList<>();
        List<UUID> reviewCaseIds = new ArrayList<>();

        if (transaction.getAmount().compareTo(row.amount()) != 0) {
            mismatches.add(ReviewReason.AMOUNT_MISMATCH);
            reviewCaseIds.add(openReview(ReviewReason.AMOUNT_MISMATCH, AggregateType.PROVIDER_TRANSACTION, reference, intent.getId(),
                    "Provider amount " + row.amount().toPlainString() + " does not match local amount "
                            + transaction.getAmount().toPlainString() + ".").getId());
        }
        if (!transaction.getCurrency().equals(row.currency())) {
            mismatches.add(ReviewReason.CURRENCY_MISMATCH);
            reviewCaseIds.add(openReview(ReviewReason.CURRENCY_MISMATCH, AggregateType.PROVIDER_TRANSACTION, reference, intent.getId(),
                    "Provider currency " + row.currency() + " does not match local currency " + transaction.getCurrency() + ".").getId());
        }
        if (transaction.getProviderStatus() != row.providerStatus()) {
            mismatches.add(ReviewReason.STATUS_MISMATCH);
            reviewCaseIds.add(openReview(ReviewReason.STATUS_MISMATCH, AggregateType.PROVIDER_TRANSACTION, reference, intent.getId(),
                    "Provider status " + row.providerStatus() + " does not match local status " + transaction.getProviderStatus() + ".").getId());
        }
        if (row.settlementDate() != null && !row.settlementDate().equals(transaction.getSettledOn())) {
            mismatches.add(ReviewReason.SETTLEMENT_DATE_MISMATCH);
            reviewCaseIds.add(openReview(ReviewReason.SETTLEMENT_DATE_MISMATCH, AggregateType.PROVIDER_TRANSACTION, reference, intent.getId(),
                    "Provider settlement date " + row.settlementDate() + " does not match local settlement date "
                            + (transaction.getSettledOn() == null ? "(not settled locally)" : transaction.getSettledOn()) + ".").getId());
        }
        if (isRefundedIntentReportedAsSuccessful(intent, row.providerStatus())) {
            mismatches.add(ReviewReason.STATUS_MISMATCH);
            reviewCaseIds.add(openReview(ReviewReason.STATUS_MISMATCH, AggregateType.PAYMENT_INTENT, intent.getId().toString(), intent.getId(),
                    "Payment is " + intent.getState() + " locally but still appears successful in the provider report.").getId());
        }

        if (!mismatches.isEmpty()) {
            return new RowResult(rowNumber, reference, RowOutcome.MISMATCH, intent.getId(), intent.getState(),
                    mismatches, reviewCaseIds, mismatches.size() + " discrepancy(ies) sent to manual review.");
        }

        return switch (intent.getState()) {
            case SETTLED -> {
                PaymentState previousState = intent.reconcile();
                auditService.recordStateChange(intent.getId(), previousState, intent.getState(), RECONCILIATION_ACTOR,
                        "Settlement row matched the local transaction.", "providerTransactionId=" + reference);
                yield new RowResult(rowNumber, reference, RowOutcome.RECONCILED, intent.getId(), intent.getState(),
                        List.of(), List.of(), "Matched; payment reconciled.");
            }
            case RECONCILED -> new RowResult(rowNumber, reference, RowOutcome.ALREADY_RECONCILED, intent.getId(),
                    intent.getState(), List.of(), List.of(), "Matched; payment was already reconciled.");
            default -> new RowResult(rowNumber, reference, RowOutcome.MATCHED_NO_ACTION, intent.getId(), intent.getState(),
                    List.of(), List.of(), "Matched, but only SETTLED payments are reconciled; payment is " + intent.getState() + ".");
        };
    }

    private ReviewCase openReview(ReviewReason reason, String referenceType, String referenceId, UUID paymentIntentId, String details) {
        ReviewCase reviewCase = reviewService.openCase(reason, referenceType, referenceId, paymentIntentId, details, RECONCILIATION_ACTOR);
        auditService.record(referenceType, referenceId, paymentIntentId, "RECONCILIATION_MISMATCH_FOUND",
                RECONCILIATION_ACTOR, details, "reason=" + reason + ",reviewCaseId=" + reviewCase.getId());
        return reviewCase;
    }

    /** Only a completed refund contradicts SUCCESS; a requested refund is expected to still look successful. */
    private static boolean isRefundedIntentReportedAsSuccessful(PaymentIntent intent, ProviderStatus providerStatus) {
        return intent.getState() == PaymentState.REFUNDED && providerStatus == ProviderStatus.SUCCESS;
    }
}
