package id.fatarc.portfolio.payflowreliability.audit;

import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    /** The payment this event concerns, when known. Lets one query rebuild a payment's full history. */
    private UUID paymentIntentId;

    @Column(nullable = false)
    private String eventType;

    private String previousState;

    private String newState;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false, length = 2000)
    private String metadata;

    /** Correlation id of the request that caused this event; matches the X-Request-Id header and log lines. */
    @Column(length = 64)
    private String requestId;

    @Column(nullable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(String aggregateType,
                      String aggregateId,
                      UUID paymentIntentId,
                      String eventType,
                      String previousState,
                      String newState,
                      String actor,
                      String reason,
                      String metadata,
                      String requestId) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.paymentIntentId = paymentIntentId;
        this.eventType = eventType;
        this.previousState = previousState;
        this.newState = newState;
        this.actor = Text.fit(actor, 100);
        this.reason = Text.fit(reason, 1000);
        this.metadata = Text.fit(metadata, 2000);
        this.requestId = requestId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getEventType() { return eventType; }
    public String getPreviousState() { return previousState; }
    public String getNewState() { return newState; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public String getMetadata() { return metadata; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
}
