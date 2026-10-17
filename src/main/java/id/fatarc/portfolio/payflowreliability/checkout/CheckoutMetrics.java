package id.fatarc.portfolio.payflowreliability.checkout;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * What slowness costs, counted where it happens:
 * <ul>
 *   <li>{@code payflow.payment.confirmation}: time from start to a confirmed capture, and
 *       {@code payflow.payment.confirmations{within_slo}}: each confirmation counted good or bad against the SLO;</li>
 *   <li>{@code payflow.checkout.abandoned} and {@code payflow.checkout.abandoned.amount}: checkouts withdrawn
 *       because the customer gave up, and the money they would have brought;</li>
 *   <li>{@code payflow.idempotency.replays}: retries answered with the original result instead of new work.</li>
 * </ul>
 */
@Component
public class CheckoutMetrics {
    private final MeterRegistry registry;
    private final CheckoutProperties properties;

    public CheckoutMetrics(MeterRegistry registry, CheckoutProperties properties) {
        this.registry = registry;
        this.properties = properties;
        Gauge.builder("payflow.checkout.slo.objective", properties, CheckoutProperties::sloObjective)
                .description("Share of confirmations that must meet the confirmation SLO").register(registry);
        Gauge.builder("payflow.checkout.slo.target", properties, p -> p.confirmationSlo().toMillis() / 1000.0)
                .description("The confirmation SLO, start to confirmed capture").baseUnit("seconds").register(registry);
        // Registered up front so dashboards and alerts see a zero before the first event.
        for (String within : new String[]{"true", "false"}) {
            Counter.builder("payflow.payment.confirmations").tag("within_slo", within).register(registry);
        }
        Counter.builder("payflow.idempotency.replays").tag("operation", "payment-intents.create").register(registry);
    }

    /** A capture was recorded: how long did the customer wait since the payment was handed to the provider? */
    public void confirmed(PaymentIntent intent) {
        if (intent.getStartedAt() == null || intent.getCapturedAt() == null) {
            return;
        }
        Duration waited = Duration.between(intent.getStartedAt(), intent.getCapturedAt());
        if (waited.isNegative()) {
            waited = Duration.ZERO;
        }
        Timer.builder("payflow.payment.confirmation")
                .description("Time from start to a confirmed capture")
                .serviceLevelObjectives(properties.confirmationSlo())
                .register(registry)
                .record(waited);
        boolean within = waited.compareTo(properties.confirmationSlo()) <= 0;
        registry.counter("payflow.payment.confirmations", "within_slo", String.valueOf(within)).increment();
    }

    public void abandoned(PaymentIntent intent) {
        registry.counter("payflow.checkout.abandoned", "currency", intent.getCurrency()).increment();
        registry.counter("payflow.checkout.abandoned.amount", "currency", intent.getCurrency())
                .increment(intent.getAmount().doubleValue());
    }

    public void replayed(String operation) {
        registry.counter("payflow.idempotency.replays", "operation", operation).increment();
    }

    public Duration confirmationSlo() {
        return properties.confirmationSlo();
    }
}
