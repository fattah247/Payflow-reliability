package id.fatarc.portfolio.payflowreliability.bank;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.SortedMap;

/**
 * Which statements of an account have arrived without a gap. Statements may arrive in any order; coverage only
 * moves forward once the missing ones are in. Also the row every import of the account locks first.
 */
@Entity
@Table(name = "bank_accounts")
public class BankAccountCursor {
    @Id
    private String accountId;

    @Version
    private Long version;

    @Column(nullable = false, length = 3)
    private String currency;

    /** The first statement number the platform expects from this account: coverage counts from there. */
    @Column(nullable = false)
    private long firstSequence;

    /** The highest sequence number with no gap below it. */
    @Column(nullable = false)
    private long coveredSequence;

    /** The statement date of coveredSequence: every statement through this date is in. Null until one is. */
    private LocalDate coveredThrough;

    @Column(nullable = false)
    private Instant lastImportedAt;

    protected BankAccountCursor() {
    }

    BankAccountCursor(String accountId, String currency, long firstSequence, Instant now) {
        this.accountId = accountId;
        this.currency = currency;
        this.firstSequence = firstSequence;
        this.coveredSequence = firstSequence - 1;
        this.lastImportedAt = now;
    }

    /** Advances coverage over every consecutive statement now present (sequence number to statement date). */
    void advance(SortedMap<Long, LocalDate> imported, Instant now) {
        long next = coveredSequence + 1;
        while (imported.containsKey(next)) {
            coveredSequence = next;
            coveredThrough = imported.get(next);
            next++;
        }
        lastImportedAt = now;
    }

    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public long getFirstSequence() { return firstSequence; }
    public long getCoveredSequence() { return coveredSequence; }
    public LocalDate getCoveredThrough() { return coveredThrough; }
    public Instant getLastImportedAt() { return lastImportedAt; }
}
