package id.fatarc.portfolio.payflowreliability.banksim;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One booking on the settlement account; it appears in the statement of its booking date. */
@Entity
@Table(name = "bank_sim_entries")
public class BankSimEntry {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Always positive; the direction is in creditDebit. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 4)
    private String creditDebit;

    @Column(nullable = false)
    private boolean reversal;

    @Column(nullable = false)
    private LocalDate bookingDate;

    @Column(nullable = false)
    private LocalDate valueDate;

    /** The bank's own unique reference for the entry (camt AcctSvcrRef). */
    @Column(nullable = false, length = 35)
    private String accountServicerRef;

    @Column(length = 35)
    private String endToEndId;

    @Column(length = 140)
    private String remittanceInfo;

    @Column(length = 140)
    private String debtorName;

    @Column(precision = 19, scale = 4)
    private BigDecimal instructedAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal chargesAmount;

    @Column(length = 35)
    private String statementId;

    @Column(nullable = false)
    private Instant createdAt;

    protected BankSimEntry() {
    }

    BankSimEntry(String accountId, String currency, BigDecimal amount, String creditDebit, boolean reversal,
                 LocalDate bookingDate, LocalDate valueDate, String accountServicerRef, String endToEndId,
                 String remittanceInfo, String debtorName, BigDecimal instructedAmount, BigDecimal chargesAmount,
                 Instant now) {
        this.id = UUID.randomUUID();
        this.accountId = accountId;
        this.currency = currency;
        this.amount = amount;
        this.creditDebit = creditDebit;
        this.reversal = reversal;
        this.bookingDate = bookingDate;
        this.valueDate = valueDate;
        this.accountServicerRef = accountServicerRef;
        this.endToEndId = endToEndId;
        this.remittanceInfo = remittanceInfo;
        this.debtorName = debtorName;
        this.instructedAmount = instructedAmount;
        this.chargesAmount = chargesAmount;
        this.createdAt = now;
    }

    void includeIn(String statementId) {
        this.statementId = statementId;
    }

    BigDecimal signedAmount() {
        return "CRDT".equals(creditDebit) ? amount : amount.negate();
    }

    public UUID getId() { return id; }
    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public String getCreditDebit() { return creditDebit; }
    public boolean isReversal() { return reversal; }
    public LocalDate getBookingDate() { return bookingDate; }
    public LocalDate getValueDate() { return valueDate; }
    public String getAccountServicerRef() { return accountServicerRef; }
    public String getEndToEndId() { return endToEndId; }
    public String getRemittanceInfo() { return remittanceInfo; }
    public String getDebtorName() { return debtorName; }
    public BigDecimal getInstructedAmount() { return instructedAmount; }
    public BigDecimal getChargesAmount() { return chargesAmount; }
    public String getStatementId() { return statementId; }
    public Instant getCreatedAt() { return createdAt; }
}
