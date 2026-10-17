package id.fatarc.portfolio.payflowreliability.checkout;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The checkout service level, measured inside the payment system.
 *
 * @param confirmationSlo how long a customer may wait from start (handed to the provider) to a confirmed capture
 * @param sloObjective    the share of confirmations that must meet it (0.95 means 95 %); the alert's burn rate
 *                        is the observed bad share divided by the error budget (1 - objective)
 */
@ConfigurationProperties("payflow.checkout")
public record CheckoutProperties(Duration confirmationSlo, Double sloObjective) {
    public CheckoutProperties {
        confirmationSlo = confirmationSlo == null ? Duration.ofSeconds(10) : confirmationSlo;
        sloObjective = sloObjective == null ? 0.95 : sloObjective;
    }
}
