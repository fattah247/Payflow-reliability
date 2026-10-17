package id.fatarc.portfolio.payflowreliability.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConfigurationGuardTest {
    private static ProviderProperties provider(String baseUrl, String apiKey, String... secrets) {
        return new ProviderProperties(baseUrl, apiKey, List.of(secrets), null, null, null, null);
    }

    @Test
    void shouldAllowTheSimulatorLocally() {
        assertThat(ProviderConfigurationGuard.problems(provider("", "sim_local_provider_key", "whsec_local_payflow"), true)).isEmpty();
    }

    @Test
    void shouldRefuseTheSimulatorNextToARealProvider() {
        assertThat(ProviderConfigurationGuard.problems(provider("https://api.psp.example", "live-key", "whsec_live"), true))
                .singleElement().asString().contains("must not run next to a real provider");
    }

    @Test
    void shouldRefuseLocalSecretsAgainstARealProvider() {
        assertThat(ProviderConfigurationGuard.problems(provider("https://api.psp.example", "sim_local_provider_key",
                "whsec_live", "whsec_local_payflow"), false)).hasSize(2);
        assertThat(ProviderConfigurationGuard.problems(provider("https://api.psp.example", "live-key", "whsec_live"), false)).isEmpty();
    }

    @Test
    void shouldRefuseTheBankSimulatorNextToARealBankFeed() {
        assertThat(ProviderConfigurationGuard.bankProblems("", "bank_local_statement_key", true)).isEmpty();
        assertThat(ProviderConfigurationGuard.bankProblems("https://bank.example/statements", "real-key", true))
                .singleElement().asString().contains("bank simulator must not run");
        assertThat(ProviderConfigurationGuard.bankProblems("https://bank.example/statements", "bank_local_statement_key", false))
                .singleElement().asString().contains("local demo key");
    }

    @Test
    void shouldRefuseWebhooksToPrivateAddressesAndTheMerchantSimulatorNextToARealProvider() {
        ProviderProperties local = provider("", "sim_local_provider_key", "whsec_local_payflow");
        ProviderProperties real = provider("https://api.psp.example", "live-key", "whsec_live");

        assertThat(ProviderConfigurationGuard.merchantWebhookProblems(local, true, true)).isEmpty();
        assertThat(ProviderConfigurationGuard.merchantWebhookProblems(real, false, false)).isEmpty();
        assertThat(ProviderConfigurationGuard.merchantWebhookProblems(real, true, false))
                .singleElement().asString().contains("allow-private-addresses=false");
        assertThat(ProviderConfigurationGuard.merchantWebhookProblems(real, false, true))
                .singleElement().asString().contains("merchant simulator must not run");
    }
}
