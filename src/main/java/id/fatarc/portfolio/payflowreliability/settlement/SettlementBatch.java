package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One payout to a merchant in one currency. {@code totalAmount} is net of refunds and reversals: captures
 * minus refunds and reversals deducted in this batch. It is the gross of the payout the provider sends; the
 * provider's fee and any bank charges come off it on the way to the bank.
 *
 * <p>The payout fields follow {@link PayoutStatus}: what the provider said when it accepted the payout
 * (payout id, fee, net, expected value date) and what the bank showed (amount received, charges, value date,
 * the matching line).
 */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatch implements Persistable<UUID> {
    @Id
    private UUID id;

    @Version
    private Long version;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayoutStatus payoutStatus;

    /** What the provider puts in the bank transfer (ISO 20022 end-to-end id, at most 35 characters). */
    @Column(length = 35)
    private String payoutReference;

    @Enumerated(EnumType.STRING)
    private BrokenReason brokenReason;

    private String providerPayoutId;

    @Column(precision = 19, scale = 4)
    private BigDecimal providerFeeAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal expectedNetAmount;

    private LocalDate expectedValueDate;

    @Column(precision = 19, scale = 4)
    private BigDecimal landedAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal bankChargesAmount;

    private LocalDate landedValueDate;

    private UUID bankLineId;

    @Column(precision = 19, scale = 4)
    private BigDecimal returnedAmount;

    private Instant inTransitAt;

    private Instant landedAt;

    private Instant brokenAt;

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
        this.payoutStatus = PayoutStatus.SENT;
        this.payoutReference = referenceFor(id);
    }

    /** "PFB" and the batch id without dashes: 35 characters, the ISO 20022 limit for an end-to-end id. */
    public static String referenceFor(UUID batchId) {
        return "PFB" + batchId.toString().replace("-", "").toUpperCase();
    }

    /** The provider accepted the payout and told us what to expect in the bank. */
    public void markInTransit(String providerPayoutId, BigDecimal fee, BigDecimal net, LocalDate expectedValueDate,
                              Instant now) {
        if (payoutStatus != PayoutStatus.SENT) {
            throw new IllegalStateException("Batch " + id + " is " + payoutStatus + "; only a SENT payout can go in transit.");
        }
        this.payoutStatus = PayoutStatus.IN_TRANSIT;
        this.providerPayoutId = providerPayoutId;
        this.providerFeeAmount = Money.normalize(fee, currency);
        this.expectedNetAmount = Money.normalize(net, currency);
        this.expectedValueDate = expectedValueDate;
        this.inTransitAt = now;
    }

    /** A bank credit matched by reference, amount and value-date window. A late credit may land a broken batch. */
    public void markLanded(UUID bankLineId, BigDecimal received, BigDecimal bankCharges, LocalDate valueDate, Instant now) {
        boolean lateAfterMissing = payoutStatus == PayoutStatus.BROKEN && brokenReason == BrokenReason.MISSING_CREDIT;
        if (payoutStatus != PayoutStatus.IN_TRANSIT && !lateAfterMissing) {
            throw new IllegalStateException("Batch " + id + " is " + payoutStatus + "; only a payout in transit can land.");
        }
        this.payoutStatus = PayoutStatus.LANDED;
        this.brokenReason = null;
        this.brokenAt = null;
        this.bankLineId = bankLineId;
        this.landedAmount = Money.normalize(received, currency);
        this.bankChargesAmount = Money.normalize(bankCharges, currency);
        this.landedValueDate = valueDate;
        this.landedAt = now;
    }

    public void markBroken(BrokenReason reason, Instant now) {
        this.payoutStatus = PayoutStatus.BROKEN;
        this.brokenReason = reason;
        this.brokenAt = now;
    }

    public void markReturned(BigDecimal amount, Instant now) {
        if (payoutStatus != PayoutStatus.LANDED) {
            throw new IllegalStateException("Batch " + id + " is " + payoutStatus + "; only a landed payout can be returned.");
        }
        this.returnedAmount = Money.normalize(amount, currency);
        markBroken(BrokenReason.RETURNED, now);
    }

    /** Whether the provider has accepted the payout but no credit has landed (money in payouts_in_transit). */
    public boolean isInTransit() {
        return inTransitAt != null && landedAt == null;
    }

    public boolean isBankTracked() {
        return payoutStatus != PayoutStatus.LEGACY;
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
    public Long getVersion() { return version; }
    public PayoutStatus getPayoutStatus() { return payoutStatus; }
    public String getPayoutReference() { return payoutReference; }
    public BrokenReason getBrokenReason() { return brokenReason; }
    public String getProviderPayoutId() { return providerPayoutId; }
    public BigDecimal getProviderFeeAmount() { return Money.atScale(providerFeeAmount, currency); }
    public BigDecimal getExpectedNetAmount() { return Money.atScale(expectedNetAmount, currency); }
    public LocalDate getExpectedValueDate() { return expectedValueDate; }
    public BigDecimal getLandedAmount() { return Money.atScale(landedAmount, currency); }
    public BigDecimal getBankChargesAmount() { return Money.atScale(bankChargesAmount, currency); }
    public LocalDate getLandedValueDate() { return landedValueDate; }
    public UUID getBankLineId() { return bankLineId; }
    public BigDecimal getReturnedAmount() { return Money.atScale(returnedAmount, currency); }
    public Instant getInTransitAt() { return inTransitAt; }
    public Instant getLandedAt() { return landedAt; }
    public Instant getBrokenAt() { return brokenAt; }
}
