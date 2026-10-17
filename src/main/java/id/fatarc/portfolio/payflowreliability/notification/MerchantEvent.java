package id.fatarc.portfolio.payflowreliability.notification;

import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Something a shop should hear about: a payment or refund changed. Written in the same transaction as the change, so a
 * change never happens without its event and an event never announces a change that rolled back. Numbered per payment
 * ({@code sequence}), so a shop can ignore an event older than one it already processed.
 */
@Entity
@Table(name = "merchant_events")
public class MerchantEvent {
    public static final String PAYMENT_CREATED = "payment.created";
    public static final String PAYMENT_UPDATED = "payment.updated";
    public static final String REFUND_CREATED = "refund.created";
    public static final String REFUND_UPDATED = "refund.updated";

    public enum Status {
        /** Waiting for its first or next delivery attempt. */
        PENDING,
        /** The shop's endpoint answered 2xx. */
        DELIVERED,
        /** Every attempt failed; it stays readable through the events API and can be redelivered. */
        FAILED,
        /** The shop had no endpoint when it happened; it is only readable through the events API. */
        SKIPPED
    }

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private UUID paymentIntentId;

    private UUID refundId;

    @Column(nullable = false)
    private long sequence;

    /** The payment's state (payment events) or the refund's status (refund events) this event announces. */
    @Column(nullable = false)
    private String state;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    private Instant nextAttemptAt;

    private Instant leaseUntil;

    private Integer lastStatusCode;

    @Column(length = 1000)
    private String lastError;

    private Instant deliveredAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected MerchantEvent() {
    }

    MerchantEvent(UUID id, String merchantId, String eventType, UUID paymentIntentId, UUID refundId, long sequence, String state,
                  String payload, boolean deliverable, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.eventType = eventType;
        this.paymentIntentId = paymentIntentId;
        this.refundId = refundId;
        this.sequence = sequence;
        this.state = state;
        this.payload = payload;
        this.status = deliverable ? Status.PENDING : Status.SKIPPED;
        this.nextAttemptAt = deliverable ? now : null;
        this.createdAt = now;
    }

    /** Takes this attempt for {@code lease}, if it is due and nobody else holds it. */
    boolean claim(Instant now, Duration lease) {
        if (status != Status.PENDING || nextAttemptAt == null || nextAttemptAt.isAfter(now)
                || (leaseUntil != null && leaseUntil.isAfter(now))) {
            return false;
        }
        this.leaseUntil = now.plus(lease);
        return true;
    }

    void delivered(int statusCode, Instant now) {
        this.attempts++;
        this.status = Status.DELIVERED;
        this.lastStatusCode = statusCode;
        this.lastError = null;
        this.deliveredAt = now;
        this.nextAttemptAt = null;
        this.leaseUntil = null;
    }

    void retry(Integer statusCode, String error, Instant next) {
        this.attempts++;
        this.lastStatusCode = statusCode;
        this.lastError = Text.fit(error, 1000);
        this.nextAttemptAt = next;
        this.leaseUntil = null;
    }

    void giveUp(Integer statusCode, String error) {
        this.attempts++;
        this.status = Status.FAILED;
        this.lastStatusCode = statusCode;
        this.lastError = Text.fit(error, 1000);
        this.nextAttemptAt = null;
        this.leaseUntil = null;
    }

    /** No endpoint to send to (removed or disabled since the event was written). */
    void skip(String reason) {
        this.status = Status.SKIPPED;
        this.lastError = Text.fit(reason, 1000);
        this.nextAttemptAt = null;
        this.leaseUntil = null;
    }

    /**
     * Sends it again from the start: the shop fixed its endpoint, or wants a copy. One still waiting for a retry is sent
     * at the next run instead of after its backoff (unless an attempt is in flight).
     */
    void redeliver(Instant now) {
        if (status == Status.PENDING) {
            if (leaseUntil == null || !leaseUntil.isAfter(now)) {
                this.nextAttemptAt = now;
            }
            return;
        }
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.leaseUntil = null;
        this.lastError = null;
        this.lastStatusCode = null;
        this.deliveredAt = null;
    }

    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getEventType() { return eventType; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getRefundId() { return refundId; }
    public long getSequence() { return sequence; }
    public String getState() { return state; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Integer getLastStatusCode() { return lastStatusCode; }
    public String getLastError() { return lastError; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
