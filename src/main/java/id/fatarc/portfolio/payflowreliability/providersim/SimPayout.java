package id.fatarc.portfolio.payflowreliability.providersim;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A payout the provider executed: it deducted its fee from the gross and sent the net to the platform's
 * settlement account, with the platform's reference on the transfer.
 */
@Entity
@Table(name = "sim_payouts")
public class SimPayout {
    public enum Status { SUCCEEDED, FAILED }

    @Id
    private String id;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false, length = 35)
    private String reference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal feeAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private LocalDate payoutDate;

    @Column(nullable = false)
    private LocalDate valueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(length = 300)
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sim_payout_items", joinColumns = @JoinColumn(name = "payout_id"))
    @Column(name = "provider_transaction_id", nullable = false)
    private Set<String> providerTransactionIds = new LinkedHashSet<>();

    @Transient
    private boolean replayed;

    protected SimPayout() {
    }

    SimPayout(String id, String idempotencyKey, String requestHash, String merchantId, String reference, BigDecimal gross,
              BigDecimal fee, String currency, LocalDate payoutDate, LocalDate valueDate, Status status,
              String failureReason, Set<String> providerTransactionIds, Instant now) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.merchantId = merchantId;
        this.reference = reference;
        this.grossAmount = gross;
        this.feeAmount = fee;
        this.netAmount = gross.subtract(fee);
        this.currency = currency;
        this.payoutDate = payoutDate;
        this.valueDate = valueDate;
        this.status = status;
        this.failureReason = failureReason;
        this.providerTransactionIds.addAll(providerTransactionIds);
        this.createdAt = now;
    }

    SimPayout asReplay() {
        replayed = true;
        return this;
    }

    void forgetKey() {
        idempotencyKey = idempotencyKey + "~forgotten~" + id;
    }

    public boolean wasReplayed() { return replayed; }
    public String getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getMerchantId() { return merchantId; }
    public String getReference() { return reference; }
    public BigDecimal getGrossAmount() { return Money.atScale(grossAmount, currency); }
    public BigDecimal getFeeAmount() { return Money.atScale(feeAmount, currency); }
    public BigDecimal getNetAmount() { return Money.atScale(netAmount, currency); }
    public String getCurrency() { return currency; }
    public LocalDate getPayoutDate() { return payoutDate; }
    public LocalDate getValueDate() { return valueDate; }
    public Status getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<String> getProviderTransactionIds() { return providerTransactionIds; }
}
