package id.fatarc.portfolio.payflowreliability.providersim;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The provider's own record of a payment. */
@Entity
@Table(name = "sim_payments")
public class SimPayment {
    public enum Status { AUTHORIZED, SUCCESS, FAILED, EXPIRED, VOIDED, REVERSED }

    @Id
    private UUID paymentIntentId;

    @Column(nullable = false)
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SimPayment() {
    }

    SimPayment(UUID paymentIntentId, String providerTransactionId, Status status, BigDecimal amount, String currency,
               Instant now) {
        this.paymentIntentId = paymentIntentId;
        this.providerTransactionId = providerTransactionId;
        this.status = status;
        this.amount = amount;
        this.currency = currency;
        this.refundedAmount = BigDecimal.ZERO;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void moveTo(Status status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    void addRefund(BigDecimal refund, Instant now) {
        this.refundedAmount = refundedAmount.add(refund);
        this.updatedAt = now;
    }

    BigDecimal refundable() {
        return status == Status.SUCCESS ? amount.subtract(refundedAmount) : BigDecimal.ZERO;
    }

    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public Status getStatus() { return status; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCurrency() { return currency; }
    public BigDecimal getRefundedAmount() { return Money.atScale(refundedAmount, currency); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
