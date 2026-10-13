package id.fatarc.portfolio.payflowreliability.merchant;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Only the SHA-256 hash of a key is stored; the prefix lets people recognise a key without revealing it. */
@Entity
@Table(name = "merchant_api_keys")
public class MerchantApiKey {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 16)
    private String keyPrefix;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    private String revokedBy;

    private Instant revokedAt;

    protected MerchantApiKey() {
    }

    public MerchantApiKey(String merchantId, String keyHash, String keyPrefix, String createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public void revoke(String revokedBy, Instant revokedAt) {
        if (this.revokedAt == null) {
            this.revokedBy = revokedBy;
            this.revokedAt = revokedAt;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getKeyHash() { return keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getRevokedBy() { return revokedBy; }
    public Instant getRevokedAt() { return revokedAt; }
}
