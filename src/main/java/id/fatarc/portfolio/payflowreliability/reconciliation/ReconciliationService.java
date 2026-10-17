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
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.PayoutOutcome;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.PayoutResult;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.RowOutcome;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.RowResult;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.settlement.BrokenReason;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares a provider settlement report against local records, in both directions:
 * <ul>
 *   <li>every reported row gets a verdict; every discrepancy (amount, currency, status, date, refunded
 *       amount) becomes a review case. A clean match is one of two pieces of evidence: the payment moves
 *       SETTLED to RECONCILED only once its payout has also landed in the bank (otherwise it waits, and
 *       {@link PaymentReconciler} finishes the job when the credit arrives);</li>
 *   <li>payout rows are the provider's leg of the three-way match per batch: gross, fee and net must agree
 *       with the ledger and with what the provider said when it accepted the payout;</li>
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
    private final PaymentReconciler paymentReconciler;
    private final SettlementBatchRepository batchRepository;
    private final ProviderPayoutReportRepository payoutReportRepository;
    private final BreakService breakService;
    private final Clock clock;

    public ReconciliationService(PaymentTransactionRepository paymentTransactionRepository,
                                 PaymentIntentRepository paymentIntentRepository,
                                 ReviewService reviewService,
                                 AuditService auditService,
                                 PaymentReconciler paymentReconciler,
                                 SettlementBatchRepository batchRepository,
                                 ProviderPayoutReportRepository payoutReportRepository,
                                 BreakService breakService,
                                 Clock clock) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.paymentReconciler = paymentReconciler;
        this.batchRepository = batchRepository;
        this.payoutReportRepository = payoutReportRepository;
        this.breakService = breakService;
        this.clock = clock;
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

        List<PayoutResult> payoutResults = report.payouts() == null ? List.of() : importPayouts(report, caller);

        int mismatchCount = results.stream().mapToInt(result -> result.mismatches().size()).sum();
        int reconciledCount = (int) results.stream().filter(result -> result.outcome() == RowOutcome.RECONCILED).count();
        int awaitingBank = (int) results.stream().filter(result -> result.outcome() == RowOutcome.MATCHED_AWAITING_BANK).count();
        log.info("Reconciliation import checkedRows={} reconciled={} awaitingBank={} mismatches={} missing={} payouts={}",
                rows.size(), reconciledCount, awaitingBank, mismatchCount, missing.size(), payoutResults.size());
        return new ReconciliationReportResponse(rows.size(), mismatchCount, reconciledCount, awaitingBank, missing.size(),
                results, payoutResults);
    }

    /**
     * The provider's leg per batch. A row must name a payout we sent, its gross must be the batch total, its fee and
     * net must add up, and both must equal what the provider said when it accepted the payout. With a settlement
     * date, every payout we sent that day is expected in the report.
     */
    private List<PayoutResult> importPayouts(ReconciliationReportRequest report, Caller caller) {
        Instant now = clock.instant();
        List<PayoutResult> results = new ArrayList<>();
        Set<UUID> reported = new HashSet<>();
        for (ReconciliationReportRequest.PayoutRow row : report.payouts()) {
            Optional<SettlementBatch> found = batchRepository.findIdByPayoutReference(row.payoutReference())
                    .flatMap(batchRepository::findById);
            if (found.isEmpty()) {
                ReconciliationBreak opened = breakService.open(BreakCategory.UNKNOWN_PROVIDER_PAYOUT,
                        "provider-payout:" + row.providerPayoutId(), null, null, null, row.currency(), row.netAmount(),
                        BigDecimal.ZERO, "The provider reports payout " + row.providerPayoutId() + " with reference "
                                + row.payoutReference() + " (net " + row.netAmount().toPlainString() + " " + row.currency()
                                + "), which we never sent.", caller.name());
                results.add(new PayoutResult(row.payoutReference(), PayoutOutcome.UNKNOWN_REFERENCE, null,
                        opened.getCategory(), opened.getId(), "No payout with this reference."));
                continue;
            }
            SettlementBatch batch = found.get();
            reported.add(batch.getId());
            List<String> problems = payoutProblems(batch, row);
            Optional<ProviderPayoutReport> earlier = payoutReportRepository.findBySettlementBatchId(batch.getId());
            if (earlier.isPresent() && earlier.get().sameAs(row.providerPayoutId(), row.grossAmount(), row.feeAmount(), row.netAmount())) {
                results.add(new PayoutResult(row.payoutReference(), PayoutOutcome.ALREADY_REPORTED, batch.getId(), null, null,
                        "Reported before with the same values."));
                continue;
            }
            if (earlier.isPresent()) {
                problems.add("an earlier report said gross " + earlier.get().getGrossAmount().toPlainString() + ", fee "
                        + earlier.get().getFeeAmount().toPlainString() + ", net " + earlier.get().getNetAmount().toPlainString());
            } else {
                payoutReportRepository.save(new ProviderPayoutReport(batch.getId(),
                        report.settlementDate() == null ? LocalDate.ofInstant(now, ZoneId.systemDefault()) : report.settlementDate(),
                        row.providerPayoutId(), row.grossAmount(), row.feeAmount(), row.netAmount(), row.currency(),
                        row.valueDate(), problems.isEmpty(), caller.name(), now));
            }
            if (problems.isEmpty()) {
                results.add(new PayoutResult(row.payoutReference(), PayoutOutcome.CONSISTENT, batch.getId(), null, null,
                        "Gross " + row.grossAmount().toPlainString() + " - fee " + row.feeAmount().toPlainString() + " = net "
                                + row.netAmount().toPlainString() + " " + row.currency() + ", as the ledger and the provider's answer say."));
                continue;
            }
            String detail = "Provider report for payout " + batch.getPayoutReference() + ": " + String.join("; ", problems) + ".";
            ReconciliationBreak opened = breakService.open(BreakCategory.PROVIDER_PAYOUT_MISMATCH,
                    BreakService.batchSubject(batch.getId()), batch.getId(), null, null, batch.getCurrency(), row.grossAmount(),
                    BigDecimal.ZERO, detail, caller.name());
            results.add(new PayoutResult(row.payoutReference(), PayoutOutcome.MISMATCH, batch.getId(), opened.getCategory(),
                    opened.getId(), detail));
        }
        if (report.settlementDate() != null) {
            // Only the batches created that day (in the business time zone), and only the report's merchant if it names one.
            Instant dayStart = report.settlementDate().atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant nextDay = report.settlementDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            List<SettlementBatch> sentThatDay = report.merchantId() == null
                    ? batchRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(dayStart, nextDay)
                    : batchRepository.findByMerchantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                            report.merchantId(), dayStart, nextDay);
            for (SettlementBatch batch : sentThatDay) {
                boolean expected = batch.isBankTracked() && batch.getBrokenReason() != BrokenReason.PROVIDER_REFUSED;
                if (!expected || reported.contains(batch.getId())) {
                    continue;
                }
                ReconciliationBreak opened = breakService.open(BreakCategory.MISSING_FROM_PROVIDER_REPORT,
                        BreakService.batchSubject(batch.getId()), batch.getId(), null, null, batch.getCurrency(),
                        batch.getTotalAmount(), BigDecimal.ZERO, "We sent payout " + batch.getPayoutReference() + " of "
                                + batch.getTotalAmount().toPlainString() + " " + batch.getCurrency() + " on "
                                + report.settlementDate() + ", but the provider's report for that day does not list it.", caller.name());
                results.add(new PayoutResult(batch.getPayoutReference(), PayoutOutcome.MISSING_FROM_REPORT, batch.getId(),
                        opened.getCategory(), opened.getId(), "Sent on " + report.settlementDate() + " but not reported."));
            }
        }
        return results;
    }

    private static List<String> payoutProblems(SettlementBatch batch, ReconciliationReportRequest.PayoutRow row) {
        List<String> problems = new ArrayList<>();
        if (!batch.getCurrency().equals(row.currency())) {
            problems.add("currency " + row.currency() + ", ledger " + batch.getCurrency());
        }
        if (row.grossAmount().compareTo(batch.getTotalAmount()) != 0) {
            problems.add("gross " + row.grossAmount().toPlainString() + ", ledger batch total " + batch.getTotalAmount().toPlainString());
        }
        if (row.grossAmount().subtract(row.feeAmount()).compareTo(row.netAmount()) != 0) {
            problems.add("gross " + row.grossAmount().toPlainString() + " minus fee " + row.feeAmount().toPlainString()
                    + " is not net " + row.netAmount().toPlainString());
        }
        if (batch.getProviderPayoutId() != null && !batch.getProviderPayoutId().equals(row.providerPayoutId())) {
            problems.add("payout id " + row.providerPayoutId() + ", the provider answered " + batch.getProviderPayoutId());
        }
        if (batch.getProviderFeeAmount() != null && batch.getProviderFeeAmount().compareTo(row.feeAmount()) != 0) {
            problems.add("fee " + row.feeAmount().toPlainString() + ", the provider answered " + batch.getProviderFeeAmount().toPlainString());
        }
        if (batch.getExpectedNetAmount() != null && batch.getExpectedNetAmount().compareTo(row.netAmount()) != 0) {
            problems.add("net " + row.netAmount().toPlainString() + ", the provider answered " + batch.getExpectedNetAmount().toPlainString());
        }
        return problems;
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
                intent.markProviderReportMatched(clock.instant());
                Optional<SettlementBatch> landed = paymentReconciler.landedBatchOf(intent.getId());
                if (landed.isEmpty()) {
                    yield new RowResult(rowNumber, reference, RowOutcome.MATCHED_AWAITING_BANK, intent.getId(), intent.getState(),
                            List.of(), List.of(), "Matched; reconciled as soon as its payout lands in the bank.");
                }
                PaymentState previousState = intent.reconcile();
                auditService.recordStateChange(intent.getId(), previousState, intent.getState(), caller.name(),
                        "Three-way match: the settlement row matched and payout " + landed.get().getPayoutReference()
                                + " landed in the bank.", "providerTransactionId=" + reference + ",batchId=" + landed.get().getId());
                yield new RowResult(rowNumber, reference, RowOutcome.RECONCILED, intent.getId(), intent.getState(),
                        List.of(), List.of(), "Matched, and its payout landed in the bank; payment reconciled.");
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
