package id.fatarc.portfolio.payflowreliability.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantApiKeyRepository extends JpaRepository<MerchantApiKey, UUID> {
    Optional<MerchantApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);

    List<MerchantApiKey> findByMerchantIdOrderByCreatedAtAsc(String merchantId);
}
