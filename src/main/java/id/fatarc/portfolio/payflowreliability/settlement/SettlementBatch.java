package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One payout to a merchant in one currency. {@code totalAmount} is net: captures minus refunds and
 * reversals deducted in this batch.
 */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatch implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String merchantId;

    /** Number of items (captures, refunds, reversals) in the batch. */
    @Column(nullable = false)
    private int transactionCount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal captureAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal deductionAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    protected SettlementBatch() {
    }

    public SettlementBatch(String merchantId, int itemCount, BigDecimal captureAmount, BigDecimal deductionAmount,
                           String currency, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.transactionCount = itemCount;
        this.captureAmount = Money.normalize(captureAmount, currency);
        this.deductionAmount = Money.normalize(deductionAmount, currency);
        this.totalAmount = this.captureAmount.subtract(this.deductionAmount);
        this.currency = currency;
        this.createdAt = createdAt;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }

    @Override
    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public int getTransactionCount() { return transactionCount; }
    public BigDecimal getCaptureAmount() { return Money.atScale(captureAmount, currency); }
    public BigDecimal getDeductionAmount() { return Money.atScale(deductionAmount, currency); }
    public BigDecimal getTotalAmount() { return Money.atScale(totalAmount, currency); }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
