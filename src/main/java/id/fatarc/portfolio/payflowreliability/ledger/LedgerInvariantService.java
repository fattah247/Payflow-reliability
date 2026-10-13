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
