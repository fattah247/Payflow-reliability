package id.fatarc.portfolio.payflowreliability.loadgen;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The labelled-traffic model's parameters. Like the checkout model, they are illustrative assumptions: the share of
 * fraud, how often people complete a step-up, and (in {@link RiskTrafficGenerator}) which devices, amounts and goods
 * each group tends to have. Every run echoes them.
 *
 * @param customers               customers per run, unless the run says otherwise
 * @param fraudRate               share of customers who are fraudsters
 * @param legitimateConfirmRate   chance an honest customer completes a step-up
 * @param fraudulentConfirmRate   chance a fraudster completes one (a victim can be talked into confirming)
 * @param concurrency             customers checking out at the same time
 */
@ConfigurationProperties("payflow.loadgen.risk")
public record RiskTrafficProperties(Integer customers, Double fraudRate, Double legitimateConfirmRate,
                                    Double fraudulentConfirmRate, Integer concurrency) {
    public RiskTrafficProperties {
        customers = customers == null ? 200 : customers;
        fraudRate = fraudRate == null ? 0.12 : fraudRate;
        legitimateConfirmRate = legitimateConfirmRate == null ? 0.85 : legitimateConfirmRate;
        fraudulentConfirmRate = fraudulentConfirmRate == null ? 0.25 : fraudulentConfirmRate;
        concurrency = concurrency == null ? 8 : concurrency;
    }
}
