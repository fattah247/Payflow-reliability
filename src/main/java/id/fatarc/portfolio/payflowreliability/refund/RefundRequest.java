package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.common.Text;
import id.fatarc.portfolio.payflowreliability.idempotency.RequestFingerprint;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "refund_requests")
public class RefundRequest {
    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false)
    private UUID paymentIntentId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    /** The requester's Idempotency-Key, unique per payment. */
    private String idempotencyKey;

    /** Canonical hash of the creating request; a retry under the same key must match it. */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Column(nullable = false)
    private String requestedBy;

    private String providerRefundId;

    @Column(length = 500)
    private String failureReason;

    private Instant completedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RefundRequest() {
    }

    public RefundRequest(UUID paymentIntentId, BigDecimal amount, String currency, String reason, RefundStatus initialStatus,
                         String idempotencyKey, String requestedBy) {
        if (initialStatus != RefundStatus.REQUESTED && initialStatus != RefundStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("A refund starts as REQUESTED or PENDING_REVIEW, not " + initialStatus);
        }
        this.id = UUID.randomUUID();
        this.paymentIntentId = paymentIntentId;
        this.amount = Money.normalize(amount, currency);
        this.currency = currency;
        this.reason = reason;
        this.status = initialStatus;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = fingerprintOf(paymentIntentId, amount, reason);
        this.requestedBy = requestedBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void approve() {
        transition(RefundStatus.PENDING_REVIEW, RefundStatus.REQUESTED, "Only a refund pending review can be approved.");
    }

    public void reject() {
        transition(RefundStatus.PENDING_REVIEW, RefundStatus.REJECTED, "Only a refund pending review can be rejected.");
    }

    public void cancel() {
        if (status != RefundStatus.PENDING_REVIEW && status != RefundStatus.REQUESTED) {
            throw new InvalidStateTransitionException("Only a refund that has not reached the provider can be cancelled. Current status: " + status);
        }
        status = RefundStatus.CANCELLED;
        updatedAt = Instant.now();
    }

    public void complete(String providerRefundId, Instant now) {
        transition(RefundStatus.REQUESTED, RefundStatus.COMPLETED, "Only a requested refund can be completed.");
        this.providerRefundId = providerRefundId;
        this.completedAt = now;
    }

    public void fail(String failureReason) {
        transition(RefundStatus.REQUESTED, RefundStatus.FAILED, "Only a requested refund can fail.");
        this.failureReason = Text.fit(failureReason, 500);
    }

    /** The fingerprint of a refund request: which payment, how much, and why. */
    public static String fingerprintOf(UUID paymentIntentId, BigDecimal amount, String reason) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("paymentIntentId", paymentIntentId);
        fields.put("amount", amount);
        fields.put("reason", reason);
        return RequestFingerprint.of("refunds.create", fields);
    }

    /** Same request again (a client retry with the same Idempotency-Key). */
    public boolean matchesFingerprint(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    private void transition(RefundStatus from, RefundStatus to, String message) {
        if (status != from) {
            throw new InvalidStateTransitionException(message + " Current status: " + status);
        }
        status = to;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getRequestedBy() { return requestedBy; }
    public String getProviderRefundId() { return providerRefundId; }
    public String getFailureReason() { return failureReason; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
