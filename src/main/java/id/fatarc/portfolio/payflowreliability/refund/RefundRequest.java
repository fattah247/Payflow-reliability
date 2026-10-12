package id.fatarc.portfolio.payflowreliability.refund;

import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RefundRequest() {
    }

    public RefundRequest(UUID paymentIntentId, BigDecimal amount, String currency, String reason, RefundStatus initialStatus) {
        if (initialStatus != RefundStatus.REQUESTED && initialStatus != RefundStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("A refund starts as REQUESTED or PENDING_REVIEW, not " + initialStatus);
        }
        this.id = UUID.randomUUID();
        this.paymentIntentId = paymentIntentId;
        this.amount = Money.normalize(amount);
        this.currency = currency;
        this.reason = reason;
        this.status = initialStatus;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void approve() {
        transition(RefundStatus.PENDING_REVIEW, RefundStatus.REQUESTED, "Only a refund pending review can be approved.");
    }

    public void reject() {
        transition(RefundStatus.PENDING_REVIEW, RefundStatus.REJECTED, "Only a refund pending review can be rejected.");
    }

    public void complete() {
        transition(RefundStatus.REQUESTED, RefundStatus.COMPLETED, "Only a requested refund can be completed.");
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
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    /** Kept for clients written against the earlier boolean field. */
    public boolean isCompleted() { return status == RefundStatus.COMPLETED; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
