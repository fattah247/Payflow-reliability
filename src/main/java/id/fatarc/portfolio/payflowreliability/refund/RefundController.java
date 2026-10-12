package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/refunds")
public class RefundController {
    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * 201 refund requested; 202 accepted but waiting for manual review (reconciled payment);
     * 200 with {@code Idempotent-Replayed: true} when the payment already had this refund.
     */
    @PostMapping("/payment-intents/{paymentIntentId}")
    public ResponseEntity<RefundRequest> requestRefund(@PathVariable UUID paymentIntentId,
                                                       @Valid @RequestBody CreateRefundRequest request) {
        RefundSubmission submission = refundService.requestRefund(paymentIntentId, request);
        RefundRequest refund = submission.refund();
        if (submission.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(refund);
        }
        HttpStatus status = refund.getStatus() == RefundStatus.PENDING_REVIEW ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).location(URI.create("/api/refunds/" + refund.getId())).body(refund);
    }

    @GetMapping("/payment-intents/{paymentIntentId}")
    public List<RefundRequest> listForPayment(@PathVariable UUID paymentIntentId) {
        return refundService.refundsForPayment(paymentIntentId);
    }

    @GetMapping("/{refundId}")
    public RefundRequest get(@PathVariable UUID refundId) {
        return refundService.getRequired(refundId);
    }

    @PostMapping("/{refundId}/complete")
    public RefundRequest completeRefund(@PathVariable UUID refundId) {
        return refundService.completeRefund(refundId);
    }
}
