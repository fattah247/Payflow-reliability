package id.fatarc.portfolio.payflowreliability.settlement;

import com.fasterxml.jackson.annotation.JsonIgnore;
import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a payout. Unique constraints guarantee each capture, refund and reversal is settled at
 * most once, whatever happens above them.
 */
@Entity
@Table(name = "settlement_batch_items")
public class SettlementBatchItem implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementItemType itemType;

    @Column(nullable = false)
    private UUID paymentIntentId;

    private UUID transactionId;

    private UUID refundId;

    private UUID reversalPaymentIntentId;

    /** Set on capture items only; unique, so a payment's capture is paid out at most once. */
    private UUID capturePaymentIntentId;

    /** Signed: captures positive, deductions negative. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    protected SettlementBatchItem() {
    }

    private SettlementBatchItem(UUID batchId, SettlementItemType itemType, UUID paymentIntentId, UUID transactionId,
                                UUID refundId, UUID reversalPaymentIntentId, BigDecimal amount, String currency,
                                Instant createdAt) {
        this.id = UUID.randomUUID();
        this.batchId = batchId;
        this.itemType = itemType;
        this.paymentIntentId = paymentIntentId;
        this.transactionId = transactionId;
        this.refundId = refundId;
        this.reversalPaymentIntentId = reversalPaymentIntentId;
        this.capturePaymentIntentId = itemType == SettlementItemType.CAPTURE ? paymentIntentId : null;
        this.amount = Money.normalize(amount, currency);
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static SettlementBatchItem capture(UUID batchId, UUID paymentIntentId, UUID transactionId, BigDecimal amount,
                                              String currency, Instant now) {
        return new SettlementBatchItem(batchId, SettlementItemType.CAPTURE, paymentIntentId, transactionId, null, null,
                amount, currency, now);
    }

    public static SettlementBatchItem refund(UUID batchId, UUID paymentIntentId, UUID refundId, BigDecimal amount,
                                             String currency, Instant now) {
        return new SettlementBatchItem(batchId, SettlementItemType.REFUND, paymentIntentId, null, refundId, null,
                amount.negate(), currency, now);
    }

    public static SettlementBatchItem reversal(UUID batchId, UUID paymentIntentId, BigDecimal amount, String currency,
                                               Instant now) {
        return new SettlementBatchItem(batchId, SettlementItemType.REVERSAL, paymentIntentId, null, null, paymentIntentId,
                amount.negate(), currency, now);
    }

    @JsonIgnore

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
    public UUID getBatchId() { return batchId; }
    public SettlementItemType getItemType() { return itemType; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getRefundId() { return refundId; }
    public UUID getReversalPaymentIntentId() { return reversalPaymentIntentId; }
    public UUID getCapturePaymentIntentId() { return capturePaymentIntentId; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
