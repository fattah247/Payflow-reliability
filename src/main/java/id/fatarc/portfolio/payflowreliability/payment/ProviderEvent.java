package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.common.Text;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Webhook inbox: every authenticated provider event, recorded once (unique provider event id)
 * together with the outcome it received. It is both the deduplication key and the evidence an
 * operator sees when investigating a payment.
 */
@Entity
@Table(name = "provider_events")
public class ProviderEvent {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String providerEventId;

    @Column(nullable = false)
    private String providerTransactionId;

    /** The payment the provider claims this event is for; it may not exist locally. */
    @Column(nullable = false)
    private UUID paymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventSource source;

    @Column(nullable = false, length = 1000)
    private String detail;

    private UUID reviewCaseId;

    @Column(nullable = false)
    private Instant receivedAt;

    protected ProviderEvent() {
    }

    public ProviderEvent(ProviderWebhookRequest webhook, EventSource source, WebhookOutcome outcome, String detail,
                         UUID reviewCaseId) {
        this.id = UUID.randomUUID();
        this.providerEventId = webhook.providerEventId();
        this.providerTransactionId = webhook.providerTransactionId();
        this.paymentIntentId = webhook.paymentIntentId();
        this.status = webhook.status();
        this.amount = Money.normalize(webhook.amount(), webhook.currency());
        this.currency = webhook.currency();
        this.outcome = outcome;
        this.source = source;
        this.detail = Text.fit(detail, 1000);
        this.reviewCaseId = reviewCaseId;
        this.receivedAt = Instant.now();
    }

    /** True when a redelivery carries exactly what was recorded the first time. */
    public boolean hasSamePayload(ProviderWebhookRequest webhook) {
        return providerTransactionId.equals(webhook.providerTransactionId())
                && paymentIntentId.equals(webhook.paymentIntentId())
                && status == webhook.status()
                && amount.compareTo(webhook.amount()) == 0
                && currency.equals(webhook.currency());
    }

    public UUID getId() { return id; }
    public String getProviderEventId() { return providerEventId; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public ProviderStatus getStatus() { return status; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCurrency() { return currency; }
    public WebhookOutcome getOutcome() { return outcome; }
    public EventSource getSource() { return source; }
    public String getDetail() { return detail; }
    public UUID getReviewCaseId() { return reviewCaseId; }
    public Instant getReceivedAt() { return receivedAt; }
}
