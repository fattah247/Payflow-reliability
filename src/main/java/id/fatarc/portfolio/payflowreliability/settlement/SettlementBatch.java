package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_batches")
public class SettlementBatch {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private int transactionCount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    protected SettlementBatch() {
    }

    public SettlementBatch(String merchantId, int transactionCount, BigDecimal totalAmount, String currency) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.transactionCount = transactionCount;
        this.totalAmount = Money.normalize(totalAmount);
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public int getTransactionCount() { return transactionCount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
