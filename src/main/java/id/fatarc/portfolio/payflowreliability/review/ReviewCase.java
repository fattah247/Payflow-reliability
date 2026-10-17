package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;

import java.time.Duration;
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

    /** What the case is about: a payment, a provider event or transaction, a refund, or an outbox command. */
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

    @Enumerated(EnumType.STRING)
    private ReviewAction proposedAction;

    private String proposedBy;

    private Instant proposedAt;

    @Column(length = 1000)
    private String proposalNote;

    private String approvedBy;

    private String resolvedBy;

    private Instant resolvedAt;

    @Column(length = 1000)
    private String resolutionNote;

    @Enumerated(EnumType.STRING)
    private ReviewAction resolutionAction;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * When a person should have acted, matching the alert that fires if nobody does: a decision within 4 hours
     * (PayflowReviewQueueAging), an unknown provider outcome within 5 minutes (PayflowProviderCallOutcomeUnknown), a
     * proposal's approval within 1 hour of the proposal (PayflowApprovalWaiting).
     */
    @Column(nullable = false)
    private Instant dueAt;

    public static final Duration DECIDE_WITHIN = Duration.ofHours(4);
    public static final Duration UNKNOWN_OUTCOME_WITHIN = Duration.ofMinutes(5);
    public static final Duration APPROVE_WITHIN = Duration.ofHours(1);

    protected ReviewCase() {
    }

    public ReviewCase(ReviewReason reason, String referenceType, String referenceId, UUID paymentIntentId,
                      String details, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.reason = reason;
        this.status = ReviewStatus.OPEN;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.paymentIntentId = paymentIntentId;
        this.details = Text.fit(details, 1000);
        this.createdBy = createdBy;
        this.createdAt = now;
        this.dueAt = decisionDue();
    }

    /** When the decision itself is due: from the case's creation, whatever happened to proposals since. */
    private Instant decisionDue() {
        return createdAt.plus(reason == ReviewReason.PROVIDER_OUTCOME_UNKNOWN ? UNKNOWN_OUTCOME_WITHIN : DECIDE_WITHIN);
    }

    /** First half of a two-person decision: nothing happens until a different operator approves. */
    public void propose(ReviewAction action, String proposedBy, String note, Instant now) {
        requireStatus(ReviewStatus.OPEN);
        this.status = ReviewStatus.AWAITING_APPROVAL;
        this.proposedAction = action;
        this.proposedBy = proposedBy;
        this.proposedAt = now;
        this.proposalNote = Text.fit(note, 1000);
        this.dueAt = now.plus(APPROVE_WITHIN);
    }

    public void withdrawProposal() {
        requireStatus(ReviewStatus.AWAITING_APPROVAL);
        this.status = ReviewStatus.OPEN;
        this.proposedAction = null;
        this.proposedBy = null;
        this.proposedAt = null;
        this.proposalNote = null;
        this.dueAt = decisionDue();
    }

    public void resolve(String resolvedBy, String resolutionNote, ReviewAction action, String approvedBy, Instant now) {
        if (status == ReviewStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.REVIEW_CASE_ALREADY_RESOLVED,
                    "Review case " + id + " is already resolved.");
        }
        this.status = ReviewStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolutionNote = Text.fit(resolutionNote, 1000);
        this.resolutionAction = action;
        this.approvedBy = approvedBy;
        this.resolvedAt = now;
    }

    public boolean isOverdue(Instant now) {
        return isUnresolved() && now.isAfter(dueAt);
    }

    public boolean isUnresolved() {
        return status != ReviewStatus.RESOLVED;
    }

    private void requireStatus(ReviewStatus expected) {
        if (status != expected) {
            throw new BusinessException(status == ReviewStatus.RESOLVED ? ErrorCode.REVIEW_CASE_ALREADY_RESOLVED
                    : ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE,
                    "Review case " + id + " is " + status + ", expected " + expected + ".");
        }
    }

    public UUID getId() { return id; }
    public ReviewReason getReason() { return reason; }
    public ReviewStatus getStatus() { return status; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getDetails() { return details; }
    public String getCreatedBy() { return createdBy; }
    public ReviewAction getProposedAction() { return proposedAction; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getProposalNote() { return proposalNote; }
    public String getApprovedBy() { return approvedBy; }
    public String getResolvedBy() { return resolvedBy; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public ReviewAction getResolutionAction() { return resolutionAction; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDueAt() { return dueAt; }
}
