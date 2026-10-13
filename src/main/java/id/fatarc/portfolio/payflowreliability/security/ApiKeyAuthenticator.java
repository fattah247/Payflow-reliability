package id.fatarc.portfolio.payflowreliability.security;

import id.fatarc.portfolio.payflowreliability.common.Hashing;
import id.fatarc.portfolio.payflowreliability.merchant.MerchantApiKeyRepository;
import id.fatarc.portfolio.payflowreliability.merchant.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves a bearer API key to a {@link Caller}: configured operators first, then merchant keys in the database. */
@Component
public class ApiKeyAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);
    /** Hashes of the keys documented for local use; seeing them configured outside development is a mistake. */
    private static final Set<String> LOCAL_DEMO_KEY_HASHES = Set.of(
            Hashing.sha256Hex("pf_o_local_ops-ana"),
            Hashing.sha256Hex("pf_o_local_ops-budi"));

    private final Map<String, String> operatorNamesByKeyHash;
    private final MerchantApiKeyRepository apiKeyRepository;
    private final MerchantRepository merchantRepository;

    public ApiKeyAuthenticator(SecurityProperties properties, MerchantApiKeyRepository apiKeyRepository,
                               MerchantRepository merchantRepository) {
        this.operatorNamesByKeyHash = properties.operators().stream()
                .collect(Collectors.toUnmodifiableMap(
                        operator -> operator.keySha256().toLowerCase(Locale.ROOT), SecurityProperties.Operator::name));
        this.apiKeyRepository = apiKeyRepository;
        this.merchantRepository = merchantRepository;
        if (operatorNamesByKeyHash.keySet().stream().anyMatch(LOCAL_DEMO_KEY_HASHES::contains)) {
            log.warn("Local demo operator API keys are configured. Replace payflow.security.operators outside local development.");
        }
    }

    public Optional<Caller> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 200) {
            return Optional.empty();
        }
        String hash = Hashing.sha256Hex(apiKey);
        String operator = operatorNamesByKeyHash.get(hash);
        if (operator != null) {
            return Optional.of(Caller.operator(operator));
        }
        return apiKeyRepository.findByKeyHashAndRevokedAtIsNull(hash)
                .filter(key -> merchantRepository.findById(key.getMerchantId()).map(m -> m.isActive()).orElse(false))
                .map(key -> Caller.merchant(key.getMerchantId()));
    }
}
