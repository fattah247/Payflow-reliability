package id.fatarc.portfolio.payflowreliability.banksim;

import id.fatarc.portfolio.payflowreliability.common.Money;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bank-side behavior for the platform's settlement account: incoming credits are booked on their value date,
 * each closed business day produces one camt.053 statement, and faults change what the statement shows.
 *
 * <p>The bank keeps its own business date per account. It starts at today's date and only moves when a day is
 * closed, so a demo can "fast-forward the bank" through several days in seconds without touching the clock.
 */
@Service
@ConditionalOnProperty(name = "payflow.bank-sim.enabled", havingValue = "true")
public class BankSimulator {
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    private final BankSimAccountRepository accounts;
    private final BankSimEntryRepository entries;
    private final BankSimStatementRepository statements;
    private final BankFaults faults;
    private final BankSimProperties properties;
    private final Clock clock;

    public BankSimulator(BankSimAccountRepository accounts, BankSimEntryRepository entries,
                         BankSimStatementRepository statements, BankFaults faults, BankSimProperties properties,
                         Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.statements = statements;
        this.faults = faults;
        this.properties = properties;
        this.clock = clock;
    }

    /** The settlement account's current business date (a payout cannot be valued before it). */
    @Transactional
    public LocalDate businessDate(String currency) {
        return account(currency).getBusinessDate();
    }

    /**
     * An incoming transfer to the settlement account. The next queued fault, if any, decides what the bank
     * books: on time, late, not at all, twice, net of a charge, with the wrong or no reference, or returned.
     *
     * @return the bank entries created (empty when the fault drops the credit)
     */
    @Transactional
    public List<BankSimEntry> receiveCredit(String currency, BigDecimal amount, String endToEndId, String remittanceInfo,
                                            String debtorName, LocalDate valueDate) {
        BankSimAccount account = account(currency);
        BankFaults.Fault fault = faults.nextCreditFault().orElse(null);
        BankFaults.Mode mode = fault == null ? null : fault.mode();
        List<BankSimEntry> booked = new ArrayList<>();
        if (mode == BankFaults.Mode.DROP_CREDIT) {
            return booked;
        }
        LocalDate value = mode == BankFaults.Mode.LATE_CREDIT ? valueDate.plusDays(days(fault, 3)) : valueDate;
        String reference = mode == BankFaults.Mode.WRONG_REFERENCE
                ? "PFB" + UUID.randomUUID().toString().replace("-", "").toUpperCase()
                : mode == BankFaults.Mode.MISSING_REFERENCE ? null : endToEndId;
        String remittance = mode == BankFaults.Mode.MISSING_REFERENCE ? "SETTLEMENT" : reference == null ? remittanceInfo
                : remittanceInfo.replace(endToEndId, reference);
        BigDecimal charges = mode == BankFaults.Mode.NET_OF_FEES ? charge(fault, amount, currency) : null;
        BigDecimal credited = charges == null ? amount : amount.subtract(charges);
        BankSimEntry credit = book(account, credited, "CRDT", false, value, reference, remittance, debtorName,
                charges == null ? null : amount, charges);
        booked.add(credit);
        if (mode == BankFaults.Mode.DUPLICATE_CREDIT) {
            booked.add(book(account, credited, "CRDT", false, value, reference, remittance, debtorName, null, null));
        }
        if (mode == BankFaults.Mode.RETURN_PAYOUT) {
            booked.add(book(account, credited, "DBIT", true, value.plusDays(days(fault, 1)), reference,
                    "RETURN " + remittance, properties.bankName(), null, null));
        }
        return booked;
    }

    /** Closes business days up to and including {@code through} (or just the current one), issuing a statement for each. */
    @Transactional
    public List<BankSimStatement> closeDays(String currency, LocalDate through) {
        BankSimAccount account = accounts.findForUpdate(account(currency).getAccountId()).orElseThrow();
        LocalDate last = through == null ? account.getBusinessDate() : through;
        List<BankSimStatement> issued = new ArrayList<>();
        while (!account.getBusinessDate().isAfter(last)) {
            issued.add(closeDay(account));
        }
        return issued;
    }

    /** End-of-day processing for every account whose business date is behind today's date. */
    @Transactional
    public int closeElapsedDays() {
        LocalDate today = today();
        int issued = 0;
        for (BankSimAccount account : accounts.findAll()) {
            BankSimAccount locked = accounts.findForUpdate(account.getAccountId()).orElseThrow();
            while (locked.getBusinessDate().isBefore(today)) {
                closeDay(locked);
                issued++;
            }
        }
        return issued;
    }

    @Transactional(readOnly = true)
    public List<BankSimAccount> accounts() {
        return accounts.findAll();
    }

    /** The statement feed: statements after a sequence number, a re-sent statement listed twice. */
    @Transactional(readOnly = true)
    public List<BankSimStatement> statementsAfter(String accountId, long afterSequence) {
        List<BankSimStatement> feed = new ArrayList<>();
        for (BankSimStatement statement : statements.findByAccountIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(accountId, afterSequence)) {
            for (int delivery = 0; delivery < statement.getDeliveries(); delivery++) {
                feed.add(statement);
            }
        }
        return feed;
    }

    @Transactional(readOnly = true)
    public Optional<BankSimStatement> statement(String statementId) {
        return statements.findById(statementId);
    }

    @Transactional(readOnly = true)
    public List<BankSimEntry> entriesFor(String endToEndId) {
        return entries.findByEndToEndIdOrderByCreatedAtAsc(endToEndId);
    }

    private BankSimStatement closeDay(BankSimAccount account) {
        LocalDate date = account.getBusinessDate();
        List<BankSimEntry> booked = entries.findByAccountIdAndStatementIdIsNullAndBookingDateLessThanEqualOrderByCreatedAtAsc(
                account.getAccountId(), date);
        BigDecimal opening = account.getBalance();
        BigDecimal movement = booked.stream().map(BankSimEntry::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long sequence = account.close(movement);
        String statementId = "STMT-" + account.getCurrency() + "-" + date.format(COMPACT) + "-" + sequence;
        Instant now = clock.instant();
        String xml = CamtStatementWriter.write(statementId, sequence, account.getAccountId(), account.getCurrency(), date,
                opening, opening.add(movement), booked, now);
        booked.forEach(entry -> entry.includeIn(statementId));
        return statements.save(new BankSimStatement(statementId, account.getAccountId(), sequence, date, xml,
                faults.nextStatementResent() ? 2 : 1, now));
    }

    private BankSimEntry book(BankSimAccount account, BigDecimal amount, String direction, boolean reversal,
                              LocalDate valueDate, String reference, String remittance, String debtor,
                              BigDecimal instructed, BigDecimal charges) {
        // A bank books on the value date, but never into a day it has already closed.
        LocalDate bookingDate = valueDate.isBefore(account.getBusinessDate()) ? account.getBusinessDate() : valueDate;
        String servicerRef = "BNK" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        return entries.save(new BankSimEntry(account.getAccountId(), account.getCurrency(), amount, direction, reversal,
                bookingDate, valueDate, servicerRef, reference, remittance, debtor, instructed, charges, clock.instant()));
    }

    private BankSimAccount account(String currency) {
        String accountId = properties.accountFor(currency);
        return accounts.findById(accountId).orElseGet(() -> accounts.saveAndFlush(new BankSimAccount(accountId, currency, today())));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private static int days(BankFaults.Fault fault, int fallback) {
        return fault.days() == null ? fallback : fault.days();
    }

    /** The charge deducted: as injected, or 0.1 % of the transfer (at least one minor unit). */
    private static BigDecimal charge(BankFaults.Fault fault, BigDecimal amount, String currency) {
        if (fault.amount() != null) {
            return Money.normalize(fault.amount(), currency);
        }
        BigDecimal minimum = BigDecimal.ONE.movePointLeft(Money.fractionDigits(currency));
        return amount.multiply(new BigDecimal("0.001")).setScale(Money.fractionDigits(currency), RoundingMode.HALF_UP).max(minimum);
    }
}
