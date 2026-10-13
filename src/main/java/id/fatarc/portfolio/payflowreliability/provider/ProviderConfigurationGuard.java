package id.fatarc.portfolio.payflowreliability.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start with a provider setup that would let outsiders move money: the simulator (whose key
 * can create signed provider events) next to a real provider, or a real provider with the documented
 * local secrets. Warns whenever the simulator is on.
 */
@Component
public class ProviderConfigurationGuard {
    static final String LOCAL_API_KEY = "sim_local_provider_key";
    static final String LOCAL_WEBHOOK_SECRET = "whsec_local_payflow";
    private static final Logger log = LoggerFactory.getLogger(ProviderConfigurationGuard.class);

    public ProviderConfigurationGuard(ProviderProperties provider, Environment environment) {
        boolean simulatorEnabled = environment.getProperty("payflow.provider-sim.enabled", Boolean.class, false);
        List<String> problems = problems(provider, simulatorEnabled);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe provider configuration: " + String.join(" ", problems));
        }
        if (simulatorEnabled) {
            log.warn("The provider simulator is enabled: its key can create signed provider events. Local use only.");
        }
    }

    static List<String> problems(ProviderProperties provider, boolean simulatorEnabled) {
        List<String> problems = new ArrayList<>();
        boolean realProvider = provider.baseUrl() != null && !provider.baseUrl().isBlank();
        if (realProvider && simulatorEnabled) {
            problems.add("The provider simulator must not run next to a real provider (payflow.provider.base-url is set);"
                    + " set payflow.provider-sim.enabled=false.");
        }
        if (realProvider && LOCAL_API_KEY.equals(provider.apiKey())) {
            problems.add("payflow.provider.api-key is the local demo key.");
        }
        if (realProvider && provider.webhookSecrets().contains(LOCAL_WEBHOOK_SECRET)) {
            problems.add("payflow.provider.webhook-secrets contains the local demo secret.");
        }
        return problems;
    }
}
