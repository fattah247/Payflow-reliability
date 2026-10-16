package id.fatarc.portfolio.payflowreliability.banksim;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** An issued end-of-day statement (camt.053), kept as the exact XML the bank delivers. */
@Entity
@Table(name = "bank_sim_statements")
public class BankSimStatement {
    @Id
    private String statementId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private long sequenceNumber;

    @Column(nullable = false)
    private LocalDate statementDate;

    @Column(nullable = false, length = 1_000_000)
    private String content;

    /** How many times the feed lists it: 2 when the bank re-sends it (RESEND_STATEMENT). */
    @Column(nullable = false)
    private int deliveries;

    @Column(nullable = false)
    private Instant createdAt;

    protected BankSimStatement() {
    }

    BankSimStatement(String statementId, String accountId, long sequenceNumber, LocalDate statementDate, String content,
                     int deliveries, Instant now) {
        this.statementId = statementId;
        this.accountId = accountId;
        this.sequenceNumber = sequenceNumber;
        this.statementDate = statementDate;
        this.content = content;
        this.deliveries = deliveries;
        this.createdAt = now;
    }

    public String getStatementId() { return statementId; }
    public String getAccountId() { return accountId; }
    public long getSequenceNumber() { return sequenceNumber; }
    public LocalDate getStatementDate() { return statementDate; }
    public String getContent() { return content; }
    public int getDeliveries() { return deliveries; }
    public Instant getCreatedAt() { return createdAt; }
}
