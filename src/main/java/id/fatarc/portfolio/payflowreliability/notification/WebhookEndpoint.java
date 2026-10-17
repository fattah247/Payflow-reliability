package id.fatarc.portfolio.payflowreliability.notification;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a shop wants its notifications, and the secrets they are signed with. After a rotation the previous secret
 * keeps signing (next to the new one) until {@code previousSecretUntil}, so the shop can switch without missing an
 * event. The secrets are stored as they are because signing needs them; production would keep them encrypted.
 */
@Entity
@Table(name = "merchant_webhook_endpoints")
public class WebhookEndpoint {
    @Id
    private String merchantId;

    @Version
    private Long version;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false)
    private String secret;

    private String previousSecret;

    private Instant previousSecretUntil;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected WebhookEndpoint() {
    }

    WebhookEndpoint(String merchantId, String url, String secret, Instant now) {
        this.merchantId = merchantId;
        this.url = url;
        this.secret = secret;
        this.enabled = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void moveTo(String newUrl, Instant now) {
        this.url = newUrl;
        this.enabled = true;
        this.updatedAt = now;
    }

    void rotate(String newSecret, Duration overlap, Instant now) {
        this.previousSecret = secret;
        this.previousSecretUntil = now.plus(overlap);
        this.secret = newSecret;
        this.updatedAt = now;
    }

    void disable(Instant now) {
        this.enabled = false;
        this.updatedAt = now;
    }

    /** The secrets a delivery is signed with right now: the current one, and the previous one during the overlap. */
    List<String> signingSecrets(Instant now) {
        List<String> secrets = new ArrayList<>(List.of(secret));
        if (previousSecret != null && previousSecretUntil != null && now.isBefore(previousSecretUntil)) {
            secrets.add(previousSecret);
        }
        return secrets;
    }

    public String getMerchantId() { return merchantId; }
    public String getUrl() { return url; }
    String getSecret() { return secret; }
    public Instant getPreviousSecretUntil() { return previousSecretUntil; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
