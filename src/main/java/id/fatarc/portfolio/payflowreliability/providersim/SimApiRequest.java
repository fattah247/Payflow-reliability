package id.fatarc.portfolio.payflowreliability.providersim;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One money-moving request as the provider received it: which idempotency key it carried and what the
 * provider did with it. The platform never reads this; tests and scenarios use it as the provider's own
 * evidence that a resend after a crash or timeout carried the same key and was applied once.
 */
@Entity
@Table(name = "sim_api_requests")
public class SimApiRequest {
    public enum Outcome {
        /** A new operation: the provider acted on it. */
        APPLIED,
        /** A key the provider had seen: answered with the stored result, nothing done again. */
        REPLAYED,
        /** The provider processed the operation and refused it. */
        REFUSED,
        /** Same key, different request: refused with 422. */
        KEY_CONFLICT,
        /** Simulated outage: refused with 503 before doing anything. */
        UNAVAILABLE
    }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String operation;

    private String idempotencyKey;

    @Column(length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    @Column(nullable = false)
    private Instant receivedAt;

    protected SimApiRequest() {
    }

    SimApiRequest(String operation, String idempotencyKey, String requestHash, Outcome outcome, Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.outcome = outcome;
        this.receivedAt = receivedAt;
    }

    public UUID getId() { return id; }
    public String getOperation() { return operation; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Outcome getOutcome() { return outcome; }
    public Instant getReceivedAt() { return receivedAt; }
}
