package id.fatarc.portfolio.payflowreliability.idempotency;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
    public enum Status { IN_PROGRESS, COMPLETED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String scope;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String requestMethod;

    @Column(nullable = false)
    private String requestPath;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private Integer responseStatus;

    private String responseContentType;

    private String responseLocation;

    @Column(length = 1_000_000)
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    IdempotencyRecord(String scope, String idempotencyKey, String requestMethod, String requestPath,
                      String requestHash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
        this.requestMethod = requestMethod;
        this.requestPath = requestPath;
        this.requestHash = requestHash;
        this.status = Status.IN_PROGRESS;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    void complete(int responseStatus, String contentType, String location, String body, Instant now) {
        this.status = Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseContentType = contentType;
        this.responseLocation = location;
        this.responseBody = body;
        this.completedAt = now;
    }

    public UUID getId() { return id; }
    public String getScope() { return scope; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestMethod() { return requestMethod; }
    public String getRequestPath() { return requestPath; }
    public String getRequestHash() { return requestHash; }
    public Status getStatus() { return status; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseContentType() { return responseContentType; }
    public String getResponseLocation() { return responseLocation; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
