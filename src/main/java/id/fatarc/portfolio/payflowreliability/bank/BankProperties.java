package id.fatarc.portfolio.payflowreliability.bank;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the settlement account's statements come from.
 *
 * @param baseUrl the bank's statement API; empty means the bank simulator served by this application
 * @param apiKey  credential presented to the statement API
 * (payflow.bank.first-statement-sequence, default 1, is the first statement number expected from each account:
 * coverage counts from there, so statements arriving out of order never look complete too early)
 */
@ConfigurationProperties("payflow.bank")
public record BankProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
    public BankProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
}
