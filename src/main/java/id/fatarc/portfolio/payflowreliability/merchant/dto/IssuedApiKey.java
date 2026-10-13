package id.fatarc.portfolio.payflowreliability.merchant.dto;

import java.time.Instant;
import java.util.UUID;

/** The only time the plaintext key is ever returned. It cannot be retrieved again; rotate if lost. */
public record IssuedApiKey(UUID keyId, String apiKey, String keyPrefix, Instant createdAt) {
}
