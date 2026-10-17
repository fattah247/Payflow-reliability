package id.fatarc.portfolio.payflowreliability.notification;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.merchant.MerchantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * A shop's webhook endpoint: registering it, moving it, rotating its signing secret, switching it off. A secret is
 * returned once, when it is created; it is never shown again (rotate to get a new one).
 */
@Service
public class WebhookEndpointService {
    static final String AGGREGATE = "WEBHOOK_ENDPOINT";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookEndpointRepository endpoints;
    private final MerchantService merchantService;
    private final DestinationGuard guard;
    private final AuditService auditService;
    private final MerchantWebhookProperties properties;
    private final Clock clock;

    public WebhookEndpointService(WebhookEndpointRepository endpoints, MerchantService merchantService, DestinationGuard guard,
                                  AuditService auditService, MerchantWebhookProperties properties, Clock clock) {
        this.endpoints = endpoints;
        this.merchantService = merchantService;
        this.guard = guard;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param secret set only when a secret was just created: shown to the shop now and never again
     * @param created true when this call registered the endpoint
     */
    public record Registration(WebhookEndpoint endpoint, String secret, boolean created) {
    }

    /** Registers the endpoint, or moves an existing one to a new URL (keeping its secret) and switches it back on. */
    @Transactional
    public Registration register(String merchantId, String url, String actor) {
        merchantService.requireActive(merchantId);
        guard.check(url);
        Instant now = clock.instant();
        var existing = endpoints.findForUpdate(merchantId);
        if (existing.isPresent()) {
            WebhookEndpoint endpoint = existing.get();
            endpoint.moveTo(url, now);
            auditService.record(AGGREGATE, merchantId, null, "WEBHOOK_ENDPOINT_UPDATED", actor, "Webhook endpoint set.",
                    "url=" + url);
            return new Registration(endpoint, null, false);
        }
        String secret = newSecret();
        WebhookEndpoint endpoint = endpoints.save(new WebhookEndpoint(merchantId, url, secret, now));
        auditService.record(AGGREGATE, merchantId, null, "WEBHOOK_ENDPOINT_REGISTERED", actor, "Webhook endpoint registered.",
                "url=" + url);
        return new Registration(endpoint, secret, true);
    }

    /** A new secret; the previous one keeps signing next to it for the configured overlap, so no delivery fails to verify. */
    @Transactional
    public Registration rotateSecret(String merchantId, String actor) {
        WebhookEndpoint endpoint = required(merchantId);
        String secret = newSecret();
        endpoint.rotate(secret, properties.secretRotationOverlap(), clock.instant());
        auditService.record(AGGREGATE, merchantId, null, "WEBHOOK_SECRET_ROTATED", actor,
                "Webhook signing secret rotated; the previous one signs until " + endpoint.getPreviousSecretUntil() + ".",
                "url=" + endpoint.getUrl());
        return new Registration(endpoint, secret, false);
    }

    /** Stops deliveries. Events keep being written and stay readable through the events API. */
    @Transactional
    public WebhookEndpoint disable(String merchantId, String actor) {
        WebhookEndpoint endpoint = required(merchantId);
        endpoint.disable(clock.instant());
        auditService.record(AGGREGATE, merchantId, null, "WEBHOOK_ENDPOINT_DISABLED", actor, "Webhook deliveries switched off.",
                "url=" + endpoint.getUrl());
        return endpoint;
    }

    @Transactional(readOnly = true)
    public Optional<WebhookEndpoint> find(String merchantId) {
        return endpoints.findById(merchantId);
    }

    private WebhookEndpoint required(String merchantId) {
        return endpoints.findForUpdate(merchantId).orElseThrow(() -> new BusinessException(ErrorCode.WEBHOOK_ENDPOINT_NOT_FOUND,
                "Merchant " + merchantId + " has no webhook endpoint; register one with PUT /api/webhook-endpoint."));
    }

    static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
