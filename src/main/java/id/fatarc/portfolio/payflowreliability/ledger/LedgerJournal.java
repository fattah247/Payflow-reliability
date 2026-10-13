package id.fatarc.portfolio.payflowreliability.ledger;

import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** Append-only. On PostgreSQL a trigger rejects updates and deletes. */
@Entity
@Table(name = "ledger_journals")
public class LedgerJournal implements Persistable<UUID> {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JournalType journalType;

    @Column(nullable = false)
    private String reference;

    private UUID paymentIntentId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 64)
    private String requestId;

    @Column(nullable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    protected LedgerJournal() {
    }

    LedgerJournal(JournalType journalType, String reference, UUID paymentIntentId, String currency,
                  String description, String requestId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.journalType = journalType;
        this.reference = reference;
        this.paymentIntentId = paymentIntentId;
        this.currency = currency;
        this.description = Text.fit(description, 500);
        this.requestId = requestId;
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
    public JournalType getJournalType() { return journalType; }
    public String getReference() { return reference; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
}
