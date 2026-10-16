package id.fatarc.portfolio.payflowreliability.bank;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A statement as it arrived, kept verbatim with its hash. Append-only on PostgreSQL. */
@Entity
@Table(name = "bank_statements")
public class BankStatement implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(nullable = false, length = 35)
    private String statementId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long sequenceNumber;

    @Column(nullable = false)
    private LocalDate statementDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal openingBalance;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal closingBalance;

    @Column(nullable = false)
    private int entryCount;

    @Column(nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false, length = 1_000_000)
    private String content;

    @Column(nullable = false)
    private String importedBy;

    @Column(nullable = false)
    private Instant importedAt;

    @Transient
    private boolean persisted;

    protected BankStatement() {
    }

    BankStatement(CamtStatement parsed, String contentHash, String content, String importedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.statementId = parsed.statementId();
        this.accountId = parsed.accountId();
        this.currency = parsed.currency();
        this.sequenceNumber = parsed.sequenceNumber();
        this.statementDate = parsed.date();
        this.openingBalance = parsed.openingBalance();
        this.closingBalance = parsed.closingBalance();
        this.entryCount = parsed.entries().size();
        this.contentHash = contentHash;
        this.content = content;
        this.importedBy = importedBy;
        this.importedAt = now;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }

    @Override
    public UUID getId() { return id; }
    public String getStatementId() { return statementId; }
    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public long getSequenceNumber() { return sequenceNumber; }
    public LocalDate getStatementDate() { return statementDate; }
    public BigDecimal getOpeningBalance() { return Money.atScale(openingBalance, currency); }
    public BigDecimal getClosingBalance() { return Money.atScale(closingBalance, currency); }
    public int getEntryCount() { return entryCount; }
    public String getContentHash() { return contentHash; }
    public String getContent() { return content; }
    public String getImportedBy() { return importedBy; }
    public Instant getImportedAt() { return importedAt; }
}
