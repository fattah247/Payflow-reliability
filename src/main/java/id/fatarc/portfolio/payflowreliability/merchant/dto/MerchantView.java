package id.fatarc.portfolio.payflowreliability.merchant.dto;

import id.fatarc.portfolio.payflowreliability.merchant.MerchantStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MerchantView(
        String merchantId,
        String displayName,
        MerchantStatus status,
        String createdBy,
        Instant createdAt,
        List<KeySummary> apiKeys
) {
    public record KeySummary(UUID keyId, String keyPrefix, Instant createdAt, String createdBy,
                             Instant revokedAt, String revokedBy) {
    }
}
