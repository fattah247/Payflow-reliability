package id.fatarc.portfolio.payflowreliability.bank;

import id.fatarc.portfolio.payflowreliability.common.Hashing;
import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One entry of an imported statement. Its identity is the hash of its content, including the bank's own entry
 * reference, so the same entry delivered twice is recognized and a genuine second credit is not mistaken for it.
 */
@Entity
@Table(name = "bank_statement_lines")
public class BankStatementLine {
    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false, length = 35)
    private String statementId;

    @Column(nullable = false, length = 64)
    private String lineHash;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false, length = 3)
    private String currency;

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

    @Column(nullable = false, length = 35)
    private String accountServicerRef;

    @Column(length = 35)
    private String endToEndId;

    @Column(length = 140)
    private String remittanceInfo;

    @Column(precision = 19, scale = 4)
    private BigDecimal instructedAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal chargesAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BankLineStatus matchStatus;

    private UUID settlementBatchId;

    @Column(nullable = false)
    private Instant importedAt;

    protected BankStatementLine() {
    }

    BankStatementLine(String statementId, String accountId, String currency, CamtStatement.Entry entry, Instant now) {
        this.id = UUID.randomUUID();
        this.statementId = statementId;
        this.lineHash = hash(accountId, currency, entry);
        this.accountId = accountId;
        this.currency = currency;
        this.amount = entry.amount();
        this.creditDebit = entry.creditDebit();
        this.reversal = entry.reversal();
        this.bookingDate = entry.bookingDate();
        this.valueDate = entry.valueDate();
        this.accountServicerRef = entry.accountServicerRef();
        this.endToEndId = entry.endToEndId();
        this.remittanceInfo = entry.remittanceInfo() == null ? null
                : entry.remittanceInfo().substring(0, Math.min(140, entry.remittanceInfo().length()));
        this.instructedAmount = entry.instructedAmount();
        this.chargesAmount = entry.chargesAmount();
        this.matchStatus = BankLineStatus.SUSPENSE;
        this.importedAt = now;
    }

    /** Everything that makes an entry this entry, including the bank's own reference. */
    static String hash(String accountId, String currency, CamtStatement.Entry entry) {
        return Hashing.sha256Hex(String.join("|", accountId, currency, entry.accountServicerRef(),
                entry.bookingDate().toString(), entry.valueDate().toString(), entry.creditDebit(),
                String.valueOf(entry.reversal()), entry.amount().stripTrailingZeros().toPlainString(),
                String.valueOf(entry.endToEndId()),
                entry.chargesAmount() == null ? "-" : entry.chargesAmount().stripTrailingZeros().toPlainString()));
    }

    public void matched(UUID batchId) {
        this.matchStatus = BankLineStatus.MATCHED;
        this.settlementBatchId = batchId;
    }

    public void returned(UUID batchId) {
        this.matchStatus = BankLineStatus.RETURN;
        this.settlementBatchId = batchId;
    }

    public void suspense(UUID relatedBatchId) {
        this.matchStatus = BankLineStatus.SUSPENSE;
        this.settlementBatchId = relatedBatchId;
    }

    public void cleared(UUID batchId) {
        this.matchStatus = BankLineStatus.CLEARED;
        this.settlementBatchId = batchId;
    }

    public boolean isCredit() {
        return "CRDT".equals(creditDebit);
    }

    /** Credits positive, debits negative: what the line did to the account balance. */
    public BigDecimal signedAmount() {
        return isCredit() ? getAmount() : getAmount().negate();
    }

    public BigDecimal chargesOrZero() {
        return chargesAmount == null ? Money.zero(currency) : getChargesAmount();
    }

    public UUID getId() { return id; }
    public String getStatementId() { return statementId; }
    public String getLineHash() { return lineHash; }
    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCreditDebit() { return creditDebit; }
    public boolean isReversal() { return reversal; }
    public LocalDate getBookingDate() { return bookingDate; }
    public LocalDate getValueDate() { return valueDate; }
    public String getAccountServicerRef() { return accountServicerRef; }
    public String getEndToEndId() { return endToEndId; }
    public String getRemittanceInfo() { return remittanceInfo; }
    public BigDecimal getInstructedAmount() { return Money.atScale(instructedAmount, currency); }
    public BigDecimal getChargesAmount() { return Money.atScale(chargesAmount, currency); }
    public BankLineStatus getMatchStatus() { return matchStatus; }
    public UUID getSettlementBatchId() { return settlementBatchId; }
    public Instant getImportedAt() { return importedAt; }
}
