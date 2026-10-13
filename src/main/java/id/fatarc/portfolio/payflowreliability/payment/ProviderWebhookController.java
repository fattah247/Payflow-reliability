package id.fatarc.portfolio.payflowreliability.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.fatarc.portfolio.payflowreliability.common.ApiError;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.WebhookResponse;
import id.fatarc.portfolio.payflowreliability.provider.WebhookSignatureVerifier;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * The signature is verified over the exact raw bytes before the body is parsed, so unauthenticated
 * input is never interpreted. Status codes are chosen for the provider's retry logic: 2xx means
 * "stop retrying" (200 handled, 202 accepted for manual review), 409 PAYMENT_ON_HOLD means
 * "redeliver later", 401 means the signature is wrong or too old.
 */
@RestController
@RequestMapping("/fake-provider/webhook")
public class ProviderWebhookController {
    private final ProviderWebhookService providerWebhookService;
    private final WebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ProviderWebhookController(ProviderWebhookService providerWebhookService,
                                     WebhookSignatureVerifier signatureVerifier,
                                     ObjectMapper objectMapper,
                                     Validator validator) {
        this.providerWebhookService = providerWebhookService;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> process(
            @RequestHeader(value = "X-Provider-Signature", required = false) String signature,
            @RequestBody byte[] body
    ) {
        signatureVerifier.verify(signature, body);
        ProviderWebhookRequest request = parse(body);

        WebhookResult result = providerWebhookService.process(request);
        return switch (result.outcome()) {
            case DEFERRED -> ResponseEntity.status(ErrorCode.PAYMENT_ON_HOLD.status())
                    .body(ApiError.of(ErrorCode.PAYMENT_ON_HOLD, result.detail()));
            case SENT_TO_REVIEW -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(WebhookResponse.from(request.providerEventId(), result));
            case APPLIED, DUPLICATE_IGNORED, STALE_IGNORED -> ResponseEntity.ok(
                    WebhookResponse.from(request.providerEventId(), result));
        };
    }

    private ProviderWebhookRequest parse(byte[] body) {
        ProviderWebhookRequest request;
        try {
            request = objectMapper.readValue(body, ProviderWebhookRequest.class);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "Request body is malformed or contains an invalid enum value");
        }
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, violations.stream()
                    .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                    .map(ProviderWebhookController::format)
                    .collect(Collectors.joining(", ")));
        }
        return request;
    }

    private static String format(ConstraintViolation<ProviderWebhookRequest> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
