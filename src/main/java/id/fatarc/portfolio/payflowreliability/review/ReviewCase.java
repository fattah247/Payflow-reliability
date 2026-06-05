package id.fatarc.portfolio.payflowreliability.review;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_cases", indexes = {
        @Index(name = "idx_review_status", columnList = "status"),
        @Index(name = "idx_review_reference", columnList = "referenceId")
})
public class ReviewCase {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status;

    @Column(nullable = false)
    private String referenceType;

    @Column(nullable = false)
    private String referenceId;

    @Column(nullable = false, length = 1000)
    private String details;

    @Column(nullable = false)
    private String createdBy;

    private String resolvedBy;

    private Instant resolvedAt;

    @Column(length = 1000)
    private String resolutionNote;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReviewCase() {
    }

    public ReviewCase(ReviewReason reason, String referenceType, String referenceId, String details, String createdBy) {
        this.id = UUID.randomUUID();
        this.reason = reason;
        this.status = ReviewStatus.OPEN;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.details = details;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void resolve(String resolvedBy, String resolutionNote) {
        if (status == ReviewStatus.RESOLVED) {
            throw new IllegalStateException("Review case is already resolved.");
        }
        this.status = ReviewStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolutionNote = resolutionNote;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public ReviewReason getReason() { return reason; }
    public ReviewStatus getStatus() { return status; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public String getDetails() { return details; }
    public String getCreatedBy() { return createdBy; }
    public String getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public Instant getCreatedAt() { return createdAt; }
}
