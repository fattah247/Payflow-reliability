package id.fatarc.portfolio.payflowreliability.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Time-based decisions (retries, leases, expiry, signature windows) read this clock so tests can move time. */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
