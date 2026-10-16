package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One disagreement between the ledger, the provider and the bank. A break is about one subject (a batch, a
 * bank line or a statement); at most one break per category and subject is open at a time.
 */
@Entity
@Table(name = "reconciliation_breaks")
public class ReconciliationBreak {
    @Id
    private UUID id;

    @Version
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BreakCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BreakOwner owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BreakStatus status;

    @Column(nullable = false, length = 100)
    private String subjectKey;

    private UUID settlementBatchId;

    private UUID bankLineId;

    @Column(length = 35)
    private String statementId;

    @Column(length = 3)
    private String currency;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    /** Signed amount held in bank_suspense by this break while it is open (credits positive). */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal suspenseAmount;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Column(nullable = false)
    private Instant openedAt;

    @Column(nullable = false)
    private Instant dueAt;

    private Instant resolvedAt;

    private String resolvedBy;

    @Enumerated(EnumType.STRING)
    private BreakResolution resolution;

    @Column(length = 1000)
    private String resolutionNote;

    protected ReconciliationBreak() {
    }

    ReconciliationBreak(BreakCategory category, String subjectKey, UUID settlementBatchId, UUID bankLineId,
                        String statementId, String currency, BigDecimal amount, BigDecimal suspenseAmount,
                        String detail, Instant now) {
        this.id = UUID.randomUUID();
        this.category = category;
        this.owner = category.owner();
        this.status = BreakStatus.OPEN;
        this.subjectKey = subjectKey;
        this.settlementBatchId = settlementBatchId;
        this.bankLineId = bankLineId;
        this.statementId = statementId;
        this.currency = currency;
        this.amount = amount == null ? null : Money.normalize(amount, currency);
        this.suspenseAmount = suspenseAmount == null ? BigDecimal.ZERO : suspenseAmount;
        this.detail = Text.fit(detail, 1000);
        this.openedAt = now;
        this.dueAt = now.plus(category.dueAfter());
    }

    void resolve(BreakResolution resolution, String actor, String note, Instant now) {
        if (status != BreakStatus.OPEN) {
            throw new IllegalStateException("Break " + id + " is already resolved.");
        }
        this.status = BreakStatus.RESOLVED;
        this.resolution = resolution;
        this.resolvedBy = actor;
        this.resolutionNote = Text.fit(note, 1000);
        this.resolvedAt = now;
    }

    public boolean isOpen() {
        return status == BreakStatus.OPEN;
    }

    public boolean isOverdue(Instant now) {
        return isOpen() && now.isAfter(dueAt);
    }

    public Duration age(Instant now) {
        return Duration.between(openedAt, resolvedAt == null ? now : resolvedAt);
    }

    public UUID getId() { return id; }
    public BreakCategory getCategory() { return category; }
    public BreakOwner getOwner() { return owner; }
    public BreakStatus getStatus() { return status; }
    public String getSubjectKey() { return subjectKey; }
    public UUID getSettlementBatchId() { return settlementBatchId; }
    public UUID getBankLineId() { return bankLineId; }
    public String getStatementId() { return statementId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public BigDecimal getSuspenseAmount() { return currency == null ? suspenseAmount : Money.atScale(suspenseAmount, currency); }
    public String getDetail() { return detail; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getDueAt() { return dueAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public BreakResolution getResolution() { return resolution; }
    public String getResolutionNote() { return resolutionNote; }
}
