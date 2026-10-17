package id.fatarc.portfolio.payflowreliability.ledger;

import id.fatarc.portfolio.payflowreliability.ledger.dto.InvariantReport;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks the books against the operational tables, independently of the code that posts journals:
 * <ol>
 *   <li>every journal balances;</li>
 *   <li>for each merchant and currency, what the ledger says we owe equals captures minus refunds,
 *       reversals and payouts as recorded on payments and settlement batches;</li>
 *   <li>payouts themselves are right: each batch's items add up to its total, a payment's capture is paid
 *       out at most once and for its amount, and each deduction equals the refund or reversal it settles.
 *       (The balance check alone cannot see an over-payout: both sides subtract the same batch totals.)</li>
 *   <li>the bank leg (ADR 025): bank_cash equals the sum of every imported bank line; bank lines equal landed payouts minus returns plus what open breaks hold in
 *       suspense, to the cent; suspense equals the open breaks; every suspense line has an open break; each
 *       landed payout's gross equals what arrived plus the provider fee plus bank charges; transit and
 *       payouts-due balances equal the batches in those stages; no payment is reconciled without a landed
 *       payout.</li>
 * </ol>
 * Runs in one repeatable-read snapshot so concurrent activity cannot produce false alarms.
 */
@Service
public class LedgerInvariantService {
    private static final Logger log = LoggerFactory.getLogger(LedgerInvariantService.class);

    private final EntityManager entityManager;
    private final LedgerPostingRepository postingRepository;
    private final Clock clock;
    private final AtomicReference<InvariantReport> lastReport = new AtomicReference<>();

    public LedgerInvariantService(EntityManager entityManager, LedgerPostingRepository postingRepository, Clock clock) {
        this.entityManager = entityManager;
        this.postingRepository = postingRepository;
        this.clock = clock;
    }

    /** The most recent check, or null before the first one. */
    public InvariantReport lastReport() {
        return lastReport.get();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvariantReport check() {
        List<InvariantReport.Violation> violations = new ArrayList<>();
        postingRepository.unbalancedJournals().forEach(row -> violations.add(new InvariantReport.Violation(
                "UNBALANCED_JOURNAL", null, null, BigDecimal.ZERO, row.getImbalance(),
                "Journal " + row.getJournalId() + " postings sum to " + row.getImbalance() + ".")));

        Map<String, BigDecimal> expected = new TreeMap<>();
        addAll(expected, "select p.merchantId, p.currency, sum(p.amount) from PaymentIntent p where p.capturedAt is not null group by p.merchantId, p.currency", 1);
        addAll(expected, "select p.merchantId, p.currency, sum(p.refundedAmount) from PaymentIntent p group by p.merchantId, p.currency", -1);
        addAll(expected, "select p.merchantId, p.currency, sum(p.reversedAmount) from PaymentIntent p group by p.merchantId, p.currency", -1);
        addAll(expected, "select b.merchantId, b.currency, sum(b.totalAmount) from SettlementBatch b group by b.merchantId, b.currency", -1);

        Map<String, BigDecimal> actual = new TreeMap<>();
        int journals = entityManager.createQuery("select count(j) from LedgerJournal j", Long.class).getSingleResult().intValue();
        postingRepository.balances().stream()
                .filter(row -> LedgerAccounts.isMerchantPayable(row.getAccount()))
                .forEach(row -> actual.put(key(LedgerAccounts.merchantOf(row.getAccount()), row.getCurrency()),
                        row.getBalance().negate()));

        Set<String> keys = new TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            BigDecimal want = expected.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal have = actual.getOrDefault(key, BigDecimal.ZERO);
            if (want.compareTo(have) != 0) {
                String[] parts = key.split("\u0000", 2);
                violations.add(new InvariantReport.Violation("MERCHANT_BALANCE_MISMATCH", parts[0], parts[1], want, have,
                        "Ledger says " + have.toPlainString() + " is owed to " + parts[0] + " in " + parts[1]
                                + ", but payments, refunds, reversals and payouts imply " + want.toPlainString() + "."));
            }
        }

        checkSettlements(violations);
        checkBankLeg(violations);

        InvariantReport report = new InvariantReport(clock.instant(), journals, keys.size(), List.copyOf(violations));
        lastReport.set(report);
        if (!report.healthy()) {
            log.error("Ledger invariant violations: {}", report.violations());
        }
        return report;
    }

    private void checkSettlements(List<InvariantReport.Violation> violations) {
        String items = "id.fatarc.portfolio.payflowreliability.settlement.SettlementItemType";
        for (Object[] row : entityManager.createQuery("""
                select b.id, b.merchantId, b.currency, b.totalAmount, sum(i.amount)
                from SettlementBatch b, SettlementBatchItem i where i.batchId = b.id
                group by b.id, b.merchantId, b.currency, b.totalAmount
                having sum(i.amount) <> b.totalAmount""", Object[].class).getResultList()) {
            violations.add(new InvariantReport.Violation("SETTLEMENT_MISMATCH", (String) row[1], (String) row[2],
                    (BigDecimal) row[3], (BigDecimal) row[4], "Items of settlement batch " + row[0] + " do not add up to its total."));
        }
        for (Object[] row : entityManager.createQuery("""
                select p.id, p.merchantId, p.currency, p.amount, sum(i.amount), count(i)
                from SettlementBatchItem i, PaymentIntent p
                where p.id = i.paymentIntentId and i.itemType = %s.CAPTURE
                group by p.id, p.merchantId, p.currency, p.amount
                having count(i) > 1 or sum(i.amount) <> p.amount""".formatted(items), Object[].class).getResultList()) {
            violations.add(new InvariantReport.Violation("SETTLEMENT_MISMATCH", (String) row[1], (String) row[2],
                    (BigDecimal) row[3], (BigDecimal) row[4],
                    "Capture of payment " + row[0] + " was paid out " + row[5] + " time(s) for " + row[4] + "."));
        }
        for (Object[] row : entityManager.createQuery("""
                select i.id, p.merchantId, p.currency, r.amount, i.amount
                from SettlementBatchItem i, RefundRequest r, PaymentIntent p
                where r.id = i.refundId and p.id = r.paymentIntentId and i.amount + r.amount <> 0""", Object[].class)
                .getResultList()) {
            violations.add(new InvariantReport.Violation("SETTLEMENT_MISMATCH", (String) row[1], (String) row[2],
                    ((BigDecimal) row[3]).negate(), (BigDecimal) row[4],
                    "Settlement item " + row[0] + " deducts a different amount than its refund."));
        }
        for (Object[] row : entityManager.createQuery("""
                select i.id, p.merchantId, p.currency, p.reversedAmount, i.amount
                from SettlementBatchItem i, PaymentIntent p
                where p.id = i.reversalPaymentIntentId and i.amount + p.reversedAmount <> 0""", Object[].class)
                .getResultList()) {
            violations.add(new InvariantReport.Violation("SETTLEMENT_MISMATCH", (String) row[1], (String) row[2],
                    ((BigDecimal) row[3]).negate(), (BigDecimal) row[4],
                    "Settlement item " + row[0] + " deducts a different amount than the reversal."));
        }
    }

    private void checkBankLeg(List<InvariantReport.Violation> violations) {
        Map<String, BigDecimal> ledgerCash = accountBalances(LedgerAccounts.BANK_CASH);
        Map<String, BigDecimal> bankLines = totals("""
                select l.currency, sum(case when l.creditDebit = 'CRDT' then l.amount else -l.amount end)
                from BankStatementLine l group by l.currency""");
        compare(violations, "BANK_CASH_MISMATCH", ledgerCash, bankLines,
                "bank_cash in the ledger is %s %s; the imported bank lines add up to %s.");

        Map<String, BigDecimal> explained = totals("""
                select b.currency, sum(b.landedAmount) from SettlementBatch b where b.landedAt is not null group by b.currency""");
        totals("""
                select b.currency, sum(b.returnedAmount) from SettlementBatch b where b.returnedAmount is not null group by b.currency""")
                .forEach((currency, returned) -> explained.merge(currency, returned.negate(), BigDecimal::add));
        Map<String, BigDecimal> openSuspense = totals("""
                select b.currency, sum(b.suspenseAmount) from ReconciliationBreak b
                where b.status = id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus.OPEN
                  and b.currency is not null group by b.currency""");
        openSuspense.forEach((currency, held) -> explained.merge(currency, held, BigDecimal::add));
        compare(violations, "BANK_CREDITS_UNEXPLAINED", bankLines, explained,
                "the bank lines add up to %s %s, but landed payouts minus returns plus open breaks explain %s.");

        Map<String, BigDecimal> suspense = accountBalances(LedgerAccounts.BANK_SUSPENSE);
        suspense.replaceAll((currency, balance) -> balance.negate());
        compare(violations, "SUSPENSE_MISMATCH", suspense, openSuspense,
                "bank_suspense holds %s %s, but open breaks account for %s.");

        for (Object[] row : entityManager.createQuery("""
                select l.id, l.currency, l.amount from BankStatementLine l
                where l.matchStatus = id.fatarc.portfolio.payflowreliability.bank.BankLineStatus.SUSPENSE
                  and not exists (select 1 from ReconciliationBreak b where b.bankLineId = l.id
                                  and b.status = id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus.OPEN)""",
                Object[].class).getResultList()) {
            violations.add(new InvariantReport.Violation("SUSPENSE_LINE_WITHOUT_BREAK", null, (String) row[1], (BigDecimal) row[2],
                    BigDecimal.ZERO, "Bank line " + row[0] + " is in suspense with no open break explaining why."));
        }

        for (Object[] row : entityManager.createQuery("""
                select b.id, b.merchantId, b.currency, b.totalAmount, b.landedAmount + b.providerFeeAmount + b.bankChargesAmount
                from SettlementBatch b
                where b.landedAt is not null
                  and b.landedAmount + b.providerFeeAmount + b.bankChargesAmount <> b.totalAmount""", Object[].class).getResultList()) {
            violations.add(new InvariantReport.Violation("PAYOUT_FEES_MISMATCH", (String) row[1], (String) row[2],
                    (BigDecimal) row[3], (BigDecimal) row[4],
                    "Payout " + row[0] + ": received plus provider fee plus bank charges is not the gross."));
        }

        compare(violations, "IN_TRANSIT_MISMATCH", accountBalances(LedgerAccounts.PAYOUTS_IN_TRANSIT), totals("""
                        select b.currency, sum(b.totalAmount) from SettlementBatch b
                        where b.inTransitAt is not null and b.landedAt is null group by b.currency"""),
                "payouts_in_transit is %s %s; payouts accepted by the provider and not landed total %s.");

        Map<String, BigDecimal> due = new TreeMap<>();
        postingRepository.balances().stream()
                .filter(row -> LedgerAccounts.isPayoutsDue(row.getAccount()))
                .forEach(row -> due.merge(row.getCurrency(), row.getBalance().negate(), BigDecimal::add));
        compare(violations, "PAYOUTS_DUE_MISMATCH", due, totals("""
                        select b.currency, sum(b.totalAmount) from SettlementBatch b
                        where b.payoutStatus <> id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus.LEGACY
                        group by b.currency"""),
                "payouts_due is %s %s; batches committed to payouts total %s.");

        for (Object[] row : entityManager.createQuery("""
                select p.id, p.merchantId, p.currency, p.amount from PaymentIntent p, SettlementBatchItem i, SettlementBatch b
                where p.state = id.fatarc.portfolio.payflowreliability.payment.PaymentState.RECONCILED
                  and i.capturePaymentIntentId = p.id and b.id = i.batchId
                  and b.payoutStatus <> id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus.LEGACY
                  and b.landedAt is null""", Object[].class).getResultList()) {
            violations.add(new InvariantReport.Violation("RECONCILED_WITHOUT_BANK_LINE", (String) row[1], (String) row[2],
                    BigDecimal.ZERO, (BigDecimal) row[3],
                    "Payment " + row[0] + " is RECONCILED, but no bank credit landed the payout that paid it."));
        }
    }

    private Map<String, BigDecimal> accountBalances(String account) {
        Map<String, BigDecimal> balances = new TreeMap<>();
        postingRepository.balances().stream()
                .filter(row -> row.getAccount().equals(account))
                .forEach(row -> balances.put(row.getCurrency(), row.getBalance()));
        return balances;
    }

    private Map<String, BigDecimal> totals(String jpql) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (Object[] row : entityManager.createQuery(jpql, Object[].class).getResultList()) {
            totals.merge((String) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1], BigDecimal::add);
        }
        return totals;
    }

    /** One violation per currency where the two sides differ (a missing side counts as zero). */
    private static void compare(List<InvariantReport.Violation> violations, String kind, Map<String, BigDecimal> actual,
                                Map<String, BigDecimal> expected, String message) {
        Set<String> currencies = new TreeSet<>(actual.keySet());
        currencies.addAll(expected.keySet());
        for (String currency : currencies) {
            BigDecimal have = actual.getOrDefault(currency, BigDecimal.ZERO);
            BigDecimal want = expected.getOrDefault(currency, BigDecimal.ZERO);
            if (have.compareTo(want) != 0) {
                violations.add(new InvariantReport.Violation(kind, null, currency, want, have,
                        message.formatted(have.toPlainString(), currency, want.toPlainString())));
            }
        }
    }

    private void addAll(Map<String, BigDecimal> totals, String jpql, int sign) {
        for (Object[] row : entityManager.createQuery(jpql, Object[].class).getResultList()) {
            BigDecimal amount = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            totals.merge(key((String) row[0], (String) row[1]), sign > 0 ? amount : amount.negate(), BigDecimal::add);
        }
    }

    private static String key(String merchantId, String currency) {
        return merchantId + "\u0000" + currency;
    }
}
