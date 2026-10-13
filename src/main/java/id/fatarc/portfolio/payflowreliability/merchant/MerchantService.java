package id.fatarc.portfolio.payflowreliability.merchant;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Hashing;
import id.fatarc.portfolio.payflowreliability.merchant.dto.IssuedApiKey;
import id.fatarc.portfolio.payflowreliability.merchant.dto.MerchantRegistration;
import id.fatarc.portfolio.payflowreliability.merchant.dto.MerchantView;
import id.fatarc.portfolio.payflowreliability.merchant.dto.RegisterMerchantRequest;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Merchant onboarding and API key lifecycle (issue, rotate by issuing another, revoke). */
@Service
public class MerchantService {
    static final String AGGREGATE = "MERCHANT";
    private static final String KEY_PREFIX = "pf_m_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MerchantRepository merchantRepository;
    private final MerchantApiKeyRepository apiKeyRepository;
    private final AuditService auditService;
    private final Clock clock;

    public MerchantService(MerchantRepository merchantRepository, MerchantApiKeyRepository apiKeyRepository,
                           AuditService auditService, Clock clock) {
        this.merchantRepository = merchantRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public MerchantRegistration register(RegisterMerchantRequest request, Caller caller) {
        if (merchantRepository.existsById(request.merchantId())) {
            throw new BusinessException(ErrorCode.MERCHANT_ALREADY_EXISTS, "Merchant already exists: " + request.merchantId());
        }
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant(request.merchantId(), request.displayName(), caller.name(), clock.instant()));
        auditService.record(AGGREGATE, merchant.getMerchantId(), null, "MERCHANT_REGISTERED", caller.name(),
                "Merchant registered: " + request.displayName(), "merchantId=" + merchant.getMerchantId());
        IssuedApiKey key = issue(merchant.getMerchantId(), caller);
        return new MerchantRegistration(view(merchant), key);
    }

    @Transactional
    public IssuedApiKey issueKey(String merchantId, Caller caller) {
        requireActive(merchantId);
        return issue(merchantId, caller);
    }

    @Transactional
    public MerchantView revokeKey(String merchantId, UUID keyId, Caller caller) {
        Merchant merchant = require(merchantId);
        MerchantApiKey key = apiKeyRepository.findById(keyId)
                .filter(candidate -> candidate.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new BusinessException(ErrorCode.MERCHANT_NOT_FOUND,
                        "API key " + keyId + " not found for merchant " + merchantId));
        if (!key.isRevoked()) {
            key.revoke(caller.name(), clock.instant());
            auditService.record(AGGREGATE, merchantId, null, "API_KEY_REVOKED", caller.name(),
                    "API key revoked.", "keyId=" + keyId + ",keyPrefix=" + key.getKeyPrefix());
        }
        return view(merchant);
    }

    @Transactional(readOnly = true)
    public MerchantView get(String merchantId) {
        return view(require(merchantId));
    }

    /** The merchant, if it exists and may create payments. */
    @Transactional(readOnly = true)
    public Merchant requireActive(String merchantId) {
        Merchant merchant = require(merchantId);
        if (!merchant.isActive()) {
            throw new BusinessException(ErrorCode.MERCHANT_INACTIVE, "Merchant " + merchantId + " is " + merchant.getStatus() + ".");
        }
        return merchant;
    }

    private IssuedApiKey issue(String merchantId, Caller caller) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String apiKey = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String prefix = apiKey.substring(0, 12);
        Instant now = clock.instant();
        MerchantApiKey stored = apiKeyRepository.save(
                new MerchantApiKey(merchantId, Hashing.sha256Hex(apiKey), prefix, caller.name(), now));
        auditService.record(AGGREGATE, merchantId, null, "API_KEY_ISSUED", caller.name(),
                "API key issued.", "keyId=" + stored.getId() + ",keyPrefix=" + prefix);
        return new IssuedApiKey(stored.getId(), apiKey, prefix, now);
    }

    private Merchant require(String merchantId) {
        return merchantRepository.findById(merchantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found: " + merchantId));
    }

    private MerchantView view(Merchant merchant) {
        return new MerchantView(merchant.getMerchantId(), merchant.getDisplayName(), merchant.getStatus(),
                merchant.getCreatedBy(), merchant.getCreatedAt(),
                apiKeyRepository.findByMerchantIdOrderByCreatedAtAsc(merchant.getMerchantId()).stream()
                        .map(key -> new MerchantView.KeySummary(key.getId(), key.getKeyPrefix(), key.getCreatedAt(),
                                key.getCreatedBy(), key.getRevokedAt(), key.getRevokedBy()))
                        .toList());
    }
}
