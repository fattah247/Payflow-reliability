package id.fatarc.portfolio.payflowreliability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Callers authenticate with API keys (see security.SecurityConfig); there are no username/password users.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class PayflowReliabilityApplication {
    public static void main(String[] args) {
        SpringApplication.run(PayflowReliabilityApplication.class, args);
    }
}
