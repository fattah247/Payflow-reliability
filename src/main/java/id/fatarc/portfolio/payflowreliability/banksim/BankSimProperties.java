package id.fatarc.portfolio.payflowreliability.banksim;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The local stand-in for the bank that holds the platform's settlement account. Off unless {@code enabled=true}
 * (application.yml turns it on for local runs, next to the provider simulator).
 *
 * @param apiKey        credential the platform presents when it downloads statements
 * @param accountPrefix settlement accounts are named prefix + currency, one per currency
 */
@ConfigurationProperties("payflow.bank-sim")
public record BankSimProperties(boolean enabled, String apiKey, String accountPrefix, String bankName) {
    public BankSimProperties {
        accountPrefix = accountPrefix == null || accountPrefix.isBlank() ? "PAYFLOW-SETTLE-" : accountPrefix;
        bankName = bankName == null || bankName.isBlank() ? "Simulated Settlement Bank" : bankName;
    }

    public String accountFor(String currency) {
        return accountPrefix + currency;
    }
}
