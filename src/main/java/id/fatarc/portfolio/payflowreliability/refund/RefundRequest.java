package id.fatarc.portfolio.payflowreliability.refund;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refund_requests", indexes = {
        @Index(name = "idx_refund_payment_intent", columnList = "paymentIntentId")
})
public class RefundRequest {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID paymentIntentId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private boolean completed;

    @Column(nullable = false)
    private Instant createdAt;

    protected RefundRequest() {
    }

    public RefundRequest(UUID paymentIntentId, BigDecimal amount, String reason) {
        this.id = UUID.randomUUID();
        this.paymentIntentId = paymentIntentId;
        this.amount = amount;
        this.reason = reason;
        this.completed = false;
        this.createdAt = Instant.now();
    }

    public void complete() {
        if (completed) {
            throw new IllegalStateException("Refund request is already completed.");
        }
        this.completed = true;
    }

    public UUID getId() { return id; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public boolean isCompleted() { return completed; }
    public Instant getCreatedAt() { return createdAt; }
}
