package id.fatarc.portfolio.payflowreliability.support;

import id.fatarc.portfolio.payflowreliability.provider.HttpPaymentProviderClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestProviderClientConfig {
    @Bean
    @Primary
    public CrashableProviderClient crashableProviderClient(HttpPaymentProviderClient httpClient) {
        return new CrashableProviderClient(httpClient);
    }
}
