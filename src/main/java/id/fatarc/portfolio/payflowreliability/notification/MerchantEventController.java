package id.fatarc.portfolio.payflowreliability.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageResponses;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Merchant notifications: the shop's webhook endpoint, the events it is sent, and a manual run of the sender.
 * See docs/merchant-webhooks.md for how a shop verifies and processes them.
 */
@RestController
public class MerchantEventController {
    private final WebhookEndpointService endpointService;
    private final MerchantEventService eventService;
    private final MerchantWebhookDispatcher dispatcher;
    private final Clock clock;

    public MerchantEventController(WebhookEndpointService endpointService, MerchantEventService eventService,
                                   MerchantWebhookDispatcher dispatcher, Clock clock) {
        this.endpointService = endpointService;
        this.eventService = eventService;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    public record EndpointRequest(@NotBlank @Size(max = 500) String url) {
    }

    /**
     * @param secret only in the response that created it (registration or rotation); never shown again
     * @param previousSecretSignsUntil after a rotation, until when deliveries also carry a signature with the previous secret
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EndpointView(String url, boolean enabled, String secret, Instant previousSecretSignsUntil, Instant createdAt,
                               Instant updatedAt) {
        static EndpointView of(WebhookEndpoint endpoint, String secret, Instant now) {
            Instant until = endpoint.getPreviousSecretUntil();
            return new EndpointView(endpoint.getUrl(), endpoint.isEnabled(), secret,
                    until != null && until.isAfter(now) ? until : null, endpoint.getCreatedAt(), endpoint.getUpdatedAt());
        }
    }

    /** An event and its delivery. {@code payload} is the exact JSON body that is (or was) delivered and signed. */
    public record EventView(UUID id, String type, String merchantId, UUID paymentIntentId, UUID refundId, long sequence,
                            String state, MerchantEvent.Status status, int attempts, Integer lastStatusCode, String lastError,
                            Instant nextAttemptAt, Instant deliveredAt, Instant createdAt, @JsonRawValue String payload) {
        static EventView of(MerchantEvent event) {
            return new EventView(event.getId(), event.getEventType(), event.getMerchantId(), event.getPaymentIntentId(),
                    event.getRefundId(), event.getSequence(), event.getState(), event.getStatus(), event.getAttempts(),
                    event.getLastStatusCode(), event.getLastError(), event.getNextAttemptAt(), event.getDeliveredAt(),
                    event.getCreatedAt(), event.getPayload());
        }
    }

    @PutMapping("/api/webhook-endpoint")
    public ResponseEntity<EndpointView> register(@Valid @RequestBody EndpointRequest request, Caller caller) {
        WebhookEndpointService.Registration registration = endpointService.register(merchant(caller), request.url(), caller.name());
        return ResponseEntity.status(registration.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(EndpointView.of(registration.endpoint(), registration.secret(), clock.instant()));
    }

    @GetMapping("/api/webhook-endpoint")
    public EndpointView endpoint(Caller caller) {
        String merchantId = merchant(caller);
        return endpointService.find(merchantId).map(endpoint -> EndpointView.of(endpoint, null, clock.instant()))
                .orElseThrow(() -> new BusinessException(ErrorCode.WEBHOOK_ENDPOINT_NOT_FOUND,
                        "Merchant " + merchantId + " has no webhook endpoint; register one with PUT /api/webhook-endpoint."));
    }

    @DeleteMapping("/api/webhook-endpoint")
    public EndpointView disable(Caller caller) {
        return EndpointView.of(endpointService.disable(merchant(caller), caller.name()), null, clock.instant());
    }

    @PostMapping("/api/webhook-endpoint/rotate-secret")
    public EndpointView rotateSecret(Caller caller) {
        WebhookEndpointService.Registration registration = endpointService.rotateSecret(merchant(caller), caller.name());
        return EndpointView.of(registration.endpoint(), registration.secret(), clock.instant());
    }

    /** Oldest first; follow the {@code Link: rel="next"} header (or {@code X-Next-After}) to catch up. */
    @GetMapping("/api/events")
    public ResponseEntity<List<EventView>> events(@RequestParam(required = false) String merchantId,
                                                  @RequestParam(required = false) UUID paymentIntentId,
                                                  @RequestParam(required = false) String type,
                                                  @RequestParam(required = false) MerchantEvent.Status status,
                                                  @RequestParam(required = false) Instant from,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) String after,
                                                  Caller caller) {
        return PageResponses.of(eventService.page(caller, merchantId, paymentIntentId, type, status, from, PageCursor.decode(after), limit)
                .map(rows -> rows.stream().map(EventView::of).toList()));
    }

    @GetMapping("/api/events/{eventId}")
    public EventView event(@PathVariable UUID eventId, Caller caller) {
        return EventView.of(eventService.get(eventId, caller));
    }

    @PostMapping("/api/events/{eventId}/redeliver")
    public EventView redeliver(@PathVariable UUID eventId, Caller caller) {
        return EventView.of(eventService.redeliver(eventId, caller));
    }

    /** Runs the sender once now (it also runs on a schedule). */
    @PostMapping("/api/merchant-webhooks/run")
    public MerchantWebhookDispatcher.RunReport run() {
        return dispatcher.runOnce();
    }

    private static String merchant(Caller caller) {
        if (!caller.isMerchant()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the shop's own API key manages its webhook endpoint.");
        }
        return caller.merchantId();
    }
}
