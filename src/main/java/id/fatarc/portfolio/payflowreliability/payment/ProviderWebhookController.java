package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.ApiError;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.WebhookResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Status codes are chosen for the provider's retry logic: 2xx means "stop retrying"
 * (200 handled, 202 accepted for manual review), 409 PAYMENT_ON_HOLD means "redeliver later",
 * 401 means the signature is wrong.
 */
@RestController
@RequestMapping("/fake-provider/webhook")
public class ProviderWebhookController {
    private final ProviderWebhookService providerWebhookService;

    public ProviderWebhookController(ProviderWebhookService providerWebhookService) {
        this.providerWebhookService = providerWebhookService;
    }

    @PostMapping
    public ResponseEntity<?> process(
            @RequestHeader(value = "X-Provider-Signature", required = false) String signature,
            @Valid @RequestBody ProviderWebhookRequest request
    ) {
        WebhookResult result = providerWebhookService.process(request, signature);
        return switch (result.outcome()) {
            case DEFERRED -> ResponseEntity.status(ErrorCode.PAYMENT_ON_HOLD.status())
                    .body(ApiError.of(ErrorCode.PAYMENT_ON_HOLD, result.detail()));
            case SENT_TO_REVIEW -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(WebhookResponse.from(request.providerEventId(), result));
            case APPLIED, DUPLICATE_IGNORED, STALE_IGNORED -> ResponseEntity.ok(
                    WebhookResponse.from(request.providerEventId(), result));
        };
    }
}
