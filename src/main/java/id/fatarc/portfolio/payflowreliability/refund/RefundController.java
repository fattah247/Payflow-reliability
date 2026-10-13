package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.security.Caller;
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
     * Requires {@code Idempotency-Key}. 201 refund accepted and queued for the provider; 202 accepted but
     * waiting for manual review (reconciled payment); 200 with {@code Idempotent-Replayed: true} for a retry.
     * Completion is asynchronous: poll the refund, or read the payment's case file.
     */
    @PostMapping("/payment-intents/{paymentIntentId}")
    public ResponseEntity<RefundRequest> requestRefund(
            @PathVariable UUID paymentIntentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request,
            Caller caller) {
        RefundSubmission submission = refundService.requestRefund(paymentIntentId, request, idempotencyKey, caller);
        RefundRequest refund = submission.refund();
        if (submission.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(refund);
        }
        HttpStatus status = refund.getStatus() == RefundStatus.PENDING_REVIEW ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).location(URI.create("/api/refunds/" + refund.getId())).body(refund);
    }

    @GetMapping("/payment-intents/{paymentIntentId}")
    public List<RefundRequest> listForPayment(@PathVariable UUID paymentIntentId, Caller caller) {
        return refundService.refundsForPayment(paymentIntentId, caller);
    }

    @GetMapping("/{refundId}")
    public RefundRequest get(@PathVariable UUID refundId, Caller caller) {
        return refundService.getAccessible(refundId, caller);
    }

    /** Withdraws a refund that has not been sent to the provider yet. */
    @PostMapping("/{refundId}/cancel")
    public RefundRequest cancel(@PathVariable UUID refundId, Caller caller) {
        return refundService.cancel(refundId, caller);
    }
}
