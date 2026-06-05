package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RefundService {
    private static final String PAYMENT_INTENT = "PAYMENT_INTENT";
    private static final String MERCHANT_API = "merchant-api";
    private static final String OPERATIONS = "operations";

    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final ReviewService reviewService;
    private final AuditService auditService;

    public RefundService(PaymentIntentRepository paymentIntentRepository,
                         RefundRequestRepository refundRequestRepository,
                         ReviewService reviewService,
                         AuditService auditService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.reviewService = reviewService;
        this.auditService = auditService;
    }

    @Transactional
    public RefundRequest requestRefund(UUID paymentIntentId, CreateRefundRequest request) {
        PaymentIntent intent = paymentIntentRepository.findById(paymentIntentId)
                .orElseThrow(() -> new BusinessException("Payment intent not found: " + paymentIntentId));

        if (request.amount().compareTo(intent.getAmount()) > 0) {
            throw new BusinessException("Refund amount cannot exceed original payment amount.");
        }

        if (intent.getState() == PaymentState.RECONCILED) {
            reviewService.openCase(
                    ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION,
                    PAYMENT_INTENT,
                    intent.getId().toString(),
                    "Refund request requires manual review because the payment is already reconciled.",
                    MERCHANT_API
            );
            auditService.record(PAYMENT_INTENT, intent.getId().toString(), "REFUND_BLOCKED",
                    MERCHANT_API, "Refund requires manual review after reconciliation.", "paymentIntentId=" + paymentIntentId);
            throw new BusinessException("Refund requires manual review after reconciliation.");
        }

        try {
            var previousState = intent.requestRefund();
            auditService.recordStateChange(PAYMENT_INTENT, intent.getId().toString(), previousState.name(),
                    intent.getState().name(), MERCHANT_API, request.reason(), "paymentIntentId=" + paymentIntentId);
        } catch (IllegalStateException ex) {
            throw new BusinessException(ex.getMessage());
        }

        RefundRequest refund = refundRequestRepository.save(new RefundRequest(paymentIntentId, request.amount(), request.reason()));
        auditService.record(PAYMENT_INTENT, intent.getId().toString(), "REFUND_REQUESTED",
                MERCHANT_API, "Refund requested.", "refundId=" + refund.getId());
        return refund;
    }

    @Transactional
    public RefundRequest completeRefund(UUID refundId) {
        RefundRequest refund = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException("Refund request not found: " + refundId));
        PaymentIntent intent = paymentIntentRepository.findById(refund.getPaymentIntentId())
                .orElseThrow(() -> new BusinessException("Payment intent not found: " + refund.getPaymentIntentId()));

        try {
            refund.complete();
            var previousState = intent.refund();
            auditService.recordStateChange(PAYMENT_INTENT, intent.getId().toString(), previousState.name(),
                    intent.getState().name(), OPERATIONS, "Refund completed for request " + refund.getId(),
                    "refundId=" + refund.getId());
        } catch (IllegalStateException ex) {
            throw new BusinessException(ex.getMessage());
        }
        return refund;
    }
}
