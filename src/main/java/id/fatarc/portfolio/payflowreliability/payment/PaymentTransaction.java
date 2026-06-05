package id.fatarc.portfolio.payflowreliability.payment;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "payment_transactions",
        indexes = {
                @Index(name = "idx_payment_tx_event", columnList = "providerEventId"),
                @Index(name = "idx_payment_tx_provider", columnList = "providerTransactionId"),
                @Index(name = "idx_payment_tx_merchant", columnList = "merchantId")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_tx_provider_event", columnNames = {"providerEventId"})
        }
)
public class PaymentTransaction {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID paymentIntentId;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String providerEventId;

    @Column(nullable = false)
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderStatus providerStatus;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean settled;

    private LocalDate settledOn;

    @Column(nullable = false)
    private Instant createdAt;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(UUID paymentIntentId, String merchantId, String providerEventId, String providerTransactionId,
                              ProviderStatus providerStatus, BigDecimal amount, String currency) {
        this.id = UUID.randomUUID();
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.providerEventId = providerEventId;
        this.providerTransactionId = providerTransactionId;
        this.providerStatus = providerStatus;
        this.amount = amount;
        this.currency = currency;
        this.settled = false;
        this.createdAt = Instant.now();
    }

    public void markSettled(LocalDate settledOn) {
        if (this.settled) {
            throw new IllegalStateException("Payment transaction is already settled.");
        }
        this.settled = true;
        this.settledOn = settledOn;
    }

    public UUID getId() { return id; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getMerchantId() { return merchantId; }
    public String getProviderEventId() { return providerEventId; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public ProviderStatus getProviderStatus() { return providerStatus; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public boolean isSettled() { return settled; }
    public LocalDate getSettledOn() { return settledOn; }
    public Instant getCreatedAt() { return createdAt; }
}
