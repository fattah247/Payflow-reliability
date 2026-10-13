package id.fatarc.portfolio.payflowreliability.merchant;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "merchants")
public class Merchant {
    @Id
    private String merchantId;

    @Column(nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantStatus status;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    protected Merchant() {
    }

    public Merchant(String merchantId, String displayName, String createdBy, Instant createdAt) {
        this.merchantId = merchantId;
        this.displayName = displayName;
        this.status = MerchantStatus.ACTIVE;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public boolean isActive() {
        return status == MerchantStatus.ACTIVE;
    }

    public String getMerchantId() { return merchantId; }
    public String getDisplayName() { return displayName; }
    public MerchantStatus getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
