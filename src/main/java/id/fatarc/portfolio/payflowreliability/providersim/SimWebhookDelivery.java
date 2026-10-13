package id.fatarc.portfolio.payflowreliability.providersim;

import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** The provider's own outbox of webhooks: delivered with retries, or dropped to simulate a lost webhook. */
@Entity
@Table(name = "sim_webhook_deliveries")
public class SimWebhookDelivery {
    public enum Status { PENDING, DELIVERED, DROPPED, FAILED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private UUID paymentIntentId;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Integer lastResponseStatus;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SimWebhookDelivery() {
    }

    SimWebhookDelivery(String eventId, UUID paymentIntentId, String payload, boolean deliver, Instant now) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.paymentIntentId = paymentIntentId;
        this.payload = payload;
        this.status = deliver ? Status.PENDING : Status.DROPPED;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void delivered(int responseStatus, Instant now) {
        attempts++;
        status = Status.DELIVERED;
        lastResponseStatus = responseStatus;
        lastError = null;
        updatedAt = now;
    }

    void retry(Integer responseStatus, String error, Instant nextAttemptAt, Instant now) {
        attempts++;
        lastResponseStatus = responseStatus;
        lastError = Text.fit(error, 500);
        this.nextAttemptAt = nextAttemptAt;
        updatedAt = now;
    }

    void failed(Integer responseStatus, String error, Instant now) {
        attempts++;
        status = Status.FAILED;
        lastResponseStatus = responseStatus;
        lastError = Text.fit(error, 500);
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getEventId() { return eventId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Integer getLastResponseStatus() { return lastResponseStatus; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
