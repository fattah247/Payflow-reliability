package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransactionRepository;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class ReconciliationService {
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
        int mismatchCount = 0;
        Set<String> seenReferences = new HashSet<>();

        for (ReconciliationReportRequest.Row row : report.rows()) {
            int rowMismatchCount = 0;

            if (!seenReferences.add(row.providerTransactionId())) {
                mismatchCount++;
                createReview(ReviewReason.DUPLICATE_PROVIDER_REFERENCE, "PROVIDER_TRANSACTION", row.providerTransactionId(),
                        "Provider report contains the same provider reference more than once.");
                continue;
            }

            Optional<PaymentTransaction> transactionLookup = paymentTransactionRepository
                    .findByProviderTransactionId(row.providerTransactionId());
            if (transactionLookup.isEmpty()) {
                mismatchCount++;
                createReview(ReviewReason.PROVIDER_TRANSACTION_NOT_FOUND, "PROVIDER_TRANSACTION", row.providerTransactionId(),
                        "Provider report contains a transaction not found locally.");
                continue;
            }
            PaymentTransaction transaction = transactionLookup.orElseThrow();

            if (transaction.getAmount().compareTo(row.amount()) != 0) {
                mismatchCount++;
                rowMismatchCount++;
                createReview(ReviewReason.AMOUNT_MISMATCH, "PROVIDER_TRANSACTION", row.providerTransactionId(),
                        "Provider amount does not match local transaction amount.");
            }

            if (!transaction.getCurrency().equals(row.currency())) {
                mismatchCount++;
                rowMismatchCount++;
                createReview(ReviewReason.CURRENCY_MISMATCH, "PROVIDER_TRANSACTION", row.providerTransactionId(),
                        "Provider currency does not match local transaction currency.");
            }

            if (transaction.getProviderStatus() != row.providerStatus()) {
                mismatchCount++;
                rowMismatchCount++;
                createReview(ReviewReason.STATUS_MISMATCH, "PROVIDER_TRANSACTION", row.providerTransactionId(),
                        "Provider status does not match local transaction status.");
            }

            if (row.settlementDate() != null && !row.settlementDate().equals(transaction.getSettledOn())) {
                mismatchCount++;
                rowMismatchCount++;
                createReview(ReviewReason.SETTLEMENT_DATE_MISMATCH, "PROVIDER_TRANSACTION", row.providerTransactionId(),
                        "Provider settlement date does not match local settlement date.");
            }

            Optional<PaymentIntent> intentLookup = paymentIntentRepository.findById(transaction.getPaymentIntentId());
            if (intentLookup.isEmpty()) {
                mismatchCount++;
                rowMismatchCount++;
                createReview(ReviewReason.PAYMENT_INTENT_NOT_FOUND, "PROVIDER_TRANSACTION", row.providerTransactionId(),
                        "Local transaction points to a payment intent that does not exist.");
                continue;
            }

            PaymentIntent intent = intentLookup.orElseThrow();
            if (isRefundedIntentReportedAsSuccessful(intent, row.providerStatus())) {
                mismatchCount++;
                rowMismatchCount++;
                createReview(ReviewReason.STATUS_MISMATCH, "PAYMENT_INTENT", intent.getId().toString(),
                        "Refunded payment still appears successful in the provider report.");
            }

            if (rowMismatchCount == 0) {
                if (intent.getState() != PaymentState.SETTLED) {
                    continue;
                }
                PaymentState previousState = intent.reconcile();
                auditService.recordStateChange("PAYMENT_INTENT", intent.getId().toString(), previousState.name(),
                        intent.getState().name(), RECONCILIATION_ACTOR,
                        "Settlement row matched the local transaction.",
                        "providerTransactionId=" + row.providerTransactionId());
            }
        }

        return new ReconciliationReportResponse(report.rows().size(), mismatchCount);
    }

    private void createReview(ReviewReason reason, String referenceType, String referenceId, String details) {
        reviewService.openCase(reason, referenceType, referenceId, details, RECONCILIATION_ACTOR);
        auditService.record(referenceType, referenceId, "RECONCILIATION_MISMATCH_FOUND",
                RECONCILIATION_ACTOR, details, "reason=" + reason);
    }

    private boolean isRefundedIntentReportedAsSuccessful(PaymentIntent intent, ProviderStatus providerStatus) {
        return (intent.getState() == PaymentState.REFUND_REQUESTED || intent.getState() == PaymentState.REFUNDED)
                && providerStatus == ProviderStatus.SUCCESS;
    }
}
