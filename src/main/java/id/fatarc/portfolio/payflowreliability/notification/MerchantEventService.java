package id.fatarc.portfolio.payflowreliability.notification;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Keyset;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageSlice;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading events and asking for them again. The list is oldest first, so a shop that missed deliveries (its server was
 * down, it lost an event) catches up by walking it from its last cursor; webhooks are a prompt, this list is the record.
 */
@Service
public class MerchantEventService {
    static final String AGGREGATE = "MERCHANT_EVENT";

    private final MerchantEventRepository events;
    private final WebhookEndpointRepository endpoints;
    private final AuditService auditService;
    private final Clock clock;

    public MerchantEventService(MerchantEventRepository events, WebhookEndpointRepository endpoints, AuditService auditService,
                                Clock clock) {
        this.events = events;
        this.endpoints = endpoints;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * One page, oldest first. A merchant sees only its own events; an operator may narrow to one merchant. {@code from}
     * starts the list at a time (inclusive): a shop catching up starts a few minutes before the newest event it processed
     * and skips ids it has seen, because an event is listed by when it was written, and a transaction that commits late
     * can land just behind a reader.
     */
    @Transactional(readOnly = true)
    public PageSlice<MerchantEvent> page(Caller caller, String merchantId, UUID paymentIntentId, String type,
                                         MerchantEvent.Status status, Instant from, PageCursor after, Integer limit) {
        if (caller.isMerchant() && merchantId != null && !merchantId.equals(caller.merchantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A merchant API key reads only its own events.");
        }
        String scope = caller.isMerchant() ? caller.merchantId() : merchantId;
        Specification<MerchantEvent> filter = Keyset.all();
        if (scope != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("merchantId"), scope));
        }
        if (paymentIntentId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("paymentIntentId"), paymentIntentId));
        }
        if (type != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("eventType"), type));
        }
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            filter = filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        return Keyset.page(events, filter, "createdAt", true, after, limit, event -> new PageCursor(event.getCreatedAt(), event.getId()));
    }

    @Transactional(readOnly = true)
    public MerchantEvent get(UUID eventId, Caller caller) {
        return accessible(events.findById(eventId), eventId, caller);
    }

    /**
     * Sends an event again from the first attempt: after the shop fixed its endpoint, or because it wants a copy. An
     * event still waiting for a retry is sent at the next run instead of after its backoff.
     */
    @Transactional
    public MerchantEvent redeliver(UUID eventId, Caller caller) {
        MerchantEvent event = accessible(events.findForUpdate(eventId), eventId, caller);
        if (endpoints.findById(event.getMerchantId()).filter(WebhookEndpoint::isEnabled).isEmpty()) {
            throw new BusinessException(ErrorCode.WEBHOOK_ENDPOINT_NOT_FOUND,
                    "Merchant " + event.getMerchantId() + " has no enabled webhook endpoint to deliver to.");
        }
        MerchantEvent.Status previous = event.getStatus();
        event.redeliver(clock.instant());
        auditService.record(AGGREGATE, eventId.toString(), null, "MERCHANT_EVENT_REDELIVERY_REQUESTED", caller.name(),
                "Delivery requested again (was " + previous + ").", "type=" + event.getEventType());
        return event;
    }

    private static MerchantEvent accessible(Optional<MerchantEvent> found, UUID eventId, Caller caller) {
        return found.filter(event -> caller.canAccessMerchant(event.getMerchantId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.MERCHANT_EVENT_NOT_FOUND, "Merchant event not found: " + eventId));
    }
}
