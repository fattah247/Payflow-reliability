package id.fatarc.portfolio.payflowreliability.providersim;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A refund or reversal the provider processed, keyed by the caller's idempotency key. */
@Entity
@Table(name = "sim_operations")
public class SimOperation {
    public enum Type { REFUND, REVERSAL }

    public enum Status { SUCCEEDED, FAILED }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type operationType;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false)
    private String providerTransactionId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(length = 300)
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    protected SimOperation() {
    }

    SimOperation(Type operationType, String idempotencyKey, String requestHash, String providerTransactionId,
                 BigDecimal amount, String currency, Status status, String failureReason, Instant now) {
        this.id = UUID.randomUUID();
        this.operationType = operationType;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.providerTransactionId = providerTransactionId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public Type getOperationType() { return operationType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
