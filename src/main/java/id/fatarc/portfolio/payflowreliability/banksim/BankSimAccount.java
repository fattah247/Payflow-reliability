package id.fatarc.portfolio.payflowreliability.banksim;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The settlement account as the bank sees it: today's business date, the next statement number, the balance. */
@Entity
@Table(name = "bank_sim_accounts")
public class BankSimAccount {
    @Id
    private String accountId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    private long nextSequence;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    protected BankSimAccount() {
    }

    BankSimAccount(String accountId, String currency, LocalDate businessDate) {
        this.accountId = accountId;
        this.currency = currency;
        this.businessDate = businessDate;
        this.nextSequence = 1;
        this.balance = BigDecimal.ZERO;
    }

    /** Closes the business day: the statement takes the next number, the balance moves, the date advances. */
    long close(BigDecimal movement) {
        balance = balance.add(movement);
        businessDate = businessDate.plusDays(1);
        return nextSequence++;
    }

    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }
    public LocalDate getBusinessDate() { return businessDate; }
    public long getNextSequence() { return nextSequence; }
    public BigDecimal getBalance() { return balance; }
}
