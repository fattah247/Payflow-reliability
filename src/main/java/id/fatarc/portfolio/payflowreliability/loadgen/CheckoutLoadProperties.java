package id.fatarc.portfolio.payflowreliability.loadgen;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The checkout model's parameters. They are assumptions of a simulation, not measurements of real customers:
 * change them and the money numbers change with them. Every run echoes the values it used.
 *
 * @param enabled            local only, like the provider and bank simulators
 * @param customers          customers per run, unless the run says otherwise
 * @param amountMin          smallest checkout amount (IDR, whole thousands)
 * @param amountMax          largest checkout amount
 * @param patienceMin        the least patient customer waits this long for the payment page or the payment answer
 * @param patienceMax        the most patient customer waits this long; each customer's patience is uniform in between
 * @param abandonProbability when out of patience, the chance the customer leaves instead of trying again
 * @param maxAttempts        how many times a customer tries before leaving for good
 * @param concurrency        customers checking out at the same time
 * @param slowCheckoutPage   how long the provider's payment page takes to load while the provider is slow
 * @param slowPay            how long the provider takes to answer a payment while it is slow (it has already charged)
 */
@ConfigurationProperties("payflow.loadgen.checkout")
public record CheckoutLoadProperties(Boolean enabled, Integer customers, BigDecimal amountMin, BigDecimal amountMax,
                                     Duration patienceMin, Duration patienceMax, Double abandonProbability,
                                     Integer maxAttempts, Integer concurrency, Duration slowCheckoutPage, Duration slowPay) {
    public CheckoutLoadProperties {
        enabled = enabled != null && enabled;
        customers = customers == null ? 40 : customers;
        amountMin = amountMin == null ? new BigDecimal("50000") : amountMin;
        amountMax = amountMax == null ? new BigDecimal("500000") : amountMax;
        patienceMin = patienceMin == null ? Duration.ofSeconds(2) : patienceMin;
        patienceMax = patienceMax == null ? Duration.ofSeconds(8) : patienceMax;
        abandonProbability = abandonProbability == null ? 0.5 : abandonProbability;
        maxAttempts = maxAttempts == null ? 3 : maxAttempts;
        concurrency = concurrency == null ? 8 : concurrency;
        slowCheckoutPage = slowCheckoutPage == null ? Duration.ofSeconds(4) : slowCheckoutPage;
        slowPay = slowPay == null ? Duration.ofSeconds(7) : slowPay;
    }
}
