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
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares a provider settlement report against local records, in both directions:
 * <ul>
 *   <li>every reported row gets a verdict; only clean matches change state (SETTLED to RECONCILED);
 *       every discrepancy (amount, currency, status, date, refunded amount) becomes a review case;</li>
 *   <li>with a settlement date, payouts we recorded for that day (optionally: for one merchant) but the
 *       provider did not report are flagged.</li>
 * </ul>
 * Payments are locked in id order before anything is written (the global lock order), and
 * re-importing the same report is safe: reconciled rows report ALREADY_RECONCILED and open review
 * cases are reused.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

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
    public ReconciliationReportResponse importReport(ReconciliationReportRequest report, Caller caller) {
        List<ReconciliationReportRequest.Row> rows = report.rows();
        Set<String> reported = rows.stream().map(ReconciliationReportRequest.Row::providerTransactionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, PaymentTransaction> transactions = paymentTransactionRepository.findByProviderTransactionIdIn(reported)
                .stream()
                .collect(Collectors.toMap(PaymentTransaction::getProviderTransactionId, Function.identity()));
        List<PaymentTransaction> missing = report.settlementDate() == null ? List.of()
                : paymentTransactionRepository.findBySettledOn(report.settlementDate()).stream()
                .filter(transaction -> report.merchantId() == null || report.merchantId().equals(transaction.getMerchantId()))
                .filter(transaction -> !reported.contains(transaction.getProviderTransactionId()))
                .toList();

        Set<UUID> paymentIds = new TreeSet<>();
        transactions.values().forEach(transaction -> paymentIds.add(transaction.getPaymentIntentId()));
        missing.forEach(transaction -> paymentIds.add(transaction.getPaymentIntentId()));
        Map<UUID, PaymentIntent> intents = paymentIds.isEmpty() ? Map.of()
                : paymentIntentRepository.findAllForUpdate(paymentIds).stream()
                .collect(Collectors.toMap(PaymentIntent::getId, Function.identity()));

        Set<String> seenReferences = new HashSet<>();
        List<RowResult> results = new ArrayList<>(rows.size() + missing.size());
        for (int index = 0; index < rows.size(); index++) {
            ReconciliationReportRequest.Row row = rows.get(index);
            int rowNumber = index + 1;
            String reference = row.providerTransactionId();

            if (!seenReferences.add(reference)) {
                ReviewCase reviewCase = openReview(ReviewReason.DUPLICATE_PROVIDER_REFERENCE,
                        AggregateType.PROVIDER_TRANSACTION, reference, null,
                        "Provider report lists provider reference " + reference + " more than once (row " + rowNumber + ").", caller);
                results.add(new RowResult(rowNumber, reference, RowOutcome.DUPLICATE_ROW, null, null,
                        List.of(ReviewReason.DUPLICATE_PROVIDER_REFERENCE), List.of(reviewCase.getId()),
                        "Reference already appeared earlier in this report; row ignored."));
                continue;
            }

            PaymentTransaction transaction = transactions.get(reference);
            if (transaction == null) {
                ReviewCase reviewCase = openReview(ReviewReason.PROVIDER_TRANSACTION_NOT_FOUND,
                        AggregateType.PROVIDER_TRANSACTION, reference, null,
                        "Provider report contains transaction " + reference + ", which is not found locally.", caller);
                results.add(new RowResult(rowNumber, reference, RowOutcome.NOT_FOUND_LOCALLY, null, null,
                        List.of(ReviewReason.PROVIDER_TRANSACTION_NOT_FOUND), List.of(reviewCase.getId()),
                        "No local transaction has this provider reference."));
                continue;
            }

            results.add(reconcileRow(rowNumber, row, transaction, intents.get(transaction.getPaymentIntentId()), caller));
        }

        for (PaymentTransaction transaction : missing) {
            PaymentIntent intent = intents.get(transaction.getPaymentIntentId());
            ReviewCase reviewCase = openReview(ReviewReason.MISSING_FROM_PROVIDER_REPORT, AggregateType.PROVIDER_TRANSACTION,
                    transaction.getProviderTransactionId(), intent.getId(),
                    "We paid out " + transaction.getProviderTransactionId() + " on " + transaction.getSettledOn()
                            + ", but the provider's report for that date does not list it.", caller);
            results.add(new RowResult(null, transaction.getProviderTransactionId(), RowOutcome.MISSING_FROM_REPORT,
                    intent.getId(), intent.getState(), List.of(ReviewReason.MISSING_FROM_PROVIDER_REPORT),
                    List.of(reviewCase.getId()), "Recorded as settled on " + transaction.getSettledOn() + " but not reported."));
        }

        int mismatchCount = results.stream().mapToInt(result -> result.mismatches().size()).sum();
        int reconciledCount = (int) results.stream().filter(result -> result.outcome() == RowOutcome.RECONCILED).count();
        log.info("Reconciliation import checkedRows={} reconciled={} mismatches={} missing={}",
                rows.size(), reconciledCount, mismatchCount, missing.size());
        return new ReconciliationReportResponse(rows.size(), mismatchCount, reconciledCount, missing.size(), results);
    }

    private RowResult reconcileRow(int rowNumber, ReconciliationReportRequest.Row row,
                                   PaymentTransaction transaction, PaymentIntent intent, Caller caller) {
        String reference = row.providerTransactionId();
        List<ReviewReason> mismatches = new ArrayList<>();
        List<UUID> reviewCaseIds = new ArrayList<>();

        if (transaction.getAmount().compareTo(row.amount()) != 0) {
            mismatch(mismatches, reviewCaseIds, ReviewReason.AMOUNT_MISMATCH, reference, intent,
                    "Provider amount " + row.amount().toPlainString() + " does not match local amount "
                            + transaction.getAmount().toPlainString() + ".", caller);
        }
        if (!transaction.getCurrency().equals(row.currency())) {
            mismatch(mismatches, reviewCaseIds, ReviewReason.CURRENCY_MISMATCH, reference, intent,
                    "Provider currency " + row.currency() + " does not match local currency " + transaction.getCurrency() + ".", caller);
        }
        if (transaction.getProviderStatus() != row.providerStatus()) {
            mismatch(mismatches, reviewCaseIds, ReviewReason.STATUS_MISMATCH, reference, intent,
                    "Provider status " + row.providerStatus() + " does not match local status " + transaction.getProviderStatus() + ".", caller);
        }
        if (row.settlementDate() != null && !row.settlementDate().equals(transaction.getSettledOn())) {
            mismatch(mismatches, reviewCaseIds, ReviewReason.SETTLEMENT_DATE_MISMATCH, reference, intent,
                    "Provider settlement date " + row.settlementDate() + " does not match local settlement date "
                            + (transaction.getSettledOn() == null ? "(not settled locally)" : transaction.getSettledOn()) + ".", caller);
        }
        if (row.refundedAmount() != null && row.refundedAmount().compareTo(intent.getRefundedAmount()) != 0) {
            mismatch(mismatches, reviewCaseIds, ReviewReason.REFUND_AMOUNT_MISMATCH, reference, intent,
                    "Provider reports " + row.refundedAmount().toPlainString() + " refunded; we recorded "
                            + intent.getRefundedAmount().toPlainString() + ".", caller);
        }
        if (intent.getState() == PaymentState.REFUNDED && row.providerStatus() == ProviderStatus.SUCCESS
                && row.refundedAmount() == null) {
            ReviewCase reviewCase = openReview(ReviewReason.STATUS_MISMATCH, AggregateType.PAYMENT_INTENT,
                    intent.getId().toString(), intent.getId(),
                    "Payment is fully refunded locally but appears successful in the provider report.", caller);
            mismatches.add(ReviewReason.STATUS_MISMATCH);
            reviewCaseIds.add(reviewCase.getId());
        }

        if (!mismatches.isEmpty()) {
            return new RowResult(rowNumber, reference, RowOutcome.MISMATCH, intent.getId(), intent.getState(),
                    mismatches, reviewCaseIds, mismatches.size() + " discrepancy(ies) sent to manual review.");
        }

        return switch (intent.getState()) {
            case SETTLED -> {
                PaymentState previousState = intent.reconcile();
                auditService.recordStateChange(intent.getId(), previousState, intent.getState(), caller.name(),
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

    private void mismatch(List<ReviewReason> mismatches, List<UUID> reviewCaseIds, ReviewReason reason, String reference,
                          PaymentIntent intent, String details, Caller caller) {
        mismatches.add(reason);
        reviewCaseIds.add(openReview(reason, AggregateType.PROVIDER_TRANSACTION, reference, intent.getId(), details, caller).getId());
    }

    private ReviewCase openReview(ReviewReason reason, String referenceType, String referenceId, UUID paymentIntentId,
                                  String details, Caller caller) {
        ReviewCase reviewCase = reviewService.openCase(reason, referenceType, referenceId, paymentIntentId, details, caller.name());
        auditService.record(referenceType, referenceId, paymentIntentId, "RECONCILIATION_MISMATCH_FOUND",
                caller.name(), details, "reason=" + reason + ",reviewCaseId=" + reviewCase.getId());
        return reviewCase;
    }
}
