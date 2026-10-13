package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The provider-side transaction behind a payment: one row per provider transaction id,
 * whose status follows the provider's lifecycle (for example AUTHORIZED, then SUCCESS).
 * Individual webhook deliveries are recorded separately as {@link ProviderEvent}s.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {
    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false)
    private UUID paymentIntentId;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderStatus providerStatus;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean settled;

    private LocalDate settledOn;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(UUID paymentIntentId, String merchantId, String providerTransactionId,
                              ProviderStatus providerStatus, BigDecimal amount, String currency) {
        this.id = UUID.randomUUID();
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.providerTransactionId = providerTransactionId;
        this.providerStatus = providerStatus;
        this.amount = Money.normalize(amount, currency);
        this.currency = currency;
        this.settled = false;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void recordProviderStatus(ProviderStatus providerStatus) {
        this.providerStatus = providerStatus;
        this.updatedAt = Instant.now();
    }

    public void markSettled(LocalDate settledOn) {
        if (this.settled) {
            throw new InvalidStateTransitionException("Payment transaction " + providerTransactionId + " is already settled.");
        }
        this.settled = true;
        this.settledOn = settledOn;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getMerchantId() { return merchantId; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public ProviderStatus getProviderStatus() { return providerStatus; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCurrency() { return currency; }
    public boolean isSettled() { return settled; }
    public LocalDate getSettledOn() { return settledOn; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
