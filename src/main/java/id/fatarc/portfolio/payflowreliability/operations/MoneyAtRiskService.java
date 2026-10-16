package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.checkout.CheckoutProperties;
import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Money at risk, as amounts and counts, from the payment system's own records: what an outage, a slow provider or
 * a broken client is costing right now, whether or not anything is "down".
 */
@Service
public class MoneyAtRiskService {
    /** Every kind, in the order the summary shows them; gauges exist for each, zero when nothing is at risk. */
    public enum Kind {
        /** Started (handed to the provider) longer ago than the confirmation SLO, and still not confirmed. */
        PENDING_BEYOND_SLA,
        /** Checkouts the customer gave up on within the abandonment window. */
        ABANDONED,
        /** A merchant reference charged more than once: the extra charges (should always be zero). */
        DUPLICATE_CHARGES,
        /** Stepped up by device risk, waiting for the customer's confirmation. */
        AWAITING_CONFIRMATION,
        /** Blocked by device risk since the start of the day. */
        BLOCKED_TODAY
    }

    /**
     * @param amount    per currency
     * @param perMinute per currency, for kinds measured over a window (abandonment); null otherwise
     */
    public record Item(Kind kind, String description, long count, Map<String, BigDecimal> amount,
                       Map<String, BigDecimal> perMinute) {
    }

    public record MoneyAtRisk(Instant asOf, List<Item> items) {
        public Item get(Kind kind) {
            return items.stream().filter(item -> item.kind() == kind).findFirst().orElseThrow();
        }
    }

    private final EntityManager entityManager;
    private final CheckoutProperties checkout;
    private final Duration abandonmentWindow;
    private final Clock clock;

    public MoneyAtRiskService(EntityManager entityManager, CheckoutProperties checkout, Clock clock,
                              @Value("${payflow.checkout.abandonment-window:15m}") Duration abandonmentWindow) {
        this.entityManager = entityManager;
        this.checkout = checkout;
        this.clock = clock;
        this.abandonmentWindow = abandonmentWindow;
    }

    @Transactional(readOnly = true)
    public MoneyAtRisk compute() {
        Instant now = clock.instant();
        List<Item> items = new ArrayList<>();

        List<Object[]> pending = entityManager.createQuery("""
                select p.currency, count(p), sum(p.amount) from PaymentIntent p
                where p.state in (id.fatarc.portfolio.payflowreliability.payment.PaymentState.PENDING,
                                  id.fatarc.portfolio.payflowreliability.payment.PaymentState.AUTHORIZED)
                  and p.startedAt < :startedBefore
                group by p.currency""", Object[].class)
                .setParameter("startedBefore", now.minus(checkout.confirmationSlo())).getResultList();
        items.add(item(Kind.PENDING_BEYOND_SLA, "Payments waiting for the provider's confirmation longer than the "
                + checkout.confirmationSlo().toSeconds() + " s SLO", pending, null));

        List<Object[]> abandoned = entityManager.createQuery("""
                select p.currency, count(p), sum(p.amount) from PaymentIntent p
                where p.state = id.fatarc.portfolio.payflowreliability.payment.PaymentState.CANCELLED
                  and p.cancellationReason = id.fatarc.portfolio.payflowreliability.payment.CancellationReason.ABANDONED
                  and p.cancelledAt >= :since
                group by p.currency""", Object[].class)
                .setParameter("since", now.minus(abandonmentWindow)).getResultList();
        items.add(item(Kind.ABANDONED, "Checkouts abandoned in the last " + abandonmentWindow.toMinutes() + " minutes",
                abandoned, abandonmentWindow));

        // Several captured payments for one merchant reference: every charge after the first is a duplicate.
        List<Object[]> duplicates = new ArrayList<>();
        Map<String, Object[]> byCurrency = new TreeMap<>();
        for (Object[] row : entityManager.createQuery("""
                select p.merchantId, p.merchantReference, p.currency, count(p), sum(p.amount), min(p.amount)
                from PaymentIntent p where p.capturedAt is not null
                group by p.merchantId, p.merchantReference, p.currency having count(p) > 1""", Object[].class).getResultList()) {
            Object[] total = byCurrency.computeIfAbsent((String) row[2], currency -> new Object[]{currency, 0L, BigDecimal.ZERO});
            total[1] = (Long) total[1] + ((Long) row[3] - 1);
            total[2] = ((BigDecimal) total[2]).add(((BigDecimal) row[4]).subtract((BigDecimal) row[5]));
        }
        duplicates.addAll(byCurrency.values());
        items.add(item(Kind.DUPLICATE_CHARGES, "Extra charges for a merchant reference already charged", duplicates, null));

        items.add(item(Kind.AWAITING_CONFIRMATION, "Payments waiting for a device-risk step-up confirmation",
                entityManager.createQuery("""
                        select p.currency, count(p), sum(p.amount) from PaymentIntent p
                        where p.state = id.fatarc.portfolio.payflowreliability.payment.PaymentState.REQUIRES_CONFIRMATION
                        group by p.currency""", Object[].class).getResultList(), null));
        Instant startOfDay = LocalDate.ofInstant(now, ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
        items.add(item(Kind.BLOCKED_TODAY, "Payments blocked by device risk today", entityManager.createQuery("""
                        select d.currency, count(d), sum(d.amount) from RiskDecision d
                        where d.decision = id.fatarc.portfolio.payflowreliability.risk.RiskDecisionType.BLOCK and d.decidedAt >= :since
                        group by d.currency""", Object[].class).setParameter("since", startOfDay).getResultList(), null));
        return new MoneyAtRisk(now, List.copyOf(items));
    }

    private static Item item(Kind kind, String description, List<Object[]> rows, Duration window) {
        long count = 0;
        Map<String, BigDecimal> amounts = new TreeMap<>();
        Map<String, BigDecimal> perMinute = window == null ? null : new TreeMap<>();
        for (Object[] row : rows) {
            String currency = (String) row[0];
            count += ((Number) row[1]).longValue();
            BigDecimal amount = Money.atScale((BigDecimal) row[2], currency);
            amounts.put(currency, amount);
            if (perMinute != null) {
                perMinute.put(currency, amount.divide(BigDecimal.valueOf(Math.max(1, window.toMinutes())),
                        Money.fractionDigits(currency), RoundingMode.HALF_UP));
            }
        }
        return new Item(kind, description, count, amounts, perMinute);
    }
}
