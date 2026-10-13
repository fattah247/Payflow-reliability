package id.fatarc.portfolio.payflowreliability.ledger;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One side of a journal. Debit is positive, credit negative; a journal's postings sum to zero. */
@Entity
@Table(name = "ledger_postings")
public class LedgerPosting implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID journalId;

    @Column(nullable = false)
    private String account;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    protected LedgerPosting() {
    }

    LedgerPosting(UUID journalId, String account, String currency, BigDecimal amount, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.journalId = journalId;
        this.account = account;
        this.currency = currency;
        this.amount = amount;
        this.createdAt = createdAt;
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
    public UUID getJournalId() { return journalId; }
    public String getAccount() { return account; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public Instant getCreatedAt() { return createdAt; }
}
