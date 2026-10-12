package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_cases")
public class ReviewCase {
    @Id
    private UUID id;

    @Version
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status;

    /** What the case is about: a payment, a provider event or transaction, or a refund request. */
    @Column(nullable = false)
    private String referenceType;

    @Column(nullable = false)
    private String referenceId;

    /** The payment affected, when known, so resolution decisions can act on it. */
    private UUID paymentIntentId;

    @Column(nullable = false, length = 1000)
    private String details;

    @Column(nullable = false)
    private String createdBy;

    private String resolvedBy;

    private Instant resolvedAt;

    @Column(length = 1000)
    private String resolutionNote;

    @Enumerated(EnumType.STRING)
    private ReviewAction resolutionAction;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReviewCase() {
    }

    public ReviewCase(ReviewReason reason, String referenceType, String referenceId, UUID paymentIntentId,
                      String details, String createdBy) {
        this.id = UUID.randomUUID();
        this.reason = reason;
        this.status = ReviewStatus.OPEN;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.paymentIntentId = paymentIntentId;
        this.details = Text.fit(details, 1000);
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void resolve(String resolvedBy, String resolutionNote, ReviewAction action) {
        if (status == ReviewStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.REVIEW_CASE_ALREADY_RESOLVED,
                    "Review case " + id + " is already resolved.");
        }
        this.status = ReviewStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolutionNote = resolutionNote;
        this.resolutionAction = action;
        this.resolvedAt = Instant.now();
    }

    public boolean isOpen() {
        return status == ReviewStatus.OPEN;
    }

    public UUID getId() { return id; }
    public ReviewReason getReason() { return reason; }
    public ReviewStatus getStatus() { return status; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getDetails() { return details; }
    public String getCreatedBy() { return createdBy; }
    public String getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public ReviewAction getResolutionAction() { return resolutionAction; }
    public Instant getCreatedAt() { return createdAt; }
}
