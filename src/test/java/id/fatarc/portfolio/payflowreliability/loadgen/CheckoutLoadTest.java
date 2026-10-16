package id.fatarc.portfolio.payflowreliability.loadgen;

import id.fatarc.portfolio.payflowreliability.operations.MoneyAtRiskService;
import id.fatarc.portfolio.payflowreliability.operations.OperationalMetrics;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeded simulated customers through the real API against a provider that turns slow while staying up. The
 * simulation knows what really happened to each customer; the payment system has to measure the same thing from
 * its own records, and it must never charge a retry twice when the shop reuses its key.
 */
class CheckoutLoadTest extends IntegrationTest {
    @Autowired
    private CheckoutLoadGenerator generator;
    @Autowired
    private MoneyAtRiskService moneyAtRiskService;
    @Autowired
    private OperationalMetrics operationalMetrics;
    @Autowired
    private MeterRegistry meterRegistry;

    private CheckoutRun.Report run(String phase, boolean slow, Boolean holdWebhooks, KeyStrategy keys) {
        return generator.run(new CheckoutRun.Request(42, phase, merchantAKey, slow, holdWebhooks, 30, keys));
    }

    private long payments(PaymentState state) {
        return paymentIntentRepository.findAll().stream().filter(intent -> intent.getState() == state).count();
    }

    @Test
    void shouldMeasureWhatASlowProviderCostsWhileEverythingIsUp() {
        CheckoutRun.Report normal = run("normal", false, null, KeyStrategy.PER_CHECKOUT);
        deliverWebhooks();
        assertThat(normal.paid().count()).as("a fast provider loses nobody").isEqualTo(30);
        assertThat(normal.abandoned().count()).isZero();
        assertThat(normal.retries()).isZero();

        CheckoutRun.Report slow = run("slow", true, true, KeyStrategy.PER_CHECKOUT);

        // The simulation's ground truth: people left, people retried, nobody was charged twice.
        assertThat(slow.abandoned().count()).isPositive();
        assertThat(slow.retries()).isPositive();
        assertThat(slow.retriesAbsorbed()).as("every retry reused the checkout's key").isEqualTo(slow.retries());
        assertThat(slow.duplicateCharges().count()).isZero();

        // The payment system measures the same money from its own records.
        MoneyAtRiskService.Item abandoned = moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.ABANDONED);
        assertThat(abandoned.count()).isEqualTo(slow.abandoned().count());
        assertThat(abandoned.amount().get("IDR")).isEqualByComparingTo(slow.abandoned().amount());

        clock.advance(Duration.ofSeconds(11));
        MoneyAtRiskService.Item pending = moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.PENDING_BEYOND_SLA);
        assertThat(pending.count()).as("paid during the slowdown, confirmation held by the provider").isEqualTo(slow.paid().count());
        assertThat(pending.amount().get("IDR")).isEqualByComparingTo(slow.paid().amount());
        operationalMetrics.refresh();
        assertThat(meterRegistry.get("payflow.health.up").gauge().value()).as("and every probe says UP").isEqualTo(1.0);
        assertThat(moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.DUPLICATE_CHARGES).count()).isZero();

        double slowBefore = meterRegistry.counter("payflow.payment.confirmations", "within_slo", "false").count();
        run("recovered", false, null, KeyStrategy.PER_CHECKOUT);
        deliverWebhooks();
        assertThat(moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.PENDING_BEYOND_SLA).count()).isZero();
        assertThat(meterRegistry.counter("payflow.payment.confirmations", "within_slo", "false").count() - slowBefore)
                .as("the held confirmations arrive late, outside the SLO").isEqualTo(slow.paid().count());
        assertThat(payments(PaymentState.CANCELLED)).isEqualTo(slow.abandoned().count());
    }

    @Test
    void shouldShowWhatAKeyPerAttemptCosts() {
        CheckoutRun.Report wrong = run("per-attempt", true, false, KeyStrategy.PER_ATTEMPT);
        deliverWebhooks();

        assertThat(wrong.retriesAbsorbed()).as("new keys are new requests").isZero();
        assertThat(wrong.duplicateCharges().count()).isPositive();
        MoneyAtRiskService.Item duplicates = moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.DUPLICATE_CHARGES);
        assertThat(duplicates.count()).as("the payment system sees every one of them").isEqualTo(wrong.duplicateCharges().count());
        assertThat(duplicates.amount().get("IDR")).isEqualByComparingTo(wrong.duplicateCharges().amount());
    }

    @Test
    void shouldGiveTheSameNumbersForTheSameSeed() {
        CheckoutRun.Report first = run("repeatable", true, true, KeyStrategy.PER_CHECKOUT);
        resetWorld();
        CheckoutRun.Report second = run("repeatable", true, true, KeyStrategy.PER_CHECKOUT);

        assertThat(second).isEqualTo(first);
        assertThat(first.paid().amount().add(first.abandoned().amount())).isGreaterThan(BigDecimal.ZERO);
        assertThat(first.disclaimer()).contains("not observed data");
    }
}
