package id.fatarc.portfolio.payflowreliability.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Operators are configured, not self-registered. Only SHA-256 hashes of their API keys are kept
 * in configuration, so the config file never contains a usable credential.
 */
@ConfigurationProperties("payflow.security")
public record SecurityProperties(List<Operator> operators) {
    public SecurityProperties {
        operators = operators == null ? List.of() : List.copyOf(operators);
    }

    public record Operator(String name, String keySha256) {
    }
}
