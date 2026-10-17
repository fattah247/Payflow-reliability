package id.fatarc.portfolio.payflowreliability.loadgen;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.Map;

/** A checkout run's request and its report. */
public final class CheckoutRun {
    private CheckoutRun() {
    }

    /**
     * @param seed             makes the run reproducible: same seed, same customers, same decisions
     * @param phase            names the run; checkout references are {@code <phase>-<seed>-<n>}
     * @param merchantApiKey   the merchant the simulated shop checks out with (the generator uses the public API only)
     * @param providerSlow     slow the provider down for this run (and leave it slow); false restores it
     * @param holdWebhooks     while slow, keep the provider's webhooks in its backlog until it recovers
     * @param customers        overrides the configured number of customers
     * @param keyStrategy      overrides PER_CHECKOUT, to show the cost of generating keys per attempt
     */
    public record Request(long seed, String phase, String merchantApiKey, boolean providerSlow, Boolean holdWebhooks,
                          Integer customers, KeyStrategy keyStrategy) {
    }

    /** Count and money. */
    public record Tally(long count, BigDecimal amount) {
        static Tally zero() {
            return new Tally(0, BigDecimal.ZERO);
        }

        Tally plus(BigDecimal value) {
            return new Tally(count + 1, amount.add(value));
        }

        Tally plus(Tally other) {
            return new Tally(count + other.count, amount.add(other.amount));
        }
    }

    /**
     * What the simulation knows happened (its ground truth). The payment system measures the same things from its
     * own records; the scenario prints both side by side.
     *
     * @param paid             customers who paid
     * @param abandoned        customers who left without paying (the merchant cancelled their checkout as ABANDONED)
     * @param retries          extra attempts after a customer ran out of patience
     * @param retriesAbsorbed  of those, answered with the original payment because the key was reused
     * @param duplicateCharges charges beyond the first for one checkout
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(String phase, long seed, boolean providerSlow, KeyStrategy keyStrategy, int customers, Tally paid,
                         Tally abandoned, long retries, long retriesAbsorbed, Tally duplicateCharges,
                         Map<String, Object> parameters, String disclaimer) {
    }
}
